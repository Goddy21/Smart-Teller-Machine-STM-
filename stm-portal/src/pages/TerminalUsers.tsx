import { useEffect, useMemo, useState, type FormEvent } from "react";
import { usePortal } from "../context";
import { can } from "../auth";
import type { OperatorRole, TerminalUser } from "../api/portal";
import { Badge, Button, Card, Empty, ErrorNote, fmt, Modal, Mono, Pager, RowMenu, Spinner, useClientPage, useDebounced, useLoad } from "../ui/kit";
import { IconCheck, IconCopy, IconEdit, IconKey, IconPause, IconPlus, IconSearch, IconTrash, IconUsers } from "../ui/Icons";
import { label, OPERATOR_FUNCTION, OPERATOR_ROLE } from "../ui/labels";

const ROLES: OperatorRole[] = ["CUSTODIAN", "CIT", "ENGINEER", "SUPERVISOR"];

/**
 * Terminal users: the people who sign in on the kiosk itself (maintenance screen) with a username and a PIN,
 * to replenish cash, clear bins, load cards and paper, or service the machine.
 */
export function TerminalUsers() {
  const { portal, toast, confirm, user } = usePortal();
  const admin = can.manageKiosks(user.role);
  const list = useLoad(() => portal.terminalUsers(), []);
  const roles = useLoad(() => portal.terminalUserRoles(), []);
  const [q, setQ] = useState("");
  const dq = useDebounced(q.trim().toLowerCase(), 200);
  const [editing, setEditing] = useState<TerminalUser | "new" | null>(null);
  const [pin, setPin] = useState<{ user: TerminalUser; pin: string; reset: boolean } | null>(null);
  const rows = useMemo(() => (list.data ?? []).filter((u) => !dq || [u.username, u.fullName, u.branchCode, u.branchName, u.phone, ...u.terminals]
    .some((s) => s?.toLowerCase().includes(dq))), [list.data, dq]);
  const paged = useClientPage(rows, "terminal-users", [dq]);

  async function act(p: Promise<unknown>, ok: string) {
    try { await p; toast(ok, "ok"); list.reload(); } catch (e) { toast((e as Error).message, "error"); }
  }
  async function resetPin(u: TerminalUser) {
    if (!await confirm({ title: `New PIN for ${u.fullName}?`, confirmLabel: "Issue new PIN", tone: "warn",
      message: "The current PIN stops working at once. The new PIN is shown once." })) return;
    try { const r = await portal.resetTerminalUserPin(u.id); setPin({ ...r, reset: true }); list.reload(); }
    catch (e) { toast((e as Error).message, "error"); }
  }

  if (list.error && !list.data) return <ErrorNote error={list.error} onRetry={list.reload} />;
  if (!list.data) return <Spinner />;

  return (
    <div className="stack">
      <Card pad={false} title="Terminal users" subtitle="People who sign in on the kiosk’s maintenance screen with a username and PIN"
            actions={<div className="toolbar toolbar--wrap">
              <label className="search"><IconSearch /><input value={q} onChange={(e) => setQ(e.target.value)} placeholder="Name, username, branch, kiosk…" /></label>
              {admin && <Button icon={<IconPlus />} onClick={() => setEditing("new")}>Add terminal user</Button>}
            </div>}>
        {rows.length === 0 ? <Empty icon={<IconUsers />} title={list.data.length ? "No terminal users match" : "No terminal users yet"}>
            {admin && !list.data.length ? "Add custodians, cash-in-transit crews and engineers who work on the kiosks." : null}</Empty> : (
          <div className="table-wrap">
            <table className="table">
              <thead><tr><th>Person</th><th>Role</th><th>Branch</th><th>Kiosks</th><th>Status</th><th>Last sign-in</th>{admin && <th />}</tr></thead>
              <tbody>
                {paged.rows.map((u) => {
                  const locked = !!u.lockedUntil && new Date(u.lockedUntil) > new Date();
                  return (
                    <tr key={u.id}>
                      <td><b>{u.fullName}</b><small className="muted"><br /><Mono>{u.username}</Mono>{u.phone ? ` · ${u.phone}` : ""}</small></td>
                      <td>{label(OPERATOR_ROLE, u.role)}</td>
                      <td>{u.branchName ?? <span className="muted">Any</span>}</td>
                      <td className="small">{u.allTerminals ? <Badge tone="info">{u.branchCode ? "All kiosks in the branch" : "All kiosks"}</Badge>
                        : u.terminals.length ? u.terminals.map((c) => <div key={c}><Mono>{c}</Mono></div>) : <span className="dir-dr">None assigned</span>}</td>
                      <td>{u.status === "DISABLED" ? <Badge tone="bad">Disabled</Badge> : locked ? <Badge tone="warn">Locked</Badge> : <Badge tone="good">Active</Badge>}
                        {locked && <small className="muted"><br />until {fmt.dateTime(u.lockedUntil!)}</small>}</td>
                      <td className="muted small">{u.lastLoginAt ? <>{fmt.ago(u.lastLoginAt)}{u.lastTerminal && <><br />on <Mono>{u.lastTerminal}</Mono></>}</> : "never"}</td>
                      {admin && <td>
                        <div className="row-actions">
                          <Button size="sm" kind="ghost" icon={<IconEdit />} onClick={() => setEditing(u)}>Edit</Button>
                          <RowMenu label="More…">
                            <button type="button" onClick={() => resetPin(u)}><IconKey /> {locked ? "Unlock with a new PIN" : "Reset PIN"}</button>
                            {u.status === "ACTIVE"
                              ? <button type="button" onClick={() => act(portal.setTerminalUserStatus(u.id, "DISABLED"), `${u.fullName} is disabled.`)}><IconPause /> Disable</button>
                              : <button type="button" onClick={() => act(portal.setTerminalUserStatus(u.id, "ACTIVE"), `${u.fullName} is active again.`)}><IconCheck /> Enable</button>}
                            <button type="button" className="is-danger" onClick={async () => await confirm({ title: `Delete ${u.fullName}?`, confirmLabel: "Delete", tone: "danger",
                              message: "They can no longer sign in on any kiosk. Their earlier sign-ins stay in the audit journal." })
                              && act(portal.removeTerminalUser(u.id), `${u.fullName} is deleted.`)}><IconTrash /> Delete</button>
                          </RowMenu>
                        </div>
                      </td>}
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
        <Pager data={paged.data} onPage={paged.setPage} onSize={paged.setSize} />
      </Card>

      <div className="roles">
        {ROLES.map((r) => (
          <div key={r} className="role-card"><b>{OPERATOR_ROLE[r]}</b>
            <span>{(roles.data?.[r] ?? []).map((f) => label(OPERATOR_FUNCTION, f)).join(" · ") || "…"}</span></div>
        ))}
      </div>

      <OperatorForm op={editing} onClose={() => setEditing(null)} onSaved={(r) => {
        setEditing(null); list.reload();
        if (r.pin) setPin({ user: r.user, pin: r.pin, reset: false }); else toast(`${r.user.fullName} is saved.`, "ok");
      }} />
      <PinModal shown={pin} onClose={() => setPin(null)} />
    </div>
  );
}

function OperatorForm({ op, onClose, onSaved }: {
  op: TerminalUser | "new" | null; onClose: () => void; onSaved: (r: { user: TerminalUser; pin?: string }) => void;
}) {
  const { portal } = usePortal();
  const isNew = op === "new";
  const branches = useLoad(() => portal.branches(), []);
  const kiosks = useLoad(() => portal.registry(), []);
  const blank = { username: "", fullName: "", role: "CUSTODIAN" as OperatorRole, phone: "", branchCode: "", allTerminals: false, terminals: [] as string[] };
  const [f, setF] = useState(blank);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  useEffect(() => {
    setError("");
    if (op && op !== "new") setF({ username: op.username, fullName: op.fullName, role: op.role, phone: op.phone ?? "", branchCode: op.branchCode ?? "",
      allTerminals: op.allTerminals, terminals: op.terminals });
    else if (op === "new") setF(blank);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [op]);
  const set = <K extends keyof typeof f>(k: K, v: (typeof f)[K]) => setF((x) => ({ ...x, [k]: v }));
  const choices = (kiosks.data ?? []).filter((k) => k.status !== "RETIRED" && (!f.branchCode || k.branchCode === f.branchCode));

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (!f.allTerminals && f.terminals.length === 0) { setError("Choose at least one kiosk, or allow all kiosks."); return; }
    setBusy(true); setError("");
    const d = { fullName: f.fullName.trim(), role: f.role, phone: f.phone.trim() || undefined, branchCode: f.branchCode || undefined,
      allTerminals: f.allTerminals, terminals: f.allTerminals ? [] : f.terminals };
    try {
      if (isNew) onSaved(await portal.createTerminalUser({ ...d, username: f.username.trim().toLowerCase() }));
      else if (op) onSaved({ user: await portal.updateTerminalUser(op.id, d) });
    } catch (err) { setError((err as Error).message); } finally { setBusy(false); }
  }

  return (
    <Modal open={op != null} busy={busy} onClose={onClose} icon={isNew ? <IconPlus /> : <IconEdit />} size="lg"
           title={isNew ? "Add a terminal user" : `Edit ${typeof op === "object" && op ? op.fullName : ""}`}
           footer={<>
             <Button kind="ghost" onClick={onClose} disabled={busy}>Cancel</Button>
             <Button type="submit" form="operator-form" disabled={busy}>{busy ? "Saving…" : isNew ? "Add and issue PIN" : "Save changes"}</Button>
           </>}>
      <form id="operator-form" className="form" onSubmit={submit}>
        <div className="form__row">
          <label><span>Full name</span><input className="input" value={f.fullName} onChange={(e) => set("fullName", e.target.value)} required maxLength={100} /></label>
          <label><span>Username</span><input className="input" value={f.username} onChange={(e) => set("username", e.target.value)} required disabled={!isNew}
                                             pattern="[a-zA-Z0-9._\-]{3,40}" title="3 to 40 letters, digits, dots, dashes or underscores" spellCheck={false} autoComplete="off" />
            {isNew && <small className="muted">Typed on the kiosk. It cannot be changed later.</small>}</label>
        </div>
        <div className="form__row">
          <label><span>Role</span>
            <select className="select" value={f.role} onChange={(e) => set("role", e.target.value as OperatorRole)}>
              {ROLES.map((r) => <option key={r} value={r}>{OPERATOR_ROLE[r]}</option>)}
            </select></label>
          <label><span>Phone <small className="muted">(optional)</small></span>
            <input className="input" value={f.phone} onChange={(e) => set("phone", e.target.value)} maxLength={20} placeholder="0712 345 678" /></label>
        </div>
        <label><span>Branch</span>
          <select className="select" value={f.branchCode} onChange={(e) => setF((x) => ({ ...x, branchCode: e.target.value, terminals: [] }))}>
            <option value="">Any branch (for example a CIT company or vendor engineer)</option>
            {(branches.data ?? []).filter((b) => b.status === "ACTIVE" || b.code === f.branchCode).map((b) => <option key={b.code} value={b.code}>{b.name} ({b.code})</option>)}
          </select></label>
        <label className="check"><input type="checkbox" checked={f.allTerminals} onChange={(e) => set("allTerminals", e.target.checked)} />
          May sign in on every kiosk {f.branchCode ? "in this branch" : ""}</label>
        {!f.allTerminals && (
          <fieldset className="pick">
            <legend>Kiosks they may sign in on</legend>
            {choices.length === 0 ? <p className="muted small">No kiosks {f.branchCode ? "in this branch" : ""} yet.</p>
              : choices.map((k) => (
                <label key={k.terminalCode} className="check">
                  <input type="checkbox" checked={f.terminals.includes(k.terminalCode)}
                         onChange={(e) => set("terminals", e.target.checked ? [...f.terminals, k.terminalCode] : f.terminals.filter((c) => c !== k.terminalCode))} />
                  <Mono>{k.terminalCode}</Mono> <span className="muted small">{k.name ?? ""} {k.branchName ? `· ${k.branchName}` : ""}</span>
                </label>
              ))}
          </fieldset>
        )}
        {error && <div className="error-note" role="alert">{error}</div>}
      </form>
    </Modal>
  );
}

function PinModal({ shown, onClose }: { shown: { user: TerminalUser; pin: string; reset: boolean } | null; onClose: () => void }) {
  const { toast } = usePortal();
  return (
    <Modal open={!!shown} tone="good" icon={<IconKey />} onClose={onClose}
           title={shown?.reset ? `New PIN for ${shown.user.fullName}` : `${shown?.user.fullName} is added`}
           footer={<Button onClick={onClose}>I have given them the PIN</Button>}>
      <p className="modal__text">Username <Mono>{shown?.user.username}</Mono>. Their PIN is below: it is shown only this once.</p>
      <div className="token-box token-box--pin"><Mono>{shown?.pin}</Mono>
        <Button size="sm" kind="secondary" icon={<IconCopy />} onClick={() => navigator.clipboard?.writeText(shown?.pin ?? "").then(() => toast("PIN copied.", "ok"))}>Copy</Button></div>
      <p className="modal__text muted small">Give it to the person directly. Five wrong PINs lock them out for 15 minutes; a new PIN unlocks them at once.</p>
    </Modal>
  );
}
