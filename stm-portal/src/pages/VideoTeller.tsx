import { useEffect, useRef, useState, type MouseEvent } from "react";
import { usePortal, useTeller } from "../context";
import type { SessionDetail } from "../api/portal";
import type { Pane } from "../video/TellerConsole";
import { Badge, Button, Card, Empty, fmt, Mono, useLoad } from "../ui/kit";
import {
  IconCamera, IconCheck, IconExpand, IconFullscreen, IconLink, IconShrink, IconMic, IconMicOff, IconMonitor, IconPhone, IconPhoneOff, IconVideo, IconVideoOff, IconX, IconZoom,
} from "../ui/Icons";
import { ApplicationPanel, SessionBody } from "./Sessions";
import { flowLabel } from "../ui/labels";

/** The gateway passes the kiosk's session id (ses_…) as the call reference (POST /support/teller-calls). */
const SESSION_REF = /^ses_[A-Z0-9]+$/;

export function VideoTeller() {
  const { teller, state } = useTeller();
  const call = state.call;

  return (
    <div className="video-page">
      {state.notice && (
        <div className={`notice notice--${state.notice.tone}`} role="status">
          <span>{state.notice.text}</span>
          <button type="button" className="icon-btn" onClick={() => teller.clearNotice()} aria-label="Dismiss"><IconX /></button>
        </div>
      )}
      {!call && <Standby />}
      {/* The stage stays mounted so the <video> elements stay attached to the console. */}
      <div className={`call-layout ${call ? "" : "is-idle"}`}>
        <Stage />
        <CallSide />
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------------------------- idle

function Standby() {
  const { teller, state } = useTeller();
  const { portal, user, openSession } = usePortal();
  const queue = useLoad(() => portal.applications({ needsTeller: true }), [], 10_000);
  return (
    <div className="standby">
      <div className={`standby__hero ${state.available ? "is-on" : ""}`}>
        <div className="standby__pulse"><IconVideo /></div>
        <div>
          <h2>{state.available ? "You are available for video calls" : "You are away"}</h2>
          <p>{state.available
            ? `Customers who tap “Talk to a teller” in ${user.languages.join(" or ").toUpperCase()} will ring here. You have ${22} seconds to answer.`
            : "Go available to start receiving calls from customers at the kiosks."}</p>
          {state.missedCalls > 0 && <p className="standby__missed">{state.missedCalls} missed call{state.missedCalls > 1 ? "s" : ""} this session</p>}
        </div>
        <Button size="lg" kind={state.available ? "secondary" : "success"} disabled={state.changingPresence}
                icon={state.available ? <IconPhoneOff /> : <IconPhone />}
                onClick={() => (state.available ? teller.goAway() : teller.goAvailable())}>
          {state.changingPresence ? "Updating…" : state.available ? "Go away" : "Go available"}
        </Button>
      </div>
      <div className="grid-2">
        <Card title="Kiosks waiting for a teller" subtitle="Referred applications and failed face matches. You can decide here or during the call.">
          {!queue.data ? null : queue.data.length === 0 ? <Empty title="Nobody is waiting" /> : (
            <ul className="attention">
              {queue.data.map((a) => (
                <li key={a.id}>
                  <button type="button" onClick={() => openSession(a.sessionId)}>
                    <span className="attention__icon is-call"><IconVideo /></span>
                    <span className="attention__text"><b>{a.fullName ?? "New customer"}</b>
                      <small>{a.status === "REFERRED" ? "Screening referral" : "Face did not match"} · {a.terminalCode} · {fmt.ago(a.updatedAt)}</small></span>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </Card>
        <Card title="Before you take a call">
          <ul className="checklist">
            <li><IconCheck /> Allow camera and microphone when the browser asks.</li>
            <li><IconCheck /> During a call your camera fills the kiosk’s upper screen: sit centred, against a plain background.</li>
            <li><IconCheck /> Every call is recorded and written to the audit trail; the customer sees a notice first.</li>
            <li><IconCheck /> Check the face against the passport photo, and zoom into the ID camera.</li>
            <li><IconCheck /> PIN and card screens are hidden from you on purpose.</li>
            <li><IconCheck /> You see which screen the customer called from; account decisions are recorded under your name.</li>
          </ul>
        </Card>
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------------------------- stage

function Stage() {
  const { teller, state } = useTeller();
  const [zoom, setZoom] = useState(1);
  const [origin, setOrigin] = useState("50% 50%");
  const ref = (p: Pane) => (el: HTMLVideoElement | null) => teller.attach(p, el);
  // One pane can be enlarged (the others shrink to a strip below it). The kiosk screen starts enlarged.
  const [focus, setFocus] = useState<"face" | "id" | "screen" | null>(() => {
    try { const v = localStorage.getItem("stm-video-focus"); return v === "none" ? null : ((v as "face" | "id" | "screen") ?? "screen"); } catch { return "screen"; }
  });
  const choose = (p: "face" | "id" | "screen") => {
    const next = focus === p ? null : p;
    setFocus(next);
    try { localStorage.setItem("stm-video-focus", next ?? "none"); } catch { /* per-viewer convenience only */ }
  };
  const tools = (p: "face" | "id" | "screen") => (
    <span className="pane__tools" onMouseMove={(e) => e.stopPropagation()}>
      <button type="button" onClick={() => choose(p)} title={focus === p ? "Back to the normal layout" : "Make this view large"}
              aria-label={focus === p ? "Shrink" : "Enlarge"}>
        {focus === p ? <IconShrink /> : <IconExpand />}
      </button>
      <button type="button" title="Full screen (Esc to leave)" aria-label="Full screen"
              onClick={(e) => { void (e.currentTarget.closest(".pane") as HTMLElement | null)?.requestFullscreen?.().catch(() => undefined); }}>
        <IconFullscreen />
      </button>
    </span>
  );
  const cls = (p: "face" | "id" | "screen") => (focus === p ? "is-focus" : focus ? "is-thumb" : "");

  const pan = (e: MouseEvent<HTMLDivElement>) => {
    const r = e.currentTarget.getBoundingClientRect();
    setOrigin(`${((e.clientX - r.left) / r.width) * 100}% ${((e.clientY - r.top) / r.height) * 100}%`);
  };

  return (
    <div className="stage">
      {state.banner && <div className="stage__banner" role="alert">{state.banner}</div>}
      <div className={`panes ${focus ? "has-focus" : ""}`}>
        <div className={`pane pane--face ${state.panes.face ? "has" : ""} ${cls("face")}`} onDoubleClick={() => choose("face")}>
          <video ref={ref("face")} autoPlay playsInline muted />
          <span className="pane__tag"><span className="rec" />Customer</span>
          <div className="pane__empty"><IconCamera /><span>Waiting for the customer camera</span></div>
          {tools("face")}
          <CallTimer />
        </div>
        <div className={`pane pane--id ${state.panes.id ? "has" : ""} ${cls("id")}`} onMouseMove={pan} onDoubleClick={() => choose("id")}>
          <video ref={ref("id")} autoPlay playsInline muted style={{ transform: `scale(${zoom})`, transformOrigin: origin }} />
          <span className="pane__tag">ID camera {zoom > 1 && `· ${zoom}×`}</span>
          <div className="pane__empty"><IconCamera /><span>Waiting for the second camera</span></div>
          {tools("id")}
        </div>
        <div className={`pane pane--screen ${state.panes.screen ? "has" : ""} ${cls("screen")}`} onDoubleClick={() => choose("screen")}>
          <video ref={ref("screen")} autoPlay playsInline muted />
          <span className="pane__tag">Kiosk screen (live)</span>
          <div className="pane__empty"><IconMonitor />
            <span>{state.screenHidden ? "Hidden: the customer is on a private screen (PIN or card)." : "Waiting for the kiosk screen"}</span></div>
          {tools("screen")}
        </div>
      </div>

      <div className="controls">
        <button type="button" className={`ctl ${state.mic === "muted" ? "is-off" : ""}`} disabled={state.mic === "none"}
                onClick={() => teller.toggleMic()} aria-pressed={state.mic === "muted"} title={state.mic === "muted" ? "Unmute" : "Mute"}>
          {state.mic === "muted" ? <IconMicOff /> : <IconMic />}<span>{state.mic === "muted" ? "Unmute" : "Mute"}</span>
        </button>
        <button type="button" className={`ctl ${state.cam === "off" ? "is-off" : ""}`} disabled={state.cam === "none"}
                onClick={() => teller.toggleCam()} aria-pressed={state.cam === "off"}
                title={state.cam === "off" ? "Show yourself again, on both kiosk screens" : "Hides you on the kiosk, including its upper screen"}>
          {state.cam === "off" ? <IconVideoOff /> : <IconVideo />}<span>{state.cam === "off" ? "Camera on" : "Camera off"}</span>
        </button>
        <label className="ctl ctl--zoom">
          <IconZoom /><span>Zoom ID</span>
          <input type="range" min={1} max={4} step={0.25} value={zoom} onChange={(e) => setZoom(Number(e.target.value))} aria-label="Zoom ID camera" />
        </label>
        <button type="button" className="ctl" disabled={!state.joined || state.snapshotRequested} onClick={() => teller.requestSnapshot()}>
          <IconCamera /><span>{state.snapshotRequested ? "Snapshot requested" : "ID snapshot"}</span>
        </button>
        <button type="button" className="ctl ctl--end" disabled={!state.call} onClick={() => teller.end()}>
          <IconPhoneOff /><span>End call</span>
        </button>
      </div>
      {state.snapshotRequested && <p className="stage__hint">The kiosk uploads the full-resolution ID image to the KYC archive.</p>}
    </div>
  );
}

function CallTimer() {
  const { state } = useTeller();
  const [, tick] = useState(0);
  useEffect(() => { const id = setInterval(() => tick((t) => t + 1), 1000); return () => clearInterval(id); }, []);
  if (!state.call) return null;
  const s = Math.floor((Date.now() - state.call.since) / 1000);
  return <span className="pane__timer">{String(Math.floor(s / 60)).padStart(2, "0")}:{String(s % 60).padStart(2, "0")}</span>;
}

// ---------------------------------------------------------------------------------------------- side column

function CallSide() {
  const { teller, state } = useTeller();
  const selfRef = useRef<HTMLVideoElement>(null);
  useEffect(() => { teller.attach("self", selfRef.current); return () => teller.attach("self", null); }, [teller]);
  const call = state.call;

  return (
    <div className="call-side">
      <div className={`self ${state.panes.self ? "has" : ""}`}>
        <video ref={selfRef} autoPlay playsInline muted />
        <span className="pane__tag">You</span>
        {call && state.cam === "on" && <span className="self__note"><IconMonitor /> Also on the kiosk’s upper screen</span>}
        <div className="pane__empty"><IconVideoOff /><span>{call ? "Camera off: the kiosk tells the customer your camera is off" : "Camera off"}</span></div>
      </div>

      {call && (
        <Card title="Call" pad>
          <div className="fields fields--2">
            <div className="field"><span className="field__label">Kiosk</span><span className="field__value"><Mono>{call.terminalId}</Mono></span></div>
            <div className="field"><span className="field__label">Language</span><span className="field__value">{(call.lang ?? "—").toUpperCase()}</span></div>
            <div className="field"><span className="field__label">Customer</span>
              <span className="field__value">{state.customerPresent ? <Badge tone="good">Connected</Badge> : <Badge tone="warn">Not in the call</Badge>}</span></div>
            <div className="field"><span className="field__label">Reference</span><span className="field__value"><Mono title={call.txId}>{call.txId.slice(0, 13)}</Mono></span></div>
          </div>
        </Card>
      )}

      {call && <Mirror />}
      {call && SESSION_REF.test(call.txId) && <LinkedSession sessionId={call.txId} />}
    </div>
  );
}

/** What the customer currently sees, sent by the kiosk over the call's data channel. */
function Mirror() {
  const { state } = useTeller();
  const m = state.mirror;
  return (
    <Card title="Customer’s screen">
      {!m ? <p className="muted">No screen information from the kiosk yet.</p>
        : m.masked ? <div className="masked">The customer is on a private screen ({fmt.words(m.state)}). It is hidden on purpose.</div>
        : (
          <dl className="mirror">
            <dt>Screen</dt><dd>{fmt.words(m.state)}</dd>
            {Object.entries(m.model ?? {}).map(([k, v]) => (
              <span key={k} className="mirror__row"><dt>{k}</dt><dd>{typeof v === "object" ? JSON.stringify(v) : String(v)}</dd></span>
            ))}
          </dl>
        )}
    </Card>
  );
}

/** The kiosk session behind the call: what the customer is doing, and any decision the kiosk is waiting for. */
function LinkedSession({ sessionId }: { sessionId: string }) {
  const { portal, openSession } = usePortal();
  const s = useLoad<SessionDetail | null>(() => portal.session(sessionId).catch(() => null), [sessionId], 5000);
  if (!s.data) return null;
  const d = s.data;
  const call = d.tellerCalls[d.tellerCalls.length - 1];
  const apps = d.applications.filter((a) => !["CANCELLED", "EXPIRED"].includes(a.status));
  return (
    <Card title={<span className="with-icon"><IconLink /> {d.customerName ?? d.applications.find((a) => a.fullName)?.fullName ?? (d.flow === "ACCOUNT_OPENING" ? "New customer" : "Customer not identified")}</span>}
          subtitle={`${flowLabel(call?.flow ?? d.flow)}${call?.screen ? ` · called from screen ${call.screen}` : ""}`}
          actions={<button type="button" className="link" onClick={() => openSession(d.id)}>Full session</button>}>
      <div className="stack stack--sm">
        {apps.map((a) => <ApplicationPanel key={a.id} a={a} s={d} compact onChange={s.reload} />)}
        {apps.length === 0 && <SessionBody s={d} compact />}
      </div>
    </Card>
  );
}
