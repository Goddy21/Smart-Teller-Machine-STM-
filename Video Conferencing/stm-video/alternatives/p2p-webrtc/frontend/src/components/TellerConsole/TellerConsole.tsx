import { useEffect, useState } from "react";
import { useTellerConsole } from "../../hooks/useTellerConsole";
import type { FsmMessage } from "../../services/webrtc/types";
import { VideoTile } from "../VideoCall/VideoTile";
import "../VideoCall/VideoCall.css";
import "./TellerConsole.css";

function formatDuration(ms: number): string {
  const s = Math.max(0, Math.floor(ms / 1000));
  return `${String(Math.floor(s / 60)).padStart(2, "0")}:${String(s % 60).padStart(2, "0")}`;
}

function ScreenMirror({ fsm }: { fsm?: FsmMessage }) {
  if (!fsm) return <p className="tc-muted">No screen yet.</p>;
  if (fsm.masked) return <p className="tc-masked">The customer is on a private screen ({fsm.state}). It is hidden on purpose.</p>;
  const entries = fsm.model && typeof fsm.model === "object" ? Object.entries(fsm.model as Record<string, unknown>) : [];
  return (
    <dl className="tc-dl">
      <dt>Screen</dt><dd>{fsm.state}</dd>
      {entries.map(([k, v]) => (<span key={k} className="tc-row"><dt>{k}</dt><dd>{typeof v === "object" ? JSON.stringify(v) : String(v)}</dd></span>))}
    </dl>
  );
}

export default function TellerConsole() {
  const { state, setAvailable, accept, reject, end, toggleMic, toggleCam } = useTellerConsole();
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (!state.startedAt) return;
    const t = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(t);
  }, [state.startedAt]);

  const inCall = state.phase === "connecting" || state.phase === "connected";
  const status = state.socket !== "open" ? "Reconnecting to the call service…"
    : state.phase === "connected" ? `Connected to terminal ${state.terminalId ?? ""}`
    : state.phase === "connecting" ? "Connecting…"
    : state.available ? "Available for calls" : "Away";

  return (
    <div className="tc">
      <header className="tc-head">
        <h1>Teller console</h1>
        <span className="tc-status" role="status">{status}</span>
        <button disabled={inCall || state.socket !== "open"} aria-pressed={state.available} onClick={() => setAvailable(!state.available)}>
          {state.available ? "Set to away" : "Go available"}
        </button>
      </header>

      {state.notice && <p className="tc-notice" role="alert">{state.notice}</p>}

      <aside className="tc-left">
        {state.incoming && (
          <div className="tc-incoming" role="alertdialog" aria-live="assertive">
            <b>Incoming call</b>
            <p>Terminal {state.incoming.terminalId}{state.incoming.txId ? `, transaction ${state.incoming.txId}` : ""}</p>
            <div className="tc-actions">
              <button className="tc-primary" onClick={() => void accept()}>Accept</button>
              <button onClick={() => void reject()}>Reject</button>
            </div>
          </div>
        )}
        <h2>Waiting customers</h2>
        {state.queue.length === 0 ? <p className="tc-muted">No one is waiting.</p> : (
          <ul className="tc-queue">
            {state.queue.map(q => (
              <li key={q.callId}><span>Terminal {q.terminalId}</span><span className="tc-muted">{q.status === "RINGING" ? "ringing" : `${q.waitingSeconds}s`}</span></li>
            ))}
          </ul>
        )}
      </aside>

      <main className="tc-main">
        <div className="tc-videos">
          <VideoTile stream={state.face} label="Customer" emptyText={inCall ? "Waiting for the customer camera" : "No active call"} />
          <VideoTile stream={state.desk} label="Desk camera / ID" muted emptyText="No desk camera" />
        </div>
      </main>

      <aside className="tc-right">
        <h2>You</h2>
        <VideoTile stream={state.local} label="Your camera" muted mirror emptyText="Camera off" />
        <div className="tc-actions">
          <button disabled={!inCall} aria-pressed={!state.micOn} onClick={toggleMic}>{state.micOn ? "Mute mic" : "Unmute mic"}</button>
          <button disabled={!inCall} aria-pressed={!state.camOn} onClick={toggleCam}>{state.camOn ? "Camera off" : "Camera on"}</button>
        </div>
        <h2>Customer screen</h2>
        <ScreenMirror fsm={state.fsm} />
        <h2>Call</h2>
        <p className="tc-muted">
          {state.startedAt ? `Duration ${formatDuration(now - state.startedAt)}` : "Not connected"}
          {state.connection && state.connection !== "connected" ? ` (${state.connection})` : ""}
        </p>
        <button className="tc-danger" disabled={!inCall} onClick={() => void end()}>End call</button>
      </aside>
    </div>
  );
}
