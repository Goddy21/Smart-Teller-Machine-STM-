# Smart Teller kiosk client (TypeScript)

Typed client for the Smart Teller Gateway (`/stm/v1`, all operations of the Smart Teller Core Banking API
Specification), plus the signaling WebSocket for remote commands. No dependencies: only `fetch`, `crypto` and
`WebSocket`. Copy `src/` into the kiosk app, for example as `src/stm/`.

## Setup

`.env.local` (do not commit it):

```
VITE_STM_URL=                       # empty when using the Vite proxy below
VITE_STM_DEVICE=STM-NBO-MOI-03
VITE_STM_TOKEN=<terminal token from POST /api/v1/terminals/register>   # development only; production uses mTLS
```

Development: copy `vite.config.example.ts` into the app. It proxies `/stm` and `/ws` to `localhost:8080`, so the
browser sees one origin.

```ts
import { StmClient, KioskSignaling, StmApiError, errorScreen, newIdempotencyKey, devPinBlock } from "./stm";

export const stm = new StmClient({
  baseUrl: import.meta.env.VITE_STM_URL ?? "",
  deviceId: import.meta.env.VITE_STM_DEVICE,
  provisioningToken: import.meta.env.VITE_STM_TOKEN,   // leave out with mTLS
  softwareVersion: "2.0.0",
});
await stm.signIn();                                      // D-1; refreshed for you before it expires
```

The client keeps the device token, the session (`X-Session-Id`, from `startSession` to `endSession`), the customer
token (`X-Customer-Token`, set by `verifyPin`, `verifyBiometrics` for card services and `submitApplication`) and the
language (`Accept-Language`). It sends a fresh `X-Request-Id` on every call.

## Screens

```ts
// A0 Landing: report health, start on tap (or when a card is inserted)
const { disabledServices } = await stm.reportHealth({ modules: { cardReader: "OK", cashRecycler: "OK", /* … */ } });
const s = await stm.startSession({ trigger: "TOUCH", language: "en" });   // s.services: which A1 tiles to show
await stm.setLanguage("sw");                                               // the EN/SW toggle

// B1–B10 Open an account
const [product] = await stm.products();
const terms = await stm.terms(product.code);
const app = await stm.startApplication({ productCode: product.code, termsId: terms.termsId,
  consents: terms.consents.map((c) => ({ code: c.code, accepted: true })) });
const id = await stm.verifyDocument({ applicationId: app.applicationId, documentType: "NATIONAL_ID", country: "KE",
  ocr: { documentNumber, surname, dateOfBirth }, images: { front, back } });   // B5 shows id.registryRecord (photo)
await stm.verifyBiometrics({ applicationId: app.applicationId, verificationId: id.verificationId,
  face: { image, livenessScore }, fingerprint: { finger: "RIGHT_THUMB", template, quality } });
await stm.confirmIdentity(app.applicationId, id.verificationId);
const kyc = await stm.captureKyc(app.applicationId, { kraPin, mobile, address: { line1, town } });
if (kyc.status === "REFERRED") { /* "We need a moment": poll stm.application(id) until it moves on, or offer a teller */ }
const otp = await stm.sendOtp({ purpose: "ONBOARDING_PHONE", applicationId: app.applicationId });
await stm.verifyOtp(otp.otpRef, code);
await stm.captureSignature(app.applicationId, { image: signaturePng, strokes, capturedWith: "STYLUS" });
const opened = await stm.submitApplication(app.applicationId, { cardProduct: "CLASSIC", nameOnCard }, submitKey);
const order = await stm.orderCard({ service: "LINK_AND_ISSUE", accountId: opened.account.accountId,
  cardProduct: "CLASSIC", nameOnCard, applicationId: app.applicationId }, orderKey);
// C5 printing: poll stm.cardOrder(order.orderId) every second until status READY, then
await stm.cardDispensed(order.orderId, { outcome: "TAKEN", qualityCheck: "PASS" });
await stm.setPin(order.cardId, { pinBlock /* from the PIN pad */ });
await stm.activateCard(order.cardId);

// D1 Card + PIN, then balance, withdrawal, statements
const auth = await stm.cardInserted({ entryMode: "EMV_CHIP", panToken, emv });
const me = await stm.verifyPin({ cardAuthRef: auth.cardAuthRef, pinBlock });
const w = await stm.withdraw({ accountId, amount: { amount: "5000.00", currency: "KES" } }, withdrawKey);
// dispense w.dispense.mix, then
await stm.withdrawalTaken(w.withdrawalId, w.dispense.mix);         // or reverseWithdrawal(…) if retracted

// E2–E7 deposit
const target = await stm.validateDepositTarget({ type: "ACCOUNT", accountNumber });
const dep = await stm.openDeposit({ targetRef: target.targetRef, depositorMobile });
const counted = await stm.depositCounted(dep.depositId, { accepted: [{ denomination: "1000", count: 10 }] });
await stm.commitDeposit(dep.depositId, counted.total, commitKey);

// C1 Card services for an existing customer: prove who it is, then offer what the server says
const who = await stm.lookupCustomer({ by: "ID_DOCUMENT", documentType: "NATIONAL_ID", documentNumber });
const code = await stm.sendOtp({ purpose: "CARD_SERVICES", lookupRef: who.lookupRef });
await stm.verifyOtp(code.otpRef, typedCode);
await stm.verifyBiometrics({ customerId: who.customerId, lookupRef: who.lookupRef, fingerprint });   // strong customer token
const profile = await stm.customerProfile(who.customerId);     // profile.suggestions: ENROL_BIOMETRICS, REGISTER_SIGNATURE …
const myCards = await stm.cards(who.customerId);               // one row per card: statusText, actions
await stm.changePin(cardId, { currentPinBlock, newPinBlock }); // or resetPin, temporaryBlockCard, unblockCard, blockCard
await stm.reactivateAccount(accountId);                        // a DORMANT account
await stm.enrolBiometrics(who.customerId, { consent: true, fingerprints: [fingerprint] });
await stm.registerSignature(who.customerId, { image: signaturePng, strokes, capturedWith: "STYLUS" });
const change = await stm.startMobileChange(who.customerId, newMobile);
const code2 = await stm.sendOtp({ purpose: "MOBILE_CHANGE", changeRef: change.changeRef });   // goes to the NEW number
await stm.verifyOtp(code2.otpRef, typedCode2);
await stm.confirmMobileChange(who.customerId, change.changeRef);
// 403 STEP_UP_REQUIRED after card + PIN: run the four lines at the top, then call again. See docs/CARD-AND-CUSTOMER-SERVICES.md.

// any screen: "Talk to a teller" (or kiosk-video.js in gateway mode with headers: () => stm.authHeaders())
const call = await stm.requestTeller({ screen: "B6", flow: "ACCOUNT_OPENING", applicationId });
// poll stm.tellerCall(call.callId) every 2 s; when .media is set, join the LiveKit room (media.roomUrl, media.token)

await stm.endSession("CUSTOMER_FINISHED");   // E13 "Thank you"; do what openItems asks before the next customer
```

### KYC status and SIM swap (card services)

```ts
const lk = await stm.lookupCustomer({ by: "ACCOUNT_NUMBER", accountNumber });
const kyc = await stm.kycStatus(lk.customerId, lk.lookupRef);      // status, riskRating, kraPinOnFile, pep, flags
if (!kyc.selfServiceAllowed) { /* explain kyc.flags and offer "Talk to a teller" */ }
const swap = await stm.simSwapCheck({ lookupRef: lk.lookupRef });
if (!swap.otpAllowed) { /* recent SIM swap: no code can be sent; offer a teller */ }
```

### Idempotency keys

Calls that move money, issue a card or charge a fee take an `idempotencyKey`: `submitApplication`, `orderCard`,
`statement`, `commitDeposit`, `withdraw`, `reverseWithdrawal`, `fxConfirm`. Create the key when the customer
confirms (`newIdempotencyKey()`), keep it with the step, and pass the same key when retrying after a timeout or a
reload. The gateway answers with the first result instead of doing it twice. If W-2's answer never arrived, call
`reverseWithdrawalByKey(sameKey)`.

Every other write gets a key automatically, and the client sends it again once with the same key if the network drops
before the answer arrives, so the gateway replays the first answer instead of acting twice.

### Errors

Every failure is a `StmApiError` with `status`, `code`, `message` / `messageSw` (show `e.text(lang)` as is),
`retryable`, `requestId` and `details` (for example `attemptsLeft`, `cardAction: RETAIN | RETURN`).
`errorScreen(e)` picks the kind of screen:

| `errorScreen` | Codes | Kiosk |
|---|---|---|
| `RETRY_STEP` | OTP_INCORRECT, PIN_INCORRECT, PIN_POLICY, KRA_PIN_INVALID, LIMIT_EXCEEDED, INSUFFICIENT_FUNDS, QUOTE_EXPIRED, VALIDATION_ERROR; IDENTITY_MISMATCH / BIOMETRIC_NO_MATCH with attempts left | stay on the screen, "Try again" |
| `TELLER` | SCREENING_REFERRED, OTP_TRIES_EXCEEDED, SIM_SWAP_RISK; no attempts left | offer "Talk to a teller" |
| `SERVICE_DOWN` | CORE_UNAVAILABLE, SERVICE_UNAVAILABLE, OUT_OF_SERVICE, VIDEO_UNAVAILABLE, CASSETTE_UNAVAILABLE, NETWORK_ERROR | "Service unavailable", back to A1 |
| `SESSION` | SESSION_INVALID, DEVICE_UNAUTHORIZED, APPLICATION_EXPIRED | back to A0 |
| `CARD` | PIN_TRIES_EXCEEDED, CARD_BLOCKED, CARD_EXPIRED | keep or return the card per `details.cardAction` |
| `GENERIC` | anything else | "Something went wrong" with `requestId` |

### PIN blocks

On the machine the encrypting PIN pad builds the PIN block; the app never sees the PIN. For the demo profile's
simulated HSM, `devPinBlock("2580")` gives the clear ISO field `042580FFFFFFFFFF`. Do not ship it.

## Remote commands

```ts
const sig = new KioskSignaling(stm);
sig.on("COMMAND", (m) => {
  if (m.command === "LOCK") showOutOfService();
  if (m.command === "UNLOCK") showLanding();
  if (m.command === "REBOOT") location.reload();
});
await sig.connect();   // one-time ticket from POST /stm/v1/signaling/ticket, reconnects with back-off
```

## Device agent

`uploadJournal` (A-1, HMAC-signed batches), `reportCashPositions` (A-2), `reportPrintStep`, `receiptPrinted` and
`reportHealth` are meant for the machine's device agent rather than the screens.
