import { useCallback, useEffect, useMemo, useState, type ReactNode } from "react";
import { authApi, can, clearSession, loadSession, ROLE_LABEL, saveSession, type Role, type Session, type User } from "./auth";
import { PortalApi } from "./api/portal";
import { TellerConsole } from "./video/TellerConsole";
import { Ctx, usePortal as usePortalCtx, useTeller, type Page, type PortalCtx } from "./context";
import { ChangePassword, Login } from "./pages/Login";
import { Users } from "./pages/Users";
import { Overview } from "./pages/Overview";
import { Sessions, SessionDrawer } from "./pages/Sessions";
import { VideoTeller } from "./pages/VideoTeller";
import { Fleet } from "./pages/Fleet";
import { Applications } from "./pages/Applications";
import { Cash } from "./pages/Cash";
import { Cards } from "./pages/Cards";
import { Reconciliation } from "./pages/Reconciliation";
import { Audit } from "./pages/Audit";
import { Reference } from "./pages/Reference";
import { Simulator } from "./pages/Simulator";
import { ConfirmDialog, useLoad, type ConfirmOptions } from "./ui/kit";
import {
  IconBook, IconCard, IconHalf, IconMoon, IconSun, IconCash, IconCheck, IconFile, IconGrid, IconLock, IconLogout, IconMonitor, IconPhone, IconRefresh, IconShield, IconUsers, IconVideo,
} from "./ui/Icons";
import mark from "./assets/blueriver-mark.png";

const ALL: Role[] = ["TELLER", "SUPERVISOR", "AUDITOR", "ADMIN"];
const NAV: { page: Page; label: string; icon: ReactNode; group: string; roles: Role[] }[] = [
  { page: "overview", label: "Overview", icon: <IconGrid />, group: "Operations", roles: ALL },
  { page: "sessions", label: "Customer sessions", icon: <IconUsers />, group: "Operations", roles: ALL },
  { page: "applications", label: "Applications", icon: <IconFile />, group: "Operations", roles: ALL },
  { page: "video", label: "Video teller", icon: <IconVideo />, group: "Operations", roles: ["TELLER", "SUPERVISOR"] },
  { page: "cash", label: "Cash & forex", icon: <IconCash />, group: "Branch", roles: ALL },
  { page: "cards", label: "Cards", icon: <IconCard />, group: "Branch", roles: ALL },
  { page: "fleet", label: "Kiosk fleet", icon: <IconMonitor />, group: "Branch", roles: ALL },
  { page: "reconciliation", label: "Reconciliation", icon: <IconCheck />, group: "Branch", roles: ["SUPERVISOR", "AUDITOR", "ADMIN"] },
  { page: "audit", label: "Audit & compliance", icon: <IconShield />, group: "Governance", roles: ["SUPERVISOR", "AUDITOR", "ADMIN"] },
  { page: "reference", label: "Products & rates", icon: <IconBook />, group: "Governance", roles: ALL },
  { page: "simulator", label: "Simulator", icon: <IconRefresh />, group: "Governance", roles: ["SUPERVISOR", "ADMIN"] },
  { page: "users", label: "Portal accounts", icon: <IconLock />, group: "Governance", roles: ["ADMIN"] },
];

const TITLES: Record<Page, { title: string; sub: string }> = {
  overview: { title: "Overview", sub: "Everything happening across your Smart Teller machines" },
  sessions: { title: "Customer sessions", sub: "Every customer at a kiosk, from “Tap to start” to “Finish”" },
  applications: { title: "Applications", sub: "Accounts opened at the kiosk, and the ones waiting for a teller" },
  video: { title: "Video teller", sub: "Take calls from customers who tap “Talk to a teller”" },
  cash: { title: "Cash & forex", sub: "Deposits, withdrawals and currency exchange, with their core-banking postings" },
  cards: { title: "Cards", sub: "Debit cards printed and handed over at the kiosks" },
  fleet: { title: "Kiosk fleet", sub: "Device health, services on offer and remote control" },
  reconciliation: { title: "Reconciliation", sub: "Kiosk cash against the general ledger" },
  audit: { title: "Audit & compliance", sub: "Tamper-evident journals, text messages and video evidence" },
  reference: { title: "Products & rates", sub: "Products, fees, terms, forex rates and the terminal registry" },
  simulator: { title: "Simulator", sub: "Demo back ends: core banking, registries, cards and SMS" },
  users: { title: "Portal accounts", sub: "Who can sign in to the STM portal, and what they may do" },
};

function readHash(): { page: Page; param?: string } {
  const [p, param] = location.hash.replace(/^#\/?/, "").split("/");
  return { page: (NAV.some((n) => n.page === p) ? p : "overview") as Page, param };
}

export default function App() {
  const [session, setSession] = useState<Session | null>(loadSession);
  const [notice, setNotice] = useState<string>();
  const [changingPassword, setChangingPassword] = useState(false);

  const end = useCallback((why?: string) => {
    clearSession();
    setSession(null);
    setChangingPassword(false);
    setNotice(why);
  }, []);

  // A stored session may have expired while the tab was closed or asleep: confirm it once.
  useEffect(() => {
    if (!session) return;
    authApi.me(session.token).then((user) => { const s = { ...session, user }; saveSession(s); setSession(s); })
      .catch(() => end("Your session ended. Please sign in again."));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  if (!session) {
    return <Login notice={notice} onSignedIn={(s) => { saveSession(s); setSession(s); setNotice(undefined); }} />;
  }
  const passwordChanged = (user: User) => { const s = { ...session, user }; saveSession(s); setSession(s); setChangingPassword(false); };
  if (session.user.mustChangePassword) {
    return <ChangePassword token={session.token} user={session.user} forced onDone={passwordChanged} onCancel={() => signOut(session, end)} />;
  }
  return (
    <>
      <Shell key={session.token} session={session} onSessionEnded={() => end("Your session ended. Please sign in again.")}
             onSignOut={() => signOut(session, end)} onChangePassword={() => setChangingPassword(true)} />
      {/* Over the portal, not instead of it, so a video call in progress keeps running. */}
      {changingPassword && (
        <div className="auth-overlay">
          <ChangePassword token={session.token} user={session.user} forced={false} onDone={passwordChanged} onCancel={() => setChangingPassword(false)} />
        </div>
      )}
    </>
  );
}

function signOut(session: Session, end: (why?: string) => void) {
  authApi.logout(session.token).catch(() => undefined).finally(() => end("You are signed out."));
}

function Shell({ session, onSessionEnded, onSignOut, onChangePassword }: {
  session: Session; onSessionEnded: () => void; onSignOut: () => void; onChangePassword: () => void;
}) {
  const user: User = session.user;
  const [route, setRoute] = useState(readHash);
  const [sessionId, setSessionId] = useState<string | null>(null);
  const [toasts, setToasts] = useState<{ id: number; text: string; tone: "ok" | "error" | "info" }[]>([]);

  const portal = useMemo(() => new PortalApi(session.token, onSessionEnded), [session.token, onSessionEnded]);
  // The call service learns who the teller is from the signed-in user (see README: call-control still trusts this header).
  const teller = useMemo(() => new TellerConsole(user.username, user.languages), [user.username, user.languages]);

  useEffect(() => {
    const on = () => setRoute(readHash());
    window.addEventListener("hashchange", on);
    return () => window.removeEventListener("hashchange", on);
  }, []);
  useEffect(() => () => { void teller.dispose(); }, [teller]);

  const toast = useCallback((text: string, tone: "ok" | "error" | "info" = "ok") => {
    const id = Date.now() + Math.random();
    setToasts((t) => [...t, { id, text, tone }]);
    setTimeout(() => setToasts((t) => t.filter((x) => x.id !== id)), 4500);
  }, []);

  // One confirmation dialog for the whole portal; confirm() resolves when the person answers.
  const [asking, setAsking] = useState<{ options: ConfirmOptions; answer: (yes: boolean) => void } | null>(null);
  const confirm = useCallback((options: ConfirmOptions) => new Promise<boolean>((resolve) => {
    setAsking((prev) => { prev?.answer(false); return { options, answer: resolve }; });
  }), []);
  const answer = useCallback((yes: boolean) => setAsking((a) => { a?.answer(yes); return null; }), []);

  const ctx: PortalCtx = useMemo(() => ({
    user, portal, teller, toast, confirm,
    go: (page, param) => { location.hash = `/${page}${param ? `/${param}` : ""}`; },
    openSession: (id) => setSessionId(id),
    signOut: () => { void teller.dispose().finally(onSignOut); },
    changePassword: onChangePassword,
  }), [user, portal, teller, toast, confirm, onSignOut, onChangePassword]);

  const nav = NAV.filter((n) => n.roles.includes(user.role));
  const page = nav.some((n) => n.page === route.page) ? route.page : "overview";
  const groups = [...new Set(nav.map((n) => n.group))];
  const calls = can.takeCalls(user.role);

  return (
    <Ctx.Provider value={ctx}>
      <div className="shell">
        <aside className="sidebar">
          <div className="brand">
            <span className="brand__tile"><img src={mark} alt="" /></span>
            <span className="brand__text"><b>Smart Teller</b><small>STM Portal · Blue River</small></span>
          </div>
          <nav>
            {groups.map((g) => (
              <div key={g} className="nav-group">
                <div className="nav-group__label">{g}</div>
                {nav.filter((n) => n.group === g).map((n) => (
                  <a key={n.page} href={`#/${n.page}`} className={`nav-item ${page === n.page ? "is-active" : ""}`}>
                    {n.icon}<span>{n.label}</span>
                    {n.page === "video" && <VideoNavBadge />}
                    {n.page === "applications" && <WaitingBadge />}
                  </a>
                ))}
              </div>
            ))}
          </nav>
          <div className="sidebar__foot">
            <div className="me">
              <span className="me__avatar">{user.displayName.split(/\s+/).map((p) => p[0]).slice(0, 2).join("").toUpperCase()}</span>
              <span className="me__text"><b>{user.displayName}</b><small>{ROLE_LABEL[user.role]}{user.branchCode ? ` · ${user.branchCode}` : ""}</small></span>
            </div>
            <div className="sidebar__actions">
              <button type="button" className="icon-btn icon-btn--light" onClick={onChangePassword} title="Change password" aria-label="Change password"><IconLock /></button>
              <button type="button" className="icon-btn icon-btn--light" onClick={ctx.signOut} title="Sign out" aria-label="Sign out"><IconLogout /></button>
            </div>
          </div>
        </aside>

        <div className="main">
          <header className="topbar">
            <div>
              <h1 className="topbar__title">{TITLES[page].title}</h1>
              <p className="topbar__sub">{TITLES[page].sub}</p>
            </div>
            <div className="topbar__right">
              {calls && <PresenceChip />}
              <ThemeButton />
              <Clock />
            </div>
          </header>
          {calls && <RingAlert />}
          <div className="content">
            {page === "overview" && <Overview />}
            {page === "sessions" && <Sessions initialId={route.param} />}
            {page === "fleet" && <Fleet />}
            {page === "applications" && <Applications />}
            {page === "cash" && <Cash />}
            {page === "cards" && <Cards />}
            {page === "reconciliation" && <Reconciliation />}
            {page === "audit" && <Audit initialEntity={route.param} />}
            {page === "reference" && <Reference />}
            {page === "simulator" && <Simulator />}
            {page === "users" && <Users />}
            {/* Always mounted, so a call keeps its video while the teller looks at other pages. */}
            {calls && <div hidden={page !== "video"}><VideoTeller /></div>}
          </div>
        </div>
      </div>
      <SessionDrawer id={sessionId} onClose={() => setSessionId(null)} />
      <ConfirmDialog options={asking?.options ?? null} onAnswer={answer} />
      <div className="toasts" aria-live="polite">
        {toasts.map((t) => <div key={t.id} className={`toast toast--${t.tone}`}>{t.text}</div>)}
      </div>
    </Ctx.Provider>
  );
}

function VideoNavBadge() {
  const { state } = useTeller();
  if (state.ring) return <span className="nav-badge nav-badge--ring">Ringing</span>;
  if (state.call) return <span className="nav-badge nav-badge--live">Live</span>;
  return state.available ? <span className="nav-dot" title="Available" /> : null;
}

/** Applications the kiosks are waiting on (referrals, failed face matches). */
function WaitingBadge() {
  const { portal } = usePortalCtx();
  const w = useLoad(() => portal.applications({ needsTeller: true }), [], 10_000);
  const n = w.data?.length ?? 0;
  return n ? <span className="nav-badge nav-badge--count">{n}</span> : null;
}

/** Light, dark, or follow the system (stored per browser). */
function ThemeButton() {
  const read = () => (document.documentElement.dataset.theme as "light" | "dark" | undefined) ?? "system";
  const [mode, setMode] = useState<"light" | "dark" | "system">(read);
  const next = { system: "light", light: "dark", dark: "system" } as const;
  const apply = () => {
    const m = next[mode];
    if (m === "system") delete document.documentElement.dataset.theme; else document.documentElement.dataset.theme = m;
    try { if (m === "system") localStorage.removeItem("stm-theme"); else localStorage.setItem("stm-theme", m); } catch { /* private window */ }
    setMode(m);
  };
  return (
    <button type="button" className="theme-btn" onClick={apply} title={`Theme: ${mode} (click to change)`} aria-label={`Theme: ${mode}`}>
      {mode === "dark" ? <IconMoon /> : mode === "light" ? <IconSun /> : <IconHalf />}
    </button>
  );
}

function PresenceChip() {
  const { teller, state } = useTeller();
  const busy = state.changingPresence;
  if (state.call) return <span className="presence presence--live"><span className="presence__dot" />On a call</span>;
  return (
    <button type="button" className={`presence ${state.available ? "presence--on" : ""}`} disabled={busy}
            onClick={() => (state.available ? teller.goAway() : teller.goAvailable())}
            title={state.available ? "Tap to go away" : "Tap to take video calls"}>
      <span className="presence__dot" />{busy ? "Updating…" : state.available ? "Available for calls" : "Away"}
    </button>
  );
}

/** Incoming call banner, visible on every page. */
function RingAlert() {
  const { teller, state } = useTeller();
  const r = state.ring;
  if (!r) return null;
  return (
    <div className="ring" role="alertdialog" aria-live="assertive">
      <span className="ring__icon"><IconPhone /></span>
      <div className="ring__text">
        <b>Incoming video call</b>
        <span>Kiosk {r.terminalId} · language {r.lang.toUpperCase()} · ref {r.txId.slice(0, 13)}</span>
      </div>
      <span className="ring__timer">{r.left}s</span>
      <button type="button" className="btn btn--success btn--md" onClick={() => { location.hash = "/video"; void teller.accept(); }}>Accept</button>
      <button type="button" className="btn btn--ghost-light btn--md" onClick={() => void teller.decline()}>Decline</button>
    </div>
  );
}

function Clock() {
  const [now, setNow] = useState(() => new Date());
  useEffect(() => { const id = setInterval(() => setNow(new Date()), 15_000); return () => clearInterval(id); }, []);
  return (
    <div className="clock">
      <b>{now.toLocaleTimeString("en-GB", { hour: "2-digit", minute: "2-digit" })}</b>
      <small>{now.toLocaleDateString("en-GB", { weekday: "short", day: "numeric", month: "short", year: "numeric" })}</small>
    </div>
  );
}
