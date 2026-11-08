import type { KioskDevices, SignalingMessage } from "./types";

export type StreamRole = "face" | "desk" | "teller" | "remote";

export interface WebRTCOptions {
  role: "kiosk" | "teller";
  iceServers: RTCIceServer[];
  sendSignal: (m: SignalingMessage) => void;
  onRemoteStream: (role: StreamRole, stream: MediaStream) => void;
  onConnectionState: (s: RTCPeerConnectionState) => void;
  onData?: (label: string, data: unknown) => void;
  onDataChannelOpen?: () => void;
}

const AUDIO: MediaTrackConstraints = { echoCancellation: true, noiseSuppression: true, autoGainControl: true };
type HintedTrack = MediaStreamTrack & { contentHint?: string };

/** One peer connection. The kiosk offers (after the teller accepts), the teller answers.
 *  The kiosk sends two video streams (face, desk) plus a data channel for the screen mirror; the teller sends audio + video. */
export class WebRTCService {
  private peer: RTCPeerConnection;
  private pending: RTCIceCandidateInit[] = [];
  private remoteReady = false;
  private roles = new Map<string, StreamRole>();
  private announced = new Set<string>();
  private local: MediaStream[] = [];
  private channel: RTCDataChannel | null = null;
  private meta: unknown;
  private restartTimer: number | undefined;
  private restarted = false;
  private closed = false;

  constructor(private o: WebRTCOptions) {
    this.peer = new RTCPeerConnection({ iceServers: o.iceServers });

    this.peer.ontrack = ev => {
      const stream = ev.streams[0];
      if (!stream || this.announced.has(stream.id)) return;   // the stream object keeps receiving its other tracks
      this.announced.add(stream.id);
      this.o.onRemoteStream(this.roles.get(stream.id) ?? (o.role === "kiosk" ? "teller" : "remote"), stream);
    };

    this.peer.onicecandidate = ev => {
      if (ev.candidate) this.o.sendSignal({ type: "ICE_CANDIDATE", candidate: ev.candidate.toJSON() });
    };

    this.peer.ondatachannel = ev => { this.channel = ev.channel; this.wire(ev.channel); };

    this.peer.onconnectionstatechange = () => {
      const s = this.peer.connectionState;
      window.clearTimeout(this.restartTimer);
      if (s === "disconnected") this.restartTimer = window.setTimeout(() => this.tryRestart(), 8_000);
      if (s === "failed" && this.o.role === "kiosk" && !this.restarted) { this.tryRestart(); return; }
      this.o.onConnectionState(s);
    };
  }

  // ------------------------------------------------------------------ local media
  /** Face camera + microphone in one stream, desk camera in another. Camera contention with the eCAT daemon is the
   *  integration risk: ask GRG which process owns each device before relying on this. */
  async startKioskMedia(dev: KioskDevices = {}): Promise<{ face: MediaStream; desk?: MediaStream }> {
    const face = await navigator.mediaDevices.getUserMedia({
      audio: { ...AUDIO, ...(dev.audio ? { deviceId: { exact: dev.audio } } : {}) },
      video: { ...(dev.face ? { deviceId: { exact: dev.face } } : {}), width: { ideal: 1280 }, height: { ideal: 720 }, frameRate: { ideal: 24 } },
    });
    this.add(face);
    const streams: Record<string, string> = { face: face.id };

    let desk: MediaStream | undefined;
    if (dev.desk) {
      try {
        desk = await navigator.mediaDevices.getUserMedia({
          video: { deviceId: { exact: dev.desk }, width: { ideal: 1920 }, height: { ideal: 1080 }, frameRate: { ideal: 15 } },
        });
        desk.getVideoTracks().forEach(t => { (t as HintedTrack).contentHint = "detail"; });   // sharpness over frame rate for IDs
        this.add(desk);
        streams.desk = desk.id;
      } catch { desk = undefined; }   // the desk camera is optional: the call goes ahead without it
    }

    this.channel = this.peer.createDataChannel("fsm", { ordered: true });
    this.wire(this.channel);
    this.meta = { streams };   // tells the teller which incoming stream is which
    return { face, desk };
  }

  /** Microphone and a modest camera. Falls back to audio only; a missing microphone is an error. */
  async startTellerMedia(dev: { audio?: string; video?: string } = {}): Promise<MediaStream> {
    const audio = { ...AUDIO, ...(dev.audio ? { deviceId: { exact: dev.audio } } : {}) };
    let stream: MediaStream;
    try {
      stream = await navigator.mediaDevices.getUserMedia({
        audio,
        video: { ...(dev.video ? { deviceId: { exact: dev.video } } : {}), width: { ideal: 640 }, height: { ideal: 360 }, frameRate: { ideal: 24 } },
      });
    } catch {
      stream = await navigator.mediaDevices.getUserMedia({ audio });
    }
    this.add(stream);
    return stream;
  }

  private add(stream: MediaStream): void {
    this.local.push(stream);
    stream.getTracks().forEach(t => this.peer.addTrack(t, stream));
  }

  setAudioEnabled(on: boolean): void { this.local.forEach(s => s.getAudioTracks().forEach(t => { t.enabled = on; })); }
  setVideoEnabled(on: boolean): void { this.local[0]?.getVideoTracks().forEach(t => { t.enabled = on; }); }

  // ------------------------------------------------------------------ negotiation
  async createOffer(options?: RTCOfferOptions): Promise<void> {
    const offer = await this.peer.createOffer(options);
    await this.peer.setLocalDescription(offer);
    const d = this.peer.localDescription;
    if (d) this.o.sendSignal({ type: "CALL_OFFER", sdp: { type: d.type, sdp: d.sdp }, meta: this.meta });
  }

  async receiveOffer(sdp: RTCSessionDescriptionInit, meta?: unknown): Promise<void> {
    const streams = (meta as { streams?: Record<string, string> } | undefined)?.streams;
    if (streams) for (const [role, id] of Object.entries(streams)) this.roles.set(id, role as StreamRole);
    await this.peer.setRemoteDescription(sdp);
    this.remoteReady = true;
    await this.flush();
    const answer = await this.peer.createAnswer();
    await this.peer.setLocalDescription(answer);
    const d = this.peer.localDescription;
    if (d) this.o.sendSignal({ type: "CALL_ANSWER", sdp: { type: d.type, sdp: d.sdp } });
  }

  async receiveAnswer(sdp: RTCSessionDescriptionInit): Promise<void> {
    await this.peer.setRemoteDescription(sdp);
    this.remoteReady = true;
    await this.flush();
  }

  /** Candidates can arrive before the remote description: hold them until it is set. */
  async receiveCandidate(c: RTCIceCandidateInit): Promise<void> {
    if (!this.remoteReady) { this.pending.push(c); return; }
    try { await this.peer.addIceCandidate(c); } catch { /* stale candidate after a restart */ }
  }

  private async flush(): Promise<void> {
    const queued = this.pending.splice(0);
    for (const c of queued) { try { await this.peer.addIceCandidate(c); } catch { /* ignore */ } }
  }

  private tryRestart(): void {
    if (this.closed || this.o.role !== "kiosk") return;
    if (this.restarted) { this.o.onConnectionState("failed"); return; }
    this.restarted = true;   // one automatic ICE restart, then report failure
    void this.createOffer({ iceRestart: true });
  }

  // ------------------------------------------------------------------ data channel
  private wire(ch: RTCDataChannel): void {
    ch.onopen = () => this.o.onDataChannelOpen?.();
    ch.onmessage = ev => {
      try { this.o.onData?.(ch.label, JSON.parse(ev.data as string)); } catch { /* ignore malformed */ }
    };
  }

  sendData(obj: unknown): boolean {
    if (this.channel?.readyState !== "open") return false;
    this.channel.send(JSON.stringify(obj));
    return true;
  }

  close(): void {
    this.closed = true;
    window.clearTimeout(this.restartTimer);
    this.local.forEach(s => s.getTracks().forEach(t => t.stop()));
    this.channel?.close();
    this.peer.close();
  }
}
