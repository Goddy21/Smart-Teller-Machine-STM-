import { useEffect, useMemo, useState } from "react";
import { usePortal } from "../context";
import { can } from "../auth";
import type { TerminalCommand, TerminalRow, TerminalView } from "../api/portal";
import { Badge, Button, Card, Empty, ErrorNote, fmt, Mono, Spinner, useLoad } from "../ui/kit";
import { Meter } from "../ui/charts";
import { IconLock, IconMonitor, IconPower, IconRefresh, IconUnlock } from "../ui/Icons";
import { label, MODULE_LABEL, SERVICE_LABEL } from "../ui/labels";

const LOW_STOCK = 50;   // matches stm.low-card-stock in the middleware
const STOCK_FULL = 500;

export function Fleet() {
  const { portal, toast } = usePortal();
  const tele = useLoad(() => portal.terminals(), [], 30_000);
  const reg = useLoad(() => portal.registry(), [], 30_000);
  const [live, setLive] = useState(false);

  // Live updates: each heartbeat from a kiosk is pushed here as it arrives.
  useEffect(() => {
    const ctl = new AbortController();
    let stopped = false;
    (async () => {
      while (!stopped) {
        try {
          await portal.telemetryStream((t) => {
            tele.setData((prev) => {
              const list = prev ? [...prev] : [];
              const i = list.findIndex((x) => x.terminalCode === t.terminalCode);
              if (i >= 0) list[i] = t; else list.push(t);
              return list;
            });
          }, ctl.signal, () => setLive(true));
        } catch { /* fall through to retry */ }
        setLive(false);
        if (!stopped) await new Promise((r) => setTimeout(r, 5000));
      }
    })();
    return () => { stopped = true; ctl.abort(); };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [portal]);

  const merged = useMemo(() => {
    const byCode = new Map((reg.data ?? []).map((r) => [r.terminalCode, r]));
    return (tele.data ?? []).map((t) => ({ t, r: byCode.get(t.terminalCode) }));
  }, [tele.data, reg.data]);

  async function command(code: string, cmd: TerminalCommand) {
    if (cmd === "REBOOT" && !confirm(`Reboot ${code}? A customer using it will lose their session.`)) return;
    if (cmd === "LOCK" && !confirm(`Put ${code} out of service? Customers will see “Temporarily out of service”.`)) return;
    try {
      const r = await portal.command(code, cmd);
      toast(r.pushed ? `${fmt.words(cmd)} sent to ${code}.` : `${fmt.words(cmd)} saved. ${code} applies it on its next status check.`, "ok");
      reg.reload();
    } catch (e) {
      toast((e as Error).message, "error");
    }
  }

  if (tele.error && !tele.data) return <ErrorNote error={tele.error} onRetry={tele.reload} />;
  if (!tele.data) return <Spinner />;

  const online = merged.filter((m) => m.t.online).length;
  const alarms = merged.reduce((n, m) => n + m.t.alarms.length, 0);

  return (
    <div className="stack">
      <div className="fleet-summary">
        <div><b>{online}</b><span>online</span></div>
        <div><b>{merged.length - online}</b><span>offline</span></div>
        <div><b>{merged.filter((m) => m.t.serviceMode === "LOCKED").length}</b><span>locked</span></div>
        <div><b>{alarms}</b><span>active alarms</span></div>
        <span className={`live ${live ? "is-on" : ""}`}><span className="live__dot" />{live ? "Live updates" : "Polling every 30 s"}</span>
      </div>

      {merged.length === 0 ? (
        <Card><Empty icon={<IconMonitor />} title="No kiosks registered">Register one with POST /api/v1/terminals/register, then the kiosk signs in with D-1.</Empty></Card>
      ) : (
        <div className="fleet">
          {merged.map(({ t, r }) => <KioskCard key={t.terminalCode} t={t} r={r} onCommand={command} />)}
        </div>
      )}
    </div>
  );
}

function KioskCard({ t, r, onCommand }: { t: TerminalView; r?: TerminalRow; onCommand: (code: string, c: TerminalCommand) => void }) {
  const { user } = usePortal();
  const control = can.controlKiosks(user.role);
  const locked = t.serviceMode === "LOCKED";
  const stock = t.cardStock;
  const stockTone = stock == null ? "warn" : stock < LOW_STOCK ? "bad" : stock < LOW_STOCK * 2 ? "warn" : "good";
  const cassettes = Object.entries(t.cassetteCounts ?? {});
  const modules = Object.entries(t.modules ?? {}).map(([k, v]) => ({ k, status: typeof v === "string" ? v : v.status }));
  const services = Object.keys(SERVICE_LABEL);

  return (
    <article className={`kiosk ${t.online ? "" : "is-offline"} ${locked ? "is-locked" : ""}`}>
      <header className="kiosk__head">
        <span className="kiosk__icon"><IconMonitor /></span>
        <div>
          <h3>{t.terminalCode}</h3>
          <small>{r?.branchName ?? t.branchCode}</small>
        </div>
        <span className={`kiosk__state ${t.online ? "on" : "off"}`}><span className="dot" />{t.online ? "Online" : "Offline"}</span>
      </header>

      <div className="badges">
        {t.status && <Badge value={t.status} />}
        <Badge value={t.serviceMode} tone={locked ? "bad" : "good"}>{locked ? "Locked" : "In service"}</Badge>
        {t.softwareVersion && <Mono>v{t.softwareVersion}</Mono>}
        <span className="muted small">seen {fmt.ago(t.lastSeen)}</span>
      </div>

      {modules.length > 0 && (
        <div className="modules">
          {modules.map((m) => (
            <span key={m.k} className={`module is-${m.status === "OK" ? "ok" : /OUT|FAULT|JAM|OFFLINE|ERROR/.test(m.status) ? "bad" : m.status ? "warn" : "unknown"}`}
                  title={`${label(MODULE_LABEL, m.k)}: ${fmt.words(m.status)}`}>
              <span className="dot" /><span>{label(MODULE_LABEL, m.k)}{m.status !== "OK" ? ` · ${fmt.words(m.status)}` : ""}</span>
            </span>
          ))}
        </div>
      )}

      <div className="services" aria-label="Services offered on the home screen">
        {services.map((sv) => {
          const off = locked || t.disabledServices.includes(sv);
          return <Badge key={sv} tone={off ? "neutral" : "good"}>{off ? <s>{SERVICE_LABEL[sv]}</s> : SERVICE_LABEL[sv]}</Badge>;
        })}
      </div>

      <div className="kiosk__metric">
        <span>Blank cards</span>
        <Meter value={stock ?? 0} max={STOCK_FULL} tone={stockTone} />
        <b>{stock ?? "—"}</b>
      </div>
      {stock != null && stock < LOW_STOCK && <p className="kiosk__warn">Low card stock: refill soon.</p>}

      {cassettes.length > 0 && (
        <div className="cassettes">
          {cassettes.map(([denom, n]) => (
            <div key={denom}><small>{denom}</small><b>{fmt.num(n)}</b></div>
          ))}
        </div>
      )}

      <div className="kiosk__alarms">
        {t.alarms.length === 0 ? <span className="muted small">No alarms</span>
          : t.alarms.map((a) => { const [m, st] = a.split(":"); return <Badge key={a} tone="warn">{st ? `${label(MODULE_LABEL, m)}: ${fmt.words(st).toLowerCase()}` : fmt.words(a)}</Badge>; })}
      </div>

      <div className="kiosk__stats">
        <div><small>Sessions today</small><b>{r?.sessionsToday ?? "—"}</b></div>
        <div><small>All time</small><b>{r?.sessionsTotal ?? "—"}</b></div>
        <div><small>Registered</small><b>{r ? fmt.date(r.createdAt) : "—"}</b></div>
      </div>

      <footer className="kiosk__actions">
        {control && (locked
          ? <Button size="sm" kind="success" icon={<IconUnlock />} onClick={() => onCommand(t.terminalCode, "UNLOCK")}>Unlock</Button>
          : <Button size="sm" kind="secondary" icon={<IconLock />} onClick={() => onCommand(t.terminalCode, "LOCK")}>Lock</Button>)}
        {control && <Button size="sm" kind="ghost" icon={<IconRefresh />} disabled={!t.online} onClick={() => onCommand(t.terminalCode, "REFRESH_CONFIG")}
                title={t.online ? "Reload configuration" : "Needs the kiosk to be online"}>Refresh</Button>}
        {control && <Button size="sm" kind="ghost" icon={<IconPower />} disabled={!t.online} onClick={() => onCommand(t.terminalCode, "REBOOT")}
                title={t.online ? "Restart the kiosk" : "Needs the kiosk to be online"}>Reboot</Button>}
        <Mono>{t.branchCode}</Mono>
      </footer>
    </article>
  );
}
