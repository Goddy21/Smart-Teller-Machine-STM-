import { Fragment, useState, type ReactNode } from "react";
import { usePortal } from "../context";
import type { TxQuery, TxRow } from "../api/portal";
import { Badge, Button, Card, Empty, ErrorNote, fmt, Kpi, Mono, Pager, Segmented, Spinner, useDebounced, useLoad, usePaging } from "../ui/kit";
import { IconCash, IconChevronRight, IconDownload, IconFile, IconSearch } from "../ui/Icons";

export const TX_KIND: Record<string, string> = {
  DEPOSIT: "Cash deposit", WITHDRAWAL: "Cash withdrawal", FX: "Forex", CARD: "Card", STATEMENT: "Statement",
  ACCOUNT_OPENING: "Account opening", SERVICE: "Customer service",
};
const KIND_TABS = ["", "DEPOSIT", "WITHDRAWAL", "FX", "CARD", "STATEMENT", "ACCOUNT_OPENING", "SERVICE"] as const;
const SHORT: Record<string, string> = {
  "": "All", DEPOSIT: "Deposits", WITHDRAWAL: "Withdrawals", FX: "Forex", CARD: "Cards", STATEMENT: "Statements",
  ACCOUNT_OPENING: "Accounts", SERVICE: "Services",
};
const BAD = ["REVERSED", "UNKNOWN", "FAILED", "REJECTED", "SPOILED", "RETAINED", "PENDING_RECONCILIATION"];

const today = () => new Date(Date.now() + 3 * 3600_000).toISOString().slice(0, 10);   // East Africa Time

/** Every customer transaction at the kiosks, like an ATM/CDM journal, with the core-banking postings behind each. */
export function Transactions() {
  const { portal } = usePortal();
  const sum = useLoad(() => portal.transactionsSummary(), [], 15_000);
  const exceptions = sum.data?.exceptions ?? 0;
  return (
    <div className="stack">
      <div className="kpis kpis--4 no-print">
        <Kpi label="Transactions today" value={fmt.num(sum.data?.countToday ?? 0)} hint={`Fees ${fmt.moneyShort(sum.data?.feesToday ?? 0)}`} icon={<IconFile />} />
        <Kpi label="Cash in today" value={fmt.moneyShort(sum.data?.depositedToday ?? 0)} icon={<IconCash />} tone="good" />
        <Kpi label="Cash out today" value={fmt.moneyShort(sum.data?.withdrawnToday ?? 0)} hint={`Forex ${fmt.moneyShort(sum.data?.fxToday ?? 0)} (KES value)`} icon={<IconCash />} />
        <Kpi label="Exceptions" value={exceptions} hint="Reversed, unknown outcome or suspect notes" icon={<IconCash />} tone={exceptions ? "warn" : "neutral"} />
      </div>
      <TransactionsTable />
    </div>
  );
}

/** The transactions list. With `terminal` it shows one kiosk only (kiosk detail). */
export function TransactionsTable({ terminal }: { terminal?: string }) {
  const { portal } = usePortal();
  const [kind, setKind] = useState<(typeof KIND_TABS)[number]>("");
  const [status, setStatus] = useState("");
  const [kiosk, setKiosk] = useState(terminal ?? "");
  const [range, setRange] = useState({ from: "", to: "" });
  const [q, setQ] = useState("");
  const [open, setOpen] = useState<string | null>(null);
  const [exporting, setExporting] = useState(false);
  const dq = useDebounced(q.trim());
  const kiosks = useLoad(() => (terminal ? Promise.resolve([]) : portal.registry()), [terminal]);
  const query: TxQuery = { kind: kind || undefined, status: status || undefined, terminal: (terminal ?? kiosk) || undefined,
    from: range.from || undefined, to: range.to || undefined, q: dq || undefined };
  const key = JSON.stringify(query);
  const paging = usePaging(terminal ? "kiosk-tx" : "transactions", [key]);
  const tx = useLoad(() => portal.transactionsPage({ ...query, page: paging.page, size: paging.size }), [key, paging.page, paging.size], 15_000);
  const rows = tx.data?.items ?? [];
  const counts = tx.data?.counts ?? {};
  const all = Object.values(counts).reduce((a, b) => a + b, 0);
  const filtered = !!(kind || status || (!terminal && kiosk) || range.from || range.to || dq);
  const badRange = !!(range.from && range.to && range.from > range.to);

  const exportCsv = async () => {
    setExporting(true);
    try { await portal.exportTransactions(query); } finally { setExporting(false); }
  };

  return (
    <Card pad={false} className="print-area" title={terminal ? "Transactions on this kiosk" : "Transactions"}
          subtitle="Open a row for the full details and the double-entry postings in core banking"
          actions={
            <div className="toolbar toolbar--wrap no-print">
              <label className="search"><IconSearch /><input value={q} onChange={(e) => setQ(e.target.value)} placeholder="Customer, account, RRN, FT…" /></label>
              {!terminal && (
                <select className="select select--sm" value={kiosk} onChange={(e) => setKiosk(e.target.value)} aria-label="Kiosk">
                  <option value="">All kiosks</option>
                  {(kiosks.data ?? []).map((k) => <option key={k.terminalCode} value={k.terminalCode}>{k.terminalCode}</option>)}
                </select>
              )}
              <select className="select select--sm" value={status} onChange={(e) => setStatus(e.target.value)} aria-label="Status">
                <option value="">Any status</option>
                {["POSTED", "COMPLETED", "TAKEN", "DISPENSED", "PRINTED", "ACCOUNT_OPENED", "AUTHORISED", "AWAITING_CASH", "COUNTED",
                  "CANCELLED", "RETURNED", "REVERSED", "UNKNOWN", "PENDING_RECONCILIATION", "REJECTED", "RETAINED", "SPOILED"].map((s) =>
                  <option key={s} value={s}>{fmt.words(s)}</option>)}
              </select>
              <label className="date-range" title="Dates are East Africa Time; both days are included">
                <span>From</span><input type="date" className="input" value={range.from} max={range.to || today()} onChange={(e) => setRange((r) => ({ ...r, from: e.target.value }))} />
                <span>to</span><input type="date" className="input" value={range.to} min={range.from || undefined} max={today()} onChange={(e) => setRange((r) => ({ ...r, to: e.target.value }))} />
              </label>
              <Button kind="ghost" size="sm" onClick={() => setRange({ from: today(), to: today() })}>Today</Button>
              {filtered && <Button kind="ghost" size="sm" onClick={() => { setKind(""); setStatus(""); setKiosk(terminal ?? ""); setRange({ from: "", to: "" }); setQ(""); }}>Clear</Button>}
              <Button kind="secondary" size="sm" icon={<IconDownload />} onClick={exportCsv} disabled={exporting || !rows.length}>{exporting ? "Exporting…" : "Excel"}</Button>
              <Button kind="secondary" size="sm" icon={<IconFile />} onClick={() => window.print()} disabled={!rows.length}>Print</Button>
            </div>
          }>
      <div className="card__tabs no-print">
        <Segmented value={kind} onChange={(v) => { setOpen(null); setKind(v); }}
                   options={KIND_TABS.map((k) => ({ value: k, label: SHORT[k], count: k ? counts[k] ?? 0 : all }))} />
      </div>
      {badRange ? <Empty icon={<IconFile />} title="The start date is after the end date" />
        : tx.error && !tx.data ? <div className="card__body"><ErrorNote error={tx.error} onRetry={tx.reload} /></div>
        : !tx.data ? <div className="card__body"><Spinner /></div>
        : rows.length === 0 ? <Empty icon={<IconCash />} title={filtered ? "No transactions match" : "No transactions yet"} />
        : (
          <div className="table-wrap">
            <table className="table table--tx">
              <thead><tr>
                <th className="no-print" /><th>Date</th><th>Reference (RRN)</th>{!terminal && <th>Terminal</th>}<th>Status</th><th>Type</th>
                <th>From account</th><th>To account</th><th>Customer</th><th className="num">Amount</th><th>Cash</th>
                <th className="num">Balance</th><th>Narration</th>
              </tr></thead>
              <tbody>
                {rows.map((t) => (
                  <Fragment key={t.id}>
                    <tr className={`clickable ${BAD.includes(t.status) ? "is-red" : ""}`} onClick={() => setOpen(open === t.id ? null : t.id)}>
                      <td className="chev no-print"><IconChevronRight style={{ transform: open === t.id ? "rotate(90deg)" : undefined }} /></td>
                      <td className="nowrap muted">{fmt.dateTime(t.createdAt)}</td>
                      <td>{t.transactionRef ? <Mono>{t.transactionRef}</Mono> : "—"}</td>
                      {!terminal && <td><Mono>{t.terminalCode}</Mono></td>}
                      <td><Badge value={t.status} /></td>
                      <td className="nowrap">{TX_KIND[t.kind] ?? t.kind}</td>
                      <td>{t.fromAccount ? <Mono>{t.fromAccount}</Mono> : "—"}</td>
                      <td>{t.toAccount ? <Mono>{t.toAccount}</Mono> : "—"}</td>
                      <td>{t.customerName ?? "—"}{t.customerId && <div className="muted small"><Mono>{t.customerId}</Mono></div>}</td>
                      <td className="num nowrap">{t.amount ? fmt.money(t.amount, t.currency) : "—"}
                        {t.fee && t.kind !== "CARD" && t.kind !== "STATEMENT" ? <div className="muted small">fee {fmt.money(t.fee)}</div> : null}</td>
                      <td>{t.cashFlow === "IN" ? <Badge tone="good">In</Badge> : t.cashFlow === "OUT" ? <Badge tone="info">Out</Badge> : <span className="muted">—</span>}</td>
                      <td className="num nowrap">{t.balanceAfter != null ? fmt.money(t.balanceAfter) : "—"}</td>
                      <td className="muted small">{t.narration ?? t.party}
                        {t.notesReturned ? <> · {t.notesReturned} notes returned</> : null}
                        {t.suspectedCounterfeit ? <span className="dir-dr"> · {t.suspectedCounterfeit} suspect</span> : null}</td>
                    </tr>
                    {open === t.id && <tr className="expand"><td colSpan={terminal ? 12 : 13}><Details t={t} /></td></tr>}
                  </Fragment>
                ))}
              </tbody>
            </table>
          </div>
        )}
      <div className="no-print"><Pager data={tx.data} onPage={(p) => { setOpen(null); paging.setPage(p); }} onSize={paging.setSize} /></div>
    </Card>
  );
}

function Details({ t }: { t: TxRow }) {
  const { portal, openSession } = usePortal();
  const refs = [t.coreRef, t.reversalRef].filter(Boolean).join(",");
  const p = useLoad(() => (refs ? portal.postings(refs) : Promise.resolve([])), [refs]);
  const rows: [string, ReactNode][] = [
    ["Type", TX_KIND[t.kind] ?? t.kind], ["Status", <Badge value={t.status} />], ["Detail", t.party],
    ["Amount", t.amount ? fmt.money(t.amount, t.currency) : "—"], ["KES value", fmt.money(t.localAmount)], ["Fee", t.fee ? fmt.money(t.fee) : "—"],
    ["From account", t.fromAccount ?? "—"], ["To account", t.toAccount ?? "—"], ["Customer", t.customerName ?? "—"],
    ["Customer id", t.customerId ? <Mono>{t.customerId}</Mono> : "—"], ["Reference (RRN)", t.transactionRef ? <Mono>{t.transactionRef}</Mono> : "—"],
    ["Core reference", t.coreRef ? <Mono>{t.coreRef}</Mono> : "—"], ["Reversal", t.reversalRef ? <Mono>{t.reversalRef}</Mono> : "—"],
    ["Balance after", t.balanceAfter != null ? fmt.money(t.balanceAfter) : "—"], ["Terminal", <Mono>{t.terminalCode}</Mono>],
    ["Transaction id", <Mono>{t.id}</Mono>], ["Started", fmt.dateTime(t.createdAt)], ["Last update", fmt.dateTime(t.updatedAt)],
  ];
  return (
    <div className="ledger ledger--stack">
      <div>
        <h4>Transaction details</h4>
        <dl className="kv kv--2">{rows.map(([k, v]) => <Fragment key={k}><dt>{k}</dt><dd>{v}</dd></Fragment>)}</dl>
        <button type="button" className="link" onClick={() => openSession(t.sessionId)}>Open the customer session</button>
      </div>
      <div>
        <h4>Core-banking postings {t.coreRef && <Mono>{t.coreRef}</Mono>}</h4>
        {!refs ? <p className="muted">Nothing posted to core banking for this transaction.</p> : !p.data ? <Spinner /> : p.data.length === 0 ? <p className="muted">No postings found.</p> : (
          <table className="table table--dense">
            <thead><tr><th>FT</th><th>Account / GL</th><th>Narrative</th><th className="num">Debit</th><th className="num">Credit</th></tr></thead>
            <tbody>
              {p.data.map((x) => (
                <tr key={x.id}>
                  <td><Mono>{x.coreRef}</Mono>{x.reversalOf && <small className="muted"> reverses {x.reversalOf}</small>}</td>
                  <td><Mono>{x.accountId}</Mono></td>
                  <td className="muted">{x.narrative}</td>
                  <td className="num dir-dr">{x.direction === "DR" ? fmt.money(x.amount, x.currency) : ""}</td>
                  <td className="num dir-cr">{x.direction === "CR" ? fmt.money(x.amount, x.currency) : ""}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  );
}
