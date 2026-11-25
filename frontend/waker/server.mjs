// Waker: runs as a sidecar next to nginx in the pulsedesk-web container app, which scales to zero.
// A visit wakes the site; the page calls /waker/start, and this service starts the database and API through
// the Azure management API (using the app's managed identity), reports progress, and stops them again once
// nobody has had the site open for IDLE_MINUTES. Visitor running time is capped at DAILY_CAP_MINUTES a day;
// an admin hold (set by scripts/azure/demo-up.ps1) bypasses both the idle stop and the cap.
import http from "node:http";
import net from "node:net";
import { readFile, writeFile, rename } from "node:fs/promises";
import { timingSafeEqual } from "node:crypto";
import { dayKey, rollDay, estimateStartSeconds, recordStart, phaseOf, decide, describe } from "./logic.mjs";

const env = (name, fallback) => process.env[name] ?? fallback;
const config = {
  port: Number(env("PORT", "8081")),
  armUrl: env("ARM_URL", "https://management.azure.com/").replace(/\/$/, ""),
  armApiVersion: env("ARM_API_VERSION", "2025-01-01"),
  dbId: env("DB_ID", ""),
  apiId: env("API_ID", ""),
  dbHost: env("DB_HOST", "pulsedesk-db"),
  dbPort: Number(env("DB_PORT", "5432")),
  apiHealthUrl: env("API_HEALTH_URL", "http://pulsedesk-api/actuator/health"),
  publicUrl: env("PUBLIC_URL", "").replace(/\/$/, ""),
  idleMs: Number(env("IDLE_MINUTES", "3")) * 60_000,
  capSeconds: Number(env("DAILY_CAP_MINUTES", "60")) * 60,
  tzOffsetMinutes: Number(env("TZ_OFFSET_MINUTES", "330")),
  stateFile: env("STATE_FILE", ""),
  adminKey: env("WAKER_KEY", ""),
};

// Persisted on a file share so the daily usage and holds survive the site scaling to zero.
let state = { day: "", usedSeconds: 0, holdUntil: 0, history: [] };
// In memory only.
const run = { startRequestedAt: 0, stopRequestedAt: 0, startedAtMs: 0, startWanted: false, lastActivityMs: Date.now() };
let obs = { db: "Unknown", api: "Unknown", apiHealthy: false, dbOpen: false };
let lastTickMs = Date.now();

const log = (...args) => console.log(new Date().toISOString(), ...args);

async function loadState() {
  if (!config.stateFile) return;
  try {
    state = { ...state, ...JSON.parse(await readFile(config.stateFile, "utf8")) };
  } catch (err) {
    if (err.code !== "ENOENT") log("Couldn't read state:", err.message);
  }
}

async function saveState() {
  if (!config.stateFile) return;
  try {
    const tmp = `${config.stateFile}.tmp`;
    await writeFile(tmp, JSON.stringify(state));
    await rename(tmp, config.stateFile);
  } catch (err) {
    log("Couldn't save state:", err.message);
  }
}

// --- Azure management API through the container app's managed identity ---
let token = { value: "", expiresAtMs: 0 };
async function armToken() {
  if (token.value && Date.now() < token.expiresAtMs - 5 * 60_000) return token.value;
  const url = `${process.env.IDENTITY_ENDPOINT}?resource=${encodeURIComponent(config.armUrl + "/")}&api-version=2019-08-01`;
  const res = await fetch(url, { headers: { "X-IDENTITY-HEADER": process.env.IDENTITY_HEADER ?? "" }, signal: AbortSignal.timeout(10_000) });
  if (!res.ok) throw new Error(`managed identity token: HTTP ${res.status}`);
  const body = await res.json();
  token = { value: body.access_token, expiresAtMs: Number(body.expires_on) * 1000 };
  return token.value;
}

async function arm(method, id, action = "") {
  const res = await fetch(`${config.armUrl}${id}${action}?api-version=${config.armApiVersion}`, {
    method,
    headers: { Authorization: `Bearer ${await armToken()}` },
    signal: AbortSignal.timeout(20_000),
  });
  if (!res.ok) throw new Error(`${method} ${id.split("/").pop()}${action}: HTTP ${res.status} ${(await res.text()).slice(0, 200)}`);
  return method === "GET" ? res.json() : null;
}

const runningStatus = async (id) => (await arm("GET", id)).properties?.runningStatus ?? "Unknown";

// --- Probes ---
async function apiHealthy() {
  try {
    const res = await fetch(config.apiHealthUrl, { signal: AbortSignal.timeout(3000) });
    return res.ok && (await res.json()).status === "UP";
  } catch {
    return false;
  }
}

function portOpen(host, port) {
  return new Promise((resolve) => {
    const socket = net.connect({ host, port, timeout: 2000 });
    const done = (ok) => { socket.destroy(); resolve(ok); };
    socket.once("connect", () => done(true));
    socket.once("timeout", () => done(false));
    socket.once("error", () => done(false));
  });
}

// --- Main loop ---
const held = (nowMs) => state.holdUntil > nowMs;

function currentPhase(nowMs) {
  return phaseOf(obs, run, nowMs);
}

async function observe() {
  try {
    const [db, api] = await Promise.all([runningStatus(config.dbId), runningStatus(config.apiId)]);
    obs = { ...obs, db, api };
  } catch (err) {
    log("Couldn't read app status:", err.message);
  }
  const maybeUp = obs.db !== "Stopped" || obs.api !== "Stopped";
  const [healthy, dbOpen] = maybeUp ? await Promise.all([apiHealthy(), portOpen(config.dbHost, config.dbPort)]) : [false, false];
  obs = { ...obs, apiHealthy: healthy, dbOpen };
}

async function startApps(nowMs, reason) {
  log(`Starting database and API (${reason})`);
  run.startRequestedAt = nowMs;
  run.startedAtMs = nowMs;
  run.stopRequestedAt = 0;
  run.startWanted = false;
  // Database first: the API retries its connection while Postgres comes up.
  await arm("POST", config.dbId, "/start");
  await arm("POST", config.apiId, "/start");
}

async function stopApps(nowMs, reason) {
  log(`Stopping API and database (${reason})`);
  run.stopRequestedAt = nowMs;
  run.startRequestedAt = 0;
  run.startedAtMs = 0;
  // API first, so Postgres shuts down after its clients have gone.
  await arm("POST", config.apiId, "/stop");
  await arm("POST", config.dbId, "/stop");
}

// While the API or database runs, keep this site replica alive with requests through the public ingress;
// otherwise it could scale to zero and nobody would be left to stop them.
async function keepSiteAwake() {
  if (!config.publicUrl) return;
  try {
    await fetch(`${config.publicUrl}/waker/ping`, { signal: AbortSignal.timeout(10_000) });
  } catch (err) {
    log("Keep-awake ping failed:", err.message);
  }
}

let ticking = false;
let lastPingMs = 0;
async function tick() {
  if (ticking) return;
  ticking = true;
  try {
    await observe();
    const nowMs = Date.now();
    const phase = currentPhase(nowMs);
    const today = dayKey(nowMs, config.tzOffsetMinutes);
    const rolled = rollDay(state, today);
    let dirty = rolled !== state;
    state = rolled;
    if (state.holdUntil && !held(nowMs)) { state.holdUntil = 0; dirty = true; }

    if (phase !== "stopped" && !held(nowMs)) {
      state.usedSeconds += (nowMs - lastTickMs) / 1000;
      dirty = true;
    }
    if (phase === "ready" && run.startedAtMs) {
      state.history = recordStart(state.history, (nowMs - run.startedAtMs) / 1000);
      log(`Ready after ${Math.round((nowMs - run.startedAtMs) / 1000)} s`);
      run.startedAtMs = 0;
      run.startRequestedAt = 0;
      dirty = true;
    }
    if (phase === "starting" && !run.startedAtMs) run.startedAtMs = nowMs; // started elsewhere, e.g. demo-up.ps1
    if (phase === "stopped") run.stopRequestedAt = 0;

    const { action, reason } = decide({
      phase, nowMs, lastActivityMs: run.lastActivityMs, idleMs: config.idleMs, held: held(nowMs),
      usedSeconds: state.usedSeconds, capSeconds: config.capSeconds, startWanted: run.startWanted,
    });
    if (action === "start") await startApps(nowMs, reason);
    if (action === "stop") await stopApps(nowMs, reason);

    if (currentPhase(nowMs) !== "stopped" && nowMs - lastPingMs > 30_000) {
      lastPingMs = nowMs;
      keepSiteAwake();
    }
    if (dirty) await saveState();
    lastTickMs = nowMs;
  } catch (err) {
    log("Tick failed:", err.message);
  } finally {
    ticking = false;
  }
}

function status() {
  const nowMs = Date.now();
  return describe({
    phase: currentPhase(nowMs), obs, held: held(nowMs), usedSeconds: state.usedSeconds, capSeconds: config.capSeconds,
    startedAtMs: run.startedAtMs, estimateSeconds: estimateStartSeconds(state.history), nowMs,
  });
}

function isAdmin(req) {
  const given = Buffer.from(String(req.headers["x-waker-key"] ?? ""));
  const expected = Buffer.from(config.adminKey);
  return expected.length > 0 && given.length === expected.length && timingSafeEqual(given, expected);
}

function send(res, code, body) {
  res.writeHead(code, { "Content-Type": "application/json", "Cache-Control": "no-store" });
  res.end(JSON.stringify(body));
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url ?? "/", "http://waker");
  const route = `${req.method} ${url.pathname}`;
  try {
    switch (route) {
      case "GET /waker/ping":
        return send(res, 200, { ok: true });
      case "GET /waker/status":
        // The page polls this while open, so it doubles as the "someone is here" heartbeat.
        run.lastActivityMs = Date.now();
        return send(res, 200, status());
      case "POST /waker/start": {
        run.lastActivityMs = Date.now();
        const now = status();
        if (now.phase === "stopped" || now.phase === "stopping") {
          run.startWanted = true;
          tick();
        }
        return send(res, 202, status());
      }
      case "POST /waker/hold": {
        if (!isAdmin(req)) return send(res, 403, { error: "forbidden" });
        const minutes = Math.max(0, Math.min(24 * 60, Number(url.searchParams.get("minutes") ?? "120")));
        state.holdUntil = minutes > 0 ? Date.now() + minutes * 60_000 : 0;
        run.lastActivityMs = Date.now();
        await saveState();
        log(minutes > 0 ? `Held awake for ${minutes} min` : "Hold released");
        return send(res, 200, { ...status(), holdUntil: state.holdUntil ? new Date(state.holdUntil).toISOString() : null });
      }
      default:
        return send(res, 404, { error: "not found" });
    }
  } catch (err) {
    log("Request failed:", err.message);
    return send(res, 500, { error: "internal error" });
  }
});

await loadState();
for (const [name, value] of Object.entries({ DB_ID: config.dbId, API_ID: config.apiId })) {
  if (!value) throw new Error(`${name} is required`);
}
server.listen(config.port, "127.0.0.1", () => log(`Waker listening on ${config.port}`));
setInterval(tick, 10_000);
tick();

process.on("SIGTERM", async () => {
  await saveState();
  process.exit(0);
});
