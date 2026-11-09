// Runs in the kiosk's Chromium page (React app imports this). npm i livekit-client@2.23.0
// Respects the eCAT constraints: no second WebSocket client, cameras shared with the eCAT daemon via handoff.
import { Room, RoomEvent, Track, createLocalVideoTrack, createLocalAudioTrack } from "livekit-client";

const MASKED_STATES = new Set(["PIN_ENTRY", "CARD_DATA_ENTRY"]); // never mirrored to the teller
// Default camera match: a substring of the device label. Set per hardware, or pass deps.cameras.
// Labels on the STM are visible on the kiosk test page (Cameras list) or in chrome://media-internals.
// GRG STM seen 2026-11: "DC003-EA(V)" = face (visible lens; its "-IR" twin is never used), "LHT-820VM31B" = wide/second
// camera, "GRGBanking SIDR-001 Image Device" = ID scanner imaging. Fallback when a label is missing: 1st / 2nd camera.
const CAM_LABELS = { face: "DC003-EA(V)", id: "LHT-820VM31B" };
const FINAL = new Set(["ENDED", "ABANDONED"]);

export class VideoSession {
  /** deps: {
   *   getFsmState(): {name, model}, uploadSnapshot(callId, blob): Promise, onDropped(): void, onState(s): void,
   *   api?: "/api"                      base path of the Terminal API Gateway (dev: call-control itself)
   *   cameras?: { face, id }            deviceId or label substring for each camera (id: false = none); default CAM_LABELS, then 1st/2nd camera
   *   onWarn?(msg): void                non-fatal problems (e.g. no second camera: the ID camera is skipped)
   *   onEnded?(state): void             the call reached ENDED or ABANDONED; the session has closed itself
   *   shareScreen?: true                also publish the kiosk screen to the teller ("kiosk-screen"); hidden on masked states
   *   getScreenStream?(): MediaStream   override how the screen is captured (default getDisplayMedia; tests pass a canvas)
   * } */
  constructor(deps) { this.deps = deps; this.api = deps.api ?? "/api"; this.room = null; this.tracks = {}; this.callId = null; }

  async request(lang, txId) {
    if (this.deps.gateway) return this.#requestViaGateway(lang);
    const r = await fetch(`${this.api}/calls`, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ lang, txId }) });
    if (!r.ok) throw new Error(`call request failed (${r.status})`);
    this.callId = (await r.json()).callId;
    // If the page is closed or reloaded, do not leave a ghost call in the queue for a teller to answer.
    this.onHide = () => {
      if (this.room) this.room.disconnect();
      else if (this.callId) fetch(`${this.api}/calls/${this.callId}`, { method: "DELETE", keepalive: true }).catch(() => {});
    };
    window.addEventListener("pagehide", this.onHide);
    this.events = new EventSource(`${this.api}/calls/${this.callId}/events`);
    this.events.addEventListener("state", e => {
      const s = JSON.parse(e.data).state;
      this.deps.onState(s);
      if (FINAL.has(s)) { this.close().finally(() => this.deps.onEnded?.(s)); }
    });
    this.events.addEventListener("connected", e => this.#connect(JSON.parse(e.data)).catch(err => {
      this.deps.onWarn?.("Could not join the video room: " + err.message);
      this.deps.onDropped();
    }));
    return this.callId;
  }

  /** Gateway mode: D-8 places the call; polling GET (every 2 s) follows QUEUED -> CONNECTED -> ENDED. */
  async #requestViaGateway(lang) {
    const g = this.deps.gateway, base = g.base ?? "/stm/v1";
    const hdr = () => ({ "Content-Type": "application/json", ...(g.headers?.() ?? {}) });
    const r = await fetch(`${base}/support/teller-calls`, { method: "POST", headers: hdr(),
      body: JSON.stringify({ screen: g.screen ?? null, flow: g.flow ?? null, applicationId: g.applicationId ?? null, language: lang }) });
    if (!r.ok) throw new Error(`call request failed (${r.status})`);
    this.callId = (await r.json()).callId;
    this.onHide = () => {
      if (this.room) this.room.disconnect();
      fetch(`${base}/support/teller-calls/${this.callId}`, { method: "DELETE", headers: hdr(), keepalive: true }).catch(() => {});
    };
    window.addEventListener("pagehide", this.onHide);
    let last = null;
    const callId = this.callId;
    const poll = async () => {
      if (this.callId !== callId) return;
      try {
        const res = await fetch(`${base}/support/teller-calls/${callId}`, { headers: hdr() });
        if (res.ok) {
          const d = await res.json();
          const s = d.callState || d.status;
          if (s !== last) { last = s; this.deps.onState(s); }
          if (d.status === "CONNECTED" && d.media && !this.room) {
            this.#connect({ url: d.media.roomUrl, token: d.media.token }).catch(err => {
              this.deps.onWarn?.("Could not join the video room: " + err.message);
              this.deps.onDropped();
            });
          }
          if (d.status === "ENDED" || d.status === "CANCELLED") {
            const fin = d.status === "CANCELLED" ? "ABANDONED" : "ENDED";
            this.close().finally(() => this.deps.onEnded?.(fin));
            return;
          }
        }
      } catch { /* the network blinked: try again */ }
      this.pollTimer = setTimeout(poll, 2000);
    };
    poll();
    return this.callId;
  }

  /** Call when the customer taps Continue on the recording notice. Logged to the audit trail. */
  ackConsent(accepted = true) {
    if (this.deps.gateway) return Promise.resolve();   // the Gateway logs the call; consent is part of the session's audit
    return fetch(`${this.api}/calls/${this.callId}/consent`, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ accepted }) });
  }

  /** Register the <video> that shows the teller and a callback(visible) that shows or hides its pane.
   *  Safe to call before the call connects, or with (null) on unmount. */
  setTellerVideo(el, onVisible) {
    this.tv = { el, cb: onVisible };
    if (el && this.tellerTrack) { this.tellerTrack.attach(el); this.#showTeller(true); }
  }

  get hasTeller() { return !!this.tellerTrack; }

  #showTeller(visible) {
    if (visible && this.tellerTrack && this.tv?.el) this.tellerTrack.attach(this.tv.el);
    this.tv?.cb?.(visible && !!this.tellerTrack);
  }

  cancel() {
    const g = this.deps.gateway;
    if (g) return fetch(`${g.base ?? "/stm/v1"}/support/teller-calls/${this.callId}`, { method: "DELETE", headers: g.headers?.() ?? {} });
    return fetch(`${this.api}/calls/${this.callId}`, { method: "DELETE" });
  }

  async #connect({ url, token }) {
    if (this.room) return;
    const room = new Room({ adaptiveStream: true, dynacast: true });
    this.room = room;
    room.on(RoomEvent.TrackSubscribed, (track, pub) => {
      if (track.kind === "audio") { const a = track.attach(); a.dataset.stm = "teller-audio"; a.style.display = "none"; document.body.appendChild(a); } // teller's voice (Chromium autoplay must be allowed by policy)
      else if (pub.trackName === "teller-cam") { this.tellerTrack = track; this.#showTeller(!pub.isMuted); }
    });
    room.on(RoomEvent.TrackUnsubscribed, (track, pub) => {
      track.detach().forEach(el => { if (el.dataset.stm === "teller-audio") el.remove(); });
      if (pub.trackName === "teller-cam") { this.tellerTrack = null; this.#showTeller(false); }
    });
    room.on(RoomEvent.TrackMuted, pub => { if (pub.trackName === "teller-cam") this.#showTeller(false); });     // teller turned the camera off
    room.on(RoomEvent.TrackUnmuted, pub => { if (pub.trackName === "teller-cam") this.#showTeller(true); });
    room.on(RoomEvent.DataReceived, (payload, _p, _k, topic) => this.#onData(payload, topic));
    room.on(RoomEvent.Disconnected, () => {
      if (this.room !== room) return;              // we closed it ourselves
      this.room = null; this.#showTeller(false); this.deps.onDropped();
    });
    await room.connect(url, token);
    await this.#publishMic();
    await this.#publishCameras();
    if (this.deps.shareScreen !== false) await this.#publishScreen();
  }

  /** The kiosk screen as a third video. Needs no prompt when the browser runs with
   *  --auto-select-desktop-capture-source="Entire screen" (or the ScreenCaptureAllowedByOrigins policy). */
  async #publishScreen() {
    try {
      const stream = this.deps.getScreenStream ? await this.deps.getScreenStream()
        : await navigator.mediaDevices.getDisplayMedia({ video: { frameRate: 10, width: { ideal: 1280 } }, audio: false, monitorTypeSurfaces: "include", selfBrowserSurface: "include" });
      const t = stream.getVideoTracks()[0];
      if (!this.room) { t.stop(); return; }          // the call ended while the screen was being captured
      t.contentHint = "text";                       // crisp text over smooth motion
      t.onended = () => this.deps.onWarn?.("Screen sharing was stopped on the kiosk.");
      this.tracks.screen = await this.room.localParticipant.publishTrack(t, { name: "kiosk-screen", source: Track.Source.ScreenShare, simulcast: false });
      if (this.masked) await this.tracks.screen.track?.mute();
    } catch (e) { if (!this.room) return; this.deps.onWarn?.("Screen sharing unavailable (" + (e.name || "") + " " + e.message + "). The teller still sees the screen fields."); }
  }

  /** Resolve a camera: exact deviceId, else label substring, else the Nth camera. */
  async #camera(want, fallbackIndex, cams) {
    if (want === false) return null;              // explicitly "no camera"
    if (want) {
      // exact deviceId, then exact label (ignoring the "(vid:pid)" suffix, so "DC003-EA(V)" does not pick "DC003-EA(V)-IR"),
      // then any label containing the text
      const base = l => l.replace(/\s*\([0-9a-f]{4}:[0-9a-f]{4}\)\s*$/i, "");
      const d = cams.find(x => x.deviceId === want) || cams.find(x => base(x.label) === want) || cams.find(x => x.label.includes(want));
      if (d) return d.deviceId;
    }
    return cams[fallbackIndex]?.deviceId ?? null;
  }

  async #publishCameras() {
    // Labels are empty until one getUserMedia succeeds; Chromium policy VideoCaptureAllowedUrls avoids the prompt.
    (await navigator.mediaDevices.getUserMedia({ video: true })).getTracks().forEach(t => t.stop());
    const cams = (await navigator.mediaDevices.enumerateDevices()).filter(x => x.kind === "videoinput");
    const want = { ...CAM_LABELS, ...(this.deps.cameras || {}) };
    const faceId = await this.#camera(want.face, 0, cams);
    let idId = await this.#camera(want.id, 1, cams);
    if (!faceId) throw new Error("no camera found");
    if (idId === faceId) idId = null;           // one physical camera cannot be opened twice at different settings
    const lp = this.room.localParticipant;
    const face = await createLocalVideoTrack({ deviceId: faceId, resolution: { width: 1280, height: 720, frameRate: 24 } });
    this.tracks.face = await lp.publishTrack(face, { name: "face", source: Track.Source.Camera, simulcast: true });
    if (!idId) { this.deps.onWarn?.("Only one camera found: the ID camera (id-cam) is not published. Recording health will report it missing."); return; }
    const id = await createLocalVideoTrack({ deviceId: idId, resolution: { width: 1920, height: 1080, frameRate: 15 } });
    id.mediaStreamTrack.contentHint = "detail"; // favour sharpness over framerate for ID documents
    // Second camera as source Unknown: LiveKit allows one track per source, and ScreenShare carries the kiosk screen.
    this.tracks.id = await lp.publishTrack(id, { name: "id-cam", source: Track.Source.Unknown });
  }

  async #publishMic() {
    const mic = await createLocalAudioTrack({ echoCancellation: true, noiseSuppression: true });
    this.tracks.mic = await this.room.localParticipant.publishTrack(mic, { name: "mic", source: Track.Source.Microphone });
  }

  /** Hand the cameras to eCAT (binocularCamera / biometrics), then take them back. The room and the mic stay connected. */
  async withExclusiveCameras(fn) {
    const lp = this.room?.localParticipant;
    if (lp) for (const k of ["face", "id"]) { const t = this.tracks[k]?.track; if (t) await lp.unpublishTrack(t, true); delete this.tracks[k]; }
    try { return await fn(); }
    finally { if (this.room) await this.#publishCameras().catch(() => this.deps.onDropped()); }
  }

  /** Mirror the kiosk FSM to the teller. Sensitive screens are replaced by a masked marker. */
  mirror() {
    if (!this.room) return;
    const { name, model } = this.deps.getFsmState();
    this.masked = MASKED_STATES.has(name);
    const scr = this.tracks.screen?.track;           // never show PIN / card screens on the live screen view
    if (scr) (this.masked ? scr.mute() : scr.unmute()).catch(() => {});
    const msg = this.masked ? { v: 1, state: name, masked: true } : { v: 1, state: name, model };
    this.room.localParticipant.publishData(new TextEncoder().encode(JSON.stringify(msg)), { reliable: true, topic: "fsm" });
  }

  async #onData(payload, topic) {
    if (topic !== "ctl") return;
    const msg = JSON.parse(new TextDecoder().decode(payload));
    if (msg.type === "snapshot") {
      // Full-resolution still goes to the KYC archive via the API; the teller views it there, not over video.
      const track = this.tracks.id?.track?.mediaStreamTrack;
      if (!track) { this.deps.onWarn?.("Snapshot requested but there is no ID camera"); return; }
      let blob;
      try { blob = await new ImageCapture(track).takePhoto(); }
      catch { blob = await this.#grabFrame(track); }   // some USB cameras do not support takePhoto()
      await this.deps.uploadSnapshot(this.callId, blob);
    }
  }

  async #grabFrame(track) {
    const bmp = await new ImageCapture(track).grabFrame();
    const c = document.createElement("canvas"); c.width = bmp.width; c.height = bmp.height;
    c.getContext("2d").drawImage(bmp, 0, 0);
    return new Promise(res => c.toBlob(res, "image/jpeg", 0.92));
  }

  async close() {
    clearTimeout(this.pollTimer); this.pollTimer = null;
    if (this.onHide) window.removeEventListener("pagehide", this.onHide);
    const room = this.room; this.room = null;
    this.tellerTrack?.detach(); this.tellerTrack = null; this.#showTeller(false);
    this.events?.close(); this.events = null;
    document.querySelectorAll('audio[data-stm="teller-audio"]').forEach(a => a.remove());
    this.tracks = {};
    await room?.disconnect();
  }
}
