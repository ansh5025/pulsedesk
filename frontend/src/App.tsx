import { Navigate, Route, Routes } from "react-router-dom";
import { SessionProvider } from "./session";
import Layout from "./components/Layout";
import { Login, Register, AcceptInvite } from "./pages/Auth";
import Overview from "./pages/Overview";
import Incidents from "./pages/Incidents";
import IncidentDetail from "./pages/IncidentDetail";
import Services from "./pages/Services";
import People from "./pages/People";
import Inbox from "./pages/Inbox";

export default function App() {
  return (
    <SessionProvider
      fallback={<div className="splash">Loading…</div>}
      loggedOut={
        <Routes>
          <Route path="/register" element={<Register />} />
          <Route path="/accept-invite" element={<AcceptInvite />} />
          <Route path="*" element={<Login />} />
        </Routes>
      }
    >
      <Routes>
        <Route element={<Layout />}>
          <Route index element={<Overview />} />
          <Route path="incidents" element={<Incidents />} />
          <Route path="incidents/:id" element={<IncidentDetail />} />
          <Route path="services" element={<Services />} />
          <Route path="people" element={<People />} />
          <Route path="inbox" element={<Inbox />} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Route>
      </Routes>
    </SessionProvider>
  );
}
