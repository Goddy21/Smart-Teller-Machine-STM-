import {
  Room, RoomEvent, Track, createLocalAudioTrack, createLocalVideoTrack,
  type LocalAudioTrack, type LocalVideoTrack, type RemoteTrack, type RemoteTrackPublication, type TrackPublication,
} from "livekit-client";
import { CallsApi, type AcceptResult, type CancelledEvent, type EndedEvent, type RingEvent } from "../api/calls";

/**
 * Teller side of a video call, ported from stm-video/teller-console/index.html (same protocol, same timings).
 *
 * It lives outside React on purpose: one instance per signed-in teller, so a call keeps running while the teller
 * looks at other portal pages. React subscribes to its state (see useTellerConsole).
 *
 * Flow: go available (event stream first, then presence) -> "ring" event -> accept -> join the LiveKit room ->
 * subscribe to face / id-cam / kiosk-screen / mic, publish teller mic + camera -> decide -> end.
 *
 * The camera MUST be published as "teller-cam": the kiosk's lower screen and its upper "teller display"
 * (kiosk-client/teller-display.html, stm-video v10) both subscribe to that name. The display joins as a hidden,
 * receive-only participant, so it never appears here and never counts as the customer.
 */

export const RING_SECONDS = 22;          // how long the service rings one teller
const HEARTBEAT_MS = 20_000;             // presence expires after 60 s without a heartbeat
const STREAM_SILENCE_MS = 45_000;        // no bytes (not even a ping) for this long = dead stream
const KIOSK_JOIN_WAIT_MS = 15_000;

export type Pane = "face" | "id" | "screen" | "self";

export interface Mirror { state: string; masked?: boolean; model?: Record<string, unknown> }

export interface ConsoleState {
  available: boolean;
  changingPresence: boolean;
  ring: (RingEvent & { left: number }) | null;
  call: { id: string; txId: string; terminalId: string; lang?: string; since: number } | null;
  joined: boolean;
  customerPresent: boolean;
  banner: string;
  notice: { text: string; tone: "error" | "info" | "ok" } | null;
  mirror: Mirror | null;
  panes: Record<Pane, boolean>;
  screenHidden: boolean;
  mic: "none" | "on" | "muted";
  cam: "none" | "on" | "off";
  decision: "approved" | "rejected" | null;
  snapshotRequested: boolean;
  missedCalls: number;
}

const initial: ConsoleState = {
  available: false, changingPresence: false, ring: null, call: null, joined: false, customerPresent: false,
  banner: "", notice: null, mirror: null, panes: { face: false, id: false, screen: false, self: false },
  screenHidden: false, mic: "none", cam: "none", decision: null, snapshotRequested: false, missedCalls: 0,
};

export class TellerConsole {
  readonly api: CallsApi;
  private langs: string[];
  private state: ConsoleState = initial;
  private listeners = new Set<() => void>();

  private room: Room | null = null;
  private mic: LocalAudioTrack | null = null;
  private cam: LocalVideoTrack | null = null;
  private els: Partial<Record<Pane, HTMLVideoElement>> = {};
  private audioEls: HTMLMediaElement[] = [];

  private streamCtl: AbortController | null = null;
  private streaming = false;
  private lastData = 0;
  private hbTimer: ReturnType<typeof setInterval> | undefined;
  private ringTimer: ReturnType<typeof setInterval> | undefined;
  private joinTimer: ReturnType<typeof setTimeout> | undefined;

  constructor(tellerId: string, langs: string[]) {
    this.api = new CallsApi(tellerId);
    this.langs = langs;
  }

  // ------------------------------------------------------------------ React plumbing
  subscribe = (fn: () => void) => { this.listeners.add(fn); return () => { this.listeners.delete(fn); }; };
  getState = () => this.state;
  private set(patch: Partial<ConsoleState>) {
    this.state = { ...this.state, ...patch };
    this.listeners.forEach((l) => l());
  }
  private say(text: string, tone: "error" | "info" | "ok" = "error") { this.set({ notice: text ? { text, tone } : null }); }
  clearNotice() { this.set({ notice: null }); }

  /** The video page hands its <video> elements over; tracks that arrived earlier are re-attached. */
  attach(pane: Pane, el: HTMLVideoElement | null) {
    if (!el) { delete this.els[pane]; return; }
    this.els[pane] = el;
    if (pane === "self" && this.cam) this.cam.attach(el);
    if (this.room && pane !== "self") {
      for (const p of this.room.remoteParticipants.values()) {
        for (const pub of p.trackPublications.values()) {
          if (pub.track && pub.kind === Track.Kind.Video && paneFor(pub) === pane) pub.track.attach(el);
        }
      }
    }
  }

  setLangs(langs: string[]) { this.langs = langs; }

  // ------------------------------------------------------------------ availability
  async goAvailable() {
    if (this.state.available || this.state.changingPresence) return;
    this.set({ changingPresence: true, available: true });
    try {
      // Open the event stream FIRST, then announce availability: a call can ring the moment we are available.
      await new Promise<void>((ok, fail) => {
        const t = setTimeout(() => fail(new Error("the event stream did not open")), 8000);
        void this.openStream(() => { clearTimeout(t); ok(); });
      });
      await this.api.setPresence(true, this.langs);
      clearInterval(this.hbTimer);
      this.hbTimer = setInterval(() => void this.heartbeat(), HEARTBEAT_MS);
      this.say("");
    } catch (e) {
      this.set({ available: false });
      this.streamCtl?.abort();
      this.say(`Could not go available: ${(e as Error).message}`);
    } finally {
      this.set({ changingPresence: false });
    }
  }

  async goAway() {
    if (!this.state.available || this.state.changingPresence) return;
    this.set({ changingPresence: true });
    try {
      await this.api.setPresence(false, this.langs);
    } catch (e) {
      this.say(`The service did not confirm: ${(e as Error).message}`);
    } finally {
      this.set({ available: false, changingPresence: false });
      clearInterval(this.hbTimer);
      this.streamCtl?.abort();
    }
  }

  /** The service set this teller away (missed or declined a ring, or presence expired). */
  private goneAway(message: string) {
    this.set({ available: false });
    clearInterval(this.hbTimer);
    this.streamCtl?.abort();
    this.say(message);
  }

  private async heartbeat() {
    let h;
    try { h = await this.api.heartbeat(); } catch { this.say("Cannot reach the call service. Retrying…"); return; }
    if (!this.state.available) return;
    if (h && h.present === false) {
      try { await this.api.setPresence(true, this.langs); } catch { /* next heartbeat retries */ }
    } else if (h && h.available === false && !this.state.call && !this.room && !this.state.ring) {
      this.goneAway("You missed a call, so the service set you to away. Go available when you are ready.");
      return;
    }
    if ((h && h.stream === false) || Date.now() - this.lastData > STREAM_SILENCE_MS) this.streamCtl?.abort();
  }

  private async openStream(onFirstOpen?: () => void) {
    if (this.streaming) { onFirstOpen?.(); return; }
    this.streaming = true;
    let first = onFirstOpen;
    try {
      while (this.state.available) {
        this.streamCtl = new AbortController();   // a fresh controller per attempt
        try {
          await this.api.events(
            { ring: (e) => this.onRing(e), cancelled: (e) => this.onCancelled(e), ended: (e) => void this.onEnded(e) },
            this.streamCtl.signal,
            () => {
              this.lastData = Date.now();
              if (first) { first(); first = undefined; } else this.say("Reconnected to the call service.", "info");
            },
            () => { this.lastData = Date.now(); },
          );
        } catch {
          if (!this.state.available) return;
          this.say("Lost the connection to the call service. Reconnecting…");
        }
        if (this.state.available) await new Promise((r) => setTimeout(r, 2000));
      }
    } finally {
      this.streaming = false;
    }
  }

  // ------------------------------------------------------------------ ringing
  private onRing(e: RingEvent) {
    clearInterval(this.ringTimer);
    this.set({ ring: { ...e, left: RING_SECONDS } });
    this.ringTimer = setInterval(() => {
      const r = this.state.ring;
      if (!r) return clearInterval(this.ringTimer);
      if (r.left <= 1) this.hideRing(); else this.set({ ring: { ...r, left: r.left - 1 } });
    }, 1000);
  }

  private hideRing() {
    clearInterval(this.ringTimer);
    this.set({ ring: null });
  }

  private onCancelled(e: CancelledEvent) {
    this.hideRing();
    if (e.reason === "timeout") {
      this.set({ missedCalls: this.state.missedCalls + 1 });
      this.goneAway("You missed a call, so the service set you to away. The customer is still waiting in the queue.");
    } else if (e.reason === "declined") {
      this.goneAway("You declined the call and are set to away. Go available for the next one.");
    } else if (e.reason) {
      this.say("The customer cancelled the call.", "info");
    }
  }

  async decline() {
    const r = this.state.ring;
    if (!r) return;
    this.hideRing();
    try { await this.api.decline(r.callId); } catch (e) { this.say((e as Error).message); }
  }

  async accept() {
    const r = this.state.ring;
    if (!r) return;
    this.hideRing();
    this.set({ call: { id: r.callId, txId: r.txId, terminalId: r.terminalId, lang: r.lang, since: Date.now() } });
    try {
      const j = await this.api.accept(r.callId);
      await this.joinRoom(j);
    } catch (e) {
      this.say(`Could not accept the call: ${(e as Error).message}`);
      await this.cleanup("");
    }
  }

  // ------------------------------------------------------------------ media
  private async joinRoom({ url, token }: AcceptResult) {
    const room = new Room({ adaptiveStream: true });
    this.room = room;

    room.on(RoomEvent.TrackSubscribed, (track: RemoteTrack, pub: RemoteTrackPublication) => {
      if (track.kind === Track.Kind.Audio) {
        const a = track.attach();
        a.style.display = "none";
        document.body.appendChild(a);
        this.audioEls.push(a);
        return;
      }
      const pane = paneFor(pub);
      const el = this.els[pane];
      if (el) track.attach(el);
      this.setPane(pane, !pub.isMuted);
    });
    room.on(RoomEvent.TrackUnsubscribed, (track: RemoteTrack, pub: RemoteTrackPublication) => {
      track.detach();
      if (pub.kind === Track.Kind.Video) this.setPane(paneFor(pub), false);
    });
    // The kiosk hides its live screen on PIN / card screens: say why instead of showing a frozen picture.
    room.on(RoomEvent.TrackMuted, (pub: TrackPublication) => {
      if (pub.trackName === "kiosk-screen") { this.setPane("screen", false); this.set({ screenHidden: true }); }
    });
    room.on(RoomEvent.TrackUnmuted, (pub: TrackPublication) => {
      if (pub.trackName === "kiosk-screen") { this.setPane("screen", true); this.set({ screenHidden: false }); }
    });
    room.on(RoomEvent.DataReceived, (payload: Uint8Array, _p, _k, topic?: string) => {
      if (topic !== "fsm") return;
      try { this.renderMirror(JSON.parse(new TextDecoder().decode(payload)) as Mirror); } catch { /* ignore */ }
    });
    room.on(RoomEvent.ParticipantDisconnected, () => {
      this.set({ customerPresent: false,
        banner: "The customer disconnected. The service is holding the transaction. Wait for them to reconnect before deciding." });
    });
    room.on(RoomEvent.ParticipantConnected, () => {
      clearTimeout(this.joinTimer);
      this.set({ customerPresent: true, banner: "" });
    });
    room.on(RoomEvent.Disconnected, () => {
      if (this.room) this.say("You were disconnected from the call. Rejoin is not available yet: end the call and contact IT.");
    });

    await room.connect(url, token);
    const lp = room.localParticipant;
    try {   // the customer must hear the teller, and the recording needs a teller track
      this.mic = await createLocalAudioTrack({ echoCancellation: true, noiseSuppression: true });
      await lp.publishTrack(this.mic, { name: "mic", source: Track.Source.Microphone });
      this.set({ mic: "on" });
    } catch (e) {
      this.mic = null;
      this.say(`Microphone unavailable: ${(e as Error).message}. The customer cannot hear you.`);
    }
    try {   // modest resolution: the kiosk receives this over a branch link
      this.cam = await createLocalVideoTrack({ resolution: { width: 640, height: 360, frameRate: 24 } });
      await lp.publishTrack(this.cam, { name: "teller-cam", source: Track.Source.Camera });
      if (this.els.self) this.cam.attach(this.els.self);
      this.set({ cam: "on" });
      this.setPane("self", true);
    } catch (e) {
      this.cam = null;
      this.say(`Camera unavailable: ${(e as Error).message}. The customer will not see you.`);
    }

    const present = room.remoteParticipants.size > 0;
    this.set({ joined: true, customerPresent: present, decision: null, snapshotRequested: false });
    clearTimeout(this.joinTimer);
    if (!present) {
      this.set({ banner: "Waiting for the customer's kiosk to join the video…" });
      this.joinTimer = setTimeout(() => {
        if (this.room === room && room.remoteParticipants.size === 0) {
          this.set({ banner: "The customer's kiosk has not joined. They may have left. End the call to take the next customer." });
        }
      }, KIOSK_JOIN_WAIT_MS);
    }
  }

  private setPane(p: Pane, on: boolean) {
    if (this.state.panes[p] !== on) this.set({ panes: { ...this.state.panes, [p]: on } });
  }

  private renderMirror(m: Mirror) { this.set({ mirror: m }); }

  async toggleMic() {
    if (!this.mic) return;
    try {
      if (this.mic.isMuted) { await this.mic.unmute(); this.set({ mic: "on" }); }
      else { await this.mic.mute(); this.set({ mic: "muted" }); }
    } catch (e) { this.say(`Could not change the microphone: ${(e as Error).message}`); }
  }

  async toggleCam() {
    if (!this.cam) return;
    try {
      if (this.cam.isMuted) { await this.cam.unmute(); this.set({ cam: "on" }); this.setPane("self", true); }
      else { await this.cam.mute(); this.set({ cam: "off" }); this.setPane("self", false); }
    } catch (e) { this.say(`Could not change the camera: ${(e as Error).message}`); }
  }

  /** Asks the kiosk to upload a full-resolution ID image to the KYC archive. */
  async requestSnapshot() {
    if (!this.room) return;
    await this.room.localParticipant.publishData(new TextEncoder().encode(JSON.stringify({ type: "snapshot" })),
      { reliable: true, topic: "ctl" });
    this.set({ snapshotRequested: true });
  }

  // ------------------------------------------------------------------ decision and end
  /** Goes to call-control, which re-checks the teller limit and recording health, then to the orchestrator. */
  async decide(approve: boolean, amount: number) {
    const c = this.state.call;
    if (!c) return;
    try {
      await this.api.decide(c.id, approve, amount);
      this.set({ decision: approve ? "approved" : "rejected" });
      this.say(approve ? "Approval sent to the transaction service." : "Rejection sent to the transaction service.", "ok");
    } catch (e) {
      this.say(`Decision not sent: ${(e as Error).message}`);
    }
  }

  async end() {
    const c = this.state.call;
    if (!c) return;
    try { await this.api.end(c.id); } catch (e) { this.say(`Could not end the call: ${(e as Error).message}`); return; }
    await this.cleanup("Call ended. You are available for the next call.");
  }

  /** The service ended the call (customer did not reconnect in time, or the room closed). */
  private async onEnded(e: EndedEvent) {
    if (!this.state.call || this.state.call.id !== e.callId) return;
    try { await this.api.end(e.callId); } catch { /* idempotent: makes this teller available again */ }
    await this.cleanup(`The call was ended by the service (${e.reason}). You are available for the next call.`);
  }

  private async cleanup(message: string) {
    const r = this.room;
    this.room = null;
    clearTimeout(this.joinTimer);
    if (r) await r.disconnect();
    this.audioEls.forEach((a) => a.remove());
    this.audioEls = [];
    for (const el of Object.values(this.els)) if (el) el.srcObject = null;
    this.mic = null;
    this.cam = null;
    this.set({
      call: null, joined: false, customerPresent: false, banner: "", mirror: null,
      panes: { face: false, id: false, screen: false, self: false }, screenHidden: false,
      mic: "none", cam: "none", snapshotRequested: false,
    });
    if (message) this.say(message, "info");
  }

  /** Sign-out: leave any call, go away, stop timers. */
  async dispose() {
    if (this.state.call) await this.end().catch(() => undefined);
    if (this.state.available) await this.goAway().catch(() => undefined);
    clearInterval(this.hbTimer);
    clearInterval(this.ringTimer);
    this.streamCtl?.abort();
  }
}

/** Which pane a remote video track belongs to, by the track names the kiosk publishes. */
function paneFor(pub: { trackName: string }): Pane {
  if (pub.trackName === "id-cam") return "id";
  if (pub.trackName === "kiosk-screen") return "screen";
  return "face";
}
