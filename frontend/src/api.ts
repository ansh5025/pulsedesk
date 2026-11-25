export type Role = "ORG_ADMIN" | "MANAGER" | "TEAM_LEAD" | "DEVELOPER";
export type Status = "OPEN" | "ACKNOWLEDGED" | "IN_PROGRESS" | "RESOLVED" | "CLOSED";
export type Priority = "LOW" | "MEDIUM" | "HIGH" | "CRITICAL";
export type Health = "UNKNOWN" | "UP" | "DOWN";

export interface AuthResponse { accessToken: string; username: string; role: Role }
export interface Me { userId: string; username: string; organizationId: string; membershipId: string; role: Role }
export interface Org { id: string; name: string; slug: string }
export interface Member { membershipId: string; userId: string; username: string; fullName: string; role: Role; teamId: string | null }
export interface Sla {
  responseMinutes: number; resolutionMinutes: number; responseDueAt: string; resolutionDueAt: string;
  responseBreached: boolean; resolutionBreached: boolean;
}
export interface Incident {
  id: string; serviceId: string | null; teamId: string | null; title: string; description: string | null;
  status: Status; priority: Priority; source: "MANUAL" | "HEALTH_CHECK"; assigneeId: string | null;
  createdById: string | null; createdAt: string; acknowledgedAt: string | null; resolvedAt: string | null;
  closedAt: string | null; sla: Sla; escalationLevel: number;
}
export interface Comment { id: string; authorId: string; body: string; createdAt: string }
export interface HistoryItem { actorId: string | null; type: string; from: string | null; to: string | null; note: string | null; at: string }
export interface HealthView {
  configId: string; url: string; expectedStatus: number; intervalSeconds: number; timeoutMs: number;
  failureThreshold: number; enabled: boolean; status: Health; consecutiveFailures: number;
  lastCheckedAt: string | null; lastStatusChangeAt: string | null;
}
export interface ServiceItem { id: string; name: string; description: string | null; teamId: string | null; health: HealthView | null }
export interface CheckResult { checkedAt: string; success: boolean; statusCode: number | null; responseTimeMs: number; error: string | null }
export interface Team { id: string; name: string; teamLeadId: string | null; memberCount: number }
export interface Pending { id: string; fullName: string; role: Role; teamId: string | null; expiresAt: string }
export interface Notice { id: string; incidentId: string | null; type: string; title: string; message: string | null; createdAt: string; read: boolean }
export interface Summary {
  periodDays: number; totalIncidents: number; activeIncidents: number;
  byStatus: Record<string, number>; byPriority: Record<string, number>; bySource: Record<string, number>;
  mttaMinutes: number | null; mttrMinutes: number | null;
  sla: { responseEvaluated: number; responseMet: number; responseMetPercent: number | null;
         resolutionEvaluated: number; resolutionMet: number; resolutionMetPercent: number | null };
  topServices: { serviceId: string; serviceName: string; incidents: number }[];
  serviceHealth: Record<string, number>;
}

const KEY = "pulsedesk.token";
export const tokenStore = {
  get: () => localStorage.getItem(KEY),
  set: (t: string) => localStorage.setItem(KEY, t),
  clear: () => localStorage.removeItem(KEY),
};

export class ApiError extends Error {
  constructor(public status: number, message: string) { super(message); }
}

let onUnauthorized: () => void = () => {};
export const setUnauthorizedHandler = (fn: () => void) => { onUnauthorized = fn; };

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const headers: Record<string, string> = {};
  const token = tokenStore.get();
  if (token) headers.Authorization = `Bearer ${token}`;
  if (body !== undefined) headers["Content-Type"] = "application/json";
  const res = await fetch(`/api/v1${path}`, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  const text = await res.text();
  const data = text ? safeJson(text) : null;
  if (!res.ok) {
    // A 401 on the login form is a wrong password, not an expired session.
    if (res.status === 401 && token) onUnauthorized();
    throw new ApiError(res.status, messageFrom(data, res.status));
  }
  return data as T;
}

function safeJson(text: string): unknown {
  try { return JSON.parse(text); } catch { return null; }
}

function messageFrom(data: unknown, status: number): string {
  if (data && typeof data === "object") {
    const d = data as Record<string, unknown>;
    if (d.errors && typeof d.errors === "object") {
      const parts = Object.entries(d.errors as Record<string, string>).map(([f, m]) => `${f}: ${m}`);
      if (parts.length) return parts.join("; ");
    }
    if (typeof d.detail === "string") return d.detail;
    if (typeof d.message === "string") return d.message;
  }
  if (status === 429) return "Too many attempts. Try again in a few minutes.";
  if (status === 403) return "You don't have permission to do that.";
  return `Request failed (${status})`;
}

export const api = {
  get: <T>(p: string) => request<T>("GET", p),
  post: <T>(p: string, b?: unknown) => request<T>("POST", p, b ?? {}),
  put: <T>(p: string, b?: unknown) => request<T>("PUT", p, b ?? {}),
  patch: <T>(p: string, b?: unknown) => request<T>("PATCH", p, b ?? {}),
  del: <T>(p: string) => request<T>("DELETE", p),
};
