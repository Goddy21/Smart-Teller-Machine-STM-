# STM Portal (Tier 3)

TypeScript + React web portal for the STM kiosk platform, in the Blue River theme. It shows everything the
middleware knows (sessions, KYC captures, terminals, deposits, card collection, audit journal, products) and
includes the **video teller console**: the stm-video teller console ported to TypeScript, so a teller takes a
customer's call and decides on their kiosk session in one screen.

```
Browser (localhost:5174)
  │  /api/v1/...  ─────────▶ stm-middleware   :8080   sessions, terminals, deposits, audit (X-Portal-Key)
  │  /api/...     ─────────▶ call-control     :8090   video queue, teller presence, call evidence (X-Teller-Id)
  └─ ws://<host>:7880 ─────▶ LiveKit          :7880   media (URL comes from call-control on accept)
```

## Run it (Windows, development)

1. **Middleware** on 8080: run `StmMiddlewareApplication` in IntelliJ as before.
2. **Video stack** (from the `stm-video` folder): `scripts\start-infra.ps1`, then `scripts\start-livekit.ps1`.
3. **call-control on 8090.** It defaults to 8080 like the middleware, so give it another port:
   ```powershell
   .\scripts\start-call-control.ps1 -Port 8090
   .\scripts\setup-firewall.ps1 -CallControlPort 8090      # once, so the STM can reach it
   ```
   It keeps its own PostgreSQL (port 5434), separate from the middleware's database.
   On the STM, pass the same port as the last argument (it defaults to 8080):
   ```bat
   open-kiosk-test-on-stm.cmd 192.168.1.49 STM-01 8090
   open-teller-display-on-stm.cmd 192.168.1.49 STM-01 <x> <y> 8090
   ```
4. **Portal:**
   ```powershell
   cd "C:\Goddie\STM Middleware\stm-portal"
   npm install
   npm run dev
   ```
   Open http://localhost:5174 and sign in with a portal account (see "Signing in" below).

Different hosts or ports: copy `.env.example` to `.env.local` and set `STM_MIDDLEWARE_URL` / `STM_CALL_CONTROL_URL`.

## Signing in

Accounts live in the middleware (`portal_users`, migration V3). There is no shared key in the portal any more.

1. **First start:** the middleware log prints a one-time password for `admin`:
   ```
   STM portal: first administrator account created
     username: admin
     password: k7Hm2qWx9aPr4t
   ```
   Or set `BOOTSTRAP_ADMIN_PASSWORD` before the first start. Sign in as `admin`; you must choose a new password.
2. **Add people** on **Portal accounts**. Each gets a temporary password, shown once, which they replace at first sign-in.
3. Sessions end after **30 minutes idle** or **10 hours** in total. **5 wrong passwords** lock the account for 15 minutes
   (an administrator can unlock it). Disabling an account signs that person out at once.

| Role | Can |
|---|---|
| Teller | Take video calls; approve or reject customer sessions; read sessions, fleet, cards, products |
| Supervisor | Everything a teller can, plus lock/unlock/reboot kiosks, deposits, the audit trail |
| Auditor | Read everything including the audit trail and call evidence; cannot decide or control kiosks |
| Administrator | Manage portal accounts, control kiosks, read the audit trail; does not take calls or decide |

The middleware enforces these rules; the portal only hides what a role cannot use. Decisions, document views and
teller WebSocket tickets are recorded under the signed-in user, never under a name the browser sends.

## Pages

| Page | What it shows | Source |
|---|---|---|
| Overview | Customers waiting for a teller, KPIs, 14-day chart, outcomes, funnel, fleet | `GET /portal/overview` (new), sessions, terminals, audit verify |
| Customer sessions | Live list with filters and search; drawer with identity, biometrics, KYC captures, timeline, and decisions (Approve identity / Approve review / Reject) | `/portal/sessions`, documents, decision, `/sessions/{id}/timeline` (new) |
| Video teller | Availability, incoming call alert on every page, customer face + zoomable ID camera + live kiosk screen, mute/camera, ID snapshot, the linked kiosk session with its decisions, transaction approval within the teller limit | call-control + LiveKit |
| Kiosk fleet | Online/offline, card stock, cassettes, alarms, live updates, Lock / Unlock / Refresh / Reboot | `/portal/terminals`, telemetry stream, commands, registry (new) |
| Deposits | Two-phase deposit transactions, cash to return, escrow ledger per transaction | `/portal/transactions` (state now optional), ledger (new) |
| Card collection | Cards that failed to dispense and wait at a branch | `/portal/card-fulfilments` |
| Audit & compliance | Hash-chain verification, the journal with filters and payloads; video call evidence with sealed recordings (open / verify) | `/portal/audit/*` (journal new), call-control `/api/audit/*` |
| Products & branches | Products, branches, terminal registry | `/portal/reference` (new) |
| Portal accounts (admins) | Add people, change roles, disable, unlock, reset passwords | `/portal/users` (new) |

## The kiosk's upper "teller display" (stm-video v10)

During a call the teller's camera also fills the STM's upper monitor (`kiosk-client/teller-display.html`). It joins
the call as a hidden, video-only participant, so the portal never shows it and it never counts as the customer.
The portal publishes its camera as `teller-cam`, the name both kiosk screens subscribe to; do not rename it.
Turning the camera off in the portal makes the upper screen say "Their camera is off. You can still talk to them."
The teller sees "Also on the kiosk's upper screen" on their self-view.

## Linking a video call to a kiosk session

The teller sees the kiosk session next to the video, with Approve identity / Approve review / Reject, when the kiosk
passes its **middleware session id** as the call's transaction id:

```js
await video.request(language, session.id);   // kiosk-video.js VideoSession.request(lang, txId)
```

Any other reference still works; the session panel just doesn't appear.

## Middleware changes that come with this portal

- New `portal/PortalInsightsController.java`: `GET /api/v1/portal/overview`, `/audit/journal`, `/audit/event-types`,
  `/reference`, `/terminals/registry`, `/transactions/{id}/ledger`. Read-only, behind `X-Portal-Key`. The SQL was run
  against PostgreSQL with V1 and V2 applied.
- `GET /api/v1/portal/transactions` no longer requires `?state=` (returns the latest 200).
- Deposit views (`TxView`) now include `createdAt` and `updatedAt`.

## Before production

- **call-control still trusts the `X-Teller-Id` header.** The portal fills it with the signed-in username, but
  call-control does not check it against the middleware session. Keep call-control on the internal network behind a
  gateway that does, or ask for call-control to validate the portal session itself.
- Set `stm.portal-auth.allow-shared-key: false` (already the prod default), so the old shared key stops working.
- **Serve the built portal** (`npm run build` → `dist/`) from the same host as the gateway, so `/api` stays same-origin.
- **Viewing a KYC capture is audited** (`DOCUMENT_VIEWED`), so thumbnails load only when the teller taps them.
- Teller limit and recording threshold shown in the UI come from `VITE_TELLER_LIMIT` / `VITE_RECORDING_REQUIRED_ABOVE`;
  call-control enforces the real values.
- Google Fonts loads Plus Jakarta Sans; on an offline network the portal falls back to Segoe UI.

## What was tested

Type-checked in strict mode, production build, and driven in a headless browser against stand-in backends with
sample data (every page, filters, drawer, decisions, fleet commands, audit, call evidence). The video console was
tested end to end through a **real LiveKit server**: a simulated kiosk published face, ID camera, kiosk screen and
mic; the teller saw all three, the kiosk received the teller's camera and mic and the ID snapshot request, hiding the
kiosk screen showed the private-screen notice, and the approval and end-call reached call-control. A hidden,
receive-only display participant (same token settings and subscription logic as `teller-display.html`) received the
teller camera and no audio, followed camera off/on, and did not disturb the portal's customer-presence logic. Not yet tested
against your real middleware and call-control together.
