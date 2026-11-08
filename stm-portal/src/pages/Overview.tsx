import { usePortal } from "../context";
import { can } from "../auth";
import { Badge, Card, Empty, ErrorNote, fmt, Kpi, Spinner, useLoad } from "../ui/kit";
import { HBars, StackedDaily } from "../ui/charts";
import { IconAlert, IconCard, IconCash, IconMonitor, IconShield, IconUsers, IconVideo } from "../ui/Icons";
import { flowLabel } from "../ui/labels";
import mark from "../assets/blueriver-mark.png";

const SERIES = [
  { key: "accountOpening", label: "Accounts" },
  { key: "cash", label: "Cash" },
  { key: "cardServices", label: "Cards" },
  { key: "forex", label: "Forex" },
  { key: "statements", label: "Statements" },
  { key: "browseOnly", label: "Browse only" },
] as const;

const FUNNEL: Record<string, string> = {
  CONSENTED: "Accepted terms", IDENTITY_CAPTURED: "Scanned ID", BIOMETRICS_VERIFIED: "Face & finger passed",
  KYC_CAPTURED: "Details captured", PHONE_VERIFIED: "Phone verified", SIGNATURE_CAPTURED: "Signed", ACCOUNT_OPENED: "Account opened",
};

export function Overview() {
  const { portal, user, go } = usePortal();
  const o = useLoad(() => portal.overview(), [], 15_000);
  const waiting = useLoad(() => portal.applications({ needsTeller: true }), [], 10_000);

  if (o.error && !o.data) return <ErrorNote error={o.error} onRetry={o.reload} />;
  if (!o.data) return <Spinner />;
  const t = o.data.totals;
  const waitingCount = t.referralsWaiting + t.identityHelpWaiting;

  return (
    <div className="stack">
      <section className="hero">
        <img className="hero__mark" src={mark} alt="" />
        <div>
          <div className="hero__eyebrow">Smart Teller · {new Date().toLocaleDateString("en-GB", { weekday: "long", day: "numeric", month: "long" })}</div>
          <h2 className="hero__title">Habari, {user.displayName.split(/\s+/)[0]}. <em>{waitingCount ? `${waitingCount} waiting for you.` : "All clear."}</em></h2>
          <p className="hero__sub">{fmt.plural(t.sessionsToday, "customer session")} today · {t.sessionsActive} at a kiosk right now · {t.terminalsOnline} of {t.terminals} kiosks online</p>
        </div>
        <div className="hero__actions">
          {waitingCount > 0 && <button type="button" className="btn btn--white btn--lg" onClick={() => go("applications")}>Review applications</button>}
          {can.takeCalls(user.role) && <button type="button" className="btn btn--secondary btn--lg" onClick={() => go("video")}>Video teller</button>}
        </div>
      </section>

      <div className="kpis">
        <Kpi label="Sessions today" value={fmt.num(t.sessionsToday)} hint={`${t.sessionsActive} active now`} icon={<IconUsers />} onClick={() => go("sessions")} />
        <Kpi label="Accounts opened" value={fmt.num(t.accountsOpenedToday)} hint={`${fmt.num(t.accountsOpened)} in total`} icon={<IconShield />} tone="good" onClick={() => go("applications")} />
        <Kpi label="Needs a teller" value={fmt.num(waitingCount)} hint={`${t.referralsWaiting} referrals · ${t.identityHelpWaiting} face checks`} icon={<IconVideo />}
             tone={waitingCount ? "bad" : "neutral"} onClick={() => go("applications")} />
        <Kpi label="Deposits today" value={fmt.moneyShort(t.depositsTodayKes)} hint={`${fmt.num(t.deposits)} deposits in total`} icon={<IconCash />} onClick={() => go("cash")} />
        <Kpi label="Withdrawals today" value={fmt.moneyShort(t.withdrawalsTodayKes)} hint={`${fmt.num(t.fxDeals)} forex deals`} icon={<IconCash />} onClick={() => go("cash")} />
        <Kpi label="Cash exceptions" value={fmt.num(t.reversalsPending + t.cashVariances + t.cashStuck)}
             hint={`${t.reversalsPending} reversals · ${t.cashVariances} variances`} icon={<IconAlert />}
             tone={t.reversalsPending + t.cashVariances + t.cashStuck ? "warn" : "neutral"} onClick={() => go("reconciliation")} />
      </div>

      <div className="grid-2-1">
        <Card title="Customer sessions, last 14 days" subtitle="By the service the customer used">
          <StackedDaily rows={o.data.last14Days} series={[...SERIES]} label="Customer sessions per day by service, last 14 days" />
        </Card>
        <Card title="Kiosk waiting for a teller" subtitle="Approve or reject so the customer can carry on"
              actions={<Badge tone={waitingCount ? "bad" : "neutral"}>{waitingCount}</Badge>}>
          {!waiting.data ? <Spinner /> : waiting.data.length === 0 ? <Empty title="Nobody is waiting">Referrals and failed face matches appear here.</Empty> : (
            <ul className="attention">
              {waiting.data.slice(0, 6).map((a) => (
                <li key={a.id}>
                  <button type="button" onClick={() => go("applications")}>
                    <span className="attention__icon is-call">{a.status === "REFERRED" ? <IconShield /> : <IconVideo />}</span>
                    <span className="attention__text"><b>{a.fullName ?? "New customer"}</b>
                      <small>{a.status === "REFERRED" ? "Screening referral" : "Face did not match"} · {a.terminalCode} · {fmt.ago(a.updatedAt)}</small></span>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </Card>
      </div>

      <div className="grid-3">
        <Card title="Services used" subtitle="All sessions">
          <HBars rows={o.data.byFlow.map((f) => ({ label: flowLabel(f.key === "NONE" ? null : f.key), value: f.count }))} />
        </Card>
        <Card title="Account opening funnel" subtitle="Furthest step reached">
          <HBars rows={o.data.applicationFunnel.map((f) => ({ label: FUNNEL[f.key] ?? fmt.words(f.key), value: f.count }))} color="var(--series-3)" />
        </Card>
        <Card title="Today at a glance">
          <ul className="mini-fleet">
            <li><IconMonitor /><span className="muted">Kiosks online</span><b>{t.terminalsOnline} / {t.terminals}</b></li>
            <li><IconMonitor /><span className="muted">Kiosks locked</span><b>{t.terminalsLocked}</b></li>
            <li><IconCard /><span className="muted">Cards issued (all time)</span><b>{fmt.num(t.cardsIssued)}</b></li>
            <li><IconCard /><span className="muted">Cards needing attention</span><b>{fmt.num(t.cardsAttention)}</b></li>
            <li><IconVideo /><span className="muted">Teller calls today</span><b>{fmt.num(t.tellerCallsToday)}</b></li>
            <li><IconShield /><span className="muted">Text messages today</span><b>{fmt.num(t.smsToday)}</b></li>
          </ul>
        </Card>
      </div>
    </div>
  );
}
