import { useEffect, useMemo, useState, type FormEvent, type ReactNode } from "react";
import { usePortal } from "../context";
import { can } from "../auth";
import type { TerminalCommand, TerminalCreated, TerminalRow, TerminalView } from "../api/portal";
import { Badge, Button, Card, Drawer, Empty, ErrorNote, fmt, Modal, Mono, Pager, RowMenu, Segmented, Spinner, useClientPage, useDebounced, useLoad } from "../ui/kit";
import { Meter } from "../ui/charts";
import {
  IconAlert, IconCheck, IconCopy, IconEdit, IconKey, IconLock, IconMapPin, IconMonitor, IconPause, IconPlus, IconPower, IconRefresh, IconSearch, IconTrash, IconUnlock,
} from "../ui/Icons";
import { label, MODULE_LABEL, SERVICE_LABEL } from "../ui/labels";

const LOW_STOCK = 50;   // matches stm.low-card-stock in the middleware
const STOCK_FULL = 500;

export function Fleet() {
  const { portal, toast, confirm, user } = usePortal();
  const admin = can.manageKiosks(user.role);
  const control = can.controlKiosks(user.role);
  const [q, setQ] = useState("");
  const dq = useDebounced(q.trim().toLowerCase(), 200);
  const [filter, setFilter] = useState<"all" | "online" | "offline" | "attention">("all");
  const [open, setOpen] = useState<string | null>(null);
  const tele = useLoad(() => portal.terminals(), [], 30_000);
  const reg = useLoad(() => portal.registry(), [], 30_000);
  const branches = useLoad(() => portal.reference().then((r) => r.branches), []);
  const [live, setLive] = useState(false);
  const [creating, setCreating] = useState(false);
  const [editing, setEditing] = useState<TerminalRow | null>(null);
  const [issued, setIssued] = useState<(TerminalCreated & { replaced: boolean }) | null>(null);
  const reloadAll = () => { tele.reload(); reg.reload(); branches.reload(); };

  // Live updates: each heartbeat from a kiosk is pushed here as it arrives.
  useEffect(() => {
    const ctl = new AbortController();
    let stopped = false;
    (async () => {
      while (!stopped) {
        try {
          await portal.telemetryStream((t) => {
            tele.setData((prev) => {
              const list = prev ? [...prev] : [];
              const i = list.findIndex((x) => x.terminalCode === t.terminalCode);
              if (i >= 0) list[i] = t; else list.push(t);
              return list;
            });
          }, ctl.signal, () => setLive(true));
        } catch { /* fall through to retry */ }
        setLive(false);
        if (!stopped) await new Promise((r) => setTimeout(r, 5000));
      }
    })();
    return () => { stopped = true; ctl.abort(); };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [portal]);

  const merged = useMemo(() => {
    const byCode = new Map((reg.data ?? []).map((r) => [r.terminalCode, r]));
    return (tele.data ?? []).map((t) => ({ t, r: byCode.get(t.terminalCode) }));
  }, [tele.data, reg.data]);
  const retired = useMemo(() => (reg.data ?? []).filter((r) => r.status === "RETIRED"), [reg.data]);
  const shown = useMemo(() => merged.filter(({ t, r }) => {
    if (filter === "online" && !t.online) return false;
    if (filter === "offline" && t.online) return false;
    if (filter === "attention" && !needsAttention(t, r)) return false;
    if (!dq) return true;
    return [t.terminalCode, t.branchCode, r?.branchName, r?.name, r?.location].some((s) => s?.toLowerCase().includes(dq));
  }), [merged, filter, dq]);
  const paged = useClientPage(shown, "fleet", [filter, dq]);

  async function command(code: string, cmd: TerminalCommand) {
    if (cmd === "REBOOT" && !await confirm({ title: `Reboot ${code}?`, confirmLabel: "Reboot kiosk", tone: "warn",
      message: "A customer using it now will lose their session." })) return;
    if (cmd === "LOCK" && !await confirm({ title: `Put ${code} out of service?`, confirmLabel: "Lock kiosk", tone: "warn",
      message: "Customers will see “Temporarily out of service” until you unlock it." })) return;
    try {
      const r = await portal.command(code, cmd);
      toast(r.pushed ? `${fmt.words(cmd)} sent to ${code}.` : `${fmt.words(cmd)} saved. ${code} applies it on its next status check.`, "ok");
      reg.reload();
    } catch (e) {
      toast((e as Error).message, "error");
    }
  }

  async function rotate(r: TerminalRow) {
    if (!await confirm({ title: `Replace the token of ${r.terminalCode}?`, confirmLabel: "Replace token", tone: "danger", message: <>
      <p>The current token stops working at once and the kiosk is signed out.</p>
      <p>The kiosk stays out of service until the new token is entered on it. You will see the new token once.</p></> })) return;
    try { setIssued({ ...await portal.rotateTerminalToken(r.terminalCode), replaced: true }); reloadAll(); }
    catch (e) { toast((e as Error).message, "error"); }
  }
  async function setStatus(r: TerminalRow, status: "ACTIVE" | "DISABLED") {
    if (status === "DISABLED" && !await confirm({ title: `Disable ${r.terminalCode}?`, confirmLabel: "Disable kiosk", tone: "warn", message: <>
      <p>The kiosk is signed out and cannot connect until you enable it again. Its token is kept.</p>
      <p>To only show “Temporarily out of service” for a while, use <b>Lock</b> instead.</p></> })) return;
    try {
      await portal.setTerminalStatus(r.terminalCode, status);
      toast(status === "ACTIVE" ? `${r.terminalCode} is enabled.` : `${r.terminalCode} is disabled.`, "ok");
      reloadAll();
    } catch (e) { toast((e as Error).message, "error"); }
  }
  async function remove(r: TerminalRow) {
    const used = r.sessionsTotal > 0;
    if (!await confirm({ title: `Remove ${r.terminalCode}?`, confirmLabel: "Remove kiosk", tone: "danger", message: used ? <>
      <p>The kiosk can no longer connect and leaves this page.</p>
      <p>It has served customers, so its sessions, cash records and audit entries are kept, and the ID <b>{r.terminalCode}</b> cannot be given to another machine. You can restore it later under Removed kiosks.</p></>
      : "It has never served a customer, so it is deleted completely and its ID becomes free again." })) return;
    try {
      const out = await portal.removeTerminal(r.terminalCode);
      toast(out.outcome === "DELETED" ? `${r.terminalCode} is deleted.` : `${r.terminalCode} is removed. Its history is kept.`, "ok");
      reloadAll();
    } catch (e) { toast((e as Error).message, "error"); }
  }

  if (tele.error && !tele.data) return <ErrorNote error={tele.error} onRetry={tele.reload} />;
  if (!tele.data) return <Spinner />;

  const online = merged.filter((m) => m.t.online).length;
  const alarms = merged.reduce((n, m) => n + m.t.alarms.length, 0);
  const openRow = merged.find((m) => m.t.terminalCode === open);

  const adminMenu = (r: TerminalRow) => (<>
    <button type="button" onClick={() => setEditing(r)}><IconEdit /> Edit details</button>
    <button type="button" onClick={() => rotate(r)}><IconKey /> Replace token</button>
    {r.status === "ACTIVE"
      ? <button type="button" onClick={() => setStatus(r, "DISABLED")}><IconPause /> Disable</button>
      : <button type="button" onClick={() => setStatus(r, "ACTIVE")}><IconCheck /> Enable</button>}
    <button type="button" className="is-danger" onClick={() => remove(r)}><IconTrash /> Remove</button>
  </>);

  return (
    <div className="stack">
      <div className="fleet-summary">
        <div><b>{online}</b><span>online</span></div>
        <div><b>{merged.length - online}</b><span>offline</span></div>
        <div><b>{merged.filter((m) => m.t.serviceMode === "LOCKED").length}</b><span>locked</span></div>
        <div><b>{alarms}</b><span>active alarms</span></div>
        <span className={`live ${live ? "is-on" : ""}`}><span className="live__dot" />{live ? "Live updates" : "Polling every 30 s"}</span>
        {admin && <Button icon={<IconPlus />} onClick={() => setCreating(true)}>Add kiosk</Button>}
      </div>

      <Card pad={false} title="Kiosks" subtitle="Click a kiosk for its modules, services and cash"
            actions={<div className="toolbar toolbar--wrap">
              <label className="search"><IconSearch /><input value={q} onChange={(e) => setQ(e.target.value)} placeholder="Kiosk, branch, location…" /></label>
              <Segmented value={filter} onChange={setFilter} options={[
                { value: "all", label: "All", count: merged.length },
                { value: "online", label: "Online", count: online },
                { value: "offline", label: "Offline", count: merged.length - online },
                { value: "attention", label: "Needs attention", count: merged.filter((m) => needsAttention(m.t, m.r)).length },
              ]} />
            </div>}>
        {merged.length === 0 ? (
          <Empty icon={<IconMonitor />} title="No kiosks yet">{admin ? "Add the first kiosk with the button above. You get its terminal token to enter on the machine." : "An administrator adds kiosks here."}</Empty>
        ) : shown.length === 0 ? <Empty icon={<IconMonitor />} title="No kiosks match" /> : (
          <div className="table-wrap">
            <table className="table">
              <thead><tr><th>Kiosk</th><th>Connection</th><th>Service</th><th>Health</th><th className="num">Blank cards</th><th>Cash held</th><th className="num">Sessions today</th><th /></tr></thead>
              <tbody>
                {paged.rows.map(({ t, r }) => {
                  const locked = t.serviceMode === "LOCKED";
                  const disabled = r != null && r.status !== "ACTIVE";
                  const servicesOff = Object.keys(SERVICE_LABEL).filter((s) => t.disabledServices.includes(s)).length;
                  const cash = cashTotals(t.cassetteCounts);
                  return (
                    <tr key={t.terminalCode} className={`clickable ${disabled || (t.online && t.alarms.length) ? "is-red" : ""}`} onClick={() => setOpen(t.terminalCode)}>
                      <td><div className="cell-main"><span><b><Mono>{t.terminalCode}</Mono></b>
                        <small>{r?.name ? `${r.name} · ` : ""}{r?.branchName ?? t.branchCode}{r?.location ? ` · ${r.location}` : ""}</small></span></div></td>
                      <td>
                        <span className={`conn ${t.online ? "is-on" : ""}`}><span className="dot" />{t.online ? "Online" : "Offline"}</span>
                        <small className="muted"><br />{t.lastSeen ? `heard ${fmt.ago(t.lastSeen)}` : "never connected"}</small>
                      </td>
                      <td>
                        {disabled ? <Badge tone="bad">Disabled</Badge> : <Badge tone={locked ? "bad" : "good"}>{locked ? "Locked" : "In service"}</Badge>}
                        {!disabled && !locked && servicesOff > 0 && <small className="muted"><br />{fmt.plural(servicesOff, "service")} off</small>}
                      </td>
                      <td>
                        {!t.healthReportedAt ? <Badge tone="warn">Not reported</Badge>
                          : t.alarms.length ? <Badge tone="warn">{fmt.plural(t.alarms.length, "alarm")}</Badge>
                          : <Badge tone="good">All OK</Badge>}
                        {t.healthReportedAt && <small className="muted"><br />report {fmt.ago(t.healthReportedAt)}</small>}
                      </td>
                      <td className={`num ${t.cardStock != null && t.cardStock < LOW_STOCK ? "dir-dr" : ""}`}>{t.cardStock ?? "—"}</td>
                      <td className="small nowrap">{cash.length ? cash.map((c) => <div key={c.currency}>{fmt.money(c.value, c.currency)}</div>) : <span className="muted">—</span>}</td>
                      <td className="num">{r?.sessionsToday ?? "—"}<small className="muted"><br />{r ? `${fmt.num(r.sessionsTotal)} in all` : ""}</small></td>
                      <td onClick={(e) => e.stopPropagation()}>
                        <div className="row-actions">
                          {control && !disabled && (locked
                            ? <Button size="sm" kind="success" icon={<IconUnlock />} onClick={() => command(t.terminalCode, "UNLOCK")}>Unlock</Button>
                            : <Button size="sm" kind="secondary" icon={<IconLock />} onClick={() => command(t.terminalCode, "LOCK")}>Lock</Button>)}
                          {(control || (admin && r)) && (
                            <RowMenu label="More…">
                              {control && <button type="button" disabled={!t.commandChannel} onClick={() => command(t.terminalCode, "REFRESH_CONFIG")}
                                                  title={t.commandChannel ? "" : "Needs the kiosk’s remote-command connection"}><IconRefresh /> Refresh settings</button>}
                              {control && <button type="button" disabled={!t.commandChannel} onClick={() => command(t.terminalCode, "REBOOT")}
                                                  title={t.commandChannel ? "" : "Needs the kiosk’s remote-command connection"}><IconPower /> Reboot</button>}
                              {admin && r && adminMenu(r)}
                            </RowMenu>
                          )}
                        </div>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
        <Pager data={paged.data} onPage={paged.setPage} onSize={paged.setSize} />
      </Card>

      <Drawer open={!!openRow} onClose={() => setOpen(null)} title={openRow?.t.terminalCode ?? ""}
              subtitle={openRow ? <span className="muted">{openRow.r?.name ? `${openRow.r.name} · ` : ""}{openRow.r?.branchName ?? openRow.t.branchCode}</span> : null}>
        {openRow && <KioskCard t={openRow.t} r={openRow.r} onCommand={command}
                               menu={admin && openRow.r ? <RowMenu label="Manage">{adminMenu(openRow.r)}</RowMenu> : null} />}
      </Drawer>

      {admin && retired.length > 0 && (
        <Card pad={false} title="Removed kiosks" subtitle="They cannot connect. Their sessions, cash records and audit entries are kept.">
          <div className="table-wrap">
            <table className="table table--dense">
              <thead><tr><th>Kiosk</th><th>Branch</th><th className="num">Sessions</th><th>Removed</th><th /></tr></thead>
              <tbody>
                {retired.map((r) => (
                  <tr key={r.terminalCode}>
                    <td><Mono>{r.terminalCode}</Mono>{r.name && <small className="muted"><br />{r.name}</small>}</td>
                    <td>{r.branchName ?? r.branchCode}</td>
                    <td className="num">{fmt.num(r.sessionsTotal)}</td>
                    <td className="muted">{fmt.dateTime(r.retiredAt)}</td>
                    <td><Button size="sm" kind="secondary" onClick={async () => await confirm({ title: `Restore ${r.terminalCode}?`, confirmLabel: "Restore kiosk",
                      message: "It appears on this page again and can connect with its existing token. Replace the token afterwards if the machine changed." })
                      && setStatus(r, "ACTIVE")}>Restore</Button></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Card>
      )}

      <KioskForm open={creating} branches={branches.data ?? []} onClose={() => setCreating(false)}
                 onSaved={(c) => { setCreating(false); if (c) setIssued({ ...c, replaced: false }); reloadAll(); }} />
      <KioskForm open={!!editing} kiosk={editing ?? undefined} branches={branches.data ?? []} onClose={() => setEditing(null)}
                 onSaved={() => { toast(`${editing?.terminalCode} is updated.`, "ok"); setEditing(null); reloadAll(); }} />
      <TokenModal issued={issued} onClose={() => setIssued(null)} />
    </div>
  );
}

/** Offline, locked, disabled, alarms, low card stock, or never sent a health report. */
function needsAttention(t: TerminalView, r?: TerminalRow) {
  return !t.online || t.serviceMode === "LOCKED" || (r != null && r.status !== "ACTIVE") || t.alarms.length > 0
    || !t.healthReportedAt || (t.cardStock != null && t.cardStock < LOW_STOCK);
}

/** "KES 1000": 800 notes … → value held per currency. */
function cashTotals(cassettes: Record<string, number> | null | undefined) {
  const m = new Map<string, number>();
  for (const [k, n] of Object.entries(cassettes ?? {})) {
    const [cur, denom] = k.split(" ");
    m.set(cur, (m.get(cur) ?? 0) + Number(denom) * n);
  }
  return [...m].filter(([, v]) => v > 0).map(([currency, value]) => ({ currency, value }));
}

const NEW_BRANCH = "__new__";

/** Add a kiosk, or edit one (the ID cannot change: sessions, cash and the audit journal refer to it). */
function KioskForm({ open, kiosk, branches, onClose, onSaved }: {
  open: boolean; kiosk?: TerminalRow; branches: { code: string; name: string }[];
  onClose: () => void; onSaved: (created?: TerminalCreated) => void;
}) {
  const { portal } = usePortal();
  const blank = { code: "", branch: "", newCode: "", newName: "", name: "", location: "", notes: "" };
  const [f, setF] = useState(blank);
  const [key, setKey] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const want = open ? (kiosk?.terminalCode ?? "") : null;
  if (want !== key) {     // (re)fill the form each time it opens
    setKey(want);
    setError("");
    if (want !== null) {
      setF(kiosk ? { ...blank, code: kiosk.terminalCode, branch: kiosk.branchCode, name: kiosk.name ?? "", location: kiosk.location ?? "", notes: kiosk.notes ?? "" }
                 : { ...blank, branch: branches[0]?.code ?? NEW_BRANCH });
    }
  }
  const set = <K extends keyof typeof f>(k: K, v: string) => setF((x) => ({ ...x, [k]: v }));
  const isNewBranch = f.branch === NEW_BRANCH;
  // An edited kiosk may sit in a branch that is not in the list any more: keep it selectable.
  const options = kiosk && !branches.some((b) => b.code === kiosk.branchCode) ? [{ code: kiosk.branchCode, name: kiosk.branchName ?? kiosk.branchCode }, ...branches] : branches;

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true); setError("");
    const details = {
      branchCode: isNewBranch ? f.newCode.trim() : f.branch, branchName: isNewBranch ? f.newName.trim() : undefined,
      name: f.name.trim(), location: f.location.trim(), notes: f.notes.trim(),
    };
    try {
      if (kiosk) { await portal.updateTerminal(kiosk.terminalCode, details); onSaved(); }
      else onSaved(await portal.createTerminal(f.code.trim(), details));
    } catch (err) { setError((err as Error).message); } finally { setBusy(false); }
  }

  return (
    <Modal open={open} busy={busy} onClose={onClose} icon={kiosk ? <IconEdit /> : <IconMonitor />}
           title={kiosk ? `Edit ${kiosk.terminalCode}` : "Add a kiosk"}
           footer={<>
             <Button kind="ghost" onClick={onClose} disabled={busy}>Cancel</Button>
             <Button type="submit" form="kiosk-form" disabled={busy}>{busy ? "Saving…" : kiosk ? "Save changes" : "Add kiosk"}</Button>
           </>}>
      <form id="kiosk-form" className="form" onSubmit={submit}>
        {!kiosk && (
          <label><span>Kiosk ID</span>
            <input className="input" value={f.code} onChange={(e) => set("code", e.target.value.toUpperCase())} required minLength={3} maxLength={50}
                   pattern="[A-Za-z0-9][A-Za-z0-9\-]{2,49}" placeholder="STM-NBO-MOI-04" spellCheck={false} autoComplete="off" />
            <small className="muted">Letters, digits and hyphens. The machine uses this as its device ID, and it cannot be changed later.</small></label>
        )}
        <label><span>Branch</span>
          <select className="select" value={f.branch} onChange={(e) => set("branch", e.target.value)} required>
            {options.map((b) => <option key={b.code} value={b.code}>{b.name} ({b.code})</option>)}
            <option value={NEW_BRANCH}>Add a new branch…</option>
          </select></label>
        {isNewBranch && (
          <div className="form__row">
            <label><span>New branch code</span><input className="input" value={f.newCode} onChange={(e) => set("newCode", e.target.value.toUpperCase())}
                                                      required maxLength={20} pattern="[A-Za-z0-9][A-Za-z0-9\-]{0,19}" placeholder="045" spellCheck={false} /></label>
            <label><span>New branch name</span><input className="input" value={f.newName} onChange={(e) => set("newName", e.target.value)}
                                                      required maxLength={100} placeholder="Kisumu" /></label>
          </div>
        )}
        <div className="form__row">
          <label><span>Name <small className="muted">(optional)</small></span>
            <input className="input" value={f.name} onChange={(e) => set("name", e.target.value)} maxLength={80} placeholder="Banking hall kiosk" /></label>
          <label><span>Where it stands <small className="muted">(optional)</small></span>
            <input className="input" value={f.location} onChange={(e) => set("location", e.target.value)} maxLength={160} placeholder="Ground floor, by the entrance" /></label>
        </div>
        <label><span>Notes <small className="muted">(optional)</small></span>
          <textarea className="input" rows={2} value={f.notes} onChange={(e) => set("notes", e.target.value)} maxLength={500} /></label>
        {kiosk && kiosk.sessionsTotal > 0 && f.branch !== kiosk.branchCode && (
          <p className="muted small">Moving the kiosk changes the branch on new sessions and accounts. Earlier records keep the old branch.</p>
        )}
        {error && <div className="error-note" role="alert">{error}</div>}
      </form>
    </Modal>
  );
}

/** Shows a terminal token once, after creating a kiosk or replacing its token. */
function TokenModal({ issued, onClose }: { issued: (TerminalCreated & { replaced: boolean }) | null; onClose: () => void }) {
  const { toast } = usePortal();
  const [copied, setCopied] = useState(false);
  useEffect(() => setCopied(false), [issued]);
  function copy() {
    navigator.clipboard?.writeText(issued?.token ?? "").then(() => { setCopied(true); toast("Token copied.", "ok"); }, () => toast("Could not copy. Select the token and copy it by hand.", "error"));
  }
  return (
    <Modal open={!!issued} tone="good" icon={<IconKey />} onClose={onClose}
           title={issued?.replaced ? `New token for ${issued.terminalCode}` : `${issued?.terminalCode} is added`}
           footer={<Button onClick={onClose}>{copied ? "Done" : "I have saved the token"}</Button>}>
      <p className="modal__text">This is the kiosk’s <b>terminal token</b>. Copy it now: it is shown only this once and is not stored where anyone can read it.</p>
      <div className="token-box"><Mono>{issued?.token}</Mono>
        <Button size="sm" kind="secondary" icon={<IconCopy />} onClick={copy}>{copied ? "Copied" : "Copy"}</Button></div>
      <div className="modal__text">
        <p>Enter it on the machine (the kiosk app’s terminal token setting) together with the device ID <Mono>{issued?.terminalCode}</Mono>. Send it to the kiosk developer privately, never in a group chat.</p>
        <p>If it is lost or seen by the wrong person, use <b>Manage → Replace token</b>.</p>
      </div>
    </Modal>
  );
}

function KioskCard({ t, r, onCommand, menu }: {
  t: TerminalView; r?: TerminalRow; onCommand: (code: string, c: TerminalCommand) => void; menu?: ReactNode;
}) {
  const { user } = usePortal();
  const control = can.controlKiosks(user.role);
  const locked = t.serviceMode === "LOCKED";
  const disabled = r != null && r.status !== "ACTIVE";
  const stock = t.cardStock;
  const stockTone = stock == null ? "warn" : stock < LOW_STOCK ? "bad" : stock < LOW_STOCK * 2 ? "warn" : "good";
  const cassettes = Object.entries(t.cassetteCounts ?? {});
  const modules = Object.entries(t.modules ?? {}).map(([k, v]) => ({ k, status: typeof v === "string" ? v : v.status }));
  const services = Object.keys(SERVICE_LABEL);

  return (
    <article className={`kiosk ${t.online ? "" : "is-offline"} ${locked ? "is-locked" : ""} ${disabled ? "is-disabled" : ""}`}>
      <header className="kiosk__head">
        <span className="kiosk__icon"><IconMonitor /></span>
        <div>
          <h3>{t.terminalCode}</h3>
          <small>{r?.name ? `${r.name} · ` : ""}{r?.branchName ?? t.branchCode}</small>
        </div>
        <span className={`kiosk__state ${t.online ? "on" : "off"}`}><span className="dot" />{t.online ? "Online" : "Offline"}</span>
        {menu}
      </header>
      {r?.location && <div className="kiosk__place"><IconMapPin /> {r.location}</div>}

      <div className="badges">
        {t.status && <Badge value={t.status} />}
        {disabled ? <Badge tone="bad">Disabled</Badge>
          : <Badge value={t.serviceMode} tone={locked ? "bad" : "good"}>{locked ? "Locked" : "In service"}</Badge>}
        {t.softwareVersion && <Mono>v{t.softwareVersion}</Mono>}
        <span className="muted small">{t.lastSeen ? `heard from ${fmt.ago(t.lastSeen)}` : "never connected"}</span>
      </div>
      {!t.healthReportedAt && (
        <div className="callout callout--warn"><IconAlert /><div><b>No health report yet</b>
          <span>The kiosk app has not sent its health report (D-6, <Mono>POST /stm/v1/devices/{t.terminalCode}/health</Mono>, every 60 s), so its modules, card stock and alarms are unknown here.
            {t.lastContactAt ? " It is connecting, so the report is the only thing missing." : ""}</span></div></div>
      )}

      {modules.length > 0 && (
        <div className="modules">
          {modules.map((m) => (
            <span key={m.k} className={`module is-${m.status === "OK" ? "ok" : /OUT|FAULT|JAM|OFFLINE|ERROR/.test(m.status) ? "bad" : m.status ? "warn" : "unknown"}`}
                  title={`${label(MODULE_LABEL, m.k)}: ${fmt.words(m.status)}`}>
              <span className="dot" /><span>{label(MODULE_LABEL, m.k)}{m.status !== "OK" ? ` · ${fmt.words(m.status)}` : ""}</span>
            </span>
          ))}
        </div>
      )}

      <div className="services" aria-label="Services offered on the home screen">
        {services.map((sv) => {
          const off = locked || disabled || t.disabledServices.includes(sv);
          return <Badge key={sv} tone={off ? "neutral" : "good"}>{off ? <s>{SERVICE_LABEL[sv]}</s> : SERVICE_LABEL[sv]}</Badge>;
        })}
      </div>

      <div className="kiosk__metric">
        <span>Blank cards</span>
        <Meter value={stock ?? 0} max={STOCK_FULL} tone={stockTone} />
        <b>{stock ?? "—"}</b>
      </div>
      {stock != null && stock < LOW_STOCK && <p className="kiosk__warn">Low card stock: refill soon.</p>}

      {cassettes.length > 0 && (
        <div className="cassettes">
          {cassettes.map(([denom, n]) => (
            <div key={denom}><small>{denom}</small><b>{fmt.num(n)}</b></div>
          ))}
        </div>
      )}

      <div className="kiosk__alarms">
        {t.alarms.length === 0 ? <span className="muted small">No alarms</span>
          : t.alarms.map((a) => { const [m, st] = a.split(":"); return <Badge key={a} tone="warn">{st ? `${label(MODULE_LABEL, m)}: ${fmt.words(st).toLowerCase()}` : fmt.words(a)}</Badge>; })}
      </div>

      <div className="kiosk__stats">
        <div><small>Sessions today</small><b>{r?.sessionsToday ?? "—"}</b></div>
        <div><small>All time</small><b>{r?.sessionsTotal ?? "—"}</b></div>
        <div><small>Registered</small><b>{r ? fmt.date(r.createdAt) : "—"}</b></div>
      </div>
      {r?.tokenRotatedAt && <p className="kiosk__notes">Token replaced {fmt.ago(r.tokenRotatedAt)}</p>}
      {r?.notes && <p className="kiosk__notes">{r.notes}</p>}

      <footer className="kiosk__actions">
        {control && !disabled && (locked
          ? <Button size="sm" kind="success" icon={<IconUnlock />} onClick={() => onCommand(t.terminalCode, "UNLOCK")}>Unlock</Button>
          : <Button size="sm" kind="secondary" icon={<IconLock />} onClick={() => onCommand(t.terminalCode, "LOCK")}>Lock</Button>)}
        {control && <Button size="sm" kind="ghost" icon={<IconRefresh />} disabled={!t.commandChannel} onClick={() => onCommand(t.terminalCode, "REFRESH_CONFIG")}
                title={t.commandChannel ? "Reload configuration" : "Needs the kiosk’s remote-command connection"}>Refresh</Button>}
        {control && <Button size="sm" kind="ghost" icon={<IconPower />} disabled={!t.commandChannel} onClick={() => onCommand(t.terminalCode, "REBOOT")}
                title={t.commandChannel ? "Restart the kiosk" : "Needs the kiosk’s remote-command connection"}>Reboot</Button>}
        <Mono>{t.branchCode}</Mono>
      </footer>
    </article>
  );
}
