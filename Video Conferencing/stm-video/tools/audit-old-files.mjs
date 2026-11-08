#!/usr/bin/env node
// Classifies every file in your old, scattered folder against this project. No dependencies. Needs Node 16+.
//   node tools/audit-old-files.mjs "C:\path\to\old\folder"
// Run it BEFORE you edit files in this project: "current" means "identical to the file this project shipped with".
import fs from "node:fs";
import path from "node:path";
import crypto from "node:crypto";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const oldDir = process.argv[2];
if (!oldDir || !fs.existsSync(oldDir)) { console.error('Usage: node tools/audit-old-files.mjs "<old folder>"'); process.exit(2); }

const SKIP = new Set([".idea", "node_modules", "target", ".git", "build", "dist", "out", ".gradle", ".vscode"]);
const SKIP_EXT = new Set([".iml", ".class", ".jar", ".log", ".png", ".jpg", ".zip"]);
const rel = (base, p) => path.relative(base, p).split(path.sep).join("/");

function walk(dir, out = []) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    if (SKIP.has(e.name)) continue;
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walk(p, out); else if (!SKIP_EXT.has(path.extname(e.name).toLowerCase())) out.push(p);
  }
  return out;
}
// Windows line endings and a BOM must not make identical files look different.
const text = p => fs.readFileSync(p, "utf8").replace(/^\uFEFF/, "").replace(/\r\n/g, "\n");
const hash = s => crypto.createHash("sha256").update(s).digest("hex");
const lineSet = s => new Set(s.split("\n").map(l => l.trim()).filter(Boolean));
function similarity(a, b) {
  const A = lineSet(a), B = lineSet(b); if (!A.size && !B.size) return 1;
  let inter = 0; for (const l of A) if (B.has(l)) inter++;
  return inter / (A.size + B.size - inter);
}

const project = walk(root)
  .filter(p => !rel(root, p).startsWith("tools/"))
  .map(p => ({ path: p, rel: rel(root, p), name: path.basename(p), body: text(p) }))
  .map(f => ({ ...f, hash: hash(f.body) }));
const byName = new Map();
for (const f of project) byName.set(f.name, [...(byName.get(f.name) ?? []), f]);

const rows = []; const matchedProject = new Set();
for (const p of walk(path.resolve(oldDir))) {
  const body = text(p), h = hash(body), name = path.basename(p), where = rel(path.resolve(oldDir), p);
  const same = project.find(f => f.hash === h);
  if (same) { matchedProject.add(same.rel); rows.push(["CURRENT", where, `identical to ${same.rel}`]); continue; }
  const cands = byName.get(name);
  if (!cands) { rows.push(["NOT IN PROJECT", where, "yours, or obsolete: keep or delete as you see fit"]); continue; }
  const best = cands.map(c => ({ c, s: similarity(body, c.body) })).sort((a, b) => b.s - a.s)[0];
  matchedProject.add(best.c.rel);
  rows.push(["DIFFERENT", where, `closest project file: ${best.c.rel} (${Math.round(best.s * 100)}% similar). Older version, or edited by you? Compare, then replace`]);
}

const order = { "CURRENT": 0, "DIFFERENT": 1, "NOT IN PROJECT": 2 };
rows.sort((a, b) => order[a[0]] - order[b[0]] || a[1].localeCompare(b[1]));
const w = Math.max(...rows.map(r => r[1].length), 10);
for (const [k, f, note] of rows) console.log(k.padEnd(15), f.padEnd(w), " ", note);

const main = project.filter(f => /^(call-control|kiosk-client|teller-console|media-plane)\//.test(f.rel) && !matchedProject.has(f.rel));
console.log("\n== Project files you do not have yet (main project only) ==");
console.log(main.length ? main.map(f => "  " + f.rel).join("\n") : "  none: you have a version of every file");
const n = k => rows.filter(r => r[0] === k).length;
console.log(`\n${rows.length} old files: ${n("CURRENT")} current, ${n("DIFFERENT")} different, ${n("NOT IN PROJECT")} not in project`);
console.log("Nothing was changed. This tool only reads.");
