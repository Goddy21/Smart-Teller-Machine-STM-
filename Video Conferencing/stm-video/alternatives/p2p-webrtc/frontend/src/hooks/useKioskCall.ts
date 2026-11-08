import { useCallback, useEffect, useRef, useState } from "react";
import { callApi } from "../services/webrtc/api";
import { signalingUrl } from "../services/webrtc/config";
import { SignalingClient } from "../services/webrtc/SignalingClient";
import type { FsmMessage, KioskDevices, SignalingMessage } from "../services/webrtc/types";
import { WebRTCService } from "../services/webrtc/WebRTCService";

export type KioskPhase = "idle" | "requesting" | "queued" | "ringing" | "connecting" | "connected" | "ended" | "failed";

export interface KioskCallState {
  phase: KioskPhase;
  callId?: string;
  position?: number;
  reason?: string;
  tellerStream?: MediaStream;
  localStream?: MediaStream;
}

/** Screens that must never be mirrored to a teller. Add every screen that shows a PIN, card number or credentials. */
const MASKED_SCREENS = new Set(["PIN_ENTRY", "CARD_DATA_ENTRY"]);

export function useKioskCall(devices?: KioskDevices) {
  const [state, setState] = useState<KioskCallState>({ phase: "idle" });
  const sig = useRef<SignalingClient | null>(null);
  const svc = useRef<WebRTCService | null>(null);
  const callId = useRef<string | undefined>(undefined);
  const lastFsm = useRef<FsmMessage | null>(null);
  const dev = useRef(devices);
  dev.current = devices;

  const teardown = useCallback((phase: "ended" | "failed", reason?: string) => {
    svc.current?.close();
    svc.current = null;
    callId.current = undefined;
    setState({ phase, reason });
  }, []);

  useEffect(() => {
    const client = new SignalingClient(signalingUrl("kiosk"));
    sig.current = client;
    let chain: Promise<void> = Promise.resolve();   // handle messages strictly in order (offer before candidates)
    const send = (m: SignalingMessage) => client.send({ ...m, callId: callId.current });

    // The teller has accepted: only now are the camera and microphone opened.
    const beginMedia = async () => {
      setState(s => ({ ...s, phase: "connecting" }));
      try {
        const ice = await callApi.turnCredentials(callId.current as string);
        const service: WebRTCService = new WebRTCService({
          role: "kiosk",
          iceServers: ice.iceServers,
          sendSignal: send,
          onRemoteStream: (_role, stream) => setState(s => ({ ...s, tellerStream: stream })),
          onConnectionState: st => {
            if (st === "connected") { send({ type: "MEDIA_CONNECTED" }); setState(s => ({ ...s, phase: "connected" })); }
            if (st === "failed") send({ type: "MEDIA_FAILED", reason: "ICE_FAILED" });
          },
          onDataChannelOpen: () => { if (lastFsm.current) service.sendData(lastFsm.current); },
        });
        svc.current = service;
        const media = await service.startKioskMedia(dev.current);
        setState(s => ({ ...s, localStream: media.face }));
        await service.createOffer();
      } catch (e) {
        send({ type: "MEDIA_FAILED", reason: "NO_MEDIA" });
        teardown("failed", e instanceof Error ? e.message : "Could not start the camera or microphone");
      }
    };

    const handle = async (m: SignalingMessage) => {
      if (m.type === "CALL_QUEUED" && m.callId && !callId.current) callId.current = m.callId;   // can beat the REST reply
      if (m.callId && callId.current && m.callId !== callId.current) return;
      switch (m.type) {
        case "CALL_QUEUED": setState(s => ({ ...s, phase: "queued", position: m.position })); break;
        case "CALL_RINGING": setState(s => (s.phase === "queued" || s.phase === "requesting" ? { ...s, phase: "ringing" } : s)); break;
        case "CALL_ACCEPTED": await beginMedia(); break;
        case "CALL_ANSWER": if (m.sdp) await svc.current?.receiveAnswer(m.sdp); break;
        case "ICE_CANDIDATE": if (m.candidate) await svc.current?.receiveCandidate(m.candidate); break;
        case "CALL_ENDED": teardown("ended", m.reason); break;
        case "CALL_FAILED":
        case "CALL_REJECTED": teardown("failed", m.reason); break;
        case "ERROR": setState(s => ({ ...s, reason: m.reason })); break;
        default: break;
      }
    };

    const off = client.on(m => { chain = chain.then(() => handle(m)).catch(err => console.error("signaling handler", err)); });
    client.connect();
    return () => { off(); client.close(); svc.current?.close(); svc.current = null; };
  }, [teardown]);

  const requestAssistance = useCallback(async (txId?: string) => {
    setState({ phase: "requesting" });
    try {
      const r = await callApi.request(txId);
      callId.current = r.callId;
      setState(s => (s.phase === "requesting"
        ? { phase: r.status === "RINGING" ? "ringing" : "queued", callId: r.callId, position: r.queuePosition }
        : { ...s, callId: r.callId }));
    } catch (e) {
      setState({ phase: "failed", reason: e instanceof Error ? e.message : "Request failed" });
    }
  }, []);

  const end = useCallback(async () => {
    const id = callId.current;
    if (id) { try { await callApi.end(id); } catch { /* already over */ } }
    teardown("ended", "USER_ENDED");
  }, [teardown]);

  const reset = useCallback(() => setState({ phase: "idle" }), []);

  /** Send the current kiosk screen to the teller over the peer-to-peer data channel. Sensitive screens are replaced. */
  const mirrorFsm = useCallback((screen: string, model?: unknown) => {
    const msg: FsmMessage = MASKED_SCREENS.has(screen) ? { v: 1, state: screen, masked: true } : { v: 1, state: screen, model };
    lastFsm.current = msg;
    svc.current?.sendData(msg);
  }, []);

  return { state, requestAssistance, end, reset, mirrorFsm };
}
