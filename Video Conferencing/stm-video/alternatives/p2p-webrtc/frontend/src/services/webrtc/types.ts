export type Role = "kiosk" | "teller";

/** Mirrors the backend SignalingMessage record. */
export interface SignalingMessage {
  type: string;
  callId?: string;
  sdp?: RTCSessionDescriptionInit;
  candidate?: RTCIceCandidateInit;
  meta?: unknown;
  available?: boolean;
  reason?: string;
  position?: number;
  terminalId?: string;
  txId?: string;
}

export interface CallResponse { callId: string; status: string; queuePosition: number }
export interface IceConfig { iceServers: RTCIceServer[]; expiresAt: number }
export interface QueueRow { callId: string; terminalId: string; status: string; waitingSeconds: number }

/** What the kiosk sends the teller over the peer-to-peer data channel (never through the server). */
export interface FsmMessage { v: 1; state: string; masked?: boolean; model?: unknown }

export interface KioskDevices { audio?: string; face?: string; desk?: string }
