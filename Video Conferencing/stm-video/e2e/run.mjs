#!/usr/bin/env node
// End-to-end check of a full customer-teller call through the real stack, with two browsers and fake cameras:
// kiosk test page <-> call-control <-> LiveKit <-> teller console.
//   cd e2e && npm install && npx playwright install chromium
//   node run.mjs                       (http://localhost:8080, call-control in the dev profile)
//   node run.mjs http://192.168.1.50:8080 --headed
// Exit code 0 = every step passed. Uses unique terminal/teller IDs so it does not disturb other testers.
import { createRequire } from "node:module";
const require = createRequire(import.meta.url);
const { chromium } = require("playwright");

const args = process.argv.slice(2);
const BASE = (args.find(a => !a.startsWith("--")) || "http://localhost:8080").replace(/\/$/, "");
const HEADED = args.includes("--headed");
const SKIP_AUDIT = args.includes("--no-audit");
const run = Date.now().toString(36).toUpperCase();
const TERMINAL = "E2E-KIOSK-" + run, TELLER = "e2e-teller-" + run.toLowerCase();
const LANG = "e2e" + run.toLowerCase();   // a private "language": the test call can only ring the test teller, never a real one

let step = 0, failed = false;
async function check(name, fn) {
  const t0 = Date.now(); step++;
  try { const note = await fn(); console.log(`  ok  ${String(step).padStart(2)}  ${name}${note ? "  (" + note + ")" : ""}  ${Date.now() - t0}ms`); }
  catch (e) { failed = true; console.log(`  FAIL ${String(step).padStart(2)} ${name}\n        ${String(e.message).split("\n")[0]}`); throw e; }
}
const videoSize = (page, sel) => page.evaluate(s => { const v = document.querySelector(s); return v ? [v.videoWidth, v.videoHeight] : [0, 0]; }, sel);
async function waitVideo(page, sel) {
  await page.waitForFunction(s => { const v = document.querySelector(s); return v && v.videoWidth > 0 && !v.paused; }, sel, { timeout: 20000 });
  return (await videoSize(page, sel)).join("x");
}

console.log(`STM video e2e against ${BASE}  terminal=${TERMINAL} teller=${TELLER}`);
const browser = await chromium.launch({
  channel: "chromium",   // full Chromium: the headless shell ignores --unsafely-treat-insecure-origin-as-secure (LAN addresses)
  headless: !HEADED,
  args: ["--use-fake-ui-for-media-stream", "--use-fake-device-for-media-stream=device-count=3", "--autoplay-policy=no-user-gesture-required", "--no-proxy-server",
         `--unsafely-treat-insecure-origin-as-secure=${new URL(BASE).origin}`],
});
const errors = [];
const open = async (path, who) => {
  const ctx = await browser.newContext({ permissions: ["camera", "microphone"] });
  const page = await ctx.newPage();
  page.on("pageerror", e => errors.push(`${who}: ${e.message}`));
  page.on("console", m => { if (m.type() === "error") errors.push(`${who} console: ${m.text()}`); });
  await page.goto(BASE + path);
  if (who === "kiosk") globalThis.__k = page; else if (who === "teller") globalThis.__t = page;
  return page;
};

try {
  let teller, kiosk, txId;
  await check("call-control is up and serves the test pages", async () => {
    const r = await fetch(BASE + "/kiosk/kiosk-test.html");
    if (!r.ok) throw new Error(`GET /kiosk/kiosk-test.html -> ${r.status}. Is call-control running with the dev profile?`);
  });
  await check("teller console loads and goes available", async () => {
    teller = await open(`/teller/index.html?teller=${TELLER}`, "teller");
    await teller.fill("#langs", LANG);
    await teller.click("#presence");
    await teller.waitForFunction(() => document.getElementById("presence").getAttribute("aria-pressed") === "true", null, { timeout: 5000 });
  });
  await check("kiosk page loads and sees the fake cameras", async () => {
    kiosk = await open(`/kiosk/kiosk-test.html?terminal=${TERMINAL}&lang=${LANG}&fakescreen=1`, "kiosk");
    await kiosk.waitForFunction(() => window.__stm?.cameraCount >= 3, null, { timeout: 10000 });
    // Chromium's fake camera #2 (fake_device_1) is a Y16 depth format that WebRTC cannot encode; use #1 and #3.
    await kiosk.selectOption("#camId", { label: "fake_device_2" });
    txId = await kiosk.inputValue("#tx");
    return `tx ${txId}`;
  });
  await check("customer taps Talk to a teller, accepts the recording notice", async () => {
    await kiosk.click("#call");
    await kiosk.click("#consentYes");
    await kiosk.waitForFunction(() => window.__stm.callId, null, { timeout: 5000 });
  });
  await check("teller is rung", async () => {
    await teller.waitForSelector("#ring.on", { timeout: 10000 });
    return await teller.textContent("#ringinfo");
  });
  await check("teller accepts; kiosk reaches CONNECTED", async () => {
    await teller.click("#accept");
    await kiosk.waitForFunction(() => window.__stm.states.includes("CONNECTED"), null, { timeout: 10000 });
  });
  await check("teller sees the customer's face camera", () => waitVideo(teller, "#face"));
  await check("teller sees the ID/desk camera", () => waitVideo(teller, "#idcam"));
  await check("teller sees the customer's screen (live)", () => waitVideo(teller, "#scr"));
  await check("kiosk shows the teller's camera", async () => {
    await kiosk.waitForFunction(() => window.__stm.tellerVisible, null, { timeout: 20000 });
    return waitVideo(kiosk, "#teller");
  });
  await check("upper-screen teller display shows the teller's camera (video only)", async () => {
    const d = await open(`/kiosk/teller-display.html?terminal=${TERMINAL}`, "display");
    await d.waitForFunction(() => window.__display?.live === true, null, { timeout: 15000 });
    const size = await waitVideo(d, "#v");
    if (await d.evaluate(() => document.querySelectorAll("audio").length)) throw new Error("the display must not play audio (echo)");
    globalThis.__d = d;
    return size;
  });
  await check("audio flows both ways (audio elements attached)", async () => {
    await kiosk.waitForFunction(() => document.querySelectorAll('audio[data-stm="teller-audio"]').length > 0, null, { timeout: 15000 });
    await teller.waitForFunction(() => document.querySelectorAll("audio").length > 0, null, { timeout: 15000 });
  });
  await check("kiosk screen is mirrored to the teller", async () => {
    await kiosk.click('[data-fsm="CASH_DEPOSIT"]');
    await teller.waitForFunction(() => window.__teller.lastMirror?.state === "CASH_DEPOSIT", null, { timeout: 5000 });
    return (await teller.textContent("#mirror")).replace(/\s+/g, " ").slice(0, 60);
  });
  await check("PIN entry is masked on the teller side", async () => {
    await kiosk.click('[data-fsm="PIN_ENTRY"]');
    await teller.waitForFunction(() => window.__teller.lastMirror?.masked === true, null, { timeout: 5000 });
    await teller.waitForFunction(() => !document.getElementById("scrpane").classList.contains("has")
      && /private screen/.test(document.getElementById("scrempty").textContent), null, { timeout: 5000 });
    await kiosk.click('[data-fsm="WELCOME"]');       // back to a normal screen: the live view returns
    await teller.waitForFunction(() => document.getElementById("scrpane").classList.contains("has"), null, { timeout: 5000 });
    return "live screen hidden during PIN entry, shown again after";
  });
  await check("teller requests an ID snapshot; kiosk captures it", async () => {
    await teller.click("#snap");
    await kiosk.waitForFunction(() => window.__stm.snapshots > 0, null, { timeout: 10000 });
  });
  await check("over-limit approval is refused in the console", async () => {
    await teller.fill("#amount", "9000");
    if (!(await teller.isDisabled("#approve"))) throw new Error("Approve should be disabled above the 5000 limit");
  });
  await check("teller approves 500 (below the recording-required threshold)", async () => {
    await teller.fill("#amount", "500");
    await teller.click("#approve");
    await teller.waitForFunction(() => /Approval sent/.test(document.getElementById("msg").textContent), null, { timeout: 5000 });
  });
  await check("teller ends the call; kiosk closes itself and the upper display goes idle", async () => {
    await teller.click("#end");
    await kiosk.waitForFunction(() => window.__stm.ended === "ENDED", null, { timeout: 10000 });
    await globalThis.__d.waitForFunction(() => window.__display.live === false, null, { timeout: 10000 });
    await teller.waitForFunction(() => /Call ended/.test(document.getElementById("msg").textContent), null, { timeout: 5000 });
  });
  if (!SKIP_AUDIT) await check("audit trail has the call, notice, decision, and an intact hash chain", async () => {
    const r = await fetch(`${BASE}/api/audit/transactions/${encodeURIComponent(txId)}`, { headers: { "X-Auditor-Id": "e2e" } });
    if (!r.ok) throw new Error("audit API HTTP " + r.status);
    const j = await r.json(), c = j.calls?.[0];
    if (!c) throw new Error("no call recorded for " + txId);
    const types = (c.events || []).map(e => e.type);
    for (const t of ["REQUESTED", "STATE_CONNECTED", "RECORDING_NOTICE", "DECISION", "STATE_ENDED"])
      if (!types.includes(t)) throw new Error(`missing audit event ${t}; have ${types.join(",")}`);
    if (c.chain && !c.chain.ok) throw new Error("hash chain broken at seq " + c.chain.firstBadSeq);
    const recs = (j.recordings || []).map(x => `${x.track_name}:${x.status}`).join(" ");
    return `${types.length} events${c.chain ? ", chain ok" : ""}${recs ? "; recordings " + recs : "; no recordings (Egress not running?)"}`;
  });
  await check("second call: customer hangs up mid-call, teller sees it and can still end", async () => {
    await kiosk.click("#call"); await kiosk.click("#consentYes");
    await teller.waitForSelector("#ring.on", { timeout: 10000 });
    await teller.click("#accept");
    await waitVideo(teller, "#face");
    await kiosk.click("#hangup");
    await teller.waitForFunction(() => /customer disconnected/i.test(document.getElementById("banner").textContent), null, { timeout: 15000 });
    await teller.click("#end");
    await teller.waitForFunction(() => /Call ended/.test(document.getElementById("msg").textContent), null, { timeout: 5000 });
  });
  await check("customer can cancel while waiting for a teller", async () => {
    await teller.click("#presence");                 // teller goes away, so the call waits in the queue
    await teller.waitForFunction(() => document.getElementById("presence").getAttribute("aria-pressed") === "false", null, { timeout: 5000 });
    await kiosk.click("#call"); await kiosk.click("#consentYes");
    await kiosk.waitForFunction(() => window.__stm.state === "QUEUED", null, { timeout: 5000 });
    await kiosk.click("#cancel");
    await kiosk.waitForFunction(() => window.__stm.ended === "ABANDONED", null, { timeout: 5000 });
  });
  await check("missed ring: after 20 s the console shows the teller as away, and going available rings again", async () => {
    await kiosk.click("#call"); await kiosk.click("#consentYes");
    await kiosk.waitForFunction(() => window.__stm.state === "QUEUED", null, { timeout: 5000 });
    await teller.click("#presence");                 // available again: the queued call rings at once
    await teller.waitForSelector("#ring.on", { timeout: 10000 });
    await teller.waitForFunction(() => document.getElementById("presence").getAttribute("aria-pressed") === "false"
      && /missed a call/.test(document.getElementById("msg").textContent), null, { timeout: 30000 });
    await teller.click("#presence");                 // the customer is still queued: it rings again
    await teller.waitForSelector("#ring.on", { timeout: 10000 });
    await teller.click("#accept");
    await kiosk.waitForFunction(() => window.__stm.states.slice(-1)[0] === "CONNECTED", null, { timeout: 10000 });
    await teller.click("#end");
    await kiosk.waitForFunction(() => window.__stm.ended === "ENDED", null, { timeout: 10000 });
    return "about 25 s";
  });
  await check("a kiosk page that is closed while waiting leaves no ghost call for the teller", async () => {
    await teller.click("#presence");                  // go away so the call waits
    await teller.waitForFunction(() => document.getElementById("presence").getAttribute("aria-pressed") === "false", null, { timeout: 5000 });
    const ctx = await browser.newContext({ permissions: ["camera", "microphone"] });
    const ghost = await ctx.newPage();
    await ghost.goto(`${BASE}/kiosk/kiosk-test.html?terminal=${TERMINAL}-GHOST&lang=${LANG}&fakescreen=1`);
    await ghost.waitForFunction(() => window.__stm?.cameraCount >= 1, null, { timeout: 10000 });
    await ghost.click("#call"); await ghost.click("#consentYes");
    await ghost.waitForFunction(() => window.__stm.state === "QUEUED", null, { timeout: 5000 });
    await ghost.close({ runBeforeUnload: true });     // customer walks away: the page is closed
    await ctx.close();
    await new Promise(r => setTimeout(r, 1500));
    await teller.click("#presence");
    await new Promise(r => setTimeout(r, 4000));
    if (await teller.isVisible("#ring.on")) throw new Error("the teller was rung for a customer who had left");
  });
} catch {
  // reported above; dump what the pages know to help diagnose
  for (const [who, pg] of [["kiosk", globalThis.__k], ["teller", globalThis.__t]]) if (pg) {
    const info = await pg.evaluate(() => ({ log: document.getElementById("log")?.textContent?.slice(-1500), msg: document.getElementById("msg")?.textContent,
      warnings: window.__stm?.warnings, state: window.__stm?.state })).catch(() => null);
    if (info) console.log(`\n[${who}]`, JSON.stringify(info, null, 1));
  }
}

const relevant = errors.filter(e => !/favicon|404 \(Not Found\)/.test(e));
if (relevant.length) { console.log("\nBrowser errors:"); relevant.slice(0, 15).forEach(e => console.log("  " + e)); }
await browser.close();
console.log(failed ? "\nRESULT: FAILED" : `\nRESULT: PASSED (${step} checks)`);
process.exit(failed ? 1 : 0);
