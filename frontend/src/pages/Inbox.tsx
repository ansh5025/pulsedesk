import { useState } from "react";
import { Link, useOutletContext } from "react-router-dom";
import { api, type Notice } from "../api";
import { errText, useLoad } from "../hooks";
import { ago } from "../format";
import { ErrorLine, Head, Loading } from "../components/bits";

export default function Inbox() {
  const { refreshUnread } = useOutletContext<{ refreshUnread: () => Promise<void> }>();
  const [unreadOnly, setUnreadOnly] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const list = useLoad(() => api.get<Notice[]>(`/notifications?limit=100${unreadOnly ? "&unreadOnly=true" : ""}`), [unreadOnly]);

  const run = async (fn: () => Promise<unknown>) => {
    setError(null);
    try { await fn(); await Promise.all([list.reload(), refreshUnread()]); } catch (e) { setError(errText(e)); }
  };
  const anyUnread = list.data?.some((n) => !n.read);

  return (
    <>
      <Head title="Inbox" sub="Assignments and SLA alerts">
        <button className="btn ghost small" disabled={!anyUnread} onClick={() => run(() => api.put("/notifications/read-all"))}>Mark all read</button>
      </Head>
      <div className="seg" style={{ marginBottom: 6 }}>
        <button className={!unreadOnly ? "on" : ""} onClick={() => setUnreadOnly(false)}>All</button>
        <button className={unreadOnly ? "on" : ""} onClick={() => setUnreadOnly(true)}>Unread</button>
      </div>
      <ErrorLine msg={error ?? list.error} />
      {list.loading && !list.data && <Loading />}
      {list.data && (list.data.length === 0 ? <div className="empty">{unreadOnly ? "You're caught up." : "No notifications yet."}</div> : list.data.map((n) => (
        <div key={n.id} className={`notice ${n.read ? "" : "unread"}`}>
          <div>
            <b>{n.incidentId ? <Link to={`/incidents/${n.incidentId}`} onClick={() => !n.read && void api.put(`/notifications/${n.id}/read`).then(refreshUnread)}>{n.title}</Link> : n.title}</b>
            {n.message && <div className="muted">{n.message}</div>}
          </div>
          <div className="right muted">
            {ago(n.createdAt)}
            {!n.read && <div><button className="btn ghost small" onClick={() => run(() => api.put(`/notifications/${n.id}/read`))}>Mark read</button></div>}
          </div>
        </div>
      )))}
    </>
  );
}
