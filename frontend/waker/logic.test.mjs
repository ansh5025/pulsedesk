// Run with: node --test frontend/waker
import test from "node:test";
import assert from "node:assert/strict";
import { dayKey, rollDay, estimateStartSeconds, recordStart, phaseOf, decide, describe } from "./logic.mjs";

const MIN = 60_000;
const base = { phase: "ready", nowMs: 10 * MIN, lastActivityMs: 9 * MIN, idleMs: 3 * MIN, held: false, usedSeconds: 0, capSeconds: 3600, startWanted: false };

test("day rolls over at local midnight, not UTC midnight", () => {
  const utc1830 = Date.UTC(2026, 10, 25, 18, 30); // 00:00 IST on the 26th
  assert.equal(dayKey(utc1830 - 1, 330), "2026-11-25");
  assert.equal(dayKey(utc1830, 330), "2026-11-26");
  assert.deepEqual(rollDay({ day: "2026-11-25", usedSeconds: 900 }, "2026-11-26"), { day: "2026-11-26", usedSeconds: 0 });
  const same = { day: "2026-11-26", usedSeconds: 900 };
  assert.equal(rollDay(same, "2026-11-26"), same);
});

test("start estimate averages the last five cold starts", () => {
  assert.equal(estimateStartSeconds([]), 180);
  const history = [100, 100, 100, 100, 100].reduce(recordStart, [500]);
  assert.deepEqual(history, [100, 100, 100, 100, 100]);
  assert.equal(estimateStartSeconds(history), 100);
});

test("phase follows ARM status, health and in-flight commands", () => {
  const stopped = { db: "Stopped", api: "Stopped", apiHealthy: false };
  assert.equal(phaseOf(stopped, {}, 0), "stopped");
  assert.equal(phaseOf(stopped, { startRequestedAt: 1 }, 30_000), "starting");
  assert.equal(phaseOf(stopped, { startRequestedAt: 1 }, 62_000), "stopped");
  assert.equal(phaseOf({ db: "Running", api: "Running", apiHealthy: false }, {}, 0), "starting");
  assert.equal(phaseOf({ db: "Running", api: "Running", apiHealthy: true }, {}, 0), "ready");
  assert.equal(phaseOf({ db: "Running", api: "Stopped", apiHealthy: false }, { stopRequestedAt: 1 }, 0), "stopping");
});

test("stops after the idle window, but not while a visitor is active or a hold is set", () => {
  assert.equal(decide(base).action, null);
  assert.equal(decide({ ...base, lastActivityMs: 7 * MIN }).action, "stop");
  assert.equal(decide({ ...base, phase: "starting", lastActivityMs: 7 * MIN }).action, "stop");
  assert.equal(decide({ ...base, lastActivityMs: 0, held: true }).action, null);
});

test("daily cap stops a running demo and refuses new visitor starts, unless held", () => {
  const capped = { ...base, usedSeconds: 3600 };
  assert.deepEqual(decide(capped), { action: "stop", reason: "daily limit reached" });
  assert.equal(decide({ ...capped, held: true }).action, null);
  assert.equal(decide({ ...capped, phase: "stopped", startWanted: true }).action, null);
  assert.equal(decide({ ...capped, phase: "stopped", startWanted: true, held: true }).action, "start");
  assert.equal(decide({ ...base, phase: "stopped", startWanted: true }).action, "start");
  assert.equal(decide({ ...base, capSeconds: 0, usedSeconds: 99999, phase: "stopped", startWanted: true }).action, "start");
});

test("status shows the current step and a countdown", () => {
  const starting = { phase: "starting", obs: { dbOpen: false }, held: false, usedSeconds: 0, capSeconds: 3600, startedAtMs: 1, estimateSeconds: 180, nowMs: 60_001 };
  assert.deepEqual(
    { ...describe(starting) },
    { phase: "starting", step: 1, steps: 2, label: "Starting the database", elapsedSeconds: 60, etaSeconds: 120, usedSeconds: 0, capSeconds: 3600, held: false },
  );
  const late = describe({ ...starting, obs: { dbOpen: true }, nowMs: 400_000 });
  assert.equal(late.step, 2);
  assert.equal(late.etaSeconds, 5);
  assert.equal(describe({ ...starting, phase: "stopped", usedSeconds: 3600 }).phase, "capped");
  assert.equal(describe({ ...starting, phase: "stopped" }).phase, "stopped");
});
