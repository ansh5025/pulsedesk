import { Link, NavLink, Outlet } from "react-router-dom";
import { api } from "../api";
import { useInterval, useLoad } from "../hooks";
import { useSession } from "../session";
import { label } from "../format";
import { useTheme } from "../theme";
import { Avatar, Logo } from "./bits";
import { IconIncidents, IconInbox, IconMoon, IconOverview, IconPeople, IconServices, IconSignOut, IconSun } from "./icons";

export default function Layout() {
  const { me, org, members, signOut } = useSession();
  const [theme, toggleTheme] = useTheme();
  const unread = useLoad(() => api.get<{ unread: number }>("/notifications/unread-count"));
  useInterval(() => void unread.reload(), 30000);
  const n = unread.data?.unread ?? 0;
  const fullName = members.find((m) => m.membershipId === me.membershipId)?.fullName ?? me.username;

  return (
    <div className="app">
      <header className="topbar">
        <Link to="/" className="logo" aria-label="PulseDesk home"><Logo /> PulseDesk</Link>
        <span className="slash" aria-hidden="true">/</span>
        <span className="org"><Avatar name={org.name} size={22} />{org.name}</span>
        <span className="tag">{label(me.role)}</span>
        <div className="spacer" />
        <button className="icon-btn" onClick={toggleTheme} aria-label={`Switch to ${theme === "dark" ? "light" : "dark"} theme`}
          title={theme === "dark" ? "Light theme" : "Dark theme"}>
          {theme === "dark" ? <IconSun /> : <IconMoon />}
        </button>
        <div className="user">
          <Avatar name={fullName} size={28} />
          <div className="meta">{fullName}<span>{me.username}</span></div>
          <button className="icon-btn" onClick={signOut} aria-label="Sign out" title="Sign out"><IconSignOut /></button>
        </div>
      </header>
      <div className="frame">
        <aside className="side">
          <div className="side-label">Workspace</div>
          <nav className="nav">
            <NavLink to="/" end><IconOverview /> Overview</NavLink>
            <NavLink to="/incidents"><IconIncidents /> Incidents</NavLink>
            <NavLink to="/services"><IconServices /> Services</NavLink>
            <NavLink to="/people"><IconPeople /> People</NavLink>
            <NavLink to="/inbox"><IconInbox /> Inbox {n > 0 && <span className="count">{n > 99 ? "99+" : n}</span>}</NavLink>
          </nav>
          <div className="side-foot"><span className="mono">{org.slug}</span></div>
        </aside>
        <main className="main">
          <Outlet context={{ refreshUnread: unread.reload }} />
        </main>
      </div>
    </div>
  );
}
