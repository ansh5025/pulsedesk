import { useState, type FormEvent } from "react";
import { api, type CheckResult, type ServiceItem, type Team } from "../api";
import { errText, useInterval, useLoad } from "../hooks";
import { ago, nextCheck, stamp } from "../format";
import { ErrorLine, Head, HealthTag, Loading } from "../components/bits";
import { useSession } from "../session";

export default function Services() {
  const { isManager } = useSession();
  const list = useLoad(() => api.get<ServiceItem[]>("/services"));
  const teams = useLoad(() => api.get<Team[]>("/teams"));
  const [adding, setAdding] = useState(false);
  const [open, setOpen] = useState<string | null>(null);
  useInterval(() => void list.reload(), 15000);

  const teamName = (id: string | null) => teams.data?.find((t) => t.id === id)?.name ?? "No team";

  return (
    <>
      <Head title="Services" sub="What we monitor, and whether it's answering">
        {isManager && <button className="btn" onClick={() => setAdding((v) => !v)}>{adding ? "Cancel" : "Add a service"}</button>}
      </Head>
      {adding && <NewService teams={teams.data ?? []} onDone={() => { setAdding(false); void list.reload(); }} />}
      <ErrorLine msg={list.error} />
      {list.loading && !list.data && <Loading />}
      {list.data && (list.data.length === 0 ? <div className="empty">No services yet.{isManager ? " Add the first one to start monitoring." : ""}</div> : (
        <table>
          <thead><tr><th>Health</th><th>Service</th><th>Owner</th><th>Endpoint</th><th className="right">Last check</th></tr></thead>
          <tbody>
            {list.data.map((s) => (
              <ServiceRow key={s.id} s={s} team={teamName(s.teamId)} isManager={isManager} open={open === s.id}
                toggle={() => setOpen(open === s.id ? null : s.id)} reload={() => list.reload()} />
            ))}
          </tbody>
        </table>
      ))}
    </>
  );
}

function ServiceRow({ s, team, isManager, open, toggle, reload }: {
  s: ServiceItem; team: string; isManager: boolean; open: boolean; toggle: () => void; reload: () => Promise<void>;
}) {
  const h = s.health;
  return (
    <>
      <tr className="click" onClick={toggle}>
        <td>{h ? <HealthTag h={h.enabled ? h.status : "UNKNOWN"} /> : <span className="faint">not monitored</span>}</td>
        <td className="title"><a href="#" onClick={(e) => e.preventDefault()}>{s.name}</a>{s.description && <div className="faint" style={{ fontSize: 12 }}>{s.description}</div>}</td>
        <td className="muted">{team}</td>
        <td className="mono muted" style={{ wordBreak: "break-all" }}>{h?.url ?? "—"}</td>
        <td className="right muted">
          {h ? (h.enabled ? <>{ago(h.lastCheckedAt)}<div className="faint" style={{ fontSize: 12 }}>{nextCheck(h.lastCheckedAt, h.intervalSeconds)}</div></> : "paused") : "—"}
        </td>
      </tr>
      {open && (
        <tr><td colSpan={5} style={{ paddingBottom: 22 }}>
          <ServicePanel s={s} isManager={isManager} reload={reload} />
        </td></tr>
      )}
    </>
  );
}

function ServicePanel({ s, isManager, reload }: { s: ServiceItem; isManager: boolean; reload: () => Promise<void> }) {
  const h = s.health;
  const results = useLoad(() => (h ? api.get<CheckResult[]>(`/services/${s.id}/health-check/results`) : Promise.resolve([])), [s.id, h?.lastCheckedAt]);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [editing, setEditing] = useState(!h);
  const act = async (fn: () => Promise<unknown>) => {
    setBusy(true); setError(null);
    try { await fn(); await reload(); await results.reload(); } catch (e) { setError(errText(e)); } finally { setBusy(false); }
  };

  return (
    <div className="cols" style={{ gap: 36, marginTop: 6 }}>
      <div>
        <h2 style={{ marginBottom: 8 }}>Recent checks</h2>
        {!h && <p className="muted">No health check yet. Set one up to start monitoring this service.</p>}
        {h && (results.data?.length ? (
          <table>
            <thead><tr><th>When</th><th>Result</th><th className="right">Time</th></tr></thead>
            <tbody>
              {results.data.slice(0, 8).map((r, n) => (
                <tr key={n}>
                  <td className="muted">{stamp(r.checkedAt)}</td>
                  <td>{r.success ? <span className="tag UP">{r.statusCode}</span> : <span className="tag DOWN">{r.statusCode ?? "failed"}</span>}{r.error && <span className="faint"> {r.error}</span>}</td>
                  <td className="right mono">{r.responseTimeMs} ms</td>
                </tr>
              ))}
            </tbody>
          </table>
        ) : <p className="muted">Nothing recorded yet.</p>)}
        {isManager && h && (
          <div className="actions" style={{ marginTop: 14 }}>
            <button className="btn ghost small" disabled={busy} onClick={() => act(() => api.post(`/services/${s.id}/health-check/run`))}>Check now</button>
            <button className="btn ghost small" disabled={busy} onClick={() => setEditing((v) => !v)}>{editing ? "Hide settings" : "Edit settings"}</button>
            <button className="btn ghost small" disabled={busy}
              onClick={() => act(() => api.put(`/services/${s.id}/health-check`, { url: h.url, expectedStatus: h.expectedStatus, intervalSeconds: h.intervalSeconds, timeoutMs: h.timeoutMs, failureThreshold: h.failureThreshold, enabled: !h.enabled }))}>
              {h.enabled ? "Pause" : "Resume"}
            </button>
            <button className="btn danger small" disabled={busy} onClick={() => confirm(`Retire ${s.name}? Its monitoring stops.`) && act(() => api.del(`/services/${s.id}`))}>Retire service</button>
          </div>
        )}
        <ErrorLine msg={error} />
      </div>
      <div>{isManager && editing && <HealthForm s={s} onSaved={async () => { setEditing(false); await reload(); await results.reload(); }} />}</div>
    </div>
  );
}

function HealthForm({ s, onSaved }: { s: ServiceItem; onSaved: () => Promise<void> }) {
  const h = s.health;
  const [url, setUrl] = useState(h?.url ?? "https://");
  const [expectedStatus, setExpected] = useState(h?.expectedStatus ?? 200);
  const [intervalSeconds, setInterval_] = useState(h?.intervalSeconds ?? 60);
  const [failureThreshold, setThreshold] = useState(h?.failureThreshold ?? 3);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const submit = async (e: FormEvent) => {
    e.preventDefault(); setBusy(true); setError(null);
    try {
      await api.put(`/services/${s.id}/health-check`, { url: url.trim(), expectedStatus, intervalSeconds, timeoutMs: h?.timeoutMs ?? 5000, failureThreshold, enabled: h?.enabled ?? true });
      await onSaved();
    } catch (err) { setError(errText(err)); } finally { setBusy(false); }
  };

  return (
    <form onSubmit={submit}>
      <h2 style={{ marginBottom: 10 }}>Health check</h2>
      <div className="field"><label htmlFor={`u${s.id}`}>URL to request</label><input id={`u${s.id}`} value={url} onChange={(e) => setUrl(e.target.value)} required /></div>
      <div className="row" style={{ marginBottom: 12 }}>
        <div className="field"><label>Expected status</label><input type="number" min={100} max={599} value={expectedStatus} onChange={(e) => setExpected(+e.target.value)} /></div>
        <div className="field"><label>Every (seconds)</label><input type="number" min={30} max={3600} value={intervalSeconds} onChange={(e) => setInterval_(+e.target.value)} /></div>
        <div className="field"><label>Down after N failures</label><input type="number" min={1} max={10} value={failureThreshold} onChange={(e) => setThreshold(+e.target.value)} /></div>
      </div>
      <p className="faint" style={{ fontSize: 12 }}>Private and internal addresses are refused, and redirects aren't followed.</p>
      <ErrorLine msg={error} />
      <button className="btn" disabled={busy}>{busy ? "Saving…" : "Save"}</button>
    </form>
  );
}

function NewService({ teams, onDone }: { teams: Team[]; onDone: () => void }) {
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [teamId, setTeamId] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const submit = async (e: FormEvent) => {
    e.preventDefault(); setBusy(true); setError(null);
    try { await api.post("/services", { name: name.trim(), description: description.trim() || undefined, teamId: teamId || undefined }); onDone(); }
    catch (err) { setError(errText(err)); setBusy(false); }
  };
  return (
    <form className="box" onSubmit={submit}>
      <div className="row" style={{ marginBottom: 12 }}>
        <div className="field"><label htmlFor="sn">Name</label><input id="sn" value={name} onChange={(e) => setName(e.target.value)} maxLength={100} required autoFocus /></div>
        <div className="field"><label htmlFor="st">Owning team</label>
          <select id="st" value={teamId} onChange={(e) => setTeamId(e.target.value)}>
            <option value="">None yet</option>
            {teams.map((t) => <option key={t.id} value={t.id}>{t.name}</option>)}
          </select>
        </div>
      </div>
      <div className="field"><label htmlFor="sd">What it does</label><input id="sd" value={description} onChange={(e) => setDescription(e.target.value)} maxLength={500} /></div>
      <ErrorLine msg={error} />
      <button className="btn" disabled={busy}>Add service</button>
    </form>
  );
}
