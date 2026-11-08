import { useState, type FormEvent } from "react";
import { usePortal } from "../context";
import { ROLE_LABEL, type Role } from "../auth";
import type { PortalUserRow } from "../api/portal";
import { Badge, Button, Card, Drawer, Empty, ErrorNote, fmt, Mono, Spinner, useLoad } from "../ui/kit";
import { IconLock, IconRefresh, IconUsers } from "../ui/Icons";

const ROLES: Role[] = ["TELLER", "SUPERVISOR", "AUDITOR", "ADMIN"];
const ROLE_HELP: Record<Role, string> = {
  TELLER: "Takes video calls and approves or rejects customer sessions.",
  SUPERVISOR: "Everything a teller does, plus kiosk control (lock, reboot), deposits and the audit trail.",
  AUDITOR: "Read-only, including the audit journal and call recordings. Cannot decide or control kiosks.",
  ADMIN: "Manages portal accounts and kiosks, reads the audit trail. Does not take calls or decide.",
};

/** Portal accounts (administrators only). Temporary passwords are shown once and must be changed at first sign-in. */
export function Users() {
  const { portal, user, toast } = usePortal();
  const list = useLoad(() => portal.users(), []);
  const branches = useLoad(() => portal.reference().then((r) => r.branches), []);
  const [creating, setCreating] = useState(false);
  const [secret, setSecret] = useState<{ username: string; password: string } | null>(null);

  async function act(p: Promise<unknown>, ok: string) {
    try { await p; toast(ok, "ok"); list.reload(); } catch (e) { toast((e as Error).message, "error"); }
  }
  async function reset(u: PortalUserRow) {
    if (!confirm(`Reset the password of ${u.displayName}? They will be signed out everywhere.`)) return;
    try {
      const r = await portal.resetPassword(u.username);
      setSecret({ username: u.username, password: r.temporaryPassword });
      list.reload();
    } catch (e) { toast((e as Error).message, "error"); }
  }

  return (
    <div className="stack">
      <Card pad={false} title="Portal accounts" subtitle="Everyone who can sign in to the STM portal, and what they may do."
            actions={<Button icon={<IconUsers />} onClick={() => setCreating(true)}>Add person</Button>}>
        {list.error && !list.data ? <div className="card__body"><ErrorNote error={list.error} onRetry={list.reload} /></div>
          : !list.data ? <div className="card__body"><Spinner /></div>
          : list.data.length === 0 ? <Empty icon={<IconUsers />} title="No accounts" />
          : (
            <div className="table-wrap">
              <table className="table">
                <thead><tr><th>Person</th><th>Role</th><th>Branch</th><th>Languages</th><th>Status</th><th>Last sign-in</th><th /></tr></thead>
                <tbody>
                  {list.data.map((u) => {
                    const self = u.username === user.username;
                    return (
                      <tr key={u.username}>
                        <td>
                          <div className="cell-main">
                            <span className="avatar">{u.displayName.split(/\s+/).map((p) => p[0]).slice(0, 2).join("").toUpperCase()}</span>
                            <span><b>{u.displayName}{self && <span className="muted"> (you)</span>}</b><small><Mono>{u.username}</Mono></small></span>
                          </div>
                        </td>
                        <td>
                          <select className="select" value={u.role} disabled={self}
                                  onChange={(e) => act(portal.updateUser(u.username, { role: e.target.value as Role }), `${u.displayName} is now ${ROLE_LABEL[e.target.value as Role]}.`)}>
                            {ROLES.map((r) => <option key={r} value={r}>{ROLE_LABEL[r]}</option>)}
                          </select>
                        </td>
                        <td>{u.branchCode ?? <span className="muted">All</span>}</td>
                        <td>{u.languages.join(", ").toUpperCase()}</td>
                        <td>
                          <div className="badges">
                            <Badge value={u.status} tone={u.status === "ACTIVE" ? "good" : "neutral"}>{u.status === "ACTIVE" ? "Active" : "Disabled"}</Badge>
                            {u.locked && <Badge tone="bad">Locked</Badge>}
                            {u.mustChangePassword && <Badge tone="warn">Must change password</Badge>}
                          </div>
                        </td>
                        <td className="muted">{u.lastLoginAt ? fmt.ago(u.lastLoginAt) : "Never"}</td>
                        <td>
                          <div className="row-actions">
                            <Button size="sm" kind="ghost" icon={<IconRefresh />} onClick={() => reset(u)}>Reset password</Button>
                            {u.locked && u.status === "ACTIVE" && (
                              <Button size="sm" kind="ghost" icon={<IconLock />} onClick={() => act(portal.updateUser(u.username, { status: "ACTIVE" }), `${u.displayName} is unlocked.`)}>Unlock</Button>
                            )}
                            {!self && (u.status === "ACTIVE"
                              ? <Button size="sm" kind="secondary" onClick={() => confirm(`Disable ${u.displayName}? They are signed out at once.`) && act(portal.updateUser(u.username, { status: "DISABLED" }), `${u.displayName} is disabled.`)}>Disable</Button>
                              : <Button size="sm" kind="success" onClick={() => act(portal.updateUser(u.username, { status: "ACTIVE" }), `${u.displayName} is active again.`)}>Enable</Button>)}
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

      <CreateUser open={creating} branches={branches.data ?? []} onClose={() => setCreating(false)}
                  onCreated={(username, password) => { setCreating(false); setSecret({ username, password }); list.reload(); }} />

      <Drawer open={!!secret} onClose={() => setSecret(null)} title="Temporary password" subtitle={secret ? `For ${secret.username}` : ""}>
        {secret && (
          <div className="stack stack--sm">
            <div className="secret"><Mono>{secret.password}</Mono>
              <Button size="sm" kind="ghost" onClick={() => navigator.clipboard?.writeText(secret.password).then(() => toast("Copied.", "ok"))}>Copy</Button>
            </div>
            <div className="callout callout--warn"><IconLock /><div><b>Shown once</b><span>Give it to the person directly (not by email). They must choose their own password when they first sign in.</span></div></div>
          </div>
        )}
      </Drawer>
    </div>
  );
}

function CreateUser({ open, branches, onClose, onCreated }: {
  open: boolean; branches: { code: string; name: string }[]; onClose: () => void; onCreated: (username: string, password: string) => void;
}) {
  const { portal } = usePortal();
  const [username, setUsername] = useState("");
  const [displayName, setDisplayName] = useState("");
  const [role, setRole] = useState<Role>("TELLER");
  const [branchCode, setBranch] = useState("");
  const [languages, setLanguages] = useState("en,sw");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true); setError("");
    try {
      const r = await portal.createUser({ username: username.trim().toLowerCase(), displayName: displayName.trim(), role,
        branchCode: branchCode || undefined, languages: languages.split(",").map((s) => s.trim()).filter(Boolean) });
      setUsername(""); setDisplayName(""); setRole("TELLER"); setBranch("");
      onCreated(r.user.username, r.temporaryPassword);
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Drawer open={open} onClose={onClose} title="Add a person" subtitle="They get a temporary password to change at first sign-in.">
      <form className="form" onSubmit={submit}>
        <label><span>Full name</span><input className="input" value={displayName} onChange={(e) => setDisplayName(e.target.value)} required maxLength={100} /></label>
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
