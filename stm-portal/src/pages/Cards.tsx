import { useState } from "react";
import { usePortal } from "../context";
import { Badge, Card, Empty, ErrorNote, fmt, Mono, Segmented, Spinner, useLoad } from "../ui/kit";
import { IconCard } from "../ui/Icons";
import { CARD_SERVICE_LABEL, label, STEP_LABEL } from "../ui/labels";

/** Debit cards printed at the kiosks (C-4 … C-10, and the card that comes with a new account). */
export function Cards() {
  const { portal, openSession } = usePortal();
  const [status, setStatus] = useState("");
  const f = useLoad(() => portal.cardOrders(status || undefined), [status], 15_000);

  return (
    <Card pad={false} title="Card orders" subtitle="Instant issue: the kiosk prints the name, programs the chip and hands the card over"
          actions={<Segmented value={status} onChange={setStatus} options={[
            { value: "", label: "All" },
            { value: "PERSONALISING", label: "Printing" },
            { value: "READY", label: "Ready" },
            { value: "DISPENSED", label: "Handed over" },
            { value: "RETAINED", label: "Retained" },
            { value: "SPOILED", label: "Spoiled" },
          ]} />}>
      {f.error && !f.data ? <div className="card__body"><ErrorNote error={f.error} onRetry={f.reload} /></div>
        : !f.data ? <div className="card__body"><Spinner /></div>
        : f.data.length === 0 ? <Empty icon={<IconCard />} title="No card orders" />
        : (
          <div className="table-wrap">
            <table className="table">
              <thead><tr><th>Customer</th><th>Order</th><th>Card</th><th>Printing</th><th className="num">Fee</th><th>Status</th><th>Kiosk</th><th>When</th></tr></thead>
              <tbody>
                {f.data.map((c) => (
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
    </Card>
  );
}
