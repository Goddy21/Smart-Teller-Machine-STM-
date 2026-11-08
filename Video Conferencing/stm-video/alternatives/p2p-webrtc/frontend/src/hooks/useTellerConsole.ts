import { useCallback, useEffect, useRef, useState } from "react";
import { callApi } from "../services/webrtc/api";
import { signalingUrl } from "../services/webrtc/config";
import { SignalingClient, type SocketStatus } from "../services/webrtc/SignalingClient";
import type { FsmMessage, QueueRow, SignalingMessage } from "../services/webrtc/types";
import { WebRTCService } from "../services/webrtc/WebRTCService";

export type TellerPhase = "idle" | "incoming" | "connecting" | "connected" | "ended" | "failed";
export interface IncomingCall { callId: string; terminalId: string; txId?: string }

export interface TellerState {
  socket: SocketStatus;
  available: boolean;
  phase: TellerPhase;
  incoming?: IncomingCall;
  callId?: string;
  terminalId?: string;
  face?: MediaStream;
  desk?: MediaStream;
  local?: MediaStream;
  fsm?: FsmMessage;
  connection?: RTCPeerConnectionState;
  micOn: boolean;
  camOn: boolean;
  startedAt?: number;
  queue: QueueRow[];
  notice?: string;
}

const INITIAL: TellerState = { socket: "connecting", available: false, phase: "idle", micOn: true, camOn: true, queue: [] };

const REASONS: Record<string, string> = {
  USER_ENDED: "The customer ended the call.",
  KIOSK_DISCONNECTED: "The terminal lost its connection.",
  MEDIA_FAILED: "The video connection failed.",
  TELLER_DISCONNECTED: "You were disconnected.",
};
const reasonText = (r?: string) => (r && REASONS[r]) || "The call ended.";

export function useTellerConsole(devices?: { audio?: string; video?: string }) {
  const [state, setState] = useState<TellerState>(INITIAL);
  const sig = useRef<SignalingClient | null>(null);
  const svc = useRef<WebRTCService | null>(null);
  const callId = useRef<string | undefined>(undefined);
  const wantAvailable = useRef(false);
  const incomingRef = useRef<IncomingCall | undefined>(undefined);
  incomingRef.current = state.incoming;
  const dev = useRef(devices);
  dev.current = devices;

  const cleanup = useCallback((phase: "ended" | "failed", notice?: string) => {
    svc.current?.close();
    svc.current = null;
    callId.current = undefined;
    setState(s => ({
      ...s, phase, notice, incoming: undefined, callId: undefined, terminalId: undefined, face: undefined, desk: undefined,
      local: undefined, fsm: undefined, connection: undefined, startedAt: undefined, micOn: true, camOn: true,
    }));
  }, []);

  useEffect(() => {
    const client = new SignalingClient(signalingUrl("teller"), status => {
      setState(s => ({ ...s, socket: status }));
      if (status === "open" && wantAvailable.current) client.send({ type: "PRESENCE", available: true });   // after a reconnect
    });
    sig.current = client;
    let chain: Promise<void> = Promise.resolve();

    const onOffer = async (m: SignalingMessage) => {
      const id = m.callId as string;
      callId.current = id;
      try {
        if (!svc.current) {
          const ice = await callApi.turnCredentials(id);
          const service = new WebRTCService({
            role: "teller",
            iceServers: ice.iceServers,
            sendSignal: x => client.send({ ...x, callId: id }),
            onRemoteStream: (role, stream) => setState(s => (role === "desk" ? { ...s, desk: stream } : { ...s, face: stream })),
            onConnectionState: st => {
              setState(s => ({
                ...s, connection: st,
                phase: st === "connected" ? "connected" : s.phase,
                startedAt: st === "connected" ? (s.startedAt ?? Date.now()) : s.startedAt,
              }));
              if (st === "connected") client.send({ type: "MEDIA_CONNECTED", callId: id });
            },
            onData: (label, data) => { if (label === "fsm") setState(s => ({ ...s, fsm: data as FsmMessage })); },
          });
          svc.current = service;
          const local = await service.startTellerMedia(dev.current);
          setState(s => ({ ...s, local, camOn: local.getVideoTracks().length > 0 }));
        }
        await svc.current.receiveOffer(m.sdp as RTCSessionDescriptionInit, m.meta);
      } catch (e) {
        client.send({ type: "MEDIA_FAILED", callId: id, reason: "NO_MEDIA" });
        cleanup("failed", e instanceof Error ? "Could not start your microphone: " + e.message : "Could not start the call.");
      }
    };

    const handle = async (m: SignalingMessage) => {
      switch (m.type) {
        case "CALL_INCOMING":
          if (m.callId) setState(s => ({ ...s, phase: "incoming", notice: undefined, incoming: { callId: m.callId as string, terminalId: m.terminalId ?? "", txId: m.txId } }));
          break;
        case "CALL_OFFER": await onOffer(m); break;
        case "ICE_CANDIDATE": if (m.candidate) await svc.current?.receiveCandidate(m.candidate); break;
        case "CALL_ENDED":
          if (m.reason === "RING_TIMEOUT") {   // the server has marked this teller away
            wantAvailable.current = false;
            setState(s => ({ ...s, available: false, phase: "idle", incoming: undefined, notice: "You missed a call and were set to away." }));
          } else if (callId.current && callId.current === m.callId) {
            cleanup("ended", reasonText(m.reason));
          } else {
            setState(s => (s.incoming?.callId === m.callId ? { ...s, incoming: undefined, phase: "idle", notice: "The customer cancelled before you answered." } : s));
          }
          break;
        case "CALL_FAILED": cleanup("failed", reasonText(m.reason)); break;
        case "ERROR": setState(s => ({ ...s, notice: m.reason })); break;
        default: break;
      }
    };

    const off = client.on(m => { chain = chain.then(() => handle(m)).catch(err => console.error("signaling handler", err)); });
    client.connect();
    return () => { off(); client.close(); svc.current?.close(); svc.current = null; };
  }, [cleanup]);

  // Queue list for the console, refreshed while available.
  useEffect(() => {
    if (!state.available || state.socket !== "open") return;
    const poll = () => callApi.queue().then(queue => setState(s => ({ ...s, queue }))).catch(() => undefined);
    poll();
    const t = window.setInterval(poll, 3_000);
    return () => window.clearInterval(t);
  }, [state.available, state.socket]);

  const setAvailable = useCallback((on: boolean) => {
    wantAvailable.current = on;
    sig.current?.send({ type: "PRESENCE", available: on });
    setState(s => ({ ...s, available: on, notice: undefined }));
  }, []);

  const accept = useCallback(async () => {
    const inc = incomingRef.current;
    if (!inc) return;
    try {
      await callApi.accept(inc.callId);
      callId.current = inc.callId;
      setState(s => ({ ...s, phase: "connecting", callId: inc.callId, terminalId: inc.terminalId, incoming: undefined }));
    } catch (e) {
      setState(s => ({ ...s, incoming: undefined, phase: "idle", notice: e instanceof Error ? e.message : "Could not accept the call" }));
    }
  }, []);

  const reject = useCallback(async () => {
    const inc = incomingRef.current;
    if (!inc) return;
    setState(s => ({ ...s, incoming: undefined, phase: "idle" }));
    try { await callApi.reject(inc.callId); } catch { /* it may have already timed out */ }
  }, []);

  const end = useCallback(async () => {
    const id = callId.current;
    if (id) { try { await callApi.end(id); } catch { /* already over */ } }
    cleanup("ended", "You ended the call.");
  }, [cleanup]);

  const toggleMic = useCallback(() => setState(s => { svc.current?.setAudioEnabled(!s.micOn); return { ...s, micOn: !s.micOn }; }), []);
  const toggleCam = useCallback(() => setState(s => { svc.current?.setVideoEnabled(!s.camOn); return { ...s, camOn: !s.camOn }; }), []);

  return { state, setAvailable, accept, reject, end, toggleMic, toggleCam };
}
