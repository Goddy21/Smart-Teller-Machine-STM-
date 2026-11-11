import { useState } from "react";
import { usePortal } from "../context";
import { can } from "../auth";
import type { FxRate } from "../api/portal";
import { Badge, Button, Card, Empty, ErrorNote, fmt, Mono, Pager, Spinner, useClientPage, useLoad } from "../ui/kit";
import { IconCheck } from "../ui/Icons";

/** B-1 products, tariffs, B-2 terms, X-1 forex rates, branches and the terminal registry. */
export function Reference() {
  const { portal } = usePortal();
  const ref = useLoad(() => portal.reference(), []);
  const reg = useLoad(() => portal.registry(), []);
  const products = useClientPage(ref.data?.products ?? null, "ref-products", [], 10);
  const branches = useClientPage(ref.data?.branches ?? null, "ref-branches", [], 10);
  const kiosks = useClientPage((reg.data ?? null)?.filter((t) => t.status !== "RETIRED") ?? null, "ref-kiosks", [], 10);

  if (ref.error && !ref.data) return <ErrorNote error={ref.error} onRetry={ref.reload} />;
  if (!ref.data) return <Spinner />;
  const d = ref.data;

  return (
    <div className="stack">
      <Card pad={false} title="Account products" subtitle="What the kiosk offers on screen B1 (B-1)">
        <div className="table-wrap">
          <table className="table">
            <thead><tr><th>Product</th><th>Type</th><th className="num">Min. opening balance</th><th>Cards</th><th>Features</th><th>At kiosks</th><th className="num">Opened</th></tr></thead>
            <tbody>
              {products.rows.map((p) => (
                <tr key={p.code} className={p.active ? "" : "is-removed"}>
                  <td><b>{p.name}</b><small className="muted"><br /><Mono>{p.code}</Mono>{p.nameSw ? ` · ${p.nameSw}` : ""}</small></td>
                  <td>{fmt.words(p.accountType)} · {p.currency}</td>
                  <td className="num">{p.minOpeningBalance ? fmt.money(p.minOpeningBalance, p.currency) : "None"}</td>
                  <td>{p.cardProducts.map(fmt.words).join(", ") || "—"}{p.waiveCardFee && <small className="muted"><br />first card free</small>}</td>
                  <td className="small">{p.features.map((f) => <div key={f} className="with-icon"><IconCheck /> {f}</div>)}</td>
                  <td><Badge tone={p.active ? "good" : "neutral"}>{p.active ? "Offered" : "Not offered"}</Badge></td>
                  <td className="num"><b>{fmt.num(p.accountsOpened)}</b></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <Pager data={products.data} onPage={products.setPage} onSize={products.setSize} />
      </Card>

      <FxRates rates={d.fxRates} onSaved={ref.reload} />

      <div className="grid-2">
        <Card title="Tariffs" subtitle="Fees quoted to customers before they confirm (excise duty included)" pad={false}>
          <table className="table table--dense">
            <thead><tr><th>Fee</th><th>Breakdown</th><th className="num">Total</th></tr></thead>
            <tbody>
              {d.tariffs.map((t) => (
                <tr key={t.code}>
                  <td><b>{t.label}</b><small className="muted"><br /><Mono>{t.code}</Mono> · v{t.version}</small></td>
                  <td className="muted small">{t.lines?.map((l) => `${l.label} ${fmt.num(l.amount)}`).join(" + ") ?? "—"}</td>
                  <td className="num">{t.fee ? fmt.money(t.fee, t.currency) : "Free"}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </Card>
        <Card title="Terms and consents" subtitle="What the customer accepts on screen B-2" pad={false}>
          <table className="table table--dense">
            <thead><tr><th>Terms</th><th>Language</th><th>Consents</th></tr></thead>
            <tbody>
              {d.terms.map((t) => (
                <tr key={`${t.termsId}-${t.language}`}>
                  <td><Mono>{t.termsId}</Mono><small className="muted"><br />{t.sections} sections{t.audioUrl ? " · audio" : ""}</small></td>
                  <td>{t.language === "sw" ? "Kiswahili" : "English"} {t.active ? null : <Badge tone="neutral">Old</Badge>}</td>
                  <td className="small">{t.consents.map((c) => <div key={c.code}>{c.label}{c.required ? "" : <span className="muted"> (optional)</span>}</div>)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </Card>
      </div>

      <div className="grid-2">
        <Card title="Branches" pad={false}>
          <table className="table">
            <thead><tr><th>Code</th><th>Name</th><th className="num">Kiosks</th></tr></thead>
            <tbody>{branches.rows.map((b) => <tr key={b.code}><td><Mono>{b.code}</Mono></td><td>{b.name}</td><td className="num">{b.terminals}</td></tr>)}</tbody>
          </table>
          <Pager data={branches.data} onPage={branches.setPage} onSize={branches.setSize} compact />
        </Card>
        <Card title="Terminal registry" subtitle="Every kiosk registered with the gateway" pad={false}>
          {!reg.data ? <div className="card__body"><Spinner /></div> : reg.data.length === 0 ? <Empty title="No kiosks registered" /> : (
            <table className="table">
              <thead><tr><th>Kiosk</th><th>Branch</th><th>Software</th><th>Mode</th><th>Last sign-in</th></tr></thead>
              <tbody>
                {kiosks.rows.map((t) => (
                  <tr key={t.terminalCode}>
                    <td><Mono>{t.terminalCode}</Mono></td>
                    <td>{t.branchName ?? t.branchCode}</td>
                    <td><Mono>{t.softwareVersion ?? "—"}</Mono></td>
                    <td><Badge value={t.serviceMode}>{t.serviceMode === "LOCKED" ? "Locked" : "In service"}</Badge></td>
                    <td className="muted">{fmt.ago(t.lastSignIn)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
          <Pager data={kiosks.data} onPage={kiosks.setPage} onSize={kiosks.setSize} compact />
        </Card>
      </div>
    </div>
  );
}

function FxRates({ rates, onSaved }: { rates: FxRate[]; onSaved: () => void }) {
  const { user } = usePortal();
  const edit = can.configure(user.role);
  return (
    <Card pad={false} title="Forex rates" subtitle={edit ? "The kiosk quotes these (X-1). Edit until the treasury feed is connected." : "The kiosk quotes these (X-1)"}>
      <div className="card__body"><div className="ticker"><div className="ticker__row">
        {[...rates, ...rates].map((r, i) => <span key={i}>{r.currency}/KES<em>buy</em>{r.buy.toFixed(2)}<em>sell</em>{r.sell.toFixed(2)}</span>)}
      </div></div></div>
      <div className="table-wrap">
        <table className="table">
          <thead><tr><th>Currency</th><th className="num">We buy (KES)</th><th className="num">We sell (KES)</th><th className="num">Spread</th><th>Updated</th><th /></tr></thead>
          <tbody>{rates.map((r) => <Rate key={r.currency} r={r} edit={edit} onSaved={onSaved} />)}</tbody>
        </table>
      </div>
    </Card>
  );
}

function Rate({ r, edit, onSaved }: { r: FxRate; edit: boolean; onSaved: () => void }) {
  const { portal, toast } = usePortal();
  const [buy, setBuy] = useState(String(r.buy));
  const [sell, setSell] = useState(String(r.sell));
  const [busy, setBusy] = useState(false);
  const dirty = Number(buy) !== r.buy || Number(sell) !== r.sell;
  const bad = !(Number(buy) > 0) || !(Number(sell) > Number(buy));

  async function save() {
    setBusy(true);
    try {
      await portal.setRate(r.currency, Number(buy), Number(sell));
      toast(`${r.currency} rates saved. New quotes use them at once.`, "ok");
      onSaved();
    } catch (e) {
      toast((e as Error).message, "error");
    } finally {
      setBusy(false);
    }
  }

  return (
    <tr>
      <td><b>{r.symbol} {r.currency}</b></td>
      <td className="num">{edit ? <input className="input input--rate" inputMode="decimal" value={buy} onChange={(e) => setBuy(e.target.value)} aria-label={`${r.currency} buy rate`} /> : r.buy.toFixed(2)}</td>
      <td className="num">{edit ? <input className="input input--rate" inputMode="decimal" value={sell} onChange={(e) => setSell(e.target.value)} aria-label={`${r.currency} sell rate`} /> : r.sell.toFixed(2)}</td>
      <td className="num muted">{bad ? "—" : (Number(sell) - Number(buy)).toFixed(2)}</td>
      <td className="muted">{fmt.ago(r.updatedAt)}</td>
      <td>{edit && dirty && (
        <div className="row-actions">
          <Button size="sm" kind="ghost" disabled={busy} onClick={() => { setBuy(String(r.buy)); setSell(String(r.sell)); }}>Undo</Button>
          <Button size="sm" disabled={busy || bad} onClick={save}>{bad ? "Sell must be above buy" : busy ? "Saving…" : "Save"}</Button>
        </div>
      )}</td>
    </tr>
  );
}
