import { Fragment, useState } from "react";
import { usePortal } from "../context";
import type { TxRow } from "../api/portal";
import { Badge, Card, Empty, ErrorNote, fmt, Kpi, Mono, Pager, Segmented, Spinner, useDebounced, useLoad, usePaging } from "../ui/kit";
import { IconCash, IconChevronRight, IconSearch } from "../ui/Icons";

type Kind = "" | "DEPOSIT" | "WITHDRAWAL" | "FX";

const KIND: Record<string, string> = { DEPOSIT: "Deposit", WITHDRAWAL: "Withdrawal", FX: "Forex" };

/** Cash in and out of the kiosks (K-2 … K-5, W-2 … W-4, X-1 … X-5) with the core-banking postings behind each one. */
export function Cash() {
  const { portal } = usePortal();
  const [kind, setKind] = useState<Kind>("");
  const [q, setQ] = useState("");
  const [open, setOpen] = useState<string | null>(null);
  const dq = useDebounced(q.trim());
  const paging = usePaging("cash", [kind, dq]);
  const tx = useLoad(() => portal.transactionsPage({ kind: kind || undefined, q: dq || undefined, page: paging.page, size: paging.size }),
    [kind, dq, paging.page, paging.size], 15_000);
  // The tiles count every transaction today, not only the page on screen.
  const sum = useLoad(() => portal.transactionsSummary(), [], 15_000);
  const rows = tx.data?.items ?? [];
  const exceptions = sum.data?.exceptions ?? 0;

  return (
    <div className="stack">
      <div className="kpis kpis--4">
        <Kpi label="Deposited today" value={fmt.moneyShort(sum.data?.depositedToday ?? 0)} icon={<IconCash />} tone="good" />
        <Kpi label="Withdrawn today" value={fmt.moneyShort(sum.data?.withdrawnToday ?? 0)} icon={<IconCash />} />
        <Kpi label="Forex today (KES value)" value={fmt.moneyShort(sum.data?.fxToday ?? 0)} icon={<IconCash />} />
        <Kpi label="Exceptions" value={exceptions} hint="Reversed, unknown outcome or suspect notes" icon={<IconCash />} tone={exceptions ? "warn" : "neutral"} />
      </div>
      <Card pad={false} title="Cash transactions" subtitle="Open a row to see the double-entry postings in core banking"
            actions={
              <div className="toolbar">
                <label className="search"><IconSearch /><input value={q} onChange={(e) => setQ(e.target.value)} placeholder="Customer, reference, FT…" /></label>
                <Segmented value={kind} onChange={setKind} options={[
                  { value: "", label: "All" }, { value: "DEPOSIT", label: "Deposits" }, { value: "WITHDRAWAL", label: "Withdrawals" }, { value: "FX", label: "Forex" },
                ]} />
              </div>
            }>
        {tx.error && !tx.data ? <div className="card__body"><ErrorNote error={tx.error} onRetry={tx.reload} /></div>
          : !tx.data ? <div className="card__body"><Spinner /></div>
          : rows.length === 0 ? <Empty icon={<IconCash />} title={dq || kind ? "No transactions match" : "No cash transactions yet"} />
          : (
            <div className="table-wrap">
              <table className="table">
                <thead><tr><th /><th>Type</th><th>Customer / detail</th><th className="num">Amount</th><th className="num">KES value</th><th>Status</th><th>Reference</th><th>Kiosk</th><th>When</th></tr></thead>
                <tbody>
                  {rows.map((t) => (
                    <Fragment key={t.id}>
                      <tr className={`clickable ${t.status === "REVERSED" || t.status === "UNKNOWN" ? "is-red" : ""}`} onClick={() => setOpen(open === t.id ? null : t.id)}>
                        <td className="chev"><IconChevronRight style={{ transform: open === t.id ? "rotate(90deg)" : undefined }} /></td>
                        <td>{KIND[t.kind]}</td>
                        <td>{t.party}
                          {t.notesReturned ? <small className="muted"> · {t.notesReturned} notes returned</small> : null}
                          {t.suspectedCounterfeit ? <small className="dir-dr"> · {t.suspectedCounterfeit} suspect</small> : null}</td>
                        <td className="num">{fmt.money(t.amount, t.currency)}</td>
                        <td className="num">{fmt.money(t.localAmount)}</td>
                        <td><Badge value={t.status} /></td>
                        <td>{t.transactionRef ? <Mono>{t.transactionRef}</Mono> : "—"}</td>
                        <td><Mono>{t.terminalCode}</Mono></td>
                        <td className="muted">{fmt.dateTime(t.createdAt)}</td>
                      </tr>
                      {open === t.id && <tr className="expand"><td colSpan={9}><Postings t={t} /></td></tr>}
                    </Fragment>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        <Pager data={tx.data} onPage={(p) => { setOpen(null); paging.setPage(p); }} onSize={paging.setSize} />
      </Card>
    </div>
  );
}

function Postings({ t }: { t: TxRow }) {
  const { portal, openSession } = usePortal();
  const refs = [t.coreRef, t.reversalRef].filter(Boolean).join(",");
  const p = useLoad(() => (refs ? portal.postings(refs) : Promise.resolve([])), [refs]);
  return (
    <div className="ledger">
      <div>
        <h4>Core-banking postings {t.coreRef && <Mono>{t.coreRef}</Mono>}</h4>
        {!refs ? <p className="muted">Not posted to core banking (yet).</p> : !p.data ? <Spinner /> : p.data.length === 0 ? <p className="muted">No postings found.</p> : (
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
      <div>
        <h4>Transaction</h4>
        <p className="amount-big">{fmt.money(t.amount, t.currency)}</p>
        <p className="muted small">{KIND[t.kind]} · <Mono>{t.id}</Mono>{t.reversalRef && <> · reversal <Mono>{t.reversalRef}</Mono></>}</p>
        <button type="button" className="link" onClick={() => openSession(t.sessionId)}>Open the customer session</button>
      </div>
    </div>
  );
}
