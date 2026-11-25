import { useState, type FormEvent } from "react";
import { api, type Pending, type Role, type Team } from "../api";
import { errText, useLoad } from "../hooks";
import { label, stamp } from "../format";
import { ErrorLine, Head, Loading } from "../components/bits";
import { useSession } from "../session";

const ROLES: Role[] = ["DEVELOPER", "TEAM_LEAD", "MANAGER", "ORG_ADMIN"];

export default function People() {
  const { me, members, isManager, refreshMembers } = useSession();
  const teams = useLoad(() => api.get<Team[]>("/teams"));
  const pending = useLoad(() => (isManager || me.role === "MANAGER" ? api.get<Pending[]>("/invitations") : Promise.resolve([])), [isManager]);
  const [error, setError] = useState<string | null>(null);
  const teamName = (id: string | null) => teams.data?.find((t) => t.id === id)?.name ?? "—";

  const act = async (fn: () => Promise<unknown>) => {
    setError(null);
    try { await fn(); await Promise.all([refreshMembers(), teams.reload(), pending.reload()]); } catch (e) { setError(errText(e)); }
  };

  return (
    <>
      <Head title="People" sub={`${members.length} in the organisation`} />
      <ErrorLine msg={error} />
      <table>
        <thead><tr><th>Name</th><th>Role</th><th>Team</th>{isManager && <th className="right">Manage</th>}</tr></thead>
        <tbody>
          {members.map((m) => (
            <tr key={m.membershipId}>
              <td className="title">{m.fullName}<div className="faint mono" style={{ fontSize: 12 }}>{m.username}</div></td>
              <td>
                {isManager && m.membershipId !== me.membershipId ? (
                  <select aria-label={`Role for ${m.fullName}`} value={m.role} onChange={(e) => act(() => api.patch(`/employees/${m.membershipId}/role`, { role: e.target.value }))}>
                    {ROLES.map((r) => <option key={r} value={r}>{label(r)}</option>)}
                  </select>
                ) : label(m.role)}
              </td>
              <td className="muted">{teamName(m.teamId)}</td>
              {isManager && (
                <td className="right">
                  {m.membershipId !== me.membershipId && (
                    <button className="btn danger small" onClick={() => confirm(`Deactivate ${m.fullName}?`) && act(() => api.del(`/employees/${m.membershipId}`))}>Deactivate</button>
                  )}
                </td>
              )}
            </tr>
          ))}
        </tbody>
      </table>

      <section className="section">
        <h2>Teams</h2>
        {teams.loading && !teams.data && <Loading />}
        {teams.data?.length === 0 && <div className="empty">No teams yet.</div>}
        {teams.data && teams.data.length > 0 && (
          <table>
            <thead><tr><th>Team</th><th>Lead</th><th className="right">Members</th></tr></thead>
            <tbody>
              {teams.data.map((t) => (
                <tr key={t.id}>
                  <td className="title">{t.name}</td>
                  <td>
                    {isManager ? (
                      <select aria-label={`Lead for ${t.name}`} value={t.teamLeadId ?? ""}
                        onChange={(e) => e.target.value && act(() => api.put(`/teams/${t.id}/lead`, { membershipId: e.target.value }))}>
                        <option value="" disabled>No lead</option>
                        {members.map((m) => <option key={m.membershipId} value={m.membershipId}>{m.fullName}</option>)}
                      </select>
                    ) : members.find((m) => m.membershipId === t.teamLeadId)?.fullName ?? "—"}
                  </td>
                  <td className="right mono">{t.memberCount}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        <NewTeam onDone={() => act(async () => undefined)} />
      </section>

      {(isManager || me.role === "MANAGER") && (
        <section className="section">
          <h2>Invite someone</h2>
          <Invite teams={teams.data ?? []} onDone={() => void pending.reload()} />
          {pending.data && pending.data.length > 0 && (
            <>
              <h2 style={{ marginTop: 22 }}>Waiting to accept</h2>
              <table>
                <thead><tr><th>Name</th><th>Role</th><th className="right">Expires</th></tr></thead>
                <tbody>{pending.data.map((p) => <tr key={p.id}><td className="title">{p.fullName}</td><td>{label(p.role)}</td><td className="right muted">{stamp(p.expiresAt)}</td></tr>)}</tbody>
              </table>
            </>
          )}
        </section>
      )}
    </>
  );
}

function NewTeam({ onDone }: { onDone: () => void }) {
  const [name, setName] = useState("");
  const [error, setError] = useState<string | null>(null);
  const submit = async (e: FormEvent) => {
    e.preventDefault(); setError(null);
    try { await api.post("/teams", { name: name.trim() }); setName(""); onDone(); } catch (err) { setError(errText(err)); }
  };
  return (
    <form onSubmit={submit} className="row" style={{ marginTop: 14, alignItems: "flex-end" }}>
      <div className="field"><label htmlFor="tn">New team</label><input id="tn" value={name} onChange={(e) => setName(e.target.value)} required maxLength={100} /></div>
      <button className="btn ghost">Create team</button>
      <ErrorLine msg={error} />
    </form>
  );
}

function Invite({ teams, onDone }: { teams: Team[]; onDone: () => void }) {
  const [fullName, setName] = useState("");
  const [email, setEmail] = useState("");
  const [role, setRole] = useState<Role>("DEVELOPER");
  const [teamId, setTeamId] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [link, setLink] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const submit = async (e: FormEvent) => {
    e.preventDefault(); setBusy(true); setError(null); setLink(null);
    try {
      const r = await api.post<{ token: string }>("/invitations", {
        fullName: fullName.trim(), role, email: email.trim() || undefined, teamId: role === "DEVELOPER" && teamId ? teamId : undefined,
      });
      setLink(`${location.origin}/accept-invite?token=${encodeURIComponent(r.token)}`);
      setName(""); setEmail(""); onDone();
    } catch (err) { setError(errText(err)); } finally { setBusy(false); }
  };

  return (
    <>
      <form className="box" onSubmit={submit}>
        <div className="row" style={{ marginBottom: 12 }}>
          <div className="field"><label htmlFor="in">Full name</label><input id="in" value={fullName} onChange={(e) => setName(e.target.value)} required /></div>
          <div className="field"><label htmlFor="ie">Email (optional)</label><input id="ie" type="email" value={email} onChange={(e) => setEmail(e.target.value)} /></div>
        </div>
        <div className="row" style={{ marginBottom: 12 }}>
          <div className="field"><label htmlFor="ir">Role</label>
            <select id="ir" value={role} onChange={(e) => setRole(e.target.value as Role)}>{ROLES.map((r) => <option key={r} value={r}>{label(r)}</option>)}</select>
          </div>
          {role === "DEVELOPER" && (
            <div className="field"><label htmlFor="it">Team</label>
              <select id="it" value={teamId} onChange={(e) => setTeamId(e.target.value)}>
                <option value="">No team</option>
                {teams.map((t) => <option key={t.id} value={t.id}>{t.name}</option>)}
              </select>
            </div>
          )}
        </div>
        <ErrorLine msg={error} />
        <button className="btn" disabled={busy}>Create invitation</button>
      </form>
      {link && (
        <div className="notice unread" style={{ display: "block" }}>
          <b>Share this link. It's shown only once.</b>
          <div className="mono" style={{ wordBreak: "break-all", margin: "6px 0" }}>{link}</div>
          <button className="btn ghost small" onClick={() => void navigator.clipboard.writeText(link)}>Copy link</button>
        </div>
      )}
    </>
  );
}
