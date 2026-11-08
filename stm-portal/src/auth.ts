import { request } from "./api/http";

/**
 * Portal sign-in against the STM middleware (POST /api/v1/auth/login). The session token lives in sessionStorage,
 * so it is gone when the tab closes; the middleware also ends it after 30 min idle or 10 h in total.
 */
export type Role = "TELLER" | "SUPERVISOR" | "AUDITOR" | "ADMIN";

export interface User {
  id: number;
  username: string;
  displayName: string;
  role: Role;
  branchCode: string | null;
  languages: string[];
  mustChangePassword: boolean;
  sessionExpiresAt: string | null;
}

export interface Session {
  token: string;
  user: User;
}

const KEY = "stm.portal.session";

export function loadSession(): Session | null {
  try {
    const raw = sessionStorage.getItem(KEY);
    return raw ? (JSON.parse(raw) as Session) : null;
  } catch {
    return null;
  }
}

export function saveSession(s: Session) {
  try { sessionStorage.setItem(KEY, JSON.stringify(s)); } catch { /* private mode: memory only */ }
}

export function clearSession() {
  try { sessionStorage.removeItem(KEY); } catch { /* ignore */ }
}

export const authApi = {
  login: (username: string, password: string) =>
    request<{ token: string; expiresAt: string; user: User }>("/api/v1/auth/login", { method: "POST", body: { username, password } }),
  me: (token: string) => request<User>("/api/v1/auth/me", { headers: bearer(token) }),
  logout: (token: string) => request<void>("/api/v1/auth/logout", { method: "POST", headers: bearer(token) }),
  changePassword: (token: string, currentPassword: string, newPassword: string) =>
    request<User>("/api/v1/auth/password", { method: "POST", body: { currentPassword, newPassword }, headers: bearer(token) }),
};

export function bearer(token: string) {
  return { Authorization: `Bearer ${token}` };
}

/** What each role may do in the portal. The middleware enforces the same rules; this only hides what would fail. */
export const can = {
  decide: (r: Role) => r === "TELLER" || r === "SUPERVISOR",
  takeCalls: (r: Role) => r === "TELLER" || r === "SUPERVISOR",
  controlKiosks: (r: Role) => r === "SUPERVISOR" || r === "ADMIN",
  readAudit: (r: Role) => r === "SUPERVISOR" || r === "AUDITOR" || r === "ADMIN",
  manageUsers: (r: Role) => r === "ADMIN",
  /** Cash reconciliation and the device electronic journal, like the audit trail. */
  reconcile: (r: Role) => r === "SUPERVISOR" || r === "AUDITOR" || r === "ADMIN",
  /** FX rates (until the treasury feed is connected) and the simulator switches. */
  configure: (r: Role) => r === "SUPERVISOR" || r === "ADMIN",
};

export const ROLE_LABEL: Record<Role, string> = {
  TELLER: "Teller", SUPERVISOR: "Supervisor", AUDITOR: "Auditor", ADMIN: "Administrator",
};
