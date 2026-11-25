import { useState, type FormEvent } from "react";
import { Link, useParams } from "react-router-dom";
import { api, type Comment, type HistoryItem, type Incident, type ServiceItem, type Status } from "../api";
import { errText, useLoad } from "../hooks";
import { ago, label, minutes, stamp, until } from "../format";
import { ErrorLine, Head, Loading, PriorityTag, StatusText } from "../components/bits";
import { useSession } from "../session";

const NEXT: Record<Status, Status[]> = {
  OPEN: ["ACKNOWLEDGED", "IN_PROGRESS", "RESOLVED"],
  ACKNOWLEDGED: ["IN_PROGRESS", "RESOLVED"],
  IN_PROGRESS: ["RESOLVED"],
  RESOLVED: ["IN_PROGRESS", "CLOSED"],
  CLOSED: [],
};
const VERB: Record<Status, string> = {
  OPEN: "Reopen", ACKNOWLEDGED: "Acknowledge", IN_PROGRESS: "Start work", RESOLVED: "Mark resolved", CLOSED: "Close",
};

export default function IncidentDetail() {
  const { id = "" } = useParams();
  const { nameOf, members, isManager } = useSession();
  const inc = useLoad(() => api.get<Incident>(`/incidents/${id}`), [id]);
  const comments = useLoad(() => api.get<Comment[]>(`/incidents/${id}/comments`), [id]);
  const history = useLoad(() => api.get<HistoryItem[]>(`/incidents/${id}/history`), [id]);
  const services = useLoad(() => api.get<ServiceItem[]>("/services"));
  const [error, setError] = useState<string | null>(null);
  const [body, setBody] = useState("");
  const [busy, setBusy] = useState(false);

  const refresh = () => Promise.all([inc.reload(), history.reload()]);
  const act = async (fn: () => Promise<unknown>) => {
    setError(null);
    setBusy(true);
    try { await fn(); await refresh(); } catch (e) { setError(errText(e)); } finally { setBusy(false); }
  };

  const post = async (e: FormEvent) => {
    e.preventDefault();
    await act(async () => {
      await api.post(`/incidents/${id}/comments`, { body: body.trim() });
      setBody("");
      await comments.reload();
    });
  };

  if (inc.loading && !inc.data) return <Loading />;
  if (!inc.data) return (<><Head title="Incident not found" /><ErrorLine msg={inc.error} /><Link to="/incidents">Back to incidents</Link></>);

  const i = inc.data;
  const next = NEXT[i.status].filter((s) => s !== "CLOSED" || isManager);
  const service = services.data?.find((s) => s.id === i.serviceId);
  const active = i.status === "OPEN" || i.status === "ACKNOWLEDGED" || i.status === "IN_PROGRESS";
  const resp = until(i.sla.responseDueAt);
  const reso = until(i.sla.resolutionDueAt);

  return (
    <>
      <Head title={i.title} sub={<><PriorityTag p={i.priority} /> &nbsp;<StatusText s={i.status} /> · opened {ago(i.createdAt)}</>}>
        <Link to="/incidents" className="muted">← All incidents</Link>
      </Head>

      <div className="detail">
        <div>
          {i.description && <p style={{ whiteSpace: "pre-wrap", marginBottom: 20 }}>{i.description}</p>}
          <div className="actions">
            {next.map((s) => (
              <button key={s} className={s === "RESOLVED" ? "btn" : "btn ghost"} disabled={busy} onClick={() => act(() => api.put(`/incidents/${id}/status`, { status: s }))}>{VERB[s]}</button>
            ))}
            {next.length === 0 && <span className="muted">This incident is closed.</span>}
          </div>
          <ErrorLine msg={error} />

          <section className="section">
            <h2>Discussion</h2>
            {comments.data?.length === 0 && <div className="empty">No comments yet.</div>}
            {comments.data?.map((c) => (
              <div className="comment" key={c.id}>
                <div className="by"><b>{nameOf(c.authorId)}</b> · {stamp(c.createdAt)}</div>
                <p>{c.body}</p>
              </div>
            ))}
            <form onSubmit={post} style={{ marginTop: 14 }}>
              <div className="field"><label htmlFor="c">Add a comment</label><textarea id="c" value={body} onChange={(e) => setBody(e.target.value)} maxLength={2000} required /></div>
              <button className="btn" disabled={busy || !body.trim()}>Post</button>
            </form>
          </section>
        </div>

        <aside>
          <div className="facts">
            <div><span className="k">Service</span><span>{service?.name ?? "—"}</span></div>
            <div><span className="k">Source</span><span>{i.source === "HEALTH_CHECK" ? "Health check" : "Reported manually"}</span></div>
            <div><span className="k">Assignee</span><span>{nameOf(i.assigneeId)}</span></div>
            {i.escalationLevel > 0 && <div><span className="k">Escalation</span><span className="late">Level {i.escalationLevel}</span></div>}
          </div>

          <section className="section" style={{ marginTop: 22 }}>
            <h2>Deadlines</h2>
            <div className="facts">
              <div>
                <span className="k">Respond within {minutes(i.sla.responseMinutes)}</span>
                <span className={i.sla.responseBreached ? "late" : ""}>
                  {i.acknowledgedAt ? `done ${stamp(i.acknowledgedAt)}` : i.status === "OPEN" ? resp.text : i.sla.responseBreached ? "missed" : "—"}
                </span>
              </div>
              <div>
                <span className="k">Resolve within {minutes(i.sla.resolutionMinutes)}</span>
                <span className={i.sla.resolutionBreached ? "late" : ""}>
                  {i.resolvedAt ? `done ${stamp(i.resolvedAt)}` : active ? reso.text : "—"}
                </span>
              </div>
            </div>
          </section>

          <section className="section" style={{ marginTop: 22 }}>
            <h2>Assign</h2>
            <select aria-label="Assignee" value={i.assigneeId ?? ""} disabled={busy || !active}
              onChange={(e) => e.target.value && act(() => api.put(`/incidents/${id}/assignee`, { assigneeId: e.target.value }))}>
              <option value="" disabled>Choose a person</option>
              {members.map((m) => <option key={m.membershipId} value={m.membershipId}>{m.fullName} · {label(m.role)}</option>)}
            </select>
          </section>

          <section className="section" style={{ marginTop: 22 }}>
            <h2>History</h2>
            <ul className="timeline" style={{ marginTop: 10 }}>
              {history.data?.map((h, n) => (
                <li key={n}>
                  <span className="when">{stamp(h.at)}</span>
                  {describe(h, nameOf)}
                </li>
              ))}
            </ul>
          </section>
        </aside>
      </div>
    </>
  );
}

function describe(h: HistoryItem, nameOf: (id: string | null | undefined) => string): string {
  const who = h.actorId ? nameOf(h.actorId) : "System";
  switch (h.type) {
    case "CREATED": return h.actorId ? `${who} opened it` : (h.note ?? "Opened automatically");
    case "STATUS_CHANGED": return `${who} moved it from ${label(h.from ?? "")} to ${label(h.to ?? "")}`;
    case "ASSIGNED": return `${who} assigned it to ${nameOf(h.to)}`;
    case "ESCALATED": return h.note ?? "Escalated";
    default: return h.note ? `${label(h.type)}: ${h.note}` : label(h.type);
  }
}
