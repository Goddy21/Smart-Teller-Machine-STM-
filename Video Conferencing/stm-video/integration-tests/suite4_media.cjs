const assert = require("assert"), { execFileSync } = require("child_process");
const wrtc = require("/tmp/nodertc/node_modules/@roamhq/wrtc");
const { RTCVideoSource, RTCAudioSource, rgbaToI420, RTCVideoSink, RTCAudioSink } = wrtc.nonstandard;
const IP = execFileSync("sh", ["-c", "hostname -I | awk '{print $1}'"]).toString().trim();
const JAVA = "/tmp/jdk-21.0.5+11/bin/java";
const CP = "/tmp/tt/out:/usr/share/java/jackson-core.jar:/usr/share/java/jackson-databind.jar:/usr/share/java/jackson-annotations.jar";
const issue = ident => JSON.parse(execFileSync(JAVA, ["-cp", CP, "com.blueriver.brits.webrtc.service.TurnTool", IP, "THE-SHARED-SECRET-FROM-APPLICATION-YML", "600", ident])).iceServers;

globalThis.window = globalThis;
// Force every connection through the TURN relay: success then PROVES media crossed Coturn with the Java-issued credentials.
globalThis.RTCPeerConnection = class extends wrtc.RTCPeerConnection { constructor(cfg) { super({ ...cfg, iceTransportPolicy: "relay" }); } };
const timers = [];
function videoTrack(w, h, rgb) {
  const src = new RTCVideoSource(), rgba = new Uint8ClampedArray(w * h * 4);
  for (let i = 0; i < w * h; i++) rgba.set([...rgb, 255], i * 4);
  const i420 = { width: w, height: h, data: new Uint8ClampedArray(w * h * 1.5) }; rgbaToI420({ width: w, height: h, data: rgba }, i420);
  timers.push(setInterval(() => src.onFrame(i420), 66)); return src.createTrack();
}
function audioTrack() {
  const src = new RTCAudioSource(), samples = new Int16Array(480);
  for (let i = 0; i < 480; i++) samples[i] = Math.round(8000 * Math.sin(2 * Math.PI * 440 * i / 48000));
  timers.push(setInterval(() => src.onData({ samples, sampleRate: 48000, bitsPerSample: 16, channelCount: 1, numberOfFrames: 480 }), 10)); return src.createTrack();
}
// Each camera is recognisable by its frame size and colour: face 320x180 red, desk 480x270 blue, teller 320x180 green.
const media = {};
Object.defineProperty(globalThis, "navigator", { configurable: true, writable: true, value: { mediaDevices: { async getUserMedia(c) {
  const t = [];
  if (c.audio) t.push(audioTrack());
  if (c.video) { const big = (c.video.width && c.video.width.ideal) >= 1900; const teller = (c.video.width && c.video.width.ideal) === 640;
    t.push(big ? videoTrack(480, 270, [40, 40, 220]) : teller ? videoTrack(320, 180, [40, 200, 40]) : videoTrack(320, 180, [220, 40, 40])); }
  return new wrtc.MediaStream(t);
} } } });
const { WebRTCService } = require("/tmp/js/WebRTCService.js");

const stats = {}; // label -> {frames, width, lumaSum, lumaN, audio, maxAmp}
function sinkAll(label, stream) {
  setTimeout(() => {
    for (const t of stream.getVideoTracks()) { const s = stats[label] = stats[label] || { frames: 0, w: 0, y: [], audio: 0, amp: [] };
      new RTCVideoSink(t).onframe = ({ frame }) => { s.frames++; s.w = frame.width; let y = 0, n = 0; for (let i = 0; i < frame.width * frame.height; i += 97) { y += frame.data[i]; n++; } s.y.push([Date.now(), y / n]); }; }
    for (const t of stream.getAudioTracks()) { const s = stats[label] = stats[label] || { frames: 0, w: 0, y: [], audio: 0, amp: [] };
      new RTCAudioSink(t).ondata = d => { s.audio++; let m = 0; for (const v of d.samples) m = Math.max(m, Math.abs(v)); s.amp.push([Date.now(), m]); }; }
  }, 400);
}
const sleep = ms => new Promise(r => setTimeout(r, ms));
const pass = m => console.log("PASS", m);

(async () => {
  const dataGot = [], kioskRoles = [], tellerRoles = []; let channelOpened = false;
  const state = { kiosk: "new", teller: "new" };
  let kiosk, teller; const chainK = { p: Promise.resolve() }, chainT = { p: Promise.resolve() };
  // the in-process "server": forwards offer/answer/candidates to the other side, strictly in order, like the hooks do
  const toTeller = m => { chainT.p = chainT.p.then(async () => { if (m.type === "CALL_OFFER") await teller.receiveOffer(m.sdp, m.meta); else if (m.type === "ICE_CANDIDATE") await teller.receiveCandidate(m.candidate); }); };
  const toKiosk = m => { chainK.p = chainK.p.then(async () => { if (m.type === "CALL_ANSWER") await kiosk.receiveAnswer(m.sdp); else if (m.type === "ICE_CANDIDATE") await kiosk.receiveCandidate(m.candidate); }); };

  teller = new WebRTCService({ role: "teller", iceServers: issue("teller:a"), sendSignal: toKiosk,
    onRemoteStream: (role, s) => { tellerRoles.push(role); sinkAll("teller<-" + role, s); },
    onConnectionState: s => { state.teller = s; }, onData: (l, d) => dataGot.push([l, d]) });
  kiosk = new WebRTCService({ role: "kiosk", iceServers: issue("kiosk:T1"), sendSignal: toTeller,
    onRemoteStream: (role, s) => { kioskRoles.push(role); sinkAll("kiosk<-" + role, s); },
    onConnectionState: s => { state.kiosk = s; }, onDataChannelOpen: () => { channelOpened = true; } });

  await teller.startTellerMedia();                                    // order the hooks use: teller opens media, kiosk offers
  await kiosk.startKioskMedia({ face: "cam1", desk: "cam2", audio: "mic1" });
  await kiosk.createOffer();
  for (let i = 0; i < 100 && !(state.kiosk === "connected" && state.teller === "connected"); i++) await sleep(200);
  assert.strictEqual(state.kiosk, "connected"); assert.strictEqual(state.teller, "connected");
  pass("kiosk and teller reach 'connected' with ALL media forced through the Coturn relay (Java-issued credentials)");

  await sleep(4500);
  const t = stats;
  assert.deepStrictEqual(tellerRoles.sort(), ["desk", "face"]); pass("teller receives exactly two streams, labelled 'face' and 'desk' from the offer metadata");
  assert.deepStrictEqual(kioskRoles, ["teller"]); pass("kiosk receives one stream, labelled 'teller'");
  assert.ok(t["teller<-face"].frames > 25 && t["teller<-face"].w === 320, JSON.stringify([t["teller<-face"].frames, t["teller<-face"].w]));
  assert.ok(t["teller<-desk"].frames > 25 && t["teller<-desk"].w === 480, JSON.stringify([t["teller<-desk"].frames, t["teller<-desk"].w]));
  pass(`'face' stream really is the face camera (${t["teller<-face"].frames} frames, 320 px wide) and 'desk' really is the desk camera (${t["teller<-desk"].frames} frames, 480 px wide)`);
  assert.ok(t["teller<-face"].audio > 150, "customer audio reached the teller: " + t["teller<-face"].audio);
  assert.ok(t["kiosk<-teller"].frames > 25 && t["kiosk<-teller"].audio > 150, "teller audio+video reached the kiosk");
  pass(`audio and video flow both ways (customer mic ${t["teller<-face"].audio} chunks, teller ${t["kiosk<-teller"].frames} video frames + ${t["kiosk<-teller"].audio} audio chunks)`);
  assert.ok(t["teller<-desk"].audio === 0, "the desk stream carries no audio"); pass("the desk stream is video only (no second microphone)");

  assert.ok(channelOpened, "onDataChannelOpen fired on the kiosk");
  assert.ok(kiosk.sendData({ v: 1, state: "DEPOSIT_AMOUNT", model: { amount: 900 } }));
  await sleep(600);
  assert.ok(dataGot.some(([l, d]) => l === "fsm" && d.state === "DEPOSIT_AMOUNT" && d.model.amount === 900));
  pass("screen-mirror message travels peer to peer on data channel 'fsm' and arrives intact");

  // mute / camera-off actually change what the far end receives
  const lastAmp = lbl => { const a = stats[lbl].amp.filter(([ts]) => ts > Date.now() - 700).map(x => x[1]); return Math.max(0, ...a); };
  const meanY = lbl => { const a = stats[lbl].y.filter(([ts]) => ts > Date.now() - 800).map(x => x[1]); return a.reduce((s, v) => s + v, 0) / (a.length || 1); };
  const ampBefore = lastAmp("teller<-face"), yBefore = meanY("kiosk<-teller");
  kiosk.setAudioEnabled(false); teller.setVideoEnabled(false); await sleep(1800);
  const ampAfter = lastAmp("teller<-face"), yAfter = meanY("kiosk<-teller");
  assert.ok(ampBefore > 1000 && ampAfter < 50, `mic mute: ${ampBefore} -> ${ampAfter}`);
  assert.ok(yBefore > 80 && yAfter < 40, `camera off: luma ${yBefore.toFixed(0)} -> ${yAfter.toFixed(0)}`);
  pass(`kiosk 'mute' silences what the teller hears (peak ${ampBefore} -> ${ampAfter}); teller 'camera off' blacks the kiosk's picture (luma ${yBefore.toFixed(0)} -> ${yAfter.toFixed(0)})`);

  kiosk.close(); teller.close(); timers.forEach(clearInterval); await sleep(300);
  console.log("\nALL MEDIA CHECKS PASSED"); process.exit(0);
})().catch(e => { console.error("FAIL", e.message); process.exit(1); });
