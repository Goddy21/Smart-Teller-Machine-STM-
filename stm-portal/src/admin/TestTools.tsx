import { type FormEvent, type ReactNode, useEffect, useState } from "react";
import { usePortal } from "../context";
import type { TestCustomer, TestDeleteResult, TestToolsStatus } from "../api/portal";
import { Button, Modal, Mono, RowMenu, Segmented, Spinner, fmt } from "../ui/kit";
import { IconCard, IconCash, IconEdit, IconLock, IconTrash } from "../ui/Icons";

/**
 * Administrators' test-data tools: edit and delete test customers, accounts, cards and sessions.
 * Shown only to ADMIN users, and only while the server runs the simulated bank (the middleware answers
 * /test-data/status with enabled: true). Every delete first runs as a dry run, and the confirmation pop-up lists
 * exactly what will go; every edit asks for confirmation too. All of it is written to the audit journal.
 */

/** The tools' status, shared by every page; the Simulator page's switch updates it everywhere at once. */
let state: { user: number; status: TestToolsStatus | null; loading: Promise<void> | null } = { user: -1, status: null, loading: null };
const listeners = new Set<() => void>();
const notify = () => listeners.forEach((l) => l());

function useToolsStatus(): TestToolsStatus | null {
  const { user, portal } = usePortal();
  const [, tick] = useState(0);
  useEffect(() => {
    const l = () => tick((n) => n + 1);
    listeners.add(l);
    if (user.role === "ADMIN" && (state.user !== user.id || (!state.status && !state.loading))) {
      state = { user: user.id, status: null, loading: null };
      state.loading = portal.testDataStatus()
        .then((s) => { state.status = s; })
        .catch(() => { state.status = { enabled: false, allowed: false, switchedOn: false, builtInCustomers: [] }; })
        .finally(() => { state.loading = null; notify(); });
    }
    return () => { listeners.delete(l); };
  }, [user.id, user.role, portal]);
  return user.role === "ADMIN" && state.user === user.id ? state.status : null;
}

/** True when the signed-in user may use the test-data tools right now (administrator, allowed here, switched on). */
export function useTestTools(): boolean {
  return useToolsStatus()?.enabled ?? false;
}

/** The administrators' on/off switch for the tools (Simulator page). Hidden for everyone else. */
export function TestToolsSwitch() {
  const { portal, confirm, toast } = usePortal();
  const s = useToolsStatus();
  const [busy, setBusy] = useState(false);
  if (!s || !s.allowed) return null;
  async function flip(on: boolean) {
    const yes = await confirm(on
      ? { title: "Switch on the test-data tools", confirmLabel: "Switch on", tone: "warn",
          message: <p>Administrators will see <b>Manage</b> and <b>⋯</b> options to edit and delete customers, accounts, cards and sessions
            on the Simulator, Applications and Customer sessions pages.</p> }
      : { title: "Switch off the test-data tools", confirmLabel: "Switch off",
          message: <p>The edit and delete options disappear for every administrator, and the server refuses those changes until the
            tools are switched on again. Records are not affected.</p> });
    if (!yes) return;
    setBusy(true);
    try {
      state.status = await portal.setTestDataTools(on);
      notify();
      toast(on ? "Test-data tools switched on." : "Test-data tools switched off: the edit and delete options are hidden.", "ok");
    } catch (e) { toast((e as Error).message, "error"); } finally { setBusy(false); }
  }
  return (
    <label className="switch"><input type="checkbox" checked={s.switchedOn} disabled={busy} onChange={(e) => void flip(e.target.checked)} />
      Administrator test-data tools (edit and delete records)</label>
  );
}

export const BUILT_IN = new Set(["CIF00204417", "CIF00391144", "CIF00482913", "CIF00900001"]);

const LABEL: Record<string, string> = {
  customer: "Customer record (with the bank's fingerprint, face photo and signature)",
  accounts: "Accounts",
  cards: "Cards",
  sessions: "Customer sessions",
  applications: "Applications",
  images: "Images (ID scans, photos, fingerprints, signatures)",
  "text messages": "Text messages",
  "card orders": "Card orders",
  "card orders cancelled": "Card orders marked cancelled",
  "card sign-ins": "Card sign-ins",
  "teller calls": "Teller calls",
  "cash deposits": "Cash deposits",
  "cash withdrawals": "Cash withdrawals",
  "forex exchanges": "Forex exchanges",
  statements: "Statements",
};

function Summary({ s }: { s: Record<string, string | number> }) {
  const rows = Object.entries(s).filter(([k, v]) => LABEL[k] && typeof v === "number" && v > 0);
  return (
    <div className="stack stack--sm">
      {rows.length > 0 ? (
        <table className="table table--dense">
          <tbody>{rows.map(([k, v]) => <tr key={k}><td>{LABEL[k]}</td><td className="num"><b>{fmt.num(Number(v))}</b></td></tr>)}</tbody>
        </table>
      ) : <p className="muted">Nothing else is linked to it.</p>}
      {s.customerDeleted && <p><b>This is the customer's last account, so the customer is deleted too.</b></p>}
      {s.customerKept && <p className="muted">The customer stays: they have {fmt.plural(Number(s.otherAccounts ?? 0), "other account")}.</p>}
      {s.warning && <p className="error-note">{String(s.warning)}</p>}
      {s.note && <p className="muted">{String(s.note)}</p>}
      <p className="muted small">This cannot be undone. The audit journal keeps a record of who did it and what was removed.</p>
    </div>
  );
}

/** Dry run, confirmation pop-up with what will go, then the real delete. */
export function useTestDelete() {
  const { confirm, toast } = usePortal();
  return async function remove(o: { title: string; confirmLabel: string; intro: ReactNode; done: string;
                                    run: (dryRun: boolean) => Promise<TestDeleteResult>; after?: () => void }) {
    let preview: TestDeleteResult;
    try { preview = await o.run(true); } catch (e) { toast((e as Error).message, "error"); return; }
    const yes = await confirm({ title: o.title, tone: "danger", confirmLabel: o.confirmLabel,
      message: <div className="stack stack--sm">{o.intro}<Summary s={preview.summary} /></div> });
    if (!yes) return;
    try { await o.run(false); toast(o.done, "ok"); o.after?.(); } catch (e) { toast((e as Error).message, "error"); }
  };
}

// ---------------------------------------------------------------------------------------------- menus

export interface ToolCustomer { id: string; fullName: string }
export interface ToolAccount { id: string; number: string; available?: number; currency?: string; status?: string }
export interface ToolCard { id: string; maskedPan: string; status?: string }

type Open = { kind: "edit"; customer: ToolCustomer } | { kind: "money"; account: ToolAccount }
  | { kind: "acctStatus"; account: ToolAccount } | { kind: "cardStatus"; card: ToolCard } | null;

/** One place that renders the edit pop-ups opened from any menu. */
export function useToolModals(onChanged: () => void) {
  const [open, setOpen] = useState<Open>(null);
  const close = () => setOpen(null);
  const done = () => { setOpen(null); onChanged(); };
  const modals = open === null ? null
    : open.kind === "edit" ? <EditCustomerModal customer={open.customer} onClose={close} onDone={done} />
    : open.kind === "money" ? <BalanceModal account={open.account} onClose={close} onDone={done} />
    : open.kind === "acctStatus" ? <AccountStatusModal account={open.account} onClose={close} onDone={done} />
    : <CardStatusModal card={open.card} onClose={close} onDone={done} />;
  return { open: setOpen, modals };
}

export function CustomerMenu({ customer, open, onChanged, label = "Manage" }: {
  customer: ToolCustomer; open: (o: Open) => void; onChanged: () => void; label?: string;
}) {
  const { portal } = usePortal();
  const remove = useTestDelete();
  const builtIn = BUILT_IN.has(customer.id);
  return (
    <RowMenu label={label}>
      <button type="button" onClick={() => open({ kind: "edit", customer })}><IconEdit /> Edit customer details</button>
      <button type="button" onClick={() => remove({
        title: `Remove all cards of ${customer.fullName}`, confirmLabel: "Remove cards", done: "Cards removed.", after: onChanged,
        intro: <p>Every card of <b>{customer.fullName}</b> (<Mono>{customer.id}</Mono>) is removed. Their accounts stay and can be given a new card.</p>,
        run: (dry) => portal.testDeleteCards("all", customer.id, dry) })}><IconCard /> Remove all cards</button>
      <button type="button" className="is-danger" disabled={builtIn} title={builtIn ? "Built-in test customers cannot be deleted" : ""}
              onClick={() => remove({
                title: `Delete ${customer.fullName}`, confirmLabel: "Delete customer", done: "Customer deleted.", after: onChanged,
                intro: <p>The customer <b>{customer.fullName}</b> (<Mono>{customer.id}</Mono>) is deleted with all their accounts,
                  cards, applications and kiosk sessions.</p>,
                run: (dry) => portal.testDeleteCustomer(customer.id, dry) })}><IconTrash /> Delete customer</button>
    </RowMenu>
  );
}

export function AccountMenu({ account, customerId, open, onChanged, label = "⋯" }: {
  account: ToolAccount; customerId: string; open: (o: Open) => void; onChanged: () => void; label?: string;
}) {
  const { portal } = usePortal();
  const remove = useTestDelete();
  const builtIn = BUILT_IN.has(customerId);
  return (
    <RowMenu label={label}>
      <button type="button" onClick={() => open({ kind: "money", account })}><IconCash /> Add or take out money</button>
      <button type="button" onClick={() => open({ kind: "acctStatus", account })}><IconLock /> Change account status</button>
      <button type="button" className="is-danger" disabled={builtIn} title={builtIn ? "Built-in test customers' accounts cannot be deleted" : ""}
              onClick={() => remove({
                title: `Delete account ${account.number}`, confirmLabel: "Delete account", done: "Account deleted.", after: onChanged,
                intro: <p>Account <Mono>{account.number}</Mono> is deleted with its cards, its application and the kiosk sessions
                  where it was opened or used for a card.</p>,
                run: (dry) => portal.testDeleteAccount(account.id, dry) })}><IconTrash /> Delete account</button>
    </RowMenu>
  );
}

export function CardMenu({ card, open, onChanged, label = "⋯" }: {
  card: ToolCard; open: (o: Open) => void; onChanged: () => void; label?: string;
}) {
  const { portal } = usePortal();
  const remove = useTestDelete();
  return (
    <RowMenu label={label}>
      <button type="button" onClick={() => open({ kind: "cardStatus", card })}><IconLock /> Change card status</button>
      <button type="button" className="is-danger" onClick={() => remove({
        title: `Remove card ${card.maskedPan}`, confirmLabel: "Remove card", done: "Card removed.", after: onChanged,
        intro: <p>Card <Mono>{card.maskedPan}</Mono> is removed. The account stays and can be given a new card.</p>,
        run: (dry) => portal.testDeleteCards(card.id, null, dry) })}><IconTrash /> Remove card</button>
    </RowMenu>
  );
}

/** "Delete session" for the session drawer. */
export function DeleteSessionButton({ sessionId, onDeleted }: { sessionId: string; onDeleted: () => void }) {
  const { portal } = usePortal();
  const remove = useTestDelete();
  return (
    <Button kind="danger" size="sm" icon={<IconTrash />} onClick={() => remove({
      title: "Delete this session", confirmLabel: "Delete session", done: "Session deleted.", after: onDeleted,
      intro: <p>Session <Mono>{sessionId}</Mono> is deleted with everything done in it.</p>,
      run: (dry) => portal.testDeleteSession(sessionId, dry) })}>Delete session</Button>
  );
}

// ---------------------------------------------------------------------------------------------- edit pop-ups

function useSave(onDone: () => void) {
  const { confirm, toast } = usePortal();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  async function save(question: { title: string; message: ReactNode; confirmLabel: string }, action: () => Promise<string>) {
    setError(null);
    if (!(await confirm({ ...question, tone: "warn" }))) return;
    setBusy(true);
    try { toast(await action(), "ok"); onDone(); } catch (e) { setError((e as Error).message); } finally { setBusy(false); }
  }
  return { busy, error, save };
}

function Foot({ busy, onClose, form, label }: { busy: boolean; onClose: () => void; form: string; label: string }) {
  return <>
    <Button kind="ghost" onClick={onClose} disabled={busy}>Cancel</Button>
    <Button type="submit" form={form} disabled={busy}>{busy ? "Saving…" : label}</Button>
  </>;
}

function BalanceModal({ account, onClose, onDone }: { account: ToolAccount; onClose: () => void; onDone: () => void }) {
  const { portal } = usePortal();
  const { busy, error, save } = useSave(onDone);
  const [dir, setDir] = useState<"CREDIT" | "DEBIT">("CREDIT");
  const [amount, setAmount] = useState("10000");
  const [narrative, setNarrative] = useState("Test funding");
  const cur = account.currency ?? "KES";
  function submit(e: FormEvent) {
    e.preventDefault();
    const n = Number(amount);
    void save({ title: dir === "CREDIT" ? "Add money" : "Take money out", confirmLabel: dir === "CREDIT" ? "Add money" : "Take out",
                message: <p>{dir === "CREDIT" ? "Add" : "Take"} <b>{fmt.money(n, cur)}</b> {dir === "CREDIT" ? "to" : "from"} account <Mono>{account.number}</Mono>?
                  It is posted against the test-funding ledger, like any other posting.</p> },
      async () => {
        const r = await portal.testAdjustBalance(account.id, dir, n, narrative);
        return `Done. New balance ${fmt.money(r.balanceAfter, r.currency)}.`;
      });
  }
  return (
    <Modal open onClose={onClose} busy={busy} icon={<IconCash />} title={<>Money on <Mono>{account.number}</Mono></>}
           footer={<Foot busy={busy} onClose={onClose} form="tt-money" label={dir === "CREDIT" ? "Add money" : "Take out"} />}>
      <form id="tt-money" className="form" onSubmit={submit}>
        {account.available != null && <p className="muted">Available now: <b>{fmt.money(account.available, cur)}</b></p>}
        <Segmented value={dir} onChange={setDir} options={[{ value: "CREDIT", label: "Add money" }, { value: "DEBIT", label: "Take money out" }]} />
        <div className="form__row">
          <label><span>Amount ({cur})</span>
            <input className="input" type="number" min="0.01" max="10000000" step="0.01" required value={amount} onChange={(e) => setAmount(e.target.value)} /></label>
          <label><span>Narrative</span>
            <input className="input" maxLength={80} value={narrative} onChange={(e) => setNarrative(e.target.value)} /></label>
        </div>
        {error && <div className="error-note" role="alert">{error}</div>}
      </form>
    </Modal>
  );
}

const ACCOUNT_STATUSES: [string, string][] = [
  ["ACTIVE", "Active: normal use"],
  ["DORMANT", "Dormant: no activity for the dormancy period (the kiosk offers reactivation)"],
  ["FROZEN", "Frozen: nothing can move (teller only)"],
  ["CLOSED", "Closed"],
];

function AccountStatusModal({ account, onClose, onDone }: { account: ToolAccount; onClose: () => void; onDone: () => void }) {
  const { portal } = usePortal();
  const { busy, error, save } = useSave(onDone);
  const [status, setStatus] = useState(account.status && account.status !== "ACTIVE" ? account.status : "FROZEN");
  const [reason, setReason] = useState("");
  function submit(e: FormEvent) {
    e.preventDefault();
    void save({ title: "Change account status", confirmLabel: "Change status",
                message: <p>Set account <Mono>{account.number}</Mono> to <b>{fmt.words(status)}</b>?</p> },
      async () => { await portal.testAccountStatus(account.id, status, reason || undefined); return `Account is now ${fmt.words(status).toLowerCase()}.`; });
  }
  return (
    <Modal open onClose={onClose} busy={busy} icon={<IconLock />} title={<>Status of <Mono>{account.number}</Mono></>}
           footer={<Foot busy={busy} onClose={onClose} form="tt-acct" label="Change status" />}>
      <form id="tt-acct" className="form" onSubmit={submit}>
        {ACCOUNT_STATUSES.map(([v, l]) => (
          <label key={v} className="check"><input type="radio" name="st" checked={status === v} onChange={() => setStatus(v)} /> {l}</label>
        ))}
        {(status === "FROZEN" || status === "CLOSED") && (
          <label><span>Reason <small className="muted">(optional, e.g. COURT_ORDER)</small></span>
            <input className="input" maxLength={40} value={reason} onChange={(e) => setReason(e.target.value)} /></label>
        )}
        {error && <div className="error-note" role="alert">{error}</div>}
      </form>
    </Modal>
  );
}

const CARD_STATUSES: [string, string][] = [
  ["ACTIVE", "Active"],
  ["INACTIVE_PIN_REQUIRED", "Not yet activated (PIN to be set)"],
  ["TEMP_BLOCKED", "Temporarily blocked"],
  ["BLOCKED", "Blocked (hotlisted)"],
  ["PIN_LOCKED", "PIN locked (too many wrong PINs)"],
  ["EXPIRED", "Expired"],
];
const BLOCK_REASONS: Record<string, string[]> = { BLOCKED: ["LOST", "STOLEN", "DAMAGED", "FRAUD"], TEMP_BLOCKED: ["MISPLACED", "TRAVEL"] };

function CardStatusModal({ card, onClose, onDone }: { card: ToolCard; onClose: () => void; onDone: () => void }) {
  const { portal } = usePortal();
  const { busy, error, save } = useSave(onDone);
  const [status, setStatus] = useState(card.status && card.status !== "ACTIVE" ? card.status : "BLOCKED");
  const [reason, setReason] = useState("LOST");
  const reasons = BLOCK_REASONS[status];
  function submit(e: FormEvent) {
    e.preventDefault();
    void save({ title: "Change card status", confirmLabel: "Change status",
                message: <p>Set card <Mono>{card.maskedPan}</Mono> to <b>{fmt.words(status)}</b>{reasons ? ` (${fmt.words(reason)})` : ""}?</p> },
      async () => { await portal.testCardStatus(card.id, status, reasons ? reason : undefined); return `Card is now ${fmt.words(status).toLowerCase()}.`; });
  }
  return (
    <Modal open onClose={onClose} busy={busy} icon={<IconCard />} title={<>Status of card <Mono>{card.maskedPan}</Mono></>}
           footer={<Foot busy={busy} onClose={onClose} form="tt-card" label="Change status" />}>
      <form id="tt-card" className="form" onSubmit={submit}>
        {CARD_STATUSES.map(([v, l]) => (
          <label key={v} className="check"><input type="radio" name="cs" checked={status === v}
                 onChange={() => { setStatus(v); setReason(BLOCK_REASONS[v]?.[0] ?? ""); }} /> {l}</label>
        ))}
        {reasons && (
          <label><span>Reason</span>
            <select className="input select" value={reason} onChange={(e) => setReason(e.target.value)}>
              {reasons.map((r) => <option key={r} value={r}>{fmt.words(r)}</option>)}
            </select></label>
        )}
        {error && <div className="error-note" role="alert">{error}</div>}
      </form>
    </Modal>
  );
}

const FUNDS = [["", "—"], ["SALARY", "Salary or wages"], ["BUSINESS", "Business income"], ["FARMING", "Farming"],
  ["REMITTANCES", "Money from family or abroad"], ["PENSION", "Pension"], ["SAVINGS_INVESTMENTS", "Savings or investments"], ["OTHER", "Other"]];
const INCOME = [["", "—"], ["UP_TO_10K", "Up to KES 10,000"], ["10K_50K", "KES 10,001 – 50,000"], ["50K_100K", "KES 50,001 – 100,000"],
  ["100K_500K", "KES 100,001 – 500,000"], ["OVER_500K", "Over KES 500,000"]];

function EditCustomerModal({ customer, onClose, onDone }: { customer: ToolCustomer; onClose: () => void; onDone: () => void }) {
  const { portal } = usePortal();
  const { busy, error, save } = useSave(onDone);
  const [f, setF] = useState<TestCustomer | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  useEffect(() => { portal.testCustomer(customer.id).then(setF).catch((e) => setLoadError((e as Error).message)); }, [portal, customer.id]);
  const set = (patch: Partial<TestCustomer>) => setF((x) => (x ? { ...x, ...patch } : x));
  const addr = (k: keyof TestCustomer["address"], v: string) => setF((x) => (x ? { ...x, address: { ...x.address, [k]: v } } : x));
  const kin = (k: "fullName" | "relationship" | "mobile", v: string) => setF((x) => (x ? { ...x, nextOfKin: { ...(x.nextOfKin ?? {}), [k]: v } } : x));
  function submit(e: FormEvent) {
    e.preventDefault();
    if (!f) return;
    void save({ title: "Save customer details", confirmLabel: "Save changes",
                message: <p>Save the changes to <b>{f.fullName}</b> (<Mono>{customer.id}</Mono>)? The bank's record and the details on their
                  applications in the portal are updated.</p> },
      async () => {
        await portal.testEditCustomer(customer.id, {
          fullName: f.fullName, mobile: f.mobile, email: f.email ?? "", kraPin: f.kraPin ?? "", address: f.address,
          occupation: f.occupation ?? "", employer: f.employer ?? "", sourceOfFunds: f.sourceOfFunds ?? "", monthlyIncome: f.monthlyIncome ?? "",
          nextOfKin: f.nextOfKin ?? undefined,
        });
        return "Customer details saved.";
      });
  }
  const v = (s: string | null | undefined) => s ?? "";
  return (
    <Modal open size="lg" onClose={onClose} busy={busy} icon={<IconEdit />} title={<>Edit {customer.fullName} <Mono>{customer.id}</Mono></>}
           footer={f ? <Foot busy={busy} onClose={onClose} form="tt-cust" label="Save changes" /> : undefined}>
      {loadError ? <div className="error-note">{loadError}</div> : !f ? <Spinner /> : (
        <form id="tt-cust" className="form" onSubmit={submit}>
          <div className="form__row">
            <label><span>Full name</span><input className="input" required minLength={3} maxLength={120} value={f.fullName} onChange={(e) => set({ fullName: e.target.value })} /></label>
            <label><span>Mobile</span><input className="input" required maxLength={20} value={f.mobile} onChange={(e) => set({ mobile: e.target.value })} placeholder="0712 345 678" /></label>
          </div>
          <div className="form__row">
            <label><span>E-mail</span><input className="input" type="email" maxLength={120} value={v(f.email)} onChange={(e) => set({ email: e.target.value })} /></label>
            <label><span>KRA PIN</span><input className="input" maxLength={20} value={v(f.kraPin)} onChange={(e) => set({ kraPin: e.target.value.toUpperCase() })} placeholder="A123456789B" /></label>
          </div>
          <div className="form__row">
            <label><span>Estate or street</span><input className="input" maxLength={120} value={v(f.address.line1)} onChange={(e) => addr("line1", e.target.value)} /></label>
            <label><span>P.O. Box</span><input className="input" maxLength={30} value={v(f.address.poBox)} onChange={(e) => addr("poBox", e.target.value)} /></label>
          </div>
          <div className="form__row">
            <label><span>Town</span><input className="input" maxLength={60} value={v(f.address.town)} onChange={(e) => addr("town", e.target.value)} /></label>
            <label><span>County</span><input className="input" maxLength={60} value={v(f.address.county)} onChange={(e) => addr("county", e.target.value)} /></label>
          </div>
          <div className="form__row">
            <label><span>Sub-county</span><input className="input" maxLength={60} value={v(f.address.subCounty)} onChange={(e) => addr("subCounty", e.target.value)} /></label>
            <label><span>Ward</span><input className="input" maxLength={60} value={v(f.address.ward)} onChange={(e) => addr("ward", e.target.value)} /></label>
          </div>
          <div className="form__row">
            <label><span>Occupation</span><input className="input" maxLength={60} value={v(f.occupation)} onChange={(e) => set({ occupation: e.target.value })} /></label>
            <label><span>Employer or business</span><input className="input" maxLength={80} value={v(f.employer)} onChange={(e) => set({ employer: e.target.value })} /></label>
          </div>
          <div className="form__row">
            <label><span>Source of funds</span>
              <select className="input select" value={v(f.sourceOfFunds)} onChange={(e) => set({ sourceOfFunds: e.target.value })}>
                {FUNDS.map(([c, l]) => <option key={c} value={c}>{l}</option>)}
                {f.sourceOfFunds && !FUNDS.some(([c]) => c === f.sourceOfFunds) && <option value={f.sourceOfFunds}>{f.sourceOfFunds}</option>}
              </select></label>
            <label><span>Monthly income</span>
              <select className="input select" value={v(f.monthlyIncome)} onChange={(e) => set({ monthlyIncome: e.target.value })}>
                {INCOME.map(([c, l]) => <option key={c} value={c}>{l}</option>)}
              </select></label>
          </div>
          <div className="form__row">
            <label><span>Next of kin</span><input className="input" maxLength={80} value={v(f.nextOfKin?.fullName)} onChange={(e) => kin("fullName", e.target.value)} /></label>
            <label><span>Relationship</span><input className="input" maxLength={30} value={v(f.nextOfKin?.relationship)} onChange={(e) => kin("relationship", e.target.value)} /></label>
          </div>
          <div className="form__row">
            <label><span>Next of kin's phone</span><input className="input" maxLength={20} value={v(f.nextOfKin?.mobile)} onChange={(e) => kin("mobile", e.target.value)} /></label>
            <span />
          </div>
          {error && <div className="error-note" role="alert">{error}</div>}
        </form>
      )}
    </Modal>
  );
}
