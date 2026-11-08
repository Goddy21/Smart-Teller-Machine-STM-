const assert = require("assert");
globalThis.window = globalThis;
let streamSeq = 0;
class FakePC {
  constructor() { this.calls = []; this.connectionState = "new"; }
  addTrack() {}
  createDataChannel(l) { return { label: l, readyState: "open", send() {}, close() {} }; }
  async createOffer() { return { type: "offer", sdp: "O" }; }
  async setLocalDescription(d) { this.localDescription = d; this.calls.push("setLocal"); }
  async setRemoteDescription() { this.calls.push("setRemote"); }
  async addIceCandidate(c) { this.calls.push("addIce:" + c.candidate); }
  async createAnswer() { return { type: "answer", sdp: "A" }; }
  close() {}
}
globalThis.RTCPeerConnection = FakePC;
const track = k => ({ kind: k, enabled: true, stop() {} });
Object.defineProperty(globalThis, "navigator", { configurable: true, writable: true, value: { mediaDevices: { getUserMedia: async c => {
  const id = "S" + (++streamSeq), t = [];
  if (c.audio) t.push(track("audio")); if (c.video) t.push(track("video"));
  return { id, getTracks: () => t, getVideoTracks: () => t.filter(x => x.kind === "video"), getAudioTracks: () => t.filter(x => x.kind === "audio") };
} } } });
const { WebRTCService } = require("../build/WebRTCService.js");

(async () => {
  // teller: candidates that arrive before the offer are held, then applied in order after setRemoteDescription
  let sent = [], remotes = [];
  const teller = new WebRTCService({ role: "teller", iceServers: [], sendSignal: m => sent.push(m),
    onRemoteStream: (r, s) => remotes.push([r, s.id]), onConnectionState() {} });
  const pc = teller.peer;
  await teller.receiveCandidate({ candidate: "c1" }); await teller.receiveCandidate({ candidate: "c2" });
  assert.deepStrictEqual(pc.calls, [], "candidates must be held until the remote description is set");
  await teller.receiveOffer({ type: "offer", sdp: "O" }, { streams: { face: "F1", desk: "D1" } });
  assert.deepStrictEqual(pc.calls, ["setRemote", "addIce:c1", "addIce:c2", "setLocal"]);
  assert.strictEqual(sent[0].type, "CALL_ANSWER");
  await teller.receiveCandidate({ candidate: "c3" });
  assert.strictEqual(pc.calls.at(-1), "addIce:c3", "later candidates apply immediately");
  console.log("PASS candidates buffered, then flushed in order after the offer");

  // incoming streams are labelled from the offer metadata, and announced once each
  pc.ontrack({ streams: [{ id: "F1" }] }); pc.ontrack({ streams: [{ id: "F1" }] });   // audio + video of the same stream
  pc.ontrack({ streams: [{ id: "D1" }] }); pc.ontrack({ streams: [{ id: "X9" }] }); pc.ontrack({ streams: [] });
  assert.deepStrictEqual(remotes, [["face", "F1"], ["desk", "D1"], ["remote", "X9"]]);
  console.log("PASS face/desk roles mapped from meta; each stream announced once; trackless events ignored");

  // kiosk: two cameras become two streams, and the offer carries the stream ids as meta
  sent = [];
  const kiosk = new WebRTCService({ role: "kiosk", iceServers: [], sendSignal: m => sent.push(m), onRemoteStream() {}, onConnectionState() {} });
  const media = await kiosk.startKioskMedia({ face: "cam1", desk: "cam2", audio: "mic1" });
  assert.ok(media.face && media.desk && media.face.id !== media.desk.id);
  await kiosk.createOffer();
  assert.strictEqual(sent[0].type, "CALL_OFFER");
  assert.deepStrictEqual(sent[0].meta, { streams: { face: media.face.id, desk: media.desk.id } });
  console.log("PASS kiosk offer carries { face, desk } stream ids");

  // desk camera failure is not fatal
  const orig = globalThis.navigator.mediaDevices.getUserMedia;
  globalThis.navigator.mediaDevices.getUserMedia = async c => { if (!c.audio && c.video) throw new Error("desk camera busy"); return orig(c); };
  const k2 = new WebRTCService({ role: "kiosk", iceServers: [], sendSignal: m => sent.push(m), onRemoteStream() {}, onConnectionState() {} });
  sent = [];
  const m2 = await k2.startKioskMedia({ face: "cam1", desk: "cam2" });
  assert.ok(m2.face, "face camera still opens");
  assert.strictEqual(m2.desk, undefined, "a busy desk camera must not fail the call");
  await k2.createOffer();
  assert.deepStrictEqual(sent[0].meta, { streams: { face: m2.face.id } }, "meta lists only the streams that exist");
  console.log("PASS desk camera busy: call proceeds with the face camera and meta omits desk");

  // mute and camera toggles act on local tracks
  kiosk.setAudioEnabled(false); kiosk.setVideoEnabled(false);
  assert.ok(media.face.getAudioTracks().every(t => !t.enabled) && media.face.getVideoTracks().every(t => !t.enabled));
  console.log("PASS mute and camera-off toggle the local tracks");
})().catch(e => { console.error("FAIL", e.message); process.exit(1); });
