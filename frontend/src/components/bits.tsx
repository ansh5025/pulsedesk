import type { ReactNode } from "react";
import type { Health, Priority, Status } from "../api";
import { label } from "../format";

export const PriorityTag = ({ p }: { p: Priority }) => <span className={`tag ${p}`}>{p.toLowerCase()}</span>;
const HEALTH_TEXT: Record<Health, string> = { UP: "Healthy", DOWN: "Down", UNKNOWN: "Pending" };
export const HealthTag = ({ h }: { h: Health }) => <span className={`tag ${h}`}>{HEALTH_TEXT[h]}</span>;
export const StatusText = ({ s }: { s: Status }) => <span className={`status ${s}`}>{label(s)}</span>;

export function Head({ title, sub, children }: { title: string; sub?: ReactNode; children?: ReactNode }) {
  return (
    <div className="head">
      <div>
        <h1>{title}</h1>
        {sub && <div className="sub">{sub}</div>}
      </div>
      <div>{children}</div>
    </div>
  );
}

export const Loading = () => <div className="loading">Loading…</div>;
export const ErrorLine = ({ msg }: { msg: string | null }) => (msg ? <div className="err" role="alert">{msg}</div> : null);

export function Logo({ size = 22 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" aria-hidden="true">
      <rect width="24" height="24" rx="6" fill="currentColor" />
      <path d="M4.5 12.5h4l2-5 3 10 2-5h4" fill="none" stroke="var(--bg)" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}

const AVATAR_COLORS = ["#0d74ce", "#18794e", "#ad5700", "#ce2c31", "#5c5c5c", "#0b7d8a", "#8a4b17"];

export function Avatar({ name, size = 24 }: { name: string; size?: number }) {
  const initials = name.split(/[\s@._-]+/).filter(Boolean).slice(0, 2).map((w) => w[0]!.toUpperCase()).join("");
  const hash = [...name].reduce((h, c) => (h * 31 + c.charCodeAt(0)) >>> 0, 7);
  return (
    <span className="avatar" style={{ width: size, height: size, background: AVATAR_COLORS[hash % AVATAR_COLORS.length] }}>
      {initials || "?"}
    </span>
  );
}
