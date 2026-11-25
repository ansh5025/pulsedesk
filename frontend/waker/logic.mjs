// Pure decision logic for the waker, kept separate from I/O so it can be unit tested.

export const DEFAULT_START_SECONDS = 180;

// Calendar day in the configured time zone (offset in minutes from UTC), e.g. "2026-11-26".
export function dayKey(nowMs, offsetMinutes) {
  return new Date(nowMs + offsetMinutes * 60_000).toISOString().slice(0, 10);
}

// Resets the daily usage counter when the day changes.
export function rollDay(state, day) {
  return state.day === day ? state : { ...state, day, usedSeconds: 0 };
}

// Typical time from "start" to "API healthy", from the last few cold starts.
export function estimateStartSeconds(history) {
  const recent = (history ?? []).slice(-5);
  if (recent.length === 0) return DEFAULT_START_SECONDS;
  return Math.round(recent.reduce((sum, s) => sum + s, 0) / recent.length);
}

export function recordStart(history, seconds) {
  return [...(history ?? []), Math.round(seconds)].slice(-5);
}

// obs: { db, api } ARM running status ('Running', 'Stopped', ...), apiHealthy.
// run: { startRequestedAt, stopRequestedAt } timestamps of commands not yet reflected in obs.
export function phaseOf(obs, run, nowMs) {
  const bothStopped = obs.db === "Stopped" && obs.api === "Stopped";
  if (run.stopRequestedAt && !bothStopped) return "stopping";
  if (obs.apiHealthy) return "ready";
  // ARM takes a few seconds to report a start; don't treat that window as stopped and start twice.
  if (run.startRequestedAt && nowMs - run.startRequestedAt < 60_000) return "starting";
  return bothStopped ? "stopped" : "starting";
}

// Returns { action: 'start' | 'stop' | null, reason }.
export function decide({ phase, nowMs, lastActivityMs, idleMs, held, usedSeconds, capSeconds, startWanted }) {
  const capped = capSeconds > 0 && usedSeconds >= capSeconds;
  if (phase === "starting" || phase === "ready") {
    if (held) return { action: null };
    if (capped) return { action: "stop", reason: "daily limit reached" };
    if (nowMs - lastActivityMs >= idleMs) return { action: "stop", reason: "no visitors" };
    return { action: null };
  }
  if (phase === "stopped" && startWanted && (held || !capped)) return { action: "start", reason: "visitor" };
  return { action: null };
}

// What the wake-up screen shows.
export function describe({ phase, obs, held, usedSeconds, capSeconds, startedAtMs, estimateSeconds, nowMs }) {
  const capped = !held && capSeconds > 0 && usedSeconds >= capSeconds;
  const base = { usedSeconds: Math.round(usedSeconds), capSeconds, held };
  if (phase === "ready") return { ...base, phase };
  if (phase === "stopped" || (phase === "stopping" && capped)) {
    return { ...base, phase: capped ? "capped" : phase };
  }
  if (phase === "stopping") return { ...base, phase, label: "Finishing a previous shutdown" };
  const elapsed = startedAtMs ? Math.max(0, (nowMs - startedAtMs) / 1000) : 0;
  const dbUp = obs.dbOpen === true;
  return {
    ...base,
    phase,
    step: dbUp ? 2 : 1,
    steps: 2,
    label: dbUp ? "Starting the API" : "Starting the database",
    elapsedSeconds: Math.round(elapsed),
    etaSeconds: Math.max(5, Math.round(estimateSeconds - elapsed)),
  };
}
