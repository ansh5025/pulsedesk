export function minutes(m: number | null | undefined): string {
  if (m == null) return "—";
  const total = Math.round(m);
  if (total < 60) return `${total}m`;
  const h = Math.floor(total / 60);
  const rest = total % 60;
  if (h < 48) return rest ? `${h}h ${rest}m` : `${h}h`;
  const d = Math.floor(h / 24);
  return `${d}d ${h % 24}h`;
}

export function ago(iso: string | null | undefined): string {
  if (!iso) return "never";
  const secs = Math.round((Date.now() - new Date(iso).getTime()) / 1000);
  if (secs < 45) return "just now";
  const m = Math.round(secs / 60);
  if (m < 60) return `${m} min ago`;
  const h = Math.round(m / 60);
  if (h < 24) return `${h} h ago`;
  const d = Math.round(h / 24);
  return d === 1 ? "yesterday" : `${d} days ago`;
}

export function stamp(iso: string | null | undefined): string {
  if (!iso) return "—";
  return new Date(iso).toLocaleString(undefined, { day: "numeric", month: "short", hour: "2-digit", minute: "2-digit" });
}

/** Time left until a deadline, or how far past it we are. */
export function until(iso: string): { text: string; late: boolean } {
  const diff = Math.round((new Date(iso).getTime() - Date.now()) / 60000);
  return diff >= 0 ? { text: `${minutes(diff)} left`, late: false } : { text: `${minutes(-diff)} over`, late: true };
}

/** When a health check is next due, based on its last run and interval. */
export function nextCheck(lastCheckedAt: string | null, intervalSeconds: number): string {
  if (!lastCheckedAt) return "Next check soon";
  const secs = Math.round((new Date(lastCheckedAt).getTime() + intervalSeconds * 1000 - Date.now()) / 1000);
  if (secs <= 0) return "Next check due now";
  if (secs < 60) return `Next check in ${secs}s`;
  return `Next check in ${minutes(Math.ceil(secs / 60))}`;
}

export function label(s: string): string {
  const t = s.toLowerCase().replace(/_/g, " ");
  return t.charAt(0).toUpperCase() + t.slice(1);
}

export function shortId(id: string): string {
  return id.slice(0, 8);
}
