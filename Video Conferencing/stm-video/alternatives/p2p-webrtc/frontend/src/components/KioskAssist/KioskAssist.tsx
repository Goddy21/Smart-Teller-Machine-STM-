import { useEffect } from "react";
import { useKioskCall } from "../../hooks/useKioskCall";
import type { KioskDevices } from "../../services/webrtc/types";
import VideoCall from "../VideoCall/VideoCall";
import "./KioskAssist.css";

export interface KioskAssistLabels {
  button: string; notice: string; requesting: string; queued: (position?: number) => string; ringing: string;
  connecting: string; end: string; ok: string; ended: string; failed: string; noTeller: string;
}

const DEFAULT_LABELS: KioskAssistLabels = {
  button: "Speak to a teller",
  notice: "A teller will see and hear you only after they accept. Your camera and microphone stay off until then.",
  requesting: "Contacting a teller…",
  queued: p => (p && p > 1 ? `You are number ${p} in line. Please wait.` : "Please wait for the next available teller."),
  ringing: "A teller is being notified…",
  connecting: "Connecting you now…",
  end: "End call",
  ok: "OK",
  ended: "The call has ended.",
  failed: "We could not connect the call. Please try again.",
  noTeller: "No teller is available right now. Please try again shortly.",
};

interface Props {
  devices?: KioskDevices;
  txId?: string;
  /** Current kiosk screen. Mirrored to the teller; sensitive screens are masked by the hook. */
  fsm?: { name: string; model?: unknown };
  labels?: Partial<KioskAssistLabels>;
}

export default function KioskAssist({ devices, txId, fsm, labels }: Props) {
  const L = { ...DEFAULT_LABELS, ...labels };
  const { state, requestAssistance, end, reset, mirrorFsm } = useKioskCall(devices);
  useEffect(() => { if (fsm) mirrorFsm(fsm.name, fsm.model); }, [fsm, mirrorFsm]);

  const { phase } = state;
  if (phase === "idle") {
    return (
      <div className="ka">
        <button className="ka-primary" onClick={() => void requestAssistance(txId)}>{L.button}</button>
        <p className="ka-note">{L.notice}</p>
      </div>
    );
  }
  if (phase === "ended" || phase === "failed") {
    const text = state.reason === "NO_TELLER_AVAILABLE" ? L.noTeller : phase === "failed" ? L.failed : L.ended;
    return (
      <div className="ka" role="status">
        <p className="ka-status">{text}</p>
        <button className="ka-primary" onClick={reset}>{L.ok}</button>
      </div>
    );
  }
  if (phase === "connecting" || phase === "connected") {
    return (
      <div className="ka">
        <VideoCall localStream={state.localStream ?? null} remoteStream={state.tellerStream ?? null} />
        {phase === "connecting" && <p className="ka-status" role="status">{L.connecting}</p>}
        <button className="ka-danger" onClick={() => void end()}>{L.end}</button>
      </div>
    );
  }
  return (
    <div className="ka" role="status">
      <p className="ka-status">{phase === "requesting" ? L.requesting : phase === "ringing" ? L.ringing : L.queued(state.position)}</p>
      <button className="ka-secondary" onClick={() => void end()}>{L.end}</button>
    </div>
  );
}
