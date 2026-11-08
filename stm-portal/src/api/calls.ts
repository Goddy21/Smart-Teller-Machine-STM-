import { request, readSse } from "./http";

/**
 * Client for call-control (the stm-video service). Identity travels in X-Teller-Id / X-Auditor-Id, which in
 * production the gateway sets from the teller's SSO session.
 */

export interface RingEvent { callId: string; terminalId: string; lang: string; txId: string }
export interface CancelledEvent { callId: string; reason: string }
export interface EndedEvent { callId: string; reason: string }
export interface AcceptResult { url: string; token: string; room: string; txId: string; terminalId: string }
export interface Heartbeat { available: boolean; present: boolean; stream: boolean }

export interface CallEvent { call_id?: string; seq: number; type: string; at: string; payload: string; entry_hash?: string }
export interface ChainResult { ok: boolean; events: number; firstBadSeq?: number }
export interface AuditCall {
  call_id: string;
  tx_id: string;
  terminal_id: string;
  teller_id: string | null;
  lang: string | null;
  requested_at: string;
  connected_at: string | null;
  ended_at: string | null;
  final_state: string | null;
  chain: ChainResult;
  events: CallEvent[];
}
export interface Recording {
  track_sid: string;
  call_id: string;
  participant: string;
  track_name: string;
  status: string;
  object_key: string | null;
  size_bytes: number | null;
  duration_ms: number | null;
  sha256: string | null;
  started_at: string;
  hashed_at: string | null;
  retention_until: string;
}
export interface TransactionEvidence { transaction: string; calls: AuditCall[]; recordings: Recording[] }

const BASE = "/api";

export class CallsApi {
  private readonly tellerId: string;

  constructor(tellerId: string) {
    this.tellerId = tellerId;
  }

  private h(): Record<string, string> {
    return { "X-Teller-Id": this.tellerId };
  }

  setPresence(available: boolean, langs: string[]) {
    return request<void>(`${BASE}/tellers/me/presence`, { method: "PUT", body: { available, langs }, headers: this.h() });
  }
  heartbeat() { return request<Heartbeat>(`${BASE}/tellers/me/heartbeat`, { method: "POST", headers: this.h() }); }
  events(handlers: { ring: (e: RingEvent) => void; cancelled: (e: CancelledEvent) => void; ended: (e: EndedEvent) => void },
         signal: AbortSignal, onOpen: () => void, onData: () => void) {
    return readSse(`${BASE}/tellers/me/events`, this.h(), {
      ring: (d) => handlers.ring(d as RingEvent),
      cancelled: (d) => handlers.cancelled(d as CancelledEvent),
      ended: (d) => handlers.ended(d as EndedEvent),
    }, signal, onOpen, onData);
  }
  accept(callId: string) { return request<AcceptResult>(`${BASE}/calls/${enc(callId)}/accept`, { method: "POST", headers: this.h() }); }
  decline(callId: string) { return request<void>(`${BASE}/calls/${enc(callId)}/decline`, { method: "POST", headers: this.h() }); }
  end(callId: string) { return request<void>(`${BASE}/calls/${enc(callId)}/end`, { method: "POST", headers: this.h() }); }
  decide(callId: string, approve: boolean, amount: number) {
    return request<{ status: string }>(`${BASE}/calls/${enc(callId)}/decision`, { method: "POST", body: { approve, amount }, headers: this.h() });
  }

  // ---- Audit & Compliance Explorer (X-Auditor-Id). Every access is written to the call's hash chain.
  private a(): Record<string, string> {
    return { "X-Auditor-Id": this.tellerId };
  }
  evidence(txId: string) { return request<TransactionEvidence>(`${BASE}/audit/transactions/${enc(txId)}`, { headers: this.a() }); }
  downloadUrl(trackSid: string) {
    return request<{ url: string; sha256: string; expiresInSeconds: number }>(`${BASE}/audit/recordings/${enc(trackSid)}/download-url`, { headers: this.a() });
  }
  verifyRecording(trackSid: string) {
    return request<{ match: boolean; reason?: string; expected?: string; actual?: string }>(`${BASE}/audit/recordings/${enc(trackSid)}/verify`, { method: "POST", headers: this.a() });
  }
}

function enc(s: string) {
  return encodeURIComponent(s);
}
