#!/usr/bin/env node
// TEST DOUBLE, NOT PART OF THE PRODUCT. A dependency-free Node stand-in for call-control's HTTP contract
// (same paths, headers, SSE events, LiveKit token claims and dev-gateway cookies), used to verify the browser code
// (kiosk-video.js, kiosk-test.html, teller console) against a real LiveKit server where Maven/Spring could not run.
// Run the real call-control (mvn spring-boot:run -Dspring-boot.run.profiles=dev) for anything else.
//   node integration-tests/browser-standin/server.mjs   [env: PORT=8080 LIVEKIT_PUBLIC_URL=ws://localhost:7880]
import http from "node:http";
import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");
const PORT = +(process.env.PORT || 8080);
const KEY = "devkey", SECRET = "change-me-to-a-32+char-secret-value-0000";
const PUBLIC_URL = process.env.LIVEKIT_PUBLIC_URL || "ws://localhost:7880";
const LIMIT = 5000;
const STATIC = { "/teller/": "teller-console", "/kiosk/": "kiosk-client", "/vendor/": "vendor", "/": "call-control/src/main/resources/static" };
const TYPES = { ".html": "text/html; charset=utf-8", ".js": "text/javascript", ".jsx": "text/javascript", ".map": "application/json", ".md": "text/plain" };

const calls = new Map(), tellers = new Map(), kioskSse = new Map(), tellerSse = new Map(), displaySse = new Map(), termCall = new Map(), events = [];
const displayPayload = c => ({ url: PUBLIC_URL, token: token(c.room, "display:" + c.terminalId, "Display " + c.terminalId, false, true), room: c.room, callId: c.id });
const b64 = b => Buffer.from(b).toString("base64url");
function token(room, identity, name, publish = true, hidden = false) {
  const now = Math.floor(Date.now() / 1000);
  const h = b64(JSON.stringify({ alg: "HS256", typ: "JWT" }));
  const p = b64(JSON.stringify({ iss: KEY, sub: identity, name, nbf: now, exp: now + 120,
    video: { room, roomJoin: true, canPublish: publish, canSubscribe: true, canPublishData: publish, ...(hidden ? { hidden: true } : {}) } }));
  return `${h}.${p}.${crypto.createHmac("sha256", SECRET).update(`${h}.${p}`).digest("base64url")}`;
}
const send = (res, name, data) => res?.write(`event:${name}\ndata:${JSON.stringify(data)}\n\n`);
const audit = (callId, type, payload = {}) => events.push({ callId, type, payload, at: new Date().toISOString() });
const kiosk = (c, name, data) => send(kioskSse.get(c.id), name, data);
const connected = c => ({ url: PUBLIC_URL, token: token(c.room, "kiosk:" + c.terminalId, "Kiosk " + c.terminalId), room: c.room });
function move(c, to) {
  audit(c.id, "STATE_" + to, { from: c.state, to }); c.state = to; kiosk(c, "state", { state: to }); console.log(`call=${c.id.slice(0, 8)} -> ${to}`);
  if (to === "ENDED" || to === "ABANDONED") { send(displaySse.get(c.terminalId), "ended", { callId: c.id }); if (termCall.get(c.terminalId) === c.id) termCall.delete(c.terminalId); }
}
function dispatch() {
  for (const c of [...calls.values()].filter(c => c.state === "QUEUED").sort((a, b) => a.createdAt - b.createdAt)) {
    if (Date.now() - c.createdAt > 10000 && !kioskSse.has(c.id)) { move(c, "ABANDONED"); continue; }   // ghost: kiosk page gone
    const t = [...tellers.entries()].find(([, v]) => v.available && v.langs.includes(c.lang));
    if (!t) continue;
    t[1].available = false; c.tellerId = t[0]; move(c, "RINGING");
    send(tellerSse.get(t[0]), "ring", { callId: c.id, terminalId: c.terminalId, lang: c.lang, txId: c.txId });
    c.ringTimer = setTimeout(() => { if (c.state === "RINGING") release(c, "timeout"); }, 20000);
  }
}
function release(c, reason) { const t = c.tellerId; c.tellerId = null; move(c, "QUEUED"); if (tellers.has(t)) tellers.get(t).available = false; send(tellerSse.get(t), "cancelled", { callId: c.id, reason }); dispatch(); }

function identity(req, header, cookie) {
  if (req.headers[header]) return req.headers[header];
  const m = (req.headers.cookie || "").split(/;\s*/).find(x => x.startsWith(cookie + "="));
  return m ? decodeURIComponent(m.slice(cookie.length + 1)) : new URL(req.url, "http://x").searchParams.get(cookie.replace("stm-", "") + "Id");
}
const json = (res, code, body) => { res.writeHead(code, { "Content-Type": "application/json" }); res.end(body === undefined ? "" : JSON.stringify(body)); };
const body = req => new Promise(r => { let s = ""; req.on("data", d => s += d); req.on("end", () => { try { r(s ? JSON.parse(s) : {}); } catch { r({ raw: s }); } }); });
const sse = (req, res, map, key) => {
  res.writeHead(200, { "Content-Type": "text/event-stream", "Cache-Control": "no-cache", Connection: "keep-alive" }); res.write(":ok\n\n");
  map.set(key, res); req.on("close", () => { if (map.get(key) === res) map.delete(key); });
};

http.createServer(async (req, res) => {
  const u = new URL(req.url, "http://x"), p = u.pathname, M = req.method;
  const term = identity(req, "x-terminal-id", "stm-terminal"), tel = identity(req, "x-teller-id", "stm-teller");
  let m;
  try {
    if (M === "POST" && p === "/api/calls") {
      if (!term) return json(res, 400, { error: "X-Terminal-Id missing" });
      const b = await body(req), id = crypto.randomUUID();
      const c = { id, terminalId: term, lang: b.lang, txId: b.txId, state: "REQUESTED", createdAt: Date.now(), room: "call-" + id, present: new Set() };
      calls.set(id, c); audit(id, "REQUESTED", { terminal: term, tx: b.txId }); move(c, "QUEUED"); dispatch();
      return json(res, 200, { callId: id, state: c.state });
    }
    if ((m = p.match(/^\/api\/calls\/([^/]+)(\/[a-z]+)?$/))) {
      const c = calls.get(m[1]), op = m[2] || "";
      if (!c) return json(res, 404, { error: "no call" });
      if (op === "/events" && M === "GET") {
        if (c.terminalId !== term) return json(res, 404, {});
        sse(req, res, kioskSse, c.id); send(res, "state", { state: c.state }); if (c.state === "CONNECTED") send(res, "connected", connected(c)); return;
      }
      if (op === "" && M === "DELETE") {
        if (!["QUEUED", "RINGING"].includes(c.state)) return json(res, 409, { error: "call already connected" });
        const t = c.tellerId; move(c, "ABANDONED"); if (t) { tellers.get(t) && (tellers.get(t).available = true); send(tellerSse.get(t), "cancelled", { callId: c.id }); } return json(res, 200);
      }
      if (op === "/consent") { audit(c.id, "RECORDING_NOTICE", await body(req)); return json(res, 200); }
      if (op === "/token") return json(res, 200, connected(c));
      if (c.tellerId !== tel) return json(res, 409, { error: "not this teller's call" });
      if (op === "/accept" && c.state === "RINGING") {
        clearTimeout(c.ringTimer); move(c, "CONNECTED"); kiosk(c, "connected", connected(c));
        termCall.set(c.terminalId, c.id); send(displaySse.get(c.terminalId), "connected", displayPayload(c));
        return json(res, 200, { url: PUBLIC_URL, token: token(c.room, "teller:" + tel, "Teller " + tel), room: c.room, txId: c.txId, terminalId: c.terminalId });
      }
      if (op === "/decline" && c.state === "RINGING") { clearTimeout(c.ringTimer); release(c, "declined"); return json(res, 200); }
      if (op === "/end") {
        if (["CONNECTED", "ON_HOLD"].includes(c.state)) move(c, "ENDED"); else if (c.state !== "ENDED") return json(res, 409, { error: "not active" });
        if (tellers.has(tel)) tellers.get(tel).available = true; dispatch(); return json(res, 200);
      }
      if (op === "/decision") {
        const d = await body(req);
        if (c.state !== "CONNECTED") return json(res, 409, { error: "call not active for this teller" });
        if (d.approve && d.amount > LIMIT) return json(res, 403, { error: "amount exceeds teller limit; supervisor override required" });
        audit(c.id, "DECISION", { teller: tel, ...d }); return json(res, 200, { status: "submitted" });
      }
      return json(res, 409, { error: `call is ${c.state}` });
    }
    if (p === "/api/terminals/me/display-events") {
      sse(req, res, displaySse, term); const c = calls.get(termCall.get(term));
      if (c && ["CONNECTED", "ON_HOLD"].includes(c.state)) send(res, "connected", displayPayload(c)); return;
    }
    if (p === "/api/tellers/me/presence" && M === "PUT") { const b = await body(req); tellers.set(tel, { available: b.available, langs: b.langs || [] }); dispatch(); return json(res, 200); }
    if (p === "/api/tellers/me/heartbeat") { const t = tellers.get(tel); return json(res, 200, { available: !!t?.available, present: !!t, stream: tellerSse.has(tel) }); }
    if (p === "/api/tellers/me/events") return sse(req, res, tellerSse, tel);
    if ((m = p.match(/^\/api\/audit\/transactions\/(.+)$/))) {
      const ids = [...calls.values()].filter(c => c.txId === m[1]).map(c => c.id);
      return json(res, 200, { transaction: m[1], calls: ids.map(id => ({ call_id: id, events: events.filter(e => e.callId === id) })) });
    }
    if (p === "/webhooks/livekit" && M === "POST") {   // signature not checked here; the Java service verifies it
      const n = await body(req), room = n.room?.name || "", who = n.participant?.identity || "";
      const c = room.startsWith("call-") && calls.get(room.slice(5));
      if (c && (who === "kiosk:" + c.terminalId || who === "teller:" + c.tellerId)) {
        if (n.event === "participant_joined") { c.present.add(who); if (c.state === "ON_HOLD" && c.present.size >= 2) move(c, "CONNECTED"); }
        if (n.event === "participant_left") { c.present.delete(who); if (c.state === "CONNECTED") move(c, "ON_HOLD"); }
      }
      if (n.event === "track_published") audit(c?.id, "TRACK_PUBLISHED", { who, track: n.track?.name });
      return json(res, 200);
    }
    // static files, mirroring call-control's static/ layout
    const pre = Object.keys(STATIC).find(k => p.startsWith(k));
    let f = path.join(ROOT, STATIC[pre], decodeURIComponent(p.slice(pre.length)) || "index.html");
    if (!f.startsWith(ROOT) || !fs.existsSync(f) || fs.statSync(f).isDirectory()) return json(res, 404, { error: "not found" });
    res.writeHead(200, { "Content-Type": TYPES[path.extname(f)] || "application/octet-stream" }); fs.createReadStream(f).pipe(res);
  } catch (e) { console.error(e); json(res, 500, { error: e.message }); }
}).listen(PORT, "0.0.0.0", () => console.log(`stand-in call-control on :${PORT}, LiveKit ${PUBLIC_URL}`));
