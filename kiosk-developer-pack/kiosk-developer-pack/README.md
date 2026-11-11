# Smart Teller: kiosk developer pack

Everything the kiosk app needs to talk to the Smart Teller middleware. The middleware follows the
*Smart Teller Core Banking API Specification* (base path `/stm/v1`) and the prototype screens A0–E13.

## 1. Server and access

| | |
|---|---|
| Gateway base URL | `https://10.8.0.1:8443/stm/v1` |
| Network | Reachable only over the server's **WireGuard VPN**. Ask Blue River for a VPN profile, one for your development machine and one for the STM. Needed: TCP 8443, TCP 7881, UDP 7882. |
| HTTPS certificate | Private CA. Install `stm-server-ca.crt` (sent with this pack) as a trusted root on your machine and on the STM: double-click it → Install Certificate → Local Machine → Trusted Root Certification Authorities. Or run `install-server-certificate.ps1` as Administrator. |
| API documentation | `docs/API.md` (all operations), `docs/ACCOUNT-OPENING.md` (screens B1–B10 step by step), `docs/CARD-AND-CUSTOMER-SERVICES.md` (card care, PIN, phone number, existing customers), `https://10.8.0.1:8443/swagger-ui.html` |
| Architecture | `docs/STM-Architecture.pdf` |

## 2. This machine's identity

| | |
|---|---|
| Device ID (`X-Device-Id`) | `STM-NBO-MOI-03` |
| Branch | `012`, Moi Avenue |
| Terminal token | **Sent separately.** Keep it secret: it is the machine's credential. It is shown only once and cannot be read back from the server. |

Sign in at boot and every 15 minutes (D-1):
```http
POST https://10.8.0.1:8443/stm/v1/auth/device/token
Authorization: Bearer <terminal token>
Content-Type: application/json

{ "deviceId": "STM-NBO-MOI-03", "softwareVersion": "1.0.0", "nonce": "<uuid>" }
```
```json
200 { "accessToken": "dvt_…", "expiresIn": 900, "branchCode": "012", "branchName": "Moi Avenue" }
```
In production the machine authenticates with a client certificate (mutual TLS) instead of the terminal token.
This pilot server uses the terminal token.

## 3. Rules for every call

| Header | Value |
|---|---|
| `Authorization` | `Bearer <accessToken>` from D-1 |
| `X-Device-Id` | `STM-NBO-MOI-03` |
| `X-Request-Id` | a new UUID per call |
| `Accept-Language` | `en` or `sw` |
| `X-Session-Id` | from `POST /sessions` (D-2) until `POST /sessions/{id}/end` (D-4) |
| `X-Customer-Token` | after card + PIN (S-2), card-services verification (B-5) or a new account (B-12) |
| `Idempotency-Key` | **Required** on: account submit (B-12), card order (C-5), statement (S-5), deposit commit (K-4), withdrawal (W-2), reversal (W-4), forex confirm (X-5). **Accepted on every other write.** Make it when the customer confirms; reuse the same key when retrying. Same key + same body returns the first answer (`Idempotent-Replayed: true`). Same key + different body is 409 `IDEMPOTENCY_CONFLICT`. |

- Money is a decimal string: `{"amount": "12000.00", "currency": "KES"}`.
- Every error is `{"error": {code, message, messageSw, retryable, requestId, details}}`. Show `message` or
  `messageSw` to the customer as is.

## 4. What you asked for → endpoint

| Need | Call |
|---|---|
| Customer lookup by ID number | `POST /customers/lookup` `{by: "ID_DOCUMENT", documentType, documentNumber}` (or `by: "ACCOUNT_NUMBER"`) |
| IPRS verification | `POST /identity/documents/verify` |
| Face match | `POST /identity/biometrics/verify` (new customer: `applicationId` + `verificationId`; existing: `customerId` + `lookupRef` after the OTP) |
| OTP send / verify | `POST /otp/send`, `POST /otp/verify` |
| SIM-swap check | `POST /kyc/sim-swap-check` `{lookupRef}` or `{applicationId}`. The OTP send refuses with 403 `SIM_SWAP_RISK` after a swap within 72 h. |
| KYC status | `GET /customers/{id}/kyc-status` (customer token, or `?lookupRef=` before the OTP): status, risk rating, KRA PIN on file, PEP and flags, `selfServiceAllowed` |
| Accounts / cards | `GET /customers/{id}/accounts`, `GET /customers/{id}/cards` |
| Card eligibility | `GET /customers/{id}/accounts?eligibleFor=CARD` (eligible + reason per account) |
| Fee quote and debit | `POST /fees/quote`, then the debit happens inside the card order (C-5 with `feeQuoteId` + `customerAuthorised: true`). Waivers are server-side. |
| Create / replace card | `POST /cards/orders` `{service: LINK_AND_ISSUE \| REPLACEMENT \| NEW_CARD_SETUP}` → `cardId`, `personalisation.payloadForPrinter`; progress `GET /cards/orders/{id}` |
| Hotlist card | `POST /cards/{id}/block` `{reason: LOST \| STOLEN \| DAMAGED \| EXPIRED}` (safe to repeat; permanent) |
| Validate a card | `GET /customers/{id}/cards` or `GET /cards/{id}/status`: `status` (ACTIVE, INACTIVE_PIN_REQUIRED, TEMP_BLOCKED, BLOCKED, PIN_LOCKED, EXPIRED), `statusText`, `reason` (LOST, STOLEN, DAMAGED …), `expiresSoon`, and `actions` = the buttons to show. A customer can hold several cards. |
| PIN change / reset | `POST /cards/{id}/pin/change` (knows the PIN), `POST /cards/{id}/pin/reset` (forgot it or locked; needs code + thumbprint) |
| Temporary block / unblock | `POST /cards/{id}/temporary-block`, `POST /cards/{id}/unblock` (needs code + thumbprint) |
| Change phone number | `POST /customers/{id}/mobile-change`, OTP to the new number (`purpose: MOBILE_CHANGE`), `POST /customers/{id}/mobile-change/{changeRef}/confirm`. Phone lost: face + thumbprint instead of the code. |
| Existing (pre-STM) customers | `GET /customers/{id}/profile` says what is on file and what to offer; `POST /customers/{id}/biometrics/enrol` (fingerprints), `POST /customers/{id}/signature` (specimen signature) |
| Active or dormant account | `GET /customers/{id}/accounts`: `status` (ACTIVE, DORMANT, FROZEN, CLOSED), `canTransact`, `nextStep`; `POST /accounts/{id}/reactivate` wakes a dormant one |
| Confirm issued / void | `POST /cards/orders/{id}/dispense` `{outcome: TAKEN \| RETAINED \| SPOILED}` |
| Activate | `POST /cards/{id}/pin` (PIN block from the EPP), `POST /cards/{id}/activate` |
| Audit events | `POST /devices/{id}/journal` (signed batches), plus the gateway's own audit of every call |

Payloads and answers for each are in `docs/API.md`. The card, PIN, phone-number and existing-customer calls are
explained step by step, with full URLs, in `docs/CARD-AND-CUSTOMER-SERVICES.md`.

## 5. Video: "Talk to a teller"

Use `video/kiosk-video.js` (needs `npm i livekit-client@2.23.0`) in **gateway mode**:
```js
import { VideoSession } from "./kiosk-video.js";
const video = new VideoSession({
  gateway: {
    base: "/stm/v1",                                    // or "https://10.8.0.1:8443/stm/v1"
    headers: () => stm.authHeaders(),                    // Authorization, X-Device-Id, X-Session-Id
    screen: "B4", flow: "ACCOUNT_OPENING", applicationId,
  },
  getFsmState: () => ({ name: currentScreen, model }),   // mirrored to the teller; PIN screens are hidden
  onState: (s) => {}, onDropped: () => {}, onEnded: () => {},
  shareScreen: true,
});
await video.request("en");
```
The kiosk must keep polling while waiting (the class does it every 2 s). If polling stops for 10 s, the call is dropped.

Upper screen: open `https://10.8.0.1:8443/kiosk/teller-display.html?terminal=STM-NBO-MOI-03` full screen on the top
monitor. The terminal must be the device ID above.

## 6. Test data (the server simulates the bank)

| ID / account | Who | What happens |
|---|---|---|
| ID 29876543 (KAMAU, 1996-07-22) | Amara | New customer, account opening succeeds (once per database) |
| ID 30222333 (NDEGWA, 1985-11-30) | Wanjiku | PEP hit, referred to a teller |
| ID 30111222 (MWANGI, 1990-01-09) | Juma | Face never matches |
| ID 30333444 (OUMA) | Otieno | KRA PIN inactive |
| ID 32145678, account 0123454521 | Achieng | Existing customer. Card `tok_4512xxxx4521`, PIN **2580**. Current account 0123457780 has no card yet. |
| ID 25567890, accounts 0124417001 … 004 | Mary | Customer from before the Smart Teller: no fingerprints or signature on file. One dormant and one frozen account. Five cards, PIN **2468**: `tok_4512xxxx8101`, `…8102` active; `…8103` temporarily blocked; `…8104` blocked (damaged); `…8105` PIN locked. |
| Account 0119876543 | Brian | SIM swapped 20 h ago (OTP blocked), KYC review overdue, no KRA PIN on file |
| Passport P12345678 (GBR) | John Smith | Visitor, for forex |

- Every OTP is `123456`.
- Without a real PIN pad, the PIN block is `0` + PIN length + PIN padded with F to 16 characters (2580 →
  `042580FFFFFFFFFF`, see `kiosk-ts/src/pinblock.ts`). Never ship that helper.

## 7. Tools in this pack

- **Postman:** import `postman/STM-Gateway.postman_collection.json` and `postman/STM-Server.postman_environment.json`.
  Select the environment and paste the terminal token into `terminalToken`. Turn off SSL certificate verification in
  Postman's settings, or add `stm-server-ca.crt` there. Run the request **"D-1 Device token"** in folder 0 first,
  then any journey folder (4c and 4d are the card-care and existing-customer journeys). Skip "Register a demo terminal": your machine is already registered.
- **TypeScript client:** `kiosk-ts/` (copy `src/` into the app). `vite.config.example.ts` proxies `/stm` to the
  server during development, so there is no CORS to configure. If the kiosk app will be served from its own address
  (not through a proxy), send us that origin, for example `http://localhost:5173`, and we will allow it on the server.

Questions about the API: Blue River (the middleware team).
