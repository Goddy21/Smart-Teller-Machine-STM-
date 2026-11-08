import { usePortal } from "../context";
import { Badge, Card, Empty, ErrorNote, fmt, Mono, Spinner, useLoad } from "../ui/kit";
import { IconCash, IconCheck, IconAlert } from "../ui/Icons";

/** A-2 cash reconciliation: what each kiosk counted against what the general ledger says it holds. */
export function Reconciliation() {
  const { portal } = usePortal();
  const r = useLoad(() => portal.reconciliation(), [], 20_000);
  const gl = useLoad(() => portal.simGl().catch(() => []), []);
  if (r.error && !r.data) return <ErrorNote error={r.error} onRetry={r.reload} />;
  if (!r.data) return <Spinner />;
  const { positions, cassettes, pendingReversals } = r.data;
  const terminals = [...new Set(cassettes.map((c) => c.terminalCode))];

  return (
    <div className="stack">
      <div className="grid-2">
        {terminals.map((code) => (
          <Card key={code} title={<>Cash in <Mono>{code}</Mono></>} subtitle="Notes in the recycler now, by currency">
            <div className="rates">
              {cassettes.filter((c) => c.terminalCode === code).map((c) => {
                const g = (gl.data ?? []).find((x) => x.gl === `GL-CASH-${c.currency}-${code}`);
                const diff = g ? c.value - g.balance : null;
                return (
                  <div key={c.currency} className="rate">
                    <b>{c.currency}</b>
                    <span className="num">{fmt.money(c.value, c.currency)}</span>
                    <small className="muted">{c.notesByDenomination.filter((n) => n.count).map((n) => `${n.count}×${n.denomination}`).join(" · ") || "empty"}</small>
                    {g && <small className={diff ? "dir-dr" : "dir-cr"}>{diff ? `GL ${fmt.money(g.balance, c.currency)} (${diff > 0 ? "+" : ""}${fmt.num(diff)})` : "Matches the GL"}</small>}
                  </div>
                );
              })}
            </div>
          </Card>
        ))}
        <Card title="Reversals waiting" subtitle="Cash presented but not taken (W-4): the customer is credited back"
              actions={<Badge tone={pendingReversals.length ? "warn" : "good"}>{pendingReversals.length}</Badge>}>
          {pendingReversals.length === 0 ? <Empty title="Nothing waiting" /> : (
            <ul className="recs">
              {pendingReversals.map((p) => (
                <li key={p.id}>
                  <div><b>{fmt.money(p.amount, p.currency)} · {fmt.words(p.reverseReason)}</b>
                    <span className="muted small"><Mono>{p.transactionRef}</Mono> → <Mono>{p.reversalRef}</Mono> · credit back by {fmt.dateTime(p.creditBackBy)}</span></div>
                  <Mono>{p.terminalCode}</Mono>
                </li>
              ))}
            </ul>
          )}
        </Card>
      </div>

      <Card pad={false} title="Cash position reports" subtitle="Sent by the kiosks at end of day and when cash-in-transit replenishes them">
        {positions.length === 0 ? <Empty icon={<IconCash />} title="No position reports yet" /> : (
          <div className="table-wrap">
            <table className="table">
              <thead><tr><th>Reported</th><th>Kiosk</th><th>Event</th><th>Bins</th><th>By currency (counted vs GL)</th><th>Result</th></tr></thead>
              <tbody>
                {positions.map((p) => (
                  <tr key={p.id} className={p.reconciled ? "" : "is-red"}>
                    <td className="muted">{fmt.dateTime(p.createdAt)}</td>
                    <td><Mono>{p.terminalCode}</Mono></td>
                    <td>{fmt.words(p.event)}</td>
                    <td className="muted small">retract {p.retractBin} · reject {p.rejectBin} · suspect {p.counterfeitBin}</td>
                    <td>
                      {p.variances.filter((v) => Number(v.expected) || Number(v.counted)).map((v) => (
                        <div key={v.currency} className="small">
                          <b>{v.currency}</b> {fmt.num(Number(v.counted))} / {fmt.num(Number(v.expected))}
                          {Number(v.difference) !== 0 && <span className="dir-dr"> ({Number(v.difference) > 0 ? "+" : ""}{fmt.num(Number(v.difference))})</span>}
                        </div>
                      ))}
                    </td>
                    <td>{p.reconciled
                      ? <Badge tone="good"><IconCheck /> Balanced</Badge>
                      : <span className="badges"><Badge tone="bad"><IconAlert /> Variance</Badge>{p.caseId && <Mono>{p.caseId}</Mono>}</span>}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>
      <p className="muted small">A variance opens a case for the branch cash officer. Cash postings for each transaction are on the Cash &amp; forex page.</p>
    </div>
  );
}
