import { useMemo, useState } from "react";
import { usePortal } from "../context";
import { Badge, Card, Empty, ErrorNote, fmt, Mono, Pager, Segmented, Spinner, useClientPage, useLoad } from "../ui/kit";
import { IconCash, IconCheck, IconAlert } from "../ui/Icons";

/** A-2 cash reconciliation: what each kiosk counted against what the general ledger says it holds. */
export function Reconciliation() {
  const { portal } = usePortal();
  const r = useLoad(() => portal.reconciliation(), [], 20_000);
  const gl = useLoad(() => portal.simGl().catch(() => []), []);
  const [only, setOnly] = useState<"all" | "variance">("all");

  // One row per kiosk and currency, with the ledger balance next to the notes counted.
  const cash = useMemo(() => (r.data?.cassettes ?? []).map((c) => {
    const g = (gl.data ?? []).find((x) => x.gl === `GL-CASH-${c.currency}-${c.terminalCode}`);
    return { ...c, gl: g?.balance ?? null, diff: g ? c.value - g.balance : null };
  }), [r.data, gl.data]);
  const positions = useMemo(() => (r.data?.positions ?? []).filter((p) => only === "all" || !p.reconciled), [r.data, only]);

  const cashPage = useClientPage(cash, "recon-cash");
  const revPage = useClientPage(r.data?.pendingReversals ?? null, "recon-reversals");
  const posPage = useClientPage(positions, "recon-positions", [only]);

  if (r.error && !r.data) return <ErrorNote error={r.error} onRetry={r.reload} />;
  if (!r.data) return <Spinner />;
  const pending = r.data.pendingReversals;

  return (
    <div className="stack">
      <Card pad={false} title="Cash in the kiosks" subtitle="Notes in each recycler now, against the kiosk’s cash account in the general ledger">
        {cash.length === 0 ? <Empty icon={<IconCash />} title="No cash loaded yet" /> : (
          <div className="table-wrap">
            <table className="table">
              <thead><tr><th>Kiosk</th><th>Currency</th><th>Notes by denomination</th><th className="num">Notes</th><th className="num">Counted value</th><th className="num">Ledger (GL)</th><th>Result</th></tr></thead>
              <tbody>
                {cashPage.rows.map((c) => (
                  <tr key={`${c.terminalCode}-${c.currency}`} className={c.diff ? "is-red" : ""}>
                    <td><Mono>{c.terminalCode}</Mono></td>
                    <td><b>{c.currency}</b></td>
                    <td className="muted small">{c.notesByDenomination.filter((n) => n.count).map((n) => `${fmt.num(n.count)} × ${fmt.num(n.denomination)}`).join(" · ") || "empty"}</td>
                    <td className="num">{fmt.num(c.notes)}</td>
                    <td className="num">{fmt.money(c.value, c.currency)}</td>
                    <td className="num">{c.gl == null ? "—" : fmt.money(c.gl, c.currency)}</td>
                    <td>{c.diff == null ? <span className="muted">—</span>
                      : c.diff ? <Badge tone="bad"><IconAlert /> {c.diff > 0 ? "+" : ""}{fmt.num(c.diff)}</Badge>
                      : <Badge tone="good"><IconCheck /> Matches</Badge>}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        <Pager data={cashPage.data} onPage={cashPage.setPage} onSize={cashPage.setSize} />
      </Card>

      <Card pad={false} title="Reversals waiting" subtitle="Cash presented but not taken (W-4): the customer is credited back"
            actions={<Badge tone={pending.length ? "warn" : "good"}>{pending.length}</Badge>}>
        {pending.length === 0 ? <Empty title="Nothing waiting" /> : (
          <div className="table-wrap">
            <table className="table">
              <thead><tr><th>Kiosk</th><th className="num">Amount</th><th>Why</th><th>Transaction</th><th>Reversal</th><th>Credit back by</th></tr></thead>
              <tbody>
                {revPage.rows.map((p) => (
                  <tr key={p.id}>
                    <td><Mono>{p.terminalCode}</Mono></td>
                    <td className="num"><b>{fmt.money(p.amount, p.currency)}</b></td>
                    <td>{fmt.words(p.reverseReason)}</td>
                    <td><Mono>{p.transactionRef}</Mono></td>
                    <td><Mono>{p.reversalRef}</Mono></td>
                    <td className="muted">{fmt.dateTime(p.creditBackBy)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        <Pager data={revPage.data} onPage={revPage.setPage} onSize={revPage.setSize} />
      </Card>

      <Card pad={false} title="Cash position reports" subtitle="Sent by the kiosks at end of day and when cash-in-transit replenishes them"
            actions={<Segmented value={only} onChange={setOnly} options={[
              { value: "all", label: "All", count: r.data.positions.length },
              { value: "variance", label: "Variances", count: r.data.positions.filter((p) => !p.reconciled).length },
            ]} />}>
        {positions.length === 0 ? <Empty icon={<IconCash />} title={only === "variance" ? "No variances" : "No position reports yet"} /> : (
          <div className="table-wrap">
            <table className="table">
              <thead><tr><th>Reported</th><th>Kiosk</th><th>Event</th><th>Bins</th><th>By currency (counted vs GL)</th><th>Result</th></tr></thead>
              <tbody>
                {posPage.rows.map((p) => (
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
        <Pager data={posPage.data} onPage={posPage.setPage} onSize={posPage.setSize} />
      </Card>
      <p className="muted small">A variance opens a case for the branch cash officer. Cash postings for each transaction are on the Cash &amp; forex page.</p>
    </div>
  );
}
