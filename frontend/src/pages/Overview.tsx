import { useState } from "react";
import { Link } from "react-router-dom";
import { api, type Summary } from "../api";
import { useLoad } from "../hooks";
import { minutes, label } from "../format";
import { Head, Loading, ErrorLine } from "../components/bits";
import { useSession } from "../session";

const PRIORITIES = ["CRITICAL", "HIGH", "MEDIUM", "LOW"];
const STATUSES = ["OPEN", "ACKNOWLEDGED", "IN_PROGRESS", "RESOLVED", "CLOSED"];

function Bars({ data, order }: { data: Record<string, number>; order: string[] }) {
  const max = Math.max(1, ...order.map((k) => data[k] ?? 0));
  return (
    <div className="card">
      {order.map((k) => (
        <div className="bar" key={k}>
          <span>{label(k)}</span>
          <div className="track"><div className={`fill ${k}`} style={{ width: `${((data[k] ?? 0) / max) * 100}%` }} /></div>
          <span className="right mono">{data[k] ?? 0}</span>
        </div>
      ))}
    </div>
  );
}

const pct = (v: number | null) => (v == null ? "—" : `${Math.round(v)}%`);

export default function Overview() {
  const { isManager, me } = useSession();
  const [days, setDays] = useState(30);
  const { data, error, loading } = useLoad(() => (isManager ? api.get<Summary>(`/analytics/summary?days=${days}`) : Promise.resolve(null)), [days, isManager]);

  if (!isManager) {
    return (
      <>
        <Head title="Overview" sub={`Signed in as ${me.username}`} />
        <p>Reporting is available to managers and admins. Your day-to-day work is in <Link to="/incidents">Incidents</Link>.</p>
      </>
    );
  }

  return (
    <>
      <Head title="Overview" sub={data ? `Last ${data.periodDays} days` : undefined}>
        <div className="seg" role="group" aria-label="Period">
          {[7, 30, 90].map((d) => (
            <button key={d} className={d === days ? "on" : ""} onClick={() => setDays(d)}>{d}d</button>
          ))}
        </div>
      </Head>
      <ErrorLine msg={error} />
      {loading && !data && <Loading />}
      {data && (
        <>
          <div className="figures">
            <div className="fig"><div className="n">{data.activeIncidents}</div><div className="l">Active now <span className="faint">of {data.totalIncidents} in period</span></div></div>
            <div className="fig"><div className="n">{minutes(data.mttaMinutes)}</div><div className="l">Mean time to acknowledge</div></div>
            <div className="fig"><div className="n">{minutes(data.mttrMinutes)}</div><div className="l">Mean time to resolve</div></div>
            <div className="fig">
              <div className="n">{pct(data.sla.responseMetPercent)}</div>
              <div className="l">Response SLA met <span className="faint">· resolution {pct(data.sla.resolutionMetPercent)}</span></div>
            </div>
          </div>

          <div className="cols">
            <section className="section"><h2>By priority</h2><Bars data={data.byPriority} order={PRIORITIES} /></section>
            <section className="section"><h2>By status</h2><Bars data={data.byStatus} order={STATUSES} /></section>
          </div>

          <div className="cols">
            <section className="section">
              <h2>Noisiest services</h2>
              {data.topServices.length === 0 ? <div className="empty">No incidents tied to a service in this period.</div> : (
                <table><tbody>
                  {data.topServices.map((s) => (
                    <tr key={s.serviceId}><td className="title"><Link to="/services">{s.serviceName}</Link></td><td className="right mono">{s.incidents}</td></tr>
                  ))}
                </tbody></table>
              )}
            </section>
            <section className="section">
              <h2>Service health</h2>
              {(["UP", "DOWN", "UNKNOWN"] as const).every((k) => !data.serviceHealth[k]) ? (
                <div className="empty">No health checks configured yet. <Link to="/services">Add one</Link>.</div>
              ) : (
                <table><tbody>
                  {(["UP", "DOWN", "UNKNOWN"] as const).map((k) => (
                    <tr key={k}><td><span className={`tag ${k}`}>{k === "UP" ? "Healthy" : k === "DOWN" ? "Down" : "Not checked"}</span></td><td className="right mono">{data.serviceHealth[k] ?? 0}</td></tr>
                  ))}
                </tbody></table>
              )}
            </section>
          </div>
        </>
      )}
    </>
  );
}
