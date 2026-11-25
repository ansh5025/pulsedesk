import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import { api, ApiError, setUnauthorizedHandler, tokenStore, type AuthResponse, type Me, type Member, type Org } from "./api";

interface Session {
  me: Me;
  org: Org;
  members: Member[];
  nameOf: (membershipId: string | null | undefined) => string;
  isManager: boolean;
  refreshMembers: () => Promise<void>;
  signOut: () => void;
}

const Ctx = createContext<Session | null>(null);
const SignInCtx = createContext<(r: AuthResponse) => void>(() => {});
export const useSignIn = () => useContext(SignInCtx);
type Loaded = { me: Me; org: Org; members: Member[] };

export function SessionProvider({ children, fallback, loggedOut }: { children: ReactNode; fallback: ReactNode; loggedOut: ReactNode }) {
  const [token, setToken] = useState(tokenStore.get());
  const [data, setData] = useState<Loaded | null>(null);
  const [failure, setFailure] = useState<string | null>(null);
  const [retry, setRetry] = useState(0);

  const signOut = useCallback(() => {
    tokenStore.clear();
    setToken(null);
    setData(null);
  }, []);

  useEffect(() => { setUnauthorizedHandler(signOut); }, [signOut]);

  useEffect(() => {
    if (!token) return;
    let live = true;
    setFailure(null);
    Promise.all([api.get<Me>("/auth/me"), api.get<Org>("/organizations/current"), api.get<Member[]>("/organizations/current/members")])
      .then(([me, org, members]) => { if (live) setData({ me, org, members }); })
      .catch((error: unknown) => {
        if (!live) return;
        setFailure(error instanceof ApiError
          ? `Couldn't load your workspace (${error.status}): ${error.message}`
          : "Couldn't reach the PulseDesk API. Check that the backend is running.");
      });
    return () => { live = false; };
  }, [token, retry]);

  const refreshMembers = useCallback(async () => {
    const members = await api.get<Member[]>("/organizations/current/members");
    setData((d) => (d ? { ...d, members } : d));
  }, []);

  const value = useMemo<Session | null>(() => {
    if (!data) return null;
    const byId = new Map(data.members.map((m) => [m.membershipId, m]));
    return {
      ...data,
      nameOf: (id) => (id ? byId.get(id)?.fullName ?? "Former member" : "Nobody"),
      isManager: data.me.role === "ORG_ADMIN" || data.me.role === "MANAGER",
      refreshMembers,
      signOut,
    };
  }, [data, refreshMembers, signOut]);

  const signIn = useCallback((r: AuthResponse) => { tokenStore.set(r.accessToken); setToken(r.accessToken); }, []);

  if (!token) return <SignInCtx.Provider value={signIn}>{loggedOut}</SignInCtx.Provider>;
  if (failure) {
    return (
      <div className="splash">
        <p role="alert">{failure}</p>
        <button className="btn" onClick={() => setRetry((attempt) => attempt + 1)}>Retry</button>{" "}
        <button className="btn ghost" onClick={signOut}>Sign out</button>
      </div>
    );
  }
  if (!value) return <>{fallback}</>;
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function useSession(): Session {
  const s = useContext(Ctx);
  if (!s) throw new Error("No session");
  return s;
}
