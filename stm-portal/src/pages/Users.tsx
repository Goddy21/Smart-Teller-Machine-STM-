import { useEffect, useRef, useState, type FormEvent, type ReactNode } from "react";
import { usePortal } from "../context";
import { ROLE_LABEL, type Role } from "../auth";
import type { EmailRow, PortalUserRow, UserActivity, UserCreated } from "../api/portal";
import { Badge, Button, Card, Drawer, Empty, ErrorNote, fmt, Mono, Pager, Spinner, useLoad, usePaging } from "../ui/kit";
import { IconCheck, IconEdit, IconHistory, IconLock, IconMail, IconPause, IconRefresh, IconSignOut, IconTrash, IconUsers } from "../ui/Icons";

const ROLES: Role[] = ["TELLER", "SUPERVISOR", "AUDITOR", "ADMIN"];
const ROLE_HELP: Record<Role, string> = {
  TELLER: "Takes video calls and approves or rejects customer sessions.",
  SUPERVISOR: "Everything a teller does, plus kiosk control (lock, reboot), deposits and the audit trail.",
  AUDITOR: "Read-only, including the audit journal and call recordings. Cannot decide or control kiosks.",
  ADMIN: "Manages portal accounts and kiosks, reads the audit trail. Does not take calls or decide.",
};

/** Portal accounts (administrators only). Temporary passwords are emailed or shown once, and must be changed at first sign-in. */
export function Users() {
  const { portal, user, toast } = usePortal();
  const [showRemoved, setShowRemoved] = useState(false);
  const list = useLoad(() => portal.users(showRemoved), [showRemoved]);
  const branches = useLoad(() => portal.reference().then((r) => r.branches), []);
  const [creating, setCreating] = useState(false);
  const [editing, setEditing] = useState<PortalUserRow | null>(null);
  const [resetting, setResetting] = useState<PortalUserRow | null>(null);
  const [history, setHistory] = useState<PortalUserRow | null>(null);
  const [secret, setSecret] = useState<(UserCreated & { reset: boolean }) | null>(null);

  async function act(p: Promise<unknown>, ok: string) {
    try { await p; toast(ok, "ok"); list.reload(); } catch (e) { toast((e as Error).message, "error"); }
  }
  async function signOut(u: PortalUserRow) {
    if (!confirm(`Sign ${u.displayName} out on every device? Their account stays active; they can sign in again.`)) return;
    try {
      const r = await portal.signOutUser(u.username);
      toast(r.sessionsEnded ? `${u.displayName} is signed out (${r.sessionsEnded} session${r.sessionsEnded > 1 ? "s" : ""}).` : `${u.displayName} had no open sessions.`, "ok");
      list.reload();
    } catch (e) { toast((e as Error).message, "error"); }
  }
  async function remove(u: PortalUserRow) {
    const used = !!u.lastLoginAt;
    if (!confirm(used
      ? `Remove ${u.displayName}?\n\nThey are signed out at once and can no longer sign in. Because they have used the portal, their name stays in the audit trail and on past decisions, so the username "${u.username}" cannot be used again. Their email address is freed.`
      : `Delete ${u.displayName}? They never signed in, so the account is deleted completely.`)) return;
    try {
      const r = await portal.removeUser(u.username);
      toast(r.message, "ok");
      list.reload();
    } catch (e) { toast((e as Error).message, "error"); }
  }

  return (
    <div className="stack">
      <Card pad={false} title="Portal accounts" subtitle="Everyone who can sign in to the STM portal, and what they may do."
            actions={<div className="card-actions">
              <label className="check"><input type="checkbox" checked={showRemoved} onChange={(e) => setShowRemoved(e.target.checked)} /> Show removed</label>
              <Button icon={<IconUsers />} onClick={() => setCreating(true)}>Add person</Button>
            </div>}>
        {list.error && !list.data ? <div className="card__body"><ErrorNote error={list.error} onRetry={list.reload} /></div>
          : !list.data ? <div className="card__body"><Spinner /></div>
          : list.data.length === 0 ? <Empty icon={<IconUsers />} title="No accounts" />
          : (
            <div className="table-wrap">
              <table className="table">
                <thead><tr><th>Person</th><th>Role</th><th>Branch · calls in</th><th>Status</th><th /></tr></thead>
                <tbody>
                  {list.data.map((u) => {
                    const self = u.username === user.username;
                    const removed = u.status === "ARCHIVED";
                    return (
                      <tr key={u.username} className={removed ? "is-removed" : ""}>
                        <td>
                          <div className="cell-main">
                            <span className="avatar">{u.displayName.split(/\s+/).map((p) => p[0]).slice(0, 2).join("").toUpperCase()}</span>
                            <span><b>{u.displayName}{self && <span className="muted"> (you)</span>}</b><small><Mono>{u.username}</Mono></small>
                              {removed ? <small className="user-email">Removed {u.archivedAt ? fmt.ago(u.archivedAt) : ""}</small>
                                : u.email ? <small className="user-email">{u.email}</small>
                                : <small className="user-email user-email--none">No email: signs in without a code</small>}</span>
                          </div>
                        </td>
                        <td>
                          {removed ? ROLE_LABEL[u.role] : (
                            <select className="select" value={u.role} disabled={self}
                                    onChange={(e) => act(portal.updateUser(u.username, { role: e.target.value as Role }), `${u.displayName} is now ${ROLE_LABEL[e.target.value as Role]}.`)}>
                              {ROLES.map((r) => <option key={r} value={r}>{ROLE_LABEL[r]}</option>)}
                            </select>)}
                        </td>
                        <td className="nowrap">{u.branchCode ?? <span className="muted">All</span>}<small className="user-email">{u.languages.join(", ").toUpperCase()}</small></td>
                        <td>
                          <div className="badges">
                            {removed ? <Badge tone="neutral">Removed</Badge>
                              : <Badge value={u.status} tone={u.status === "ACTIVE" ? "good" : "neutral"}>{u.status === "ACTIVE" ? "Active" : "Disabled"}</Badge>}
                            {!removed && u.locked && <Badge tone="bad">Locked</Badge>}
                            {!removed && u.mustChangePassword && <Badge tone="warn">Must change password</Badge>}
                          </div>
                          <small className="user-email">
                            Last sign-in: {u.lastLoginAt ? fmt.ago(u.lastLoginAt) : "never"}
                            {u.activeSessions > 0 && ` · signed in on ${u.activeSessions} device${u.activeSessions > 1 ? "s" : ""}`}
                          </small>
                        </td>
                        <td>
                          <div className="row-actions row-actions--stack">
                            {removed ? (<>
                              <Button size="sm" kind="ghost" icon={<IconHistory />} onClick={() => setHistory(u)}>Activity</Button>
                              <Button size="sm" kind="secondary" onClick={() => confirm(`Restore ${u.displayName}? They can sign in again with their old password. Add an email address afterwards.`)
                                && act(portal.updateUser(u.username, { status: "ACTIVE" }), `${u.displayName} is restored.`)}>Restore</Button>
                            </>) : (<>
                              <Button size="sm" kind="ghost" icon={<IconEdit />} onClick={() => setEditing(u)}>Edit</Button>
                              <Button size="sm" kind="ghost" icon={<IconRefresh />} onClick={() => setResetting(u)}>Reset password</Button>
                              <RowMenu label="More…">
                                  <button type="button" onClick={() => setHistory(u)}><IconHistory /> Activity history</button>
                                  {!self && <button type="button" onClick={() => signOut(u)}><IconSignOut /> Sign out everywhere</button>}
                                  {u.locked && u.status === "ACTIVE" && <button type="button" onClick={() => act(portal.updateUser(u.username, { status: "ACTIVE" }), `${u.displayName} is unlocked.`)}><IconLock /> Unlock</button>}
                                  {!self && (u.status === "ACTIVE"
                                    ? <button type="button" onClick={() => confirm(`Disable ${u.displayName}? They are signed out at once and cannot sign in until enabled again.`) && act(portal.updateUser(u.username, { status: "DISABLED" }), `${u.displayName} is disabled.`)}><IconPause /> Disable</button>
                                    : <button type="button" onClick={() => act(portal.updateUser(u.username, { status: "ACTIVE" }), `${u.displayName} is active again.`)}><IconCheck /> Enable</button>)}
                                  {!self && <button type="button" className="is-danger" onClick={() => remove(u)}><IconTrash /> {u.lastLoginAt ? "Remove" : "Delete"}</button>}
                              </RowMenu>
                            </>)}
                          </div>
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}
      </Card>

      <div className="roles">
        {ROLES.map((r) => <div key={r} className="role-card"><b>{ROLE_LABEL[r]}</b><span>{ROLE_HELP[r]}</span></div>)}
      </div>

      <Emails />

      <EditUser user={editing} self={editing?.username === user.username} branches={branches.data ?? []}
                onClose={() => setEditing(null)} onSaved={(u) => { setEditing(null); toast(`${u.displayName} is saved.`, "ok"); list.reload(); }} />

      <ResetPassword user={resetting} onClose={() => setResetting(null)}
                     onDone={(r) => { setResetting(null); setSecret({ ...r, reset: true }); list.reload(); }} />

      <ActivityDrawer user={history} onClose={() => setHistory(null)} />

      <CreateUser open={creating} branches={branches.data ?? []} onClose={() => setCreating(false)}
                  onCreated={(r) => { setCreating(false); setSecret({ ...r, reset: false }); list.reload(); }} />

      <Drawer open={!!secret} onClose={() => setSecret(null)} title={secret?.reset ? "Password reset" : "Account created"}
              subtitle={secret ? `${secret.user.displayName} (${secret.user.username})` : ""}>
        {secret && (secret.emailedTo ? (
          <div className="callout callout--good"><IconMail /><div><b>Emailed to {secret.emailedTo}</b>
            <span>The temporary password is in that email. They must choose their own password when they first sign in.</span></div></div>
        ) : (
          <div className="stack stack--sm">
            {secret.emailError && <div className="callout callout--bad"><IconMail /><div><b>The email could not be sent</b><span>{secret.emailError} Give them the password below instead.</span></div></div>}
            <div className="secret"><Mono>{secret.temporaryPassword}</Mono>
              <Button size="sm" kind="ghost" onClick={() => navigator.clipboard?.writeText(secret.temporaryPassword ?? "").then(() => toast("Copied.", "ok"))}>Copy</Button>
            </div>
            <div className="callout callout--warn"><IconLock /><div><b>Shown once</b><span>Give it to the person directly. They must choose their own password when they first sign in.</span></div></div>
          </div>
        ))}
      </Drawer>
    </div>
  );
}

/**
 * A small actions menu. The list is fixed-positioned under (or above) its button so the table's scroll box cannot clip it.
 * Closes on a choice, an outside click, Escape, or scrolling.
 */
function RowMenu({ label, children }: { label: string; children: ReactNode }) {
  const [pos, setPos] = useState<{ top?: number; bottom?: number; right: number } | null>(null);
  const btn = useRef<HTMLButtonElement>(null);
  useEffect(() => {
    if (!pos) return;
    const close = (e: Event) => { if (e.type !== "mousedown" || !(e.target as Element).closest?.(".menu__list, .menu__btn")) setPos(null); };
    const esc = (e: KeyboardEvent) => { if (e.key === "Escape") setPos(null); };
    window.addEventListener("mousedown", close); window.addEventListener("scroll", close, true); window.addEventListener("resize", close);
    window.addEventListener("keydown", esc);
    return () => { window.removeEventListener("mousedown", close); window.removeEventListener("scroll", close, true);
      window.removeEventListener("resize", close); window.removeEventListener("keydown", esc); };
  }, [pos]);
  function toggle() {
    if (pos) return setPos(null);
    const r = btn.current!.getBoundingClientRect();
    const right = window.innerWidth - r.right;
    setPos(window.innerHeight - r.bottom < 260 ? { bottom: window.innerHeight - r.top + 4, right } : { top: r.bottom + 4, right });
  }
  return (
    <>
      <button ref={btn} type="button" className="btn btn--ghost btn--sm menu__btn" aria-haspopup="menu" aria-expanded={!!pos} onClick={toggle}>{label}</button>
      {pos && <div className="menu__list" role="menu" style={{ position: "fixed", ...pos }} onClick={() => setPos(null)}>{children}</div>}
    </>
  );
}

/** Reset: email the new temporary password (default when the person has an email) or show it once. */
function ResetPassword({ user, onClose, onDone }: { user: PortalUserRow | null; onClose: () => void; onDone: (r: UserCreated) => void }) {
  const { portal } = usePortal();
  const [byEmail, setByEmail] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const canEmail = !!user?.email;

  async function go() {
    if (!user) return;
    setBusy(true); setError("");
    try { onDone(await portal.resetPassword(user.username, canEmail && byEmail)); }
    catch (e) { setError((e as Error).message); }
    finally { setBusy(false); }
  }

  return (
    <Drawer open={!!user} onClose={onClose} title="Reset password" subtitle={user ? `${user.displayName} (${user.username})` : ""}>
      {user && (
        <div className="form">
          <p className="muted">They get a new temporary password, are signed out everywhere, and must choose their own password at the next sign-in.</p>
          {canEmail ? (<>
            <label className="radio"><input type="radio" checked={byEmail} onChange={() => setByEmail(true)} /> Email it to <b>{user.email}</b></label>
            <label className="radio"><input type="radio" checked={!byEmail} onChange={() => setByEmail(false)} /> Show it to me, and I will give it to them</label>
          </>) : <p className="muted">This person has no email address, so the password is shown to you once. Add an email with Edit to send it next time.</p>}
          {error && <div className="error-note">{error}</div>}
          <Button onClick={go} disabled={busy}>{busy ? "Resetting…" : "Reset password"}</Button>
        </div>
      )}
    </Drawer>
  );
}

const EVENT_LABEL: Record<string, string> = {
  PORTAL_LOGIN: "Signed in", PORTAL_LOGIN_FAILED: "Sign-in failed", PORTAL_ACCOUNT_LOCKED: "Account locked",
  PORTAL_LOGIN_CODE_SENT: "Sign-in code emailed", PORTAL_LOGOUT: "Signed out", PORTAL_PASSWORD_CHANGED: "Changed their password",
  PORTAL_PASSWORD_RESET: "Password reset", PORTAL_USER_CREATED: "Account created", PORTAL_USER_UPDATED: "Account changed",
  PORTAL_SESSIONS_REVOKED: "Signed out everywhere", PORTAL_USER_ARCHIVED: "Account removed", PORTAL_USER_DELETED: "Account deleted",
};

function describe(a: UserActivity, username: string) {
  const d = a.details as Record<string, string | number | boolean>;
  const other = a.subject !== `user:${username}` ? a.subject.replace(/^user:/, "") : null;
  const parts: string[] = [];
  if (other) parts.push(`for ${other}`);
  if (!other && d.by && d.by !== username) parts.push(`by ${d.by}`);
  if (d.reason) parts.push(String(d.reason));
  if (d.method) parts.push(`with ${d.method}`);
  if (d.role) parts.push(`role ${String(d.role).toLowerCase()}`);
  if (d.status) parts.push(String(d.status).toLowerCase());
  if (d.emailChanged === true) parts.push("email changed");
  if (d.resend === true) parts.push("new code");
  if (typeof d.sessions === "number") parts.push(`${d.sessions} session${d.sessions === 1 ? "" : "s"} ended`);
  if (d.ip) parts.push(`from ${d.ip}`);
  return parts.join(" · ");
}

/** Sign-ins, failures, codes and account changes for one person, newest first, from the audit journal. */
function ActivityDrawer({ user, onClose }: { user: PortalUserRow | null; onClose: () => void }) {
  const { portal } = usePortal();
  const rows = useLoad(() => (user ? portal.userActivity(user.username) : Promise.resolve([])), [user?.username]);
  return (
    <Drawer open={!!user} onClose={onClose} title="Activity" subtitle={user ? `${user.displayName} (${user.username}), newest first` : ""}>
      {!user ? null
        : rows.error && !rows.data ? <ErrorNote error={rows.error} onRetry={rows.reload} />
        : !rows.data ? <Spinner />
        : rows.data.length === 0 ? <Empty icon={<IconHistory />} title="Nothing recorded yet" />
        : (
          <ul className="activity">
            {rows.data.map((a, i) => (
              <li key={i} className={/FAILED|LOCKED/.test(a.event) ? "is-bad" : ""}>
                <b>{EVENT_LABEL[a.event] ?? a.event}</b>
                <span className="muted">{user ? describe(a, user.username) : ""}</span>
                <time title={a.at}>{fmt.dateTime(a.at)}</time>
              </li>
            ))}
          </ul>
        )}
    </Drawer>
  );
}

function CreateUser({ open, branches, onClose, onCreated }: {
  open: boolean; branches: { code: string; name: string }[]; onClose: () => void; onCreated: (r: UserCreated) => void;
}) {
  const { portal } = usePortal();
  const [username, setUsername] = useState("");
  const [displayName, setDisplayName] = useState("");
  const [email, setEmail] = useState("");
  const [sendEmail, setSendEmail] = useState(true);
  const [role, setRole] = useState<Role>("TELLER");
  const [branchCode, setBranch] = useState("");
  const [languages, setLanguages] = useState("en,sw");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true); setError("");
    try {
      const r = await portal.createUser({ username: username.trim().toLowerCase(), displayName: displayName.trim(),
        email: email.trim() || undefined, sendEmail: !!email.trim() && sendEmail, role,
        branchCode: branchCode || undefined, languages: languages.split(",").map((s) => s.trim()).filter(Boolean) });
      setUsername(""); setDisplayName(""); setEmail(""); setRole("TELLER"); setBranch("");
      onCreated(r);
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Drawer open={open} onClose={onClose} title="Add a person" subtitle="They get a temporary password, by email or from you, to change at first sign-in.">
      <form className="form" onSubmit={submit}>
        <label><span>Full name</span><input className="input" value={displayName} onChange={(e) => setDisplayName(e.target.value)} required maxLength={100} /></label>
        <label><span>Email</span><input className="input" type="email" value={email} onChange={(e) => setEmail(e.target.value)}
                                        maxLength={200} placeholder="name@bank.co.ke" spellCheck={false} />
          <small className="muted">Sign-in codes are sent here. Without an email, the person signs in with the password only.</small></label>
        {email.trim() && <label className="check"><input type="checkbox" checked={sendEmail} onChange={(e) => setSendEmail(e.target.checked)} />
          Email the temporary password to this address</label>}
        <label><span>Username</span><input className="input" value={username} onChange={(e) => setUsername(e.target.value)} required
                                           pattern="[a-zA-Z0-9._\-]{3,50}" title="3 to 50 letters, digits, dots, dashes or underscores" spellCheck={false} /></label>
        <label><span>Role</span>
          <select className="select" value={role} onChange={(e) => setRole(e.target.value as Role)}>
            {ROLES.map((r) => <option key={r} value={r}>{ROLE_LABEL[r]}</option>)}
          </select>
          <small className="muted">{ROLE_HELP[role]}</small>
        </label>
        <label><span>Branch</span>
          <select className="select" value={branchCode} onChange={(e) => setBranch(e.target.value)}>
            <option value="">All branches</option>
            {branches.map((b) => <option key={b.code} value={b.code}>{b.name}</option>)}
          </select>
        </label>
        <label><span>Languages for video calls</span><input className="input" value={languages} onChange={(e) => setLanguages(e.target.value)} placeholder="en,sw" /></label>
        {error && <div className="error-note">{error}</div>}
        <Button type="submit" disabled={busy}>{busy ? "Creating…" : "Create account"}</Button>
      </form>
    </Drawer>
  );
}

/** Change a person's details. The username cannot change (it is in the audit trail). */
function EditUser({ user, self, branches, onClose, onSaved }: {
  user: PortalUserRow | null; self: boolean; branches: { code: string; name: string }[];
  onClose: () => void; onSaved: (u: PortalUserRow) => void;
}) {
  const { portal } = usePortal();
  const [form, setForm] = useState({ displayName: "", email: "", role: "TELLER" as Role, branchCode: "", languages: "" });
  const [loadedFor, setLoadedFor] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  if (user && loadedFor !== user.username) {
    setLoadedFor(user.username);
    setForm({ displayName: user.displayName, email: user.email ?? "", role: user.role, branchCode: user.branchCode ?? "",
      languages: user.languages.join(",") });
    setError("");
  }
  if (!user && loadedFor !== null) setLoadedFor(null);

  const set = <K extends keyof typeof form>(k: K, v: (typeof form)[K]) => setForm((f) => ({ ...f, [k]: v }));

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (!user) return;
    setBusy(true); setError("");
    try {
      onSaved(await portal.updateUser(user.username, {
        displayName: form.displayName.trim(),
        email: form.email.trim(),
        ...(self ? {} : { role: form.role }),
        branchCode: form.branchCode,
        languages: form.languages.split(",").map((x) => x.trim()).filter(Boolean),
      }));
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Drawer open={!!user} onClose={onClose} title="Edit person" subtitle={user ? `Username: ${user.username} (cannot be changed)` : ""}>
      <form className="form" onSubmit={submit}>
        <label><span>Full name</span><input className="input" value={form.displayName} onChange={(e) => set("displayName", e.target.value)} required maxLength={100} /></label>
        <label><span>Email</span><input className="input" type="email" value={form.email} onChange={(e) => set("email", e.target.value)}
                                        maxLength={200} placeholder="name@bank.co.ke" spellCheck={false} />
          <small className="muted">{form.email.trim() ? "Sign-in codes go to this address." : "Without an email, this person signs in with the password only."}</small></label>
        <label><span>Role</span>
          <select className="select" value={form.role} disabled={self} onChange={(e) => set("role", e.target.value as Role)}>
            {ROLES.map((r) => <option key={r} value={r}>{ROLE_LABEL[r]}</option>)}
          </select>
          <small className="muted">{self ? "You cannot change your own role." : ROLE_HELP[form.role]}</small>
        </label>
        <label><span>Branch</span>
          <select className="select" value={form.branchCode} onChange={(e) => set("branchCode", e.target.value)}>
            <option value="">All branches</option>
            {branches.map((b) => <option key={b.code} value={b.code}>{b.name}</option>)}
          </select>
        </label>
        <label><span>Languages for video calls</span><input className="input" value={form.languages} onChange={(e) => set("languages", e.target.value)} placeholder="en,sw" /></label>
        {error && <div className="error-note">{error}</div>}
        <Button type="submit" disabled={busy}>{busy ? "Saving…" : "Save changes"}</Button>
      </form>
    </Drawer>
  );
}

const EMAIL_TONE: Record<EmailRow["status"], "good" | "bad" | "warn"> = { SENT: "good", FAILED: "bad", SIMULATED: "warn" };

/** The last emails. Without a mail server they are simulated: the body (with the code) is shown here instead. */
function Emails() {
  const { portal } = usePortal();
  const paging = usePaging("emails", [], 25);
  const mails = useLoad(() => portal.emails({ page: paging.page, size: paging.size }), [paging.page, paging.size], 15_000);
  const live = mails.data?.live;
  return (
    <Card pad={false} title="Emails" subtitle={live === false
      ? "No mail server is configured, so emails are simulated: they are not sent, and their text (with the sign-in code) shows here."
      : "Sign-in codes and other emails the portal sent. Codes are never stored."}
          actions={live === false ? <Badge tone="warn">Simulated</Badge> : live ? <Badge tone="good">Mail server connected</Badge> : undefined}>
      {mails.error && !mails.data ? <div className="card__body"><ErrorNote error={mails.error} onRetry={mails.reload} /></div>
        : !mails.data ? <div className="card__body"><Spinner /></div>
        : mails.data.emails.length === 0 ? <Empty icon={<IconMail />} title="No emails yet" />
        : (
          <div className="table-wrap">
            <table className="table">
              <thead><tr><th>When</th><th>To</th><th>Subject</th><th>Status</th>{live === false && <th>Text</th>}</tr></thead>
              <tbody>
                {mails.data.emails.map((m) => (
                  <tr key={m.id}>
                    <td className="muted nowrap" title={m.createdAt}>{fmt.ago(m.createdAt)}</td>
                    <td className="nowrap">{m.to}</td>
                    <td>{m.subject}</td>
                    <td><Badge tone={EMAIL_TONE[m.status]}>{m.status === "SENT" ? "Sent" : m.status === "FAILED" ? "Failed" : "Simulated"}</Badge>
                      {m.error && <small className="muted block">{m.error}</small>}</td>
                    {live === false && <td>{m.body ? <pre className="mail-body">{m.body}</pre> : <span className="muted">—</span>}</td>}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      <Pager data={mails.data ? { ...mails.data, items: mails.data.emails } : null} onPage={paging.setPage} onSize={paging.setSize} />
    </Card>
  );
}
