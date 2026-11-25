import { ReactNode, useEffect, useRef, useState } from "react";
import { Logo } from "./components/bits";

// Mirrors describe() in waker/logic.mjs.
type WakeStatus = {
  phase: "stopped" | "starting" | "ready" | "stopping" | "capped";
  step?: number;
  steps?: number;
  label?: string;
  elapsedSeconds?: number;
  etaSeconds?: number;
  capSeconds: number;
};

const HEARTBEAT_MS = 60_000;
const POLL_MS = 3_000;

async function call(method: "GET" | "POST", path: string): Promise<WakeStatus | null> {
  const res = await fetch(`/waker/${path}`, { method, cache: "no-store" });
  if (!res.ok || !res.headers.get("content-type")?.includes("application/json")) return null;
  return res.json();
}

const clock = (s: number) => `${Math.floor(s / 60)}:${String(Math.max(0, Math.floor(s % 60))).padStart(2, "0")}`;

// On Azure the database and API sleep when nobody is using the site. This gate wakes them, shows progress,
// and while the app is open sends a heartbeat so they stay up. Without the waker (local runs) it renders
// the app straight away.
export default function WakeGate({ children }: { children: ReactNode }) {
  const [mode, setMode] = useState<"checking" | "off" | "on">("checking");
  const [status, setStatus] = useState<WakeStatus | null>(null);
  const [receivedAt, setReceivedAt] = useState(Date.now());
  const [now, setNow] = useState(Date.now());
  const failures = useRef(0);

  const ready = status?.phase === "ready";

  useEffect(() => {
    let cancelled = false;
    let timer: number | undefined;
    const loop = async () => {
      let next = POLL_MS;
      try {
        // While not ready, every poll asks to start (it's idempotent); once ready, just report presence.
        const result = ready ? await call("GET", "status") : await call("POST", "start");
        if (cancelled) return;
        if (!result) throw new Error("waker unavailable");
        failures.current = 0;
        setMode("on");
        setStatus(result);
        setReceivedAt(Date.now());
        next = result.phase === "ready" ? HEARTBEAT_MS : POLL_MS;
      } catch {
        if (cancelled) return;
        failures.current += 1;
        // No waker after a few tries means a local run; on Azure the sidecar may just be a moment behind nginx.
        if (mode === "checking" && failures.current >= 3) { setMode("off"); return; }
        next = mode === "checking" ? 1000 : POLL_MS;
      }
      timer = window.setTimeout(loop, next);
    };
    timer = window.setTimeout(loop, mode === "checking" ? 0 : ready ? HEARTBEAT_MS : POLL_MS);
    return () => { cancelled = true; window.clearTimeout(timer); };
  }, [mode, ready]);

  useEffect(() => {
    if (ready || mode !== "on") return;
    const id = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(id);
  }, [ready, mode]);

  if (mode === "off" || (mode === "on" && ready)) return <>{children}</>;
  if (mode === "checking" || !status) return <div className="splash">Loading…</div>;

  const drift = (now - receivedAt) / 1000;
  const elapsed = (status.elapsedSeconds ?? 0) + drift;
  const eta = Math.max(0, (status.etaSeconds ?? 0) - drift);
  const pct = status.phase === "starting" ? Math.min(95, (elapsed / Math.max(1, elapsed + eta)) * 100) : 4;
  const capHours = status.capSeconds / 3600;

  return (
    <div className="wake">
      <div className="wake-card">
        <div className="logo"><Logo /> PulseDesk</div>
        {status.phase === "capped" ? (
          <>
            <h1>Today's running time is used up</h1>
            <p className="muted">
              This application runs on a small cloud budget, so it's limited to {capHours === 1 ? "1 hour" : `${capHours} hours`} a day.
              It'll be available again after midnight IST.
            </p>
          </>
        ) : (
          <>
            <h1>Waking up PulseDesk</h1>
            <p className="muted">
              The application sleeps when nobody is using it, to keep cloud costs down. It usually takes a few
              minutes to start; this page opens it as soon as it's ready.
            </p>
            <div className="wake-track" role="progressbar" aria-valuenow={Math.round(pct)} aria-valuemin={0} aria-valuemax={100}>
              <div className="wake-fill" style={{ width: `${pct}%` }} />
            </div>
            <ol className="wake-steps">
              <li className={status.step === 2 ? "done" : status.step === 1 ? "now" : ""}>Starting the database</li>
              <li className={status.step === 2 ? "now" : ""}>Starting the API</li>
            </ol>
            <p className="wake-time mono">
              {status.phase === "starting"
                ? eta > 5 ? `About ${clock(eta)} left · ${clock(elapsed)} elapsed` : `Almost there · ${clock(elapsed)} elapsed`
                : status.phase === "stopping" ? "Finishing a previous shutdown, then starting…" : "Requesting start…"}
            </p>
          </>
        )}
      </div>
    </div>
  );
}
