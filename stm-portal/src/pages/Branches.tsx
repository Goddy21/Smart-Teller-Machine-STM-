import { useMemo, useState, type FormEvent } from "react";
import { usePortal } from "../context";
import { can, ROLE_LABEL } from "../auth";
import type { BranchRow, PortalUserRow, UserCreated } from "../api/portal";
import { Badge, Button, Card, Empty, ErrorNote, fmt, Modal, Mono, Pager, RowMenu, Segmented, Spinner, useClientPage, useDebounced, useLoad } from "../ui/kit";
import { IconCheck, IconEdit, IconMapPin, IconPause, IconPlus, IconRefresh, IconSearch, IconTrash, IconUsers } from "../ui/Icons";
import { CreateUser, EditUser, ResetPassword, UserSecret } from "./Users";

/** Branches, and the portal accounts that belong to one branch (branch users). */
export function Branches() {
  const { user } = usePortal();
  const [tab, setTab] = useState<"branches" | "users">("branches");
  const [branchFilter, setBranchFilter] = useState("");
  const admin = can.manageUsers(user.role);
  return (
    <div className="stack">
      <Segmented value={tab} onChange={setTab} options={[{ value: "branches", label: "Branches" }, ...(admin ? [{ value: "users" as const, label: "Branch users" }] : [])]} />
      {tab === "branches" || !admin
        ? <BranchList onUsers={(code) => { setBranchFilter(code); setTab("users"); }} />
        : <BranchUsers branch={branchFilter} onBranch={setBranchFilter} />}
    </div>
  );
}

function BranchList({ onUsers }: { onUsers: (code: string) => void }) {
  const { portal, toast, confirm, user } = usePortal();
  const admin = can.manageKiosks(user.role);
  const list = useLoad(() => portal.branches(), [], 60_000);
  const [q, setQ] = useState("");
  const [show, setShow] = useState<"all" | "ACTIVE" | "CLOSED">("ACTIVE");
  const [editing, setEditing] = useState<BranchRow | "new" | null>(null);
  const dq = useDebounced(q.trim().toLowerCase(), 200);
  const rows = useMemo(() => (list.data ?? []).filter((b) => (show === "all" || b.status === show)
    && (!dq || [b.code, b.name, b.location, b.region].some((s) => s?.toLowerCase().includes(dq)))), [list.data, show, dq]);
  const paged = useClientPage(rows, "branches", [show, dq]);

  async function setStatus(b: BranchRow, status: "ACTIVE" | "CLOSED") {
    if (status === "CLOSED" && !await confirm({ title: `Close ${b.name}?`, confirmLabel: "Close branch", tone: "warn", message: <>
      <p>No new kiosks can be added to it. Its history, kiosks and users are kept.</p>
      <p>Active kiosks must first be moved to another branch or disabled.</p></> })) return;
    try { await portal.setBranchStatus(b.code, status); toast(`${b.name} is ${status === "CLOSED" ? "closed" : "open again"}.`, "ok"); list.reload(); }
    catch (e) { toast((e as Error).message, "error"); }
  }
  async function remove(b: BranchRow) {
    if (!await confirm({ title: `Delete ${b.name}?`, confirmLabel: "Delete branch", tone: "danger",
      message: "Only a branch that never had kiosks or users can be deleted. Otherwise close it." })) return;
    try { await portal.removeBranch(b.code); toast(`${b.name} is deleted.`, "ok"); list.reload(); }
    catch (e) { toast((e as Error).message, "error"); }
  }

  if (list.error && !list.data) return <ErrorNote error={list.error} onRetry={list.reload} />;
  if (!list.data) return <Spinner />;
  const all = list.data;

  return (
    <>
      <Card pad={false} title="Branches" subtitle="Each kiosk and each branch user belongs to a branch"
            actions={<div className="toolbar toolbar--wrap">
              <label className="search"><IconSearch /><input value={q} onChange={(e) => setQ(e.target.value)} placeholder="Code, name, location, region…" /></label>
              <Segmented value={show} onChange={setShow} options={[
                { value: "ACTIVE", label: "Open", count: all.filter((b) => b.status === "ACTIVE").length },
                { value: "CLOSED", label: "Closed", count: all.filter((b) => b.status === "CLOSED").length },
                { value: "all", label: "All", count: all.length },
              ]} />
              {admin && <Button icon={<IconPlus />} onClick={() => setEditing("new")}>Add branch</Button>}
            </div>}>
        {rows.length === 0 ? <Empty icon={<IconMapPin />} title={all.length ? "No branches match" : "No branches yet"} /> : (
          <div className="table-wrap">
            <table className="table">
              <thead><tr><th>Code</th><th>Branch</th><th>Region</th><th>Status</th><th className="num">Kiosks</th><th className="num">Branch users</th>
                <th className="num">Terminal users</th><th className="num">Sessions today</th><th>Added</th>{admin && <th />}</tr></thead>
              <tbody>
                {paged.rows.map((b) => (
                  <tr key={b.code}>
                    <td><Mono>{b.code}</Mono></td>
                    <td><b>{b.name}</b>{b.location && <small className="muted"><br />{b.location}</small>}</td>
                    <td>{b.region ?? <span className="muted">—</span>}</td>
                    <td><Badge tone={b.status === "ACTIVE" ? "good" : "neutral"}>{b.status === "ACTIVE" ? "Open" : "Closed"}</Badge></td>
                    <td className="num">{b.terminalsActive}{b.terminals !== b.terminalsActive && <small className="muted"> / {b.terminals}</small>}</td>
                    <td className="num">{admin ? <button type="button" className="link" onClick={() => onUsers(b.code)}>{b.users}</button> : b.users}</td>
                    <td className="num">{b.terminalUsers}</td>
                    <td className="num">{b.sessionsToday}</td>
                    <td className="muted nowrap">{fmt.date(b.createdAt)}</td>
                    {admin && <td>
                      <div className="row-actions">
                        <Button size="sm" kind="ghost" icon={<IconEdit />} onClick={() => setEditing(b)}>Edit</Button>
                        <RowMenu label="More…">
                          <button type="button" onClick={() => onUsers(b.code)}><IconUsers /> Branch users</button>
                          {b.status === "ACTIVE"
                            ? <button type="button" onClick={() => setStatus(b, "CLOSED")}><IconPause /> Close branch</button>
                            : <button type="button" onClick={() => setStatus(b, "ACTIVE")}><IconCheck /> Reopen</button>}
                          <button type="button" className="is-danger" disabled={b.terminals + b.users + b.terminalUsers > 0} onClick={() => remove(b)}
                                  title={b.terminals + b.users + b.terminalUsers > 0 ? "It has kiosks or users: close it instead" : ""}><IconTrash /> Delete</button>
                        </RowMenu>
                      </div>
                    </td>}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        <Pager data={paged.data} onPage={paged.setPage} onSize={paged.setSize} />
      </Card>
      <BranchForm branch={editing} onClose={() => setEditing(null)} onSaved={(b, isNew) => {
        setEditing(null); toast(isNew ? `${b.name} is added.` : `${b.name} is saved.`, "ok"); list.reload();
      }} />
    </>
  );
}

function BranchForm({ branch, onClose, onSaved }: { branch: BranchRow | "new" | null; onClose: () => void; onSaved: (b: BranchRow, isNew: boolean) => void }) {
  const { portal } = usePortal();
  const isNew = branch === "new";
  const [f, setF] = useState({ code: "", name: "", location: "", region: "" });
  const [key, setKey] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const want = branch == null ? null : isNew ? "" : branch.code;
  if (want !== key) {
    setKey(want); setError("");
    if (branch && branch !== "new") setF({ code: branch.code, name: branch.name, location: branch.location ?? "", region: branch.region ?? "" });
    else setF({ code: "", name: "", location: "", region: "" });
  }
  const set = (k: keyof typeof f, v: string) => setF((x) => ({ ...x, [k]: v }));

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true); setError("");
    const d = { name: f.name.trim(), location: f.location.trim() || undefined, region: f.region.trim() || undefined };
    try {
      const b = isNew ? await portal.createBranch(f.code.trim(), d) : await portal.updateBranch(f.code, d);
      onSaved(b, isNew);
    } catch (err) { setError((err as Error).message); } finally { setBusy(false); }
  }

  return (
    <Modal open={branch != null} busy={busy} onClose={onClose} icon={isNew ? <IconPlus /> : <IconEdit />}
           title={isNew ? "Add a branch" : `Edit ${typeof branch === "object" && branch ? branch.name : ""}`}
           footer={<>
             <Button kind="ghost" onClick={onClose} disabled={busy}>Cancel</Button>
             <Button type="submit" form="branch-form" disabled={busy}>{busy ? "Saving…" : isNew ? "Add branch" : "Save changes"}</Button>
           </>}>
      <form id="branch-form" className="form" onSubmit={submit}>
        <div className="form__row">
          <label><span>Branch code</span>
            <input className="input" value={f.code} onChange={(e) => set("code", e.target.value.toUpperCase())} required disabled={!isNew}
                   maxLength={20} pattern="[A-Za-z0-9][A-Za-z0-9\-]{0,19}" placeholder="045" spellCheck={false} autoComplete="off" />
            {isNew && <small className="muted">The core-banking branch code. It cannot be changed later.</small>}</label>
          <label><span>Name</span>
            <input className="input" value={f.name} onChange={(e) => set("name", e.target.value)} required maxLength={100} placeholder="Kisumu" /></label>
        </div>
        <div className="form__row">
          <label><span>Location <small className="muted">(optional)</small></span>
            <input className="input" value={f.location} onChange={(e) => set("location", e.target.value)} maxLength={160} placeholder="Oginga Odinga Street" /></label>
          <label><span>Region <small className="muted">(optional)</small></span>
            <input className="input" value={f.region} onChange={(e) => set("region", e.target.value)} maxLength={60} placeholder="Western" /></label>
        </div>
        {error && <div className="error-note" role="alert">{error}</div>}
      </form>
    </Modal>
  );
}

/** Portal accounts tied to a branch: they see the portal for their branch (tellers, supervisors …). */
function BranchUsers({ branch, onBranch }: { branch: string; onBranch: (code: string) => void }) {
  const { portal, toast, confirm } = usePortal();
  const users = useLoad(() => portal.users(), []);
  const branches = useLoad(() => portal.branches(), []);
  const [creating, setCreating] = useState(false);
  const [editing, setEditing] = useState<PortalUserRow | null>(null);
  const [resetting, setResetting] = useState<PortalUserRow | null>(null);
  const [secret, setSecret] = useState<(UserCreated & { reset: boolean }) | null>(null);
  const rows = useMemo(() => (users.data ?? []).filter((u) => u.branchCode && (!branch || u.branchCode === branch)), [users.data, branch]);
  const paged = useClientPage(rows, "branch-users", [branch]);
  const open = (branches.data ?? []).filter((b) => b.status === "ACTIVE");
  const name = (code: string | null) => (branches.data ?? []).find((b) => b.code === code)?.name ?? code;

  async function act(p: Promise<unknown>, ok: string) {
    try { await p; toast(ok, "ok"); users.reload(); } catch (e) { toast((e as Error).message, "error"); }
  }

  if (users.error && !users.data) return <ErrorNote error={users.error} onRetry={users.reload} />;
  if (!users.data) return <Spinner />;

  return (
    <>
      <Card pad={false} title="Branch users" subtitle="Portal accounts that belong to one branch. Accounts for all branches are on the Portal accounts page."
            actions={<div className="toolbar toolbar--wrap">
              <select className="select select--sm" value={branch} onChange={(e) => onBranch(e.target.value)} aria-label="Branch">
                <option value="">All branches</option>
                {(branches.data ?? []).map((b) => <option key={b.code} value={b.code}>{b.name} ({b.code})</option>)}
              </select>
              <Button icon={<IconPlus />} onClick={() => setCreating(true)} disabled={!open.length}>Add branch user</Button>
            </div>}>
        {rows.length === 0 ? <Empty icon={<IconUsers />} title={branch ? `No users in ${name(branch)} yet` : "No branch users yet"}>Add tellers and supervisors for a branch with the button above.</Empty> : (
          <div className="table-wrap">
            <table className="table">
              <thead><tr><th>Person</th><th>Branch</th><th>Role</th><th>Status</th><th>Last sign-in</th><th /></tr></thead>
              <tbody>
                {paged.rows.map((u) => (
                  <tr key={u.username}>
                    <td><b>{u.displayName}</b><small className="muted"><br /><Mono>{u.username}</Mono>{u.email ? ` · ${u.email}` : ""}</small></td>
                    <td>{name(u.branchCode)} <Mono>{u.branchCode}</Mono></td>
                    <td>{ROLE_LABEL[u.role]}</td>
                    <td>{u.locked ? <Badge tone="bad">Locked</Badge> : <Badge value={u.status} />}{u.mustChangePassword && <small className="muted"><br />must set password</small>}</td>
                    <td className="muted">{u.lastLoginAt ? fmt.ago(u.lastLoginAt) : "never"}</td>
                    <td>
                      <div className="row-actions">
                        <Button size="sm" kind="ghost" icon={<IconEdit />} onClick={() => setEditing(u)}>Edit</Button>
                        <RowMenu label="More…">
                          <button type="button" onClick={() => setResetting(u)}><IconRefresh /> Reset password</button>
                          {u.status === "ACTIVE"
                            ? <button type="button" onClick={async () => await confirm({ title: `Disable ${u.displayName}?`, confirmLabel: "Disable", tone: "warn",
                                message: "They are signed out at once and cannot sign in until enabled again." }) && act(portal.updateUser(u.username, { status: "DISABLED" }), `${u.displayName} is disabled.`)}><IconPause /> Disable</button>
                            : <button type="button" onClick={() => act(portal.updateUser(u.username, { status: "ACTIVE" }), `${u.displayName} is active again.`)}><IconCheck /> Enable</button>}
                        </RowMenu>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        <Pager data={paged.data} onPage={paged.setPage} onSize={paged.setSize} />
      </Card>
      <CreateUser open={creating} branches={open} presetBranch={branch || open[0]?.code} onClose={() => setCreating(false)}
                  onCreated={(r) => { setCreating(false); setSecret({ ...r, reset: false }); users.reload(); }} />
      <EditUser user={editing} self={false} branches={branches.data ?? []} onClose={() => setEditing(null)}
                onSaved={(u) => { setEditing(null); toast(`${u.displayName} is saved.`, "ok"); users.reload(); }} />
      <ResetPassword user={resetting} onClose={() => setResetting(null)}
                     onDone={(r) => { setResetting(null); setSecret({ ...r, reset: true }); users.reload(); }} />
      <UserSecret secret={secret} onClose={() => setSecret(null)} />
    </>
  );
}
