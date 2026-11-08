import { useState } from "react";
import { usePortal } from "../context";
import { can } from "../auth";
import { Badge, Card, ErrorNote, fmt, Mono, Spinner, useLoad } from "../ui/kit";
import { IconAlert, IconSearch } from "../ui/Icons";

/** The simulated core banking, registry, card system and SMS gateway used in the demo profile. */
export function Simulator() {
  const { portal, user, toast } = usePortal();
  const sim = useLoad(() => portal.simulator(), []);
  const [q, setQ] = useState("");
  const customers = useLoad(() => portal.simCustomers(q.trim() || undefined), [q]);
  const gl = useLoad(() => portal.simGl(), [], 15_000);
  const [account, setAccount] = useState<string | null>(null);
  const edit = can.configure(user.role);

  if (sim.error && !sim.data) return <ErrorNote error={sim.error} onRetry={sim.reload} />;
  if (!sim.data) return <Spinner />;
  const s = sim.data;

  async function set(patch: { coreDown?: boolean; latencyMs?: number }) {
    try {
      sim.setData(await portal.setSimulator(patch));
      toast(patch.coreDown === true ? "Core banking is now down: the kiosks show “Service unavailable”." : "Simulator updated.", "ok");
    } catch (e) {
      toast((e as Error).message, "error");
    }
  }

  return (
    <div className="stack">
      <div className="callout callout--warn">
        <IconAlert />
        <div><b>Demo back ends</b><span>Simulated adapters stand in for core banking, IPRS/KRA, the card system and the SMS gateway. They are off in the prod profile.</span></div>
      </div>

      <div className="grid-3">
        <Card title="Switches">
          <div className="stack stack--sm">
            <label className="switch"><input type="checkbox" checked={s.coreDown} disabled={!edit} onChange={(e) => set({ coreDown: e.target.checked })} />Core banking down</label>
            <label className="form">
              <span>Latency per call: <b>{s.latencyMs} ms</b></span>
              <input type="range" min={0} max={3000} step={60} value={s.latencyMs} disabled={!edit}
                     onChange={(e) => sim.setData({ ...s, latencyMs: Number(e.target.value) })} onMouseUp={() => set({ latencyMs: s.latencyMs })}
                     onKeyUp={() => set({ latencyMs: s.latencyMs })} />
            </label>
            <div className="fields fields--2">
              <div className="field"><span className="field__label">OTP</span><span className="field__value">{s.fixedOtp ? <Mono>{s.fixedOtp}</Mono> : "random"}</span></div>
              <div className="field"><span className="field__label">Dev PIN block</span><span className="field__value"><Mono>{s.devPinBlockExample.pin} → {s.devPinBlockExample.pinBlock}</Mono></span></div>
            </div>
          </div>
        </Card>
        <Card title="Paybills" subtitle="Deposit targets other than own accounts">
          <ul className="recs">{s.paybills.map((p) => <li key={p.paybill}><div><b>{p.name}</b></div><Mono>{p.paybill}</Mono></li>)}</ul>
        </Card>
        <Card title="Cash GLs" subtitle="Balance per kiosk and currency">
          {!gl.data ? <Spinner /> : (
            <ul className="recs">{gl.data.map((g) => (
              <li key={g.gl}><div><Mono>{g.gl}</Mono><span className="muted small">{fmt.plural(g.postings, "posting")}</span></div><b>{fmt.money(g.balance, g.currency)}</b></li>
            ))}</ul>
          )}
        </Card>
      </div>

      <Card pad={false} title="Test identities" subtitle="Scan these ID numbers at the kiosk to walk each path of the journey">
        <div className="table-wrap">
          <table className="table table--dense">
            <thead><tr><th>Document</th><th>Name</th><th>Born</th><th>KRA</th><th>Scenario</th><th>What happens</th></tr></thead>
            <tbody>
              {s.identities.map((i) => (
                <tr key={i.documentNumber}>
                  <td><Mono>{i.documentNumber}</Mono><small className="muted"><br />{fmt.words(i.documentType)} · {i.country}</small></td>
                  <td><b>{i.givenNames} {i.surname}</b></td>
                  <td>{fmt.date(i.dateOfBirth)}</td>
                  <td>{i.kraPin ? <Mono>{i.kraPin}</Mono> : "—"} <Badge value={i.kraStatus === "ACTIVE" ? "OK" : "WARNING"}>{fmt.words(i.kraStatus)}</Badge></td>
                  <td><Badge tone={i.scenario === "HAPPY_PATH" || i.scenario === "EXISTING_CUSTOMER" ? "good" : "warn"}>{fmt.words(i.scenario)}</Badge></td>
                  <td className="muted small">{i.notes ?? "—"}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Card>

      <Card pad={false} title="Core banking customers"
            actions={<label className="search"><IconSearch /><input value={q} onChange={(e) => setQ(e.target.value)} placeholder="Name, ID, CIF…" /></label>}>
        {!customers.data ? <div className="card__body"><Spinner /></div> : (
          <div className="table-wrap">
            <table className="table table--dense">
              <thead><tr><th>Customer</th><th>Mobile</th><th>Accounts</th><th>Cards</th></tr></thead>
              <tbody>
                {customers.data.map((c) => (
                  <tr key={c.id}>
                    <td><b>{c.fullName}</b><small className="muted"><br /><Mono>{c.id}</Mono> · ID {c.idNumber}</small></td>
                    <td><Mono>{c.mobile}</Mono></td>
                    <td>{(c.accounts ?? []).map((a) => (
                      <div key={a.id}><button type="button" className="link" onClick={() => setAccount(a.id)}>{fmt.words(a.type)} {a.number}</button>
                        <span className="muted small"> {fmt.money(a.available, a.currency)}{a.status !== "ACTIVE" ? ` · ${fmt.words(a.status)}` : ""}</span></div>
                    ))}</td>
                    <td>{(c.cards ?? []).map((k) => <div key={k.id}><Mono>{k.maskedPan}</Mono> <Badge value={k.status} /></div>)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>
      {account && <AccountPostings id={account} onClose={() => setAccount(null)} />}
    </div>
  );
}

function AccountPostings({ id, onClose }: { id: string; onClose: () => void }) {
  const { portal } = usePortal();
  const p = useLoad(() => portal.simPostings(id), [id]);
  return (
    <Card pad={false} title={<>Postings on <Mono>{id}</Mono></>} actions={<button type="button" className="link" onClick={onClose}>Close</button>}>
      {!p.data ? <div className="card__body"><Spinner /></div> : (
        <table className="table table--dense">
          <thead><tr><th>When</th><th>FT</th><th>Narrative</th><th className="num">Amount</th><th className="num">Balance after</th></tr></thead>
          <tbody>
            {p.data.map((x) => (
              <tr key={x.id}>
                <td className="muted">{fmt.dateTime(x.postedAt)}</td>
                <td><Mono>{x.coreRef}</Mono></td>
                <td>{x.narrative}</td>
                <td className={`num ${x.direction === "CR" ? "dir-cr" : "dir-dr"}`}>{x.direction === "CR" ? "+" : "−"}{fmt.money(x.amount, x.currency)}</td>
                <td className="num">{x.balanceAfter == null ? "—" : fmt.money(x.balanceAfter, x.currency)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </Card>
  );
}
