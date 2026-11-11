import { useState } from "react";
import { usePortal } from "../context";
import { Badge, Card, Empty, ErrorNote, fmt, Mono, Pager, Segmented, Spinner, useDebounced, useLoad, usePaging } from "../ui/kit";
import { IconCard, IconSearch, IconShield } from "../ui/Icons";
import { CARD_SERVICE_LABEL, label, SERVICE_KIND, STEP_LABEL } from "../ui/labels";
import { serviceDetail, serviceSubject } from "./Sessions";

/** Cards printed at the kiosks, and the card and account changes customers made there. */
export function Cards() {
  const [tab, setTab] = useState<"orders" | "services">("orders");
  return (
    <div className="stack">
      <Segmented value={tab} onChange={setTab} options={[
        { value: "orders", label: "Card orders" },
        { value: "services", label: "Service requests" },
      ]} />
      {tab === "orders" ? <Orders /> : <ServiceRequests />}
    </div>
  );
}

const KIND_FILTERS = [
  { value: "", label: "All" },
  { value: "PIN_CHANGED", label: "PIN change" },
  { value: "PIN_RESET", label: "PIN reset" },
  { value: "CARD_TEMP_BLOCKED", label: "Temp block" },
  { value: "CARD_UNBLOCKED", label: "Unblock" },
  { value: "CARD_BLOCKED", label: "Blocked" },
  { value: "ACCOUNT_REACTIVATED", label: "Reactivation" },
  { value: "MOBILE_CHANGED", label: "Phone number" },
  { value: "BIOMETRICS_ENROLLED", label: "Fingerprints" },
  { value: "SIGNATURE_REGISTERED", label: "Signature" },
];

/** PIN changes and resets, card blocks, reactivations, phone-number changes, enrolments and signatures (P-1 … P-5, M-1 … M-7). */
function ServiceRequests() {
  const { portal, openSession } = usePortal();
  const [kind, setKind] = useState("");
  const [q, setQ] = useState("");
  const dq = useDebounced(q.trim());
  const paging = usePaging("service-events", [kind, dq]);
  const f = useLoad(() => portal.serviceEventsPage({ kind: kind || undefined, q: dq || undefined, page: paging.page, size: paging.size }),
    [kind, dq, paging.page, paging.size], 15_000);
  const items = f.data?.items ?? [];
  return (
    <Card pad={false} title="Service requests" subtitle="What customers changed on their cards and accounts at the kiosks, after proving who they are"
          actions={<div className="toolbar toolbar--wrap">
            <label className="search"><IconSearch /><input value={q} onChange={(e) => setQ(e.target.value)} placeholder="Customer, card, reference…" /></label>
            <select className="select" value={kind} onChange={(e) => setKind(e.target.value)} aria-label="Kind of request">
              {KIND_FILTERS.map((k) => <option key={k.value} value={k.value}>{k.label}</option>)}
            </select>
          </div>}>
      {f.error && !f.data ? <div className="card__body"><ErrorNote error={f.error} onRetry={f.reload} /></div>
        : !f.data ? <div className="card__body"><Spinner /></div>
        : items.length === 0 ? <Empty icon={<IconShield />} title={dq || kind ? "No service requests match" : "No service requests yet"} />
        : (
          <div className="table-wrap">
            <table className="table">
              <thead><tr><th>Customer</th><th>What</th><th>On</th><th>Details</th><th>Reference</th><th>Kiosk</th><th>When</th></tr></thead>
              <tbody>
                {items.map((e) => (
                  <tr key={e.id} className={`clickable ${e.kind === "MOBILE_CHANGED" || e.kind === "CARD_BLOCKED" ? "is-amber" : ""}`} onClick={() => e.sessionId && openSession(e.sessionId)}>
                    <td><div className="cell-main"><span><b>{e.customerName ?? "—"}</b><small>{e.customerId}</small></span></div></td>
                    <td><b>{label(SERVICE_KIND, e.kind)}</b></td>
                    <td>{serviceSubject(e.kind, e.subject)}</td>
                    <td className="muted">{serviceDetail(e.kind, e.detail)}</td>
                    <td>{e.reference ? <Mono>{e.reference}</Mono> : "—"}</td>
                    <td><Mono>{e.terminalCode}</Mono></td>
                    <td className="muted">{fmt.dateTime(e.createdAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      <Pager data={f.data} onPage={paging.setPage} onSize={paging.setSize} />
    </Card>
  );
}

/** Debit cards printed at the kiosks (C-4 … C-10, and the card that comes with a new account). */
function Orders() {
  const { portal, openSession } = usePortal();
  const [status, setStatus] = useState("");
  const [q, setQ] = useState("");
  const dq = useDebounced(q.trim());
  const paging = usePaging("cards", [status, dq]);
  const f = useLoad(() => portal.cardOrdersPage({ status: status || undefined, q: dq || undefined, page: paging.page, size: paging.size }),
    [status, dq, paging.page, paging.size], 15_000);
  const items = f.data?.items ?? [];

  return (
    <Card pad={false} title="Card orders" subtitle="Instant issue: the kiosk prints the name, programs the chip and hands the card over"
          actions={<div className="toolbar toolbar--wrap">
            <label className="search"><IconSearch /><input value={q} onChange={(e) => setQ(e.target.value)} placeholder="Customer, card, reference…" /></label>
            <Segmented value={status} onChange={setStatus} options={[
            { value: "", label: "All" },
            { value: "PERSONALISING", label: "Printing" },
            { value: "READY", label: "Ready" },
            { value: "DISPENSED", label: "Handed over" },
            { value: "RETAINED", label: "Retained" },
            { value: "SPOILED", label: "Spoiled" },
          ]} />
          </div>}>
      {f.error && !f.data ? <div className="card__body"><ErrorNote error={f.error} onRetry={f.reload} /></div>
        : !f.data ? <div className="card__body"><Spinner /></div>
        : items.length === 0 ? <Empty icon={<IconCard />} title={dq || status ? "No card orders match" : "No card orders"} />
        : (
          <div className="table-wrap">
            <table className="table">
              <thead><tr><th>Customer</th><th>Order</th><th>Card</th><th>Printing</th><th className="num">Fee</th><th>Status</th><th>Kiosk</th><th>When</th></tr></thead>
              <tbody>
                {items.map((c) => (
                  <tr key={c.id} className={`clickable ${c.status === "SPOILED" || c.status === "RETAINED" ? "is-red" : ""}`} onClick={() => c.sessionId && openSession(c.sessionId)}>
                    <td><div className="cell-main"><span><b>{c.customerName ?? c.nameOnCard}</b><small>{c.customerId}</small></span></div></td>
                    <td>{label(CARD_SERVICE_LABEL, c.applicationId ? "ACCOUNT_OPENING" : c.service)} · {fmt.words(c.cardProduct)}<small className="muted"><br /><Mono>{c.reference ?? c.id}</Mono></small></td>
                    <td>{c.maskedPan ? <Mono>{c.maskedPan}</Mono> : <span className="muted">—</span>}<small className="muted"><br />{c.nameOnCard}</small></td>
                    <td>
                      {c.steps?.length ? (
                        <ol className="steps steps--mini" title={c.steps.map((s) => `${label(STEP_LABEL, s.code)}: ${fmt.words(s.status)}`).join("\n")}>
                          {c.steps.map((s) => <li key={s.code} className={s.status === "DONE" ? "is-done" : s.status === "FAILED" ? "is-fail" : s.status === "IN_PROGRESS" ? "is-cur" : ""}><i /></li>)}
                        </ol>
                      ) : <span className="muted">—</span>}
                    </td>
                    <td className="num">{c.feeAmount ? fmt.money(c.feeAmount) : "Waived"}</td>
                    <td><span className="badges"><Badge value={c.status} />{c.outcome && c.outcome !== "TAKEN" && <Badge value={c.outcome} />}</span></td>
                    <td><Mono>{c.terminalCode}</Mono></td>
                    <td className="muted">{fmt.dateTime(c.createdAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      <Pager data={f.data} onPage={paging.setPage} onSize={paging.setSize} />
    </Card>
  );
}
