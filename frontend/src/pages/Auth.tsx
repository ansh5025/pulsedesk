import { useState, type FormEvent, type ReactNode } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { api, type AuthResponse } from "../api";
import { errText } from "../hooks";
import { useSignIn } from "../session";
import { ErrorLine, Logo } from "../components/bits";

const PREVIEW: { title: string; meta: string; tag: string; text: string; status: string; statusText: string }[] = [
  { title: "Service down: Payments API", meta: "Health check · 3 failures", tag: "CRITICAL", text: "Critical", status: "OPEN", statusText: "Respond in 12m" },
  { title: "Slow search results on mobile", meta: "Customer Web App", tag: "MEDIUM", text: "Medium", status: "IN_PROGRESS", statusText: "In progress" },
  { title: "Login certificate warning", meta: "Customer Web App", tag: "HIGH", text: "High", status: "RESOLVED", statusText: "Resolved" },
];

function Frame({ title, lede, children }: { title: string; lede: string; children: ReactNode }) {
  return (
    <div className="auth">
      <section className="form">
        <div className="form-inner">
          <Link to="/" className="logo"><Logo /> PulseDesk</Link>
          <h1>{title}</h1>
          <p className="lede">{lede}</p>
          {children}
        </div>
      </section>
      <section className="pitch" aria-label="About PulseDesk">
        <div>
          <h2>Know when a service breaks, and who owns it.</h2>
          <p>Health checks open incidents, assign them to the right person and escalate when deadlines slip.</p>
        </div>
        <div className="preview" aria-hidden="true">
          <div className="preview-bar"><span>Incidents</span><span className="mono">2 active</span></div>
          {PREVIEW.map((r) => (
            <div className="preview-row" key={r.title}>
              <div>{r.title}<small>{r.meta}</small></div>
              <span className={`tag ${r.tag}`}>{r.text}</span>
              <span className={`status ${r.status}`}>{r.statusText}</span>
            </div>
          ))}
        </div>
        <ul className="points">
          <li>Failure thresholds, so one blip doesn't page anyone</li>
          <li>Response and resolution deadlines set by priority</li>
          <li>Every organization's data is kept separate</li>
        </ul>
      </section>
    </div>
  );
}

function useSubmit(action: () => Promise<AuthResponse>) {
  const signIn = useSignIn();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      signIn(await action());
    } catch (err) {
      setError(errText(err));
      setBusy(false);
    }
  };
  return { busy, error, submit };
}

const DEMO_PASSWORD = "Demo@12345";
const DEMO_USERS: [string, string][] = [
  ["dana@demo", "Org admin, sees everything"],
  ["marcus@demo", "Manager"],
  ["tara@demo", "Team lead"],
  ["dev@demo", "Developer"],
  ["priya@demo", "Developer"],
];

export function Login() {
  const [slug, setSlug] = useState("");
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const { busy, error, submit } = useSubmit(() =>
    api.post<AuthResponse>("/auth/login", { organizationSlug: slug.trim().toLowerCase(), username: username.trim(), password }));
  return (
    <Frame title="Sign in" lede="Welcome back. Sign in to your organization.">
      <form onSubmit={submit}>
        <div className="field">
          <label htmlFor="slug">Organization</label>
          <input id="slug" value={slug} onChange={(e) => setSlug(e.target.value)} placeholder="acme" required autoFocus />
        </div>
        <div className="field">
          <label htmlFor="user">Username</label>
          <input id="user" value={username} onChange={(e) => setUsername(e.target.value)} placeholder="priya@acme" autoComplete="username" required />
        </div>
        <div className="field">
          <label htmlFor="pw">Password</label>
          <input id="pw" type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="current-password" required />
        </div>
        <ErrorLine msg={error} />
        <button className="btn" disabled={busy}>{busy ? "Signing in…" : "Sign in"}</button>
        <p className="switch">New here? <Link to="/register">Create an organization</Link></p>
      </form>
      <div className="demo">
        <div className="demo-head">
          <b>Try the demo</b>
          <p>Organization <span className="mono">demo</span> · password <span className="mono">{DEMO_PASSWORD}</span></p>
        </div>
        {DEMO_USERS.map(([u, role]) => (
          <button type="button" key={u} className="demo-row" onClick={() => { setSlug("demo"); setUsername(u); setPassword(DEMO_PASSWORD); }}>
            <span className="mono">{u}</span><span className="muted">{role}</span>
          </button>
        ))}
      </div>
    </Frame>
  );
}

export function Register() {
  const [f, setF] = useState({ organizationName: "", organizationSlug: "", fullName: "", email: "", password: "" });
  const set = (k: keyof typeof f) => (e: { target: { value: string } }) => setF({ ...f, [k]: e.target.value });
  const { busy, error, submit } = useSubmit(() =>
    api.post<AuthResponse>("/auth/register", { ...f, organizationSlug: f.organizationSlug.trim().toLowerCase(), email: f.email.trim() || undefined }));
  const autoSlug = (name: string) => name.toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-+|-+$/g, "").slice(0, 50);
  return (
    <Frame title="Create an organization" lede="Set up a workspace for your team. You'll be its admin.">
      <form onSubmit={submit}>
        <div className="field">
          <label htmlFor="on">Organization name</label>
          <input id="on" value={f.organizationName} required autoFocus
            onChange={(e) => setF({ ...f, organizationName: e.target.value, organizationSlug: autoSlug(e.target.value) })} />
        </div>
        <div className="field">
          <label htmlFor="os">Short name</label>
          <input id="os" value={f.organizationSlug} onChange={set("organizationSlug")} required pattern="[a-z0-9][a-z0-9\-]{1,48}[a-z0-9]" title="3–50 characters: lowercase letters, digits, hyphens" />
          <div className="faint" style={{ fontSize: 12, marginTop: 4 }}>Used when signing in, and in everyone's username.</div>
        </div>
        <div className="field">
          <label htmlFor="fn">Your name</label>
          <input id="fn" value={f.fullName} onChange={set("fullName")} required />
        </div>
        <div className="field">
          <label htmlFor="em">Email <span className="faint">(optional, for alerts)</span></label>
          <input id="em" type="email" value={f.email} onChange={set("email")} />
        </div>
        <div className="field">
          <label htmlFor="pw">Password</label>
          <input id="pw" type="password" value={f.password} onChange={set("password")} minLength={8} maxLength={72} autoComplete="new-password" required />
        </div>
        <ErrorLine msg={error} />
        <button className="btn" disabled={busy}>{busy ? "Creating…" : "Create organization"}</button>
        <p className="switch">Already have one? <Link to="/">Sign in</Link></p>
      </form>
    </Frame>
  );
}

export function AcceptInvite() {
  const [params] = useSearchParams();
  const token = params.get("token") ?? "";
  const [password, setPassword] = useState("");
  const { busy, error, submit } = useSubmit(() => api.post<AuthResponse>("/invitations/accept", { token, password }));
  return (
    <Frame title="Accept your invitation" lede="Choose a password to join. Your username is created for you.">
      {!token ? (
        <p className="err">This link is missing its token. Ask whoever invited you to send it again.</p>
      ) : (
        <form onSubmit={submit}>
          <div className="field">
            <label htmlFor="pw">Password</label>
            <input id="pw" type="password" value={password} onChange={(e) => setPassword(e.target.value)} minLength={8} maxLength={72} autoComplete="new-password" required autoFocus />
          </div>
          <ErrorLine msg={error} />
          <button className="btn" disabled={busy}>{busy ? "Joining…" : "Join"}</button>
        </form>
      )}
    </Frame>
  );
}
