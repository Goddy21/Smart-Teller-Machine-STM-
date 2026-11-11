# STM Middleware (Tier 2) · Smart Teller Gateway

Java 21 / Spring Boot 3.3 / PostgreSQL. The Smart Teller machines call the **Smart Teller Gateway** (`/stm/v1`,
the 58 operations of the *Smart Teller Core Banking API Specification*); the STM portal calls `/api/v1/portal`.
Bank systems sit behind ports (`backend/`): core banking, IPRS/KRA, card system and HSM, SMS, Paybill.
In the demo profile those ports are **simulated** (`backend/sim`), with a double-entry ledger and test customers.

## Run it

```powershell
# PostgreSQL with database/user/password "stm" (see docker-compose.yml), then:
mvn spring-boot:run            # demo profile by default; or run StmMiddlewareApplication in IntelliJ
.\scripts\demo-journey.ps1                              # every customer journey, end to end (see -Flow)
```
Swagger UI: http://localhost:8080/swagger-ui.html · Health: /actuator/health.
Flyway applies `V1`…`V4` on start. **`V4__smart_teller_gateway.sql` drops the old kiosk-journey tables**
(`kiosk_sessions`, `kyc_documents`, `card_fulfilments`, `account_products`, `escrow_ledger`, `transactions`) and creates
the gateway and simulator tables. Never edit a migration that has run; add `V5__...` instead.

"Talk to a teller" needs call-control (stm-video) on `stm.call-control.url` (default http://localhost:8090).

## Conventions (every `/stm/v1` call)

| Header | |
|---|---|
| `Authorization: Bearer <device token>` | from D-1 `POST /stm/v1/auth/device/token` (15 min). Production: mTLS, client certificate CN = deviceId. Development: `Authorization: Bearer <terminal token>` from `POST /api/v1/terminals/register` (`X-Provisioning-Secret`) |
| `X-Device-Id` | must match the token |
| `X-Session-Id` | from D-2, on every call of the customer session (30 min max, 10 min idle) |
| `X-Request-Id` | a UUID per call; echoed in errors and the audit journal |
| `Accept-Language` | `en` or `sw`: error messages and receipts follow it (errors also carry `messageSw`) |
| `X-Customer-Token` | after S-2 (card + PIN), B-5 (card services) or B-12 (new account); needed for the customer's accounts and cards |
| `Idempotency-Key` | required on B-12, C-5, S-5, K-4, W-2, W-4, X-5, and accepted on every other write. Same key + same body replays the first answer (`Idempotent-Replayed: true`); same key + other body is 409 `IDEMPOTENCY_CONFLICT` |

Money is `{"amount": "12000.00", "currency": "KES"}` (decimal strings). Errors are
`{"error": {code, message, messageSw, retryable, requestId, details}}`.

## Prototype screen → operation

| Screen | Operations |
|---|---|
| A0 Landing | D-1 at boot, D-6 health (every few minutes), D-2 `POST /sessions {trigger: TOUCH\|CARD}` → `services` = home tiles |
| A1 Main menu | D-3 `PATCH /sessions/{id} {language}` · D-8 "Talk to a teller" from any screen · D-4 Finish |
| B1 Account type | B-1 `GET /products` |
| B2 Terms | B-2 `GET /legal/terms?product=`; B-3 `POST /onboarding/applications` with the consents |
| B3 ID capture | B-4 `POST /identity/documents/verify` (409 `EXISTING_CUSTOMER` → Card services) |
| B4 Biometrics | B-5 `POST /identity/biometrics/verify` (422 `BIOMETRIC_NO_MATCH`, 3 tries, then a teller decides in the portal) |
| B5 Review IPRS | B-6 `POST /onboarding/applications/{id}/identity-confirm` |
| B6 KYC form | B-7 `POST /kyc/kra-pin/validate`, B-8 `PATCH /onboarding/applications/{id}/kyc` (screening: `REFERRED` → the portal clears or rejects; poll B-13) |
| B7 Phone OTP | B-9 `POST /otp/send`, B-10 `POST /otp/verify` |
| B8 Signature | B-11 `POST /onboarding/applications/{id}/signature` |
| B9 Account created | B-12 `POST /onboarding/applications/{id}/submit` (opens the account), then C-5 with `applicationId` (fee waived), C-6, C-7 |
| B10 PIN | C-8 `POST /cards/{id}/pin`, C-9 `POST /cards/{id}/activate`, R-1 receipt |
| C1–C3 Card services | C-1 `POST /customers/lookup`, `GET /customers/{id}/kyc-status?lookupRef=` (refresh due, KRA PIN, PEP / high risk), `POST /kyc/sim-swap-check`, B-9/B-10 (purpose `CARD_SERVICES`; 403 `SIM_SWAP_RISK` after a swap within 72 h), B-5 with `customerId` + `lookupRef` → customer token; C-2 cards |
| C4 Account & fee | C-3 `GET /customers/{id}/accounts?eligibleFor=CARD`, C-4 `POST /fees/quote` |
| C5 Printing | C-5 `POST /cards/orders`, C-6 `GET /cards/orders/{id}` (steps), C-7 `POST /cards/orders/{id}/dispense`; C-10 block a lost card |
| C6 PIN activation | C-8, C-9 |
| Manage my cards (new) | C-2 every card with `statusText` and `actions` (several cards per customer); P-1 status, P-2 PIN change, P-3 PIN reset, P-4 temporary block, P-5 unblock, C-10 lost / stolen / damaged then a replacement |
| My details (new) | M-1 profile (`suggestions`), M-2 reactivate a dormant account, M-3 enrol fingerprints, M-4 / M-5 change the phone number (code to the new number; phone lost: face + thumbprint), M-6 / M-7 specimen signature (B8 pad) |
| D1 Card + PIN | S-1 `POST /auth/card`, S-2 `POST /auth/card/pin-verify` (3 tries, then `RETAIN`); S-3 balance |
| D2–D3 Statements | S-4 `GET /statements/options`, S-5 `POST /statements` (fee), S-6 `GET /statements/{id}/document` (PDF) |
| E1–E7 Deposit | K-1 `POST /accounts/validate` (account or Paybill 522522/888880), K-2 open, K-3 count, K-4 commit, K-5 cancel |
| E8–E9 Withdrawal | W-1 limits, W-2 `POST /cash/withdrawals` (note mix), W-3 taken, W-4 reverse (retracted / timeout; also by Idempotency-Key) |
| E10–E13 Forex | X-1 rates, B-4 with `purpose: FOREX`, X-2 quote, X-3 exchange, X-4 count, X-5 confirm (cash or account); bank sells: W-2 with `fxQuoteId`; X-6 `GET /devices/{id}/cassettes` |
| Any | R-1 receipts (print / SMS), R-2 SMS · A-1 signed electronic journal · A-2 cash positions (reconciliation) |

Limits (demo): withdrawals KES 40,000 per transaction and per day, multiples of 100; deposits 100 notes / KES 500,000;
forex 50 notes and USD/EUR/GBP 1,000 a day; cash-out of forex rounded down to KES 100.

## Test customers (demo profile)

| ID / passport | Who | What happens |
|---|---|---|
| 29876543 | Amara Njeri Kamau | new customer, account opening succeeds (KRA A029876543K) |
| 30222333 | Wanjiku Mary Ndegwa | PEP hit at B-8 → `REFERRED`; a teller clears it in the portal |
| 30111222 | Juma Hassan Mwangi | face never matches → after 3 tries a teller confirms identity |
| 30333444 | Otieno Peter Ouma | KRA PIN inactive (`KRA_PIN_INVALID`) |
| 30444555 | Faith Atieno Odhiambo | document tamper check fails |
| 32145678 | Achieng Wanjiru Otieno, CIF00482913 | existing customer: card `tok_4512xxxx4521` PIN **2580**; savings 0123454521, current 0123457780 (no card yet), junior saver |
| 25567890 | Mary Njoki Kariuki, CIF00204417 (customer since 2014) | pre-STM customer: no fingerprints or signature on file; accounts 0124417001 / 002 active, 0124417003 **dormant**, 0124417004 **frozen**; five cards, PIN **2468**: `tok_4512xxxx8101` and `…8102` active (two cards on one account), `…8103` temporarily blocked, `…8104` blocked (damaged), `…8105` PIN locked |
| 27654321 | Brian Kiprotich Mutai, CIF00391144 (account 0119876543) | blocked card 6611 and expired card 7002 (PIN 1357); SIM swapped 20 hours ago (no OTP); KYC review overdue, no KRA PIN on file |
| P12345678 (GBR) | John Robert Smith | visitor passport for forex |

Every OTP is `123456` (`stm.sim.fixed-otp`). PIN blocks in the demo are clear ISO 9564 fields: `0` + length + PIN,
padded with F (2580 → `042580FFFFFFFFFF`); the real PIN pad sends encrypted blocks and the prod profile has no simulator.
The portal's **Simulator** page lists all of this, switches core banking off (`CORE_UNAVAILABLE`) and adds latency.

## Portal API (`/api/v1/portal`)

Sign-in: `POST /api/v1/auth/login {username, password}` → session token (`Authorization: Bearer`). Roles TELLER,
SUPERVISOR, AUDITOR, ADMIN. Passwords BCrypt, 30 min idle / 10 h absolute, lockout after 5 failures, first `admin`
from `BOOTSTRAP_ADMIN_PASSWORD` (or printed once). `X-Portal-Key` still works in dev for scripts (`allow-shared-key`).

| | |
|---|---|
| `GET /overview` | totals, last 14 days by service, application funnel |
| `GET /sessions`, `/sessions/{id}`, `/sessions/{id}/timeline` | customer sessions with everything they did |
| `GET /applications?needsTeller=true`, `/applications/{id}` | account opening; `POST /applications/{id}/decision {decision: CLEAR_REFERRAL \| VERIFY_IDENTITY \| REJECT, notes}` (TELLER, SUPERVISOR) |
| `GET /captures/{id}` | registry photo, face, signature (every view audited) |
| `GET /transactions`, `/postings?refs=`, `/card-orders` | cash, forex, their core-banking postings; cards |
| `GET /reconciliation`, `/device-journal`, `/sms` | cash positions vs GL, the kiosk journal (signature checked), SMS outbox |
| `GET /terminals`, `/terminals/registry`, `/telemetry/stream`, `POST /terminals/{code}/commands` | fleet: modules, disabled services, LOCK/UNLOCK/REBOOT/REFRESH_CONFIG |
| `GET /reference`, `PUT /fx-rates/{cur}` | products, tariffs, terms, rates (SUPERVISOR, ADMIN edit rates) |
| `GET/PUT /simulator`, `/simulator/customers`, `/simulator/accounts/{id}/postings`, `/simulator/gl` | demo back ends |
| `GET /audit/journal`, `/audit/verify`, `/audit/event-types` | hash-chained audit journal |
| `/users` | portal accounts (ADMIN) |

## "Talk to a teller"

D-8 `POST /stm/v1/support/teller-calls {screen, flow, applicationId, language}` asks call-control for a call, with
the kiosk session id as the call reference, so the teller console opens that session (and any application waiting
for a decision). The kiosk polls D-9 `GET /support/teller-calls/{id}` every 2 s: `QUEUED` → `CONNECTED` with
`media {roomUrl, room, token}` (join the LiveKit room) → `ENDED`. `DELETE` cancels. 503 `VIDEO_UNAVAILABLE` if
call-control is down.

## Browser front-ends

- **CORS**: `stm.cors-allowed-origins` (env `CORS_ALLOWED_ORIGINS`) may call `/api/**`, `/stm/**` and open `/ws/signaling`.
- **Remote commands**: the kiosk keeps `/ws/signaling` open with a one-time ticket from `POST /stm/v1/signaling/ticket`.
- **TypeScript client**: `clients/kiosk-ts` has typed calls for all `/stm/v1` operations, keeps the device token,
  session and customer token, and maps errors to screens. See its README.

## Design rules built in

- Double-entry simulated core: every posting has a GL leg (`GL-CASH-<cur>-<device>`, `GL-VAULT`, `GL-FX-POSITION`,
  `GL-RETRACT`, `GL-FEES-*`); A-2 compares the kiosk's count with the GL and opens a case on a variance.
- Money movements run inside the idempotency record's transaction; the error a customer saw is replayed, not retried.
- Notes stay in escrow until K-4; W-2 debits before dispensing and W-4 credits back (or `PENDING_RECONCILIATION`
  with `stm.cash.retract-credit: AFTER_COUNT`).
- PINs: only PIN blocks pass through; never stored, logged or audited. OTPs are masked in the SMS outbox.
- Audit journal is hash-chained with masked identifiers; the kiosk journal (A-1) is HMAC-signed per batch.
- No one-time code goes to a number whose SIM was swapped in the last 72 h (`stm.kyc.sim-swap-block-hours`); the
  `Telco` port checks it (simulated by `sim_sim_swaps`).
- Customer tokens carry how they were earned. Card + PIN may change the PIN, freeze or block a card and move cash;
  anything that opens a door (PIN reset, unblock, reactivation, signature, fingerprints, card order after a number
  change) needs the code to the phone on record plus a thumbprint (403 `STEP_UP_REQUIRED`). Face + thumbprint without
  the code (phone lost) only allows the phone-number change.
- After a phone-number change the new number is not trusted for 24 h (`stm.services.mobile-change-hold-hours`):
  PIN reset, unblock, reactivation, signature and card orders answer 403 `MOBILE_RECENTLY_CHANGED`; both numbers get an SMS.
- Accounts are DORMANT after 12 months without customer activity (`stm.accounts.dormant-after-months`): debits are
  refused, deposits accepted, M-2 reactivates after re-identification and a current KYC. FROZEN and CLOSED go to a teller.
- Account opening registers the specimen signature (B-11, image checked for size and ink) and the thumbprint from B-5
  in core banking; pre-STM customers add theirs with M-3 and M-7, after which B-5 matches against the bank, not only IPRS.
- Sessions expire after 10 min idle; D-4 lists open items (escrowed notes, a card in the printer) the kiosk must finish.

## Not done yet

1. **Bank adapters**: only the simulators exist. Real core banking, IPRS/KRA, CMS/HSM, SMS and Paybill adapters
   implement the ports in `backend/` (profile `prod`), with timeouts and circuit breakers.
2. **HSM**: PIN blocks are checked by the simulator; production needs the bank's HSM (PIN translation, ZPK under DUKPT).
3. **mTLS** for D-1 must be terminated in front of the app (ingress) and pass the client certificate.
4. Call-control still trusts `X-Teller-Id`; it does not check the portal session.
5. Legal text, tariffs and rates in V4 are demo values.
6. Integration tests (Testcontainers) for the gateway; today `scripts` and the e2e checks cover it against a running app.
