import { useState, type FormEvent } from "react";
import { Link, useNavigate } from "react-router-dom";
import { api, type Incident, type Priority, type ServiceItem, type Status } from "../api";
import { errText, useInterval, useLoad } from "../hooks";
import { ago, label, until } from "../format";
import { ErrorLine, Head, Loading, PriorityTag, StatusText } from "../components/bits";
import { useSession } from "../session";

const FILTERS: { key: Status | "ACTIVE" | "ALL"; text: string }[] = [
  { key: "ACTIVE", text: "Active" },
  { key: "OPEN", text: "Open" },
  { key: "RESOLVED", text: "Resolved" },
  { key: "CLOSED", text: "Closed" },
  { key: "ALL", text: "All" },
];
const ACTIVE: Status[] = ["OPEN", "ACKNOWLEDGED", "IN_PROGRESS"];

export default function Incidents() {
  const { nameOf } = useSession();
  const [filter, setFilter] = useState<(typeof FILTERS)[number]["key"]>("ACTIVE");
  const [creating, setCreating] = useState(false);
  const services = useLoad(() => api.get<ServiceItem[]>("/services"));
  const list = useLoad(() => {
    const q = filter !== "ACTIVE" && filter !== "ALL" ? `&status=${filter}` : "";
    return api.get<Incident[]>(`/incidents?limit=200${q}`);
  }, [filter]);
  useInterval(() => void list.reload(), 20000);

  const weight: Record<Priority, number> = { CRITICAL: 3, HIGH: 2, MEDIUM: 1, LOW: 0 };
  const rows = (list.data ?? [])
    .filter((i) => filter !== "ACTIVE" || ACTIVE.includes(i.status))
    .sort((a, b) => (ACTIVE.includes(b.status) ? 1 : 0) - (ACTIVE.includes(a.status) ? 1 : 0)
      || weight[b.priority] - weight[a.priority] || b.createdAt.localeCompare(a.createdAt));
  const serviceName = (id: string | null) => services.data?.find((s) => s.id === id)?.name;

  return (
    <>
      <Head title="Incidents" sub={list.data ? `${rows.length} shown` : undefined}>
        <button className="btn" onClick={() => setCreating((v) => !v)}>{creating ? "Cancel" : "Report an incident"}</button>
      </Head>
      {creating && <NewIncident services={services.data ?? []} onDone={() => { setCreating(false); void list.reload(); }} />}
      <div className="seg" role="group" aria-label="Filter" style={{ marginBottom: 14 }}>
        {FILTERS.map((f) => <button key={f.key} className={f.key === filter ? "on" : ""} onClick={() => setFilter(f.key)}>{f.text}</button>)}
      </div>
      <ErrorLine msg={list.error} />
      {list.loading && !list.data && <Loading />}
      {list.data && (rows.length === 0 ? <div className="empty">Nothing here. {filter === "ACTIVE" ? "No active incidents right now." : ""}</div> : (
        <table>
          <thead><tr><th>Priority</th><th>Incident</th><th>Status</th><th>Assigned to</th><th>Deadline</th><th className="right">Opened</th></tr></thead>
          <tbody>
            {rows.map((i) => {
              const active = ACTIVE.includes(i.status);
              const due = active ? (i.status === "OPEN" ? until(i.sla.responseDueAt) : until(i.sla.resolutionDueAt)) : null;
              return (
                <tr key={i.id}>
                  <td><PriorityTag p={i.priority} /></td>
                  <td className="title">
                    <Link to={`/incidents/${i.id}`}>{i.title}</Link>
                    <div className="faint" style={{ fontSize: 12 }}>
                      {serviceName(i.serviceId) ?? "No service"}{i.source === "HEALTH_CHECK" && " · opened by health check"}
                      {i.escalationLevel > 0 && " · escalated"}
                    </div>
                  </td>
                  <td><StatusText s={i.status} /></td>
                  <td>{nameOf(i.assigneeId)}</td>
                  <td className={due?.late ? "late" : "muted"}>{due ? `${i.status === "OPEN" ? "Respond" : "Resolve"}: ${due.text}` : "—"}</td>
                  <td className="right muted">{ago(i.createdAt)}</td>
                </tr>
              );
            })}
          </tbody>
        </table>
      ))}
    </>
  );
}

function NewIncident({ services, onDone }: { services: ServiceItem[]; onDone: () => void }) {
  const nav = useNavigate();
  const [title, setTitle] = useState("");
  const [description, setDescription] = useState("");
  const [priority, setPriority] = useState<Priority>("MEDIUM");
  const [serviceId, setServiceId] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const created = await api.post<Incident>("/incidents", {
        title: title.trim(), description: description.trim() || undefined, priority, serviceId: serviceId || undefined,
      });
      onDone();
      nav(`/incidents/${created.id}`);
    } catch (err) {
      setError(errText(err));
      setBusy(false);
    }
  };

  return (
    <form className="box" onSubmit={submit}>
      <div className="field"><label htmlFor="t">What's wrong?</label><input id="t" value={title} onChange={(e) => setTitle(e.target.value)} maxLength={200} required autoFocus /></div>
      <div className="field"><label htmlFor="d">Details</label><textarea id="d" value={description} onChange={(e) => setDescription(e.target.value)} /></div>
      <div className="row" style={{ marginBottom: 12 }}>
        <div className="field">
          <label htmlFor="p">Priority</label>
          <select id="p" value={priority} onChange={(e) => setPriority(e.target.value as Priority)}>
            {(["LOW", "MEDIUM", "HIGH", "CRITICAL"] as Priority[]).map((p) => <option key={p} value={p}>{label(p)}</option>)}
          </select>
        </div>
        <div className="field">
          <label htmlFor="s">Service</label>
          <select id="s" value={serviceId} onChange={(e) => setServiceId(e.target.value)}>
            <option value="">None</option>
            {services.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
          </select>
        </div>
      </div>
      <p className="faint" style={{ fontSize: 12 }}>Priority can't be changed later. It sets the response and resolution deadlines.</p>
      <ErrorLine msg={error} />
      <button className="btn" disabled={busy}>{busy ? "Saving…" : "Open incident"}</button>
    </form>
  );
}
