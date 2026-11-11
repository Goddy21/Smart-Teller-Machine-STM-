# Card care and existing customers at the Smart Teller

**For the kiosk app developer.** This covers everything a customer who *already banks with us* can do at the kiosk
beyond cash: check and manage their cards, change or reset a PIN, freeze and unfreeze a card, change their phone
number, wake a dormant account, and give the bank their fingerprints and signature if they joined before the
Smart Teller existed. Same layout as `ACCOUNT-OPENING.md`: full URL, what to send, what comes back, what to keep.

---

## 0. Before you start

| | |
|---|---|
| **Base URL** | `https://10.8.0.1:8443/stm/v1` (VPN only, private certificate: see `ACCOUNT-OPENING.md` section 0) |
| **Device ID** | `STM-NBO-MOI-03` |
| **Headers** | The same as account opening: `Authorization`, `X-Device-Id`, `X-Request-Id`, `Accept-Language`, `X-Session-Id` |
| **Customer token** | Every call in this document also needs `X-Customer-Token` (section 2 says how to get it) |
| **Try it** | Postman folders **4c · Card care** and **4d · Existing customer** |
| **Typed client** | `kiosk-ts/` has a method for every call below (section 9) |
| **Test customer** | **Mary Njoki Kariuki**, ID `25567890`: five cards in five states, a dormant and a frozen account, no fingerprints or signature on file (section 8) |

### Where this sits in the prototype

The prototype's **C1 Card Services** menu has three tiles: *Link new card*, *Replace card*, *New card setup*. Those
three are unchanged (calls C-1 to C-10 in `API.md`). This document adds the services around them. Suggested screens:

| Screen (suggested) | What the customer sees | Calls |
|---|---|---|
| **C1** menu | Two more tiles: **Manage my cards** and **My details** | M-1 |
| **Manage my cards** | One row per card: number, state in words, buttons | C-2, P-1 |
| → Change PIN | Current PIN, new PIN twice | P-2 |
| → Forgot PIN / card locked | New PIN twice | P-3 |
| → Freeze / Unfreeze | Reason picker, confirm | P-4, P-5 |
| → Lost, stolen or damaged | Confirm, then offer a replacement | C-10, then C-4 to C-9 |
| **My details** | Phone number, fingerprints, signature, accounts | M-1 |
| → Change phone number | New number, code sent to it | M-4, B-9, B-10, M-5 |
| → Register fingerprints | Consent, capture each finger | M-3 |
| → Register / update signature | The **B8** signature pad | M-6, M-7 |
| → Reactivate account | Confirm | M-2 |

### All the calls at a glance

| ID | What | Method | Full URL | Token needed |
|---|---|---|---|---|
| C-2 | All my cards | `GET` | `https://10.8.0.1:8443/stm/v1/customers/{customerId}/cards` | any |
| P-1 | One card's status | `GET` | `https://10.8.0.1:8443/stm/v1/cards/{cardId}/status` | any |
| P-2 | Change PIN | `POST` | `https://10.8.0.1:8443/stm/v1/cards/{cardId}/pin/change` | any |
| P-3 | Reset PIN (forgot it) | `POST` | `https://10.8.0.1:8443/stm/v1/cards/{cardId}/pin/reset` | **strong** |
| P-4 | Temporary block | `POST` | `https://10.8.0.1:8443/stm/v1/cards/{cardId}/temporary-block` | any |
| P-5 | Unblock | `POST` | `https://10.8.0.1:8443/stm/v1/cards/{cardId}/unblock` | **strong** |
| C-10 | Block for good (lost, stolen, damaged) | `POST` | `https://10.8.0.1:8443/stm/v1/cards/{cardId}/block` | any |
| — | All my accounts | `GET` | `https://10.8.0.1:8443/stm/v1/customers/{customerId}/accounts` | any |
| M-1 | What the bank has on file | `GET` | `https://10.8.0.1:8443/stm/v1/customers/{customerId}/profile` | any |
| M-2 | Reactivate a dormant account | `POST` | `https://10.8.0.1:8443/stm/v1/accounts/{accountId}/reactivate` | **strong** |
| M-3 | Enrol fingerprints | `POST` | `https://10.8.0.1:8443/stm/v1/customers/{customerId}/biometrics/enrol` | **strong** |
| M-4 | Start a phone-number change | `POST` | `https://10.8.0.1:8443/stm/v1/customers/{customerId}/mobile-change` | **strong** or *phone-lost* |
| M-5 | Confirm the phone-number change | `POST` | `https://10.8.0.1:8443/stm/v1/customers/{customerId}/mobile-change/{changeRef}/confirm` | **strong** or *phone-lost* |
| M-6 | Is a signature on file? | `GET` | `https://10.8.0.1:8443/stm/v1/customers/{customerId}/signature` | any |
| M-7 | Register or update the signature | `POST` | `https://10.8.0.1:8443/stm/v1/customers/{customerId}/signature` | **strong** |

---

## 1. The idea in one minute

The bank's systems hold three things that recognise a customer: the **phone number** (codes go to it), the
**fingerprints**, and the **specimen signature**. Customers who opened their account at a Smart Teller have all three
from day one. Customers from before the Smart Teller usually have only the phone number.

So the kiosk does two things for an existing customer:

1. **Proves who they are** against what we do have (a code to the phone on record, plus a thumbprint checked against
   the national registry, IPRS).
2. **Offers to complete their record** (fingerprints, signature) so the next visit is checked against the bank's own
   records, and offers what their cards and accounts need (a PIN reset, an unblock, a reactivation).

The server tells you what to offer. You do not work it out: every card comes with its `actions`, every account with
its `nextStep`, and the profile with its `suggestions`.

---

## 2. Proving who the customer is

There are three ways in, and they are not equally strong.

| How the customer got in | Token source | What it allows |
|---|---|---|
| **Card + PIN** (screens D1) | `CARD_PIN` | Balance, cash, statements, **change PIN** (P-2), **freeze a card** (P-4), **report lost** (C-10), view cards, accounts and profile |
| **Code to the phone + thumbprint** (screens C2, C3) | `VERIFIED_IDENTITY` | Everything above, **plus** PIN reset, unblock, reactivation, fingerprints, signature, phone-number change, card orders. This is the **strong** token. |
| **Face + thumbprint, no code** (phone lost) | `BIOMETRIC_ONLY` | **Only** the phone-number change (M-4, M-5) |

Why: a stolen card with a shoulder-surfed PIN must not be enough to reset things. Anything that *opens a door* (a new
PIN, an unblock, a new phone number) needs the code and the thumbprint. Anything that *closes a door* (freeze, report
lost) is allowed with any token, because it protects the customer.

If you call a strong service with a card + PIN token you get:

```json
403
{ "error": { "code": "STEP_UP_REQUIRED",
    "message": "For your safety, confirm it is you with a code to your phone and your thumbprint first.",
    "details": { "nextStep": "CUSTOMER_LOOKUP", "required": ["OTP", "FINGERPRINT"] } } }
```
👉 Show the message, run steps V1 to V4 below **in the same session**, then repeat the call.

### V1 · C2: find the customer (C-1)

```http
POST https://10.8.0.1:8443/stm/v1/customers/lookup
```
**Send** (by ID document, or by account number)
```json
{ "by": "ID_DOCUMENT", "documentType": "NATIONAL_ID", "documentNumber": "25567890" }
```
```json
{ "by": "ACCOUNT_NUMBER", "accountNumber": "0124417001" }
```
**Receive** `200`
```json
{ "lookupRef": "lk_684C1KW6HHC6", "customerId": "CIF00204417", "displayName": "M. N. Kariuki",
  "maskedMobile": "+254 7•• ••• 417", "accountsFound": 4, "verificationRequired": ["OTP", "FINGERPRINT"] }
```
✅ **Keep** `lookupRef` and `customerId`.
👉 Show "We will send a code to `maskedMobile`". Add a link **"I no longer have this phone"** (section 6).

### V2 · C3: send the code (B-9)

```http
POST https://10.8.0.1:8443/stm/v1/otp/send
```
**Send**
```json
{ "purpose": "CARD_SERVICES", "lookupRef": "lk_684C1KW6HHC6" }
```
**Receive** `201`
```json
{ "otpRef": "otp_1RRENK25ZNY0", "maskedMobile": "+254 7•• ••• 417", "length": 6, "expiresInSec": 300, "resendAfterSec": 60 }
```
👉 The code always goes to the number **on record**, never to one typed at the kiosk.
👉 `403 SIM_SWAP_RISK`: the SIM was swapped in the last 72 hours, so no code is sent. Offer "Talk to a teller".

### V3 · C3: check the code (B-10)

```http
POST https://10.8.0.1:8443/stm/v1/otp/verify
```
**Send** `{ "otpRef": "otp_1RRENK25ZNY0", "code": "123456" }`
**Receive** `200` `{ "verified": true, "attemptsLeft": 3 }`

### V4 · C3: thumbprint (B-5)

```http
POST https://10.8.0.1:8443/stm/v1/identity/biometrics/verify
```
**Send**
```json
{ "customerId": "CIF00204417", "lookupRef": "lk_684C1KW6HHC6",
  "fingerprint": { "finger": "RIGHT_THUMB", "template": "<base64>", "quality": 82 } }
```
**Receive** `200`
```json
{
  "fingerprint": { "result": "MATCH", "against": "IPRS", "score": 0.95 },
  "overall": "PASS",
  "attemptsLeft": 3,
  "customerToken": "cst_4gOxMzShX2b1…",
  "bankEnrolment": { "enrolled": false, "fingers": [], "offerEnrolment": true }
}
```
✅ **Keep** `customerToken`. Send it as `X-Customer-Token` on every call from here.
👉 `fingerprint.against` is `BANK` when the customer's fingerprints are enrolled with the bank, `IPRS` when we could
only check the national registry.
👉 `bankEnrolment.offerEnrolment` is `true` for a customer with no fingerprints at the bank. Offer **"Register your
fingerprints"** (M-3) so the next visit is checked against the bank.
👉 `422 BIOMETRIC_NO_MATCH`: try again. At `details.attemptsLeft` 0, offer "Talk to a teller".

### V5 · What to offer: the profile (M-1)

Call this right after V4 (or after card + PIN) to build the menu.

```http
GET https://10.8.0.1:8443/stm/v1/customers/CIF00204417/profile
```
**Receive** `200`
```json
{
  "customerId": "CIF00204417",
  "firstName": "Mary",
  "maskedMobile": "+254 7•• ••• 417",
  "mobileChangedAt": null,
  "mobileHold": { "active": false, "hoursLeft": 0 },
  "verifiedBy": "VERIFIED_IDENTITY",
  "kyc": { "status": "CURRENT", "nextReviewDue": "2028-09-10", "kraPinOnFile": true, "selfServiceAllowed": true, "flags": [] },
  "biometrics": { "enrolled": false, "fingers": [], "enrolledAt": null, "source": null },
  "signature": { "onFile": false, "capturedAt": null, "source": null },
  "accounts": { "total": 4, "active": 2, "dormant": 1, "restricted": 1 },
  "cards": { "total": 5, "active": 2, "needAttention": 3 },
  "suggestions": ["ENROL_BIOMETRICS", "REGISTER_SIGNATURE", "REACTIVATE_ACCOUNT"]
}
```
👉 Show one prompt per entry in `suggestions`:

| `suggestions` | Prompt | Then |
|---|---|---|
| `ENROL_BIOMETRICS` | "Register your fingerprints with the bank" | M-3 |
| `REGISTER_SIGNATURE` | "Add your signature" | M-7 |
| `REACTIVATE_ACCOUNT` | "You have an account that has been asleep. Wake it?" | M-2 |
| `KYC_REFRESH_WITH_TELLER` | "We need to update your details" | Talk to a teller (D-8) |

👉 `verifiedBy` tells you which token you hold (`CARD_PIN`, `VERIFIED_IDENTITY`). Use it to decide whether to run
V1 to V4 before a strong service instead of waiting for `STEP_UP_REQUIRED`.

---

## 3. Cards

### List the customer's cards (C-2)

A customer can hold several cards, on one account or on several.

```http
GET https://10.8.0.1:8443/stm/v1/customers/CIF00204417/cards
```
**Receive** `200` (two of Mary's five cards shown)
```json
{
  "cards": [
    {
      "cardId": "crd_8101",
      "maskedPan": "4512 •••• •••• 8101",
      "product": "CLASSIC",
      "status": "ACTIVE",
      "statusText": "Active",
      "expiry": "2029-03",
      "expired": false,
      "expiresSoon": false,
      "pinSet": true,
      "pinAttemptsLeft": 3,
      "linkedAccountId": "acc_M1SV",
      "linkedAccount": "Savings ••• 7001",
      "accountStatus": "ACTIVE",
      "usable": true,
      "actions": ["PIN_CHANGE", "PIN_RESET", "TEMPORARY_BLOCK", "BLOCK_AND_REPLACE"]
    },
    {
      "cardId": "crd_8104",
      "maskedPan": "4512 •••• •••• 8104",
      "product": "CLASSIC",
      "status": "BLOCKED",
      "statusText": "Blocked: damaged",
      "reason": "DAMAGED",
      "since": "2026-10-31T18:03:05.679Z",
      "expiry": "2027-06",
      "expired": false,
      "expiresSoon": false,
      "pinSet": true,
      "pinAttemptsLeft": 3,
      "linkedAccountId": "acc_M2CU",
      "linkedAccount": "Current ••• 7002",
      "accountStatus": "ACTIVE",
      "usable": false,
      "actions": ["REPLACEMENT"]
    }
  ]
}
```
👉 Show one row per card: `maskedPan`, `linkedAccount`, and **`statusText` as is** (it comes in the session language).
👉 Show one button per entry in `actions`. Nothing else.

**P-1** `GET https://10.8.0.1:8443/stm/v1/cards/crd_8104/status` returns the same object for one card. Use it to
refresh a row after an action.

### What each card state means

| `status` | `statusText` (en) | Meaning | `actions` |
|---|---|---|---|
| `ACTIVE` | Active | Works everywhere | `PIN_CHANGE`, `PIN_RESET`, `TEMPORARY_BLOCK`, `BLOCK_AND_REPLACE` |
| `INACTIVE_PIN_REQUIRED` | New card: set a PIN to start using it | Printed, no PIN yet | `NEW_CARD_SETUP` |
| `TEMP_BLOCKED` | Temporarily blocked by you | Frozen by the customer; can be lifted | `UNBLOCK`, `BLOCK_AND_REPLACE` |
| `PIN_LOCKED` | Locked after too many wrong PINs | 3 wrong PINs | `PIN_RESET`, `REPLACEMENT` |
| `BLOCKED` | Blocked: reported lost / stolen / damaged | Permanent. `reason` says why | `REPLACEMENT` |
| `EXPIRED` | Expired | Past `expiry` | `REPLACEMENT` |

`expiresSoon` is `true` in the last two months before expiry: show "Expires soon, replace it now?".
`usable` is `false` when the card is fine but its **account** is dormant or frozen (`accountStatus`).

| `actions` entry | Button | Calls |
|---|---|---|
| `PIN_CHANGE` | Change PIN | P-2 |
| `PIN_RESET` | Forgot PIN | P-3 |
| `TEMPORARY_BLOCK` | Freeze card | P-4 |
| `UNBLOCK` | Unfreeze card | P-5 |
| `BLOCK_AND_REPLACE` | Lost, stolen or damaged | C-10, then the replacement flow |
| `REPLACEMENT` | Replace card | Prototype "Replace card": C-4, C-5 (`service: "REPLACEMENT"`, `replacesCardId`), C-6 to C-9 |
| `NEW_CARD_SETUP` | Set up new card | Prototype "New card setup": C-8, C-9 |

### When a card is inserted (S-1)

The card reader flow already tells you the state before any PIN is asked:

| Answer to `POST /auth/card` | `details.cardAction` | What the kiosk does |
|---|---|---|
| `200` | — | Ask for the PIN |
| `403 CARD_TEMP_BLOCKED` | `RETURN` | Give the card back. "This card is frozen. Unfreeze it in Card Services." (`details.nextStep: UNBLOCK`) |
| `403 CARD_BLOCKED` | `RETAIN` | Keep the card (lost, stolen or damaged) |
| `403 CARD_EXPIRED` | `RETURN` | Give it back and offer a replacement |
| `403 PIN_LOCKED` | `RETURN` | The card was locked by wrong PINs earlier. Give it back. "Reset your PIN in Card Services." (`details.nextStep: PIN_RESET`) |

The third wrong PIN **at the kiosk** (`POST /auth/card/pin-verify`) still answers `403 PIN_TRIES_EXCEEDED` with
`cardAction: RETAIN`: the machine keeps the card and it goes to the branch, as before.

---

### P-2 · Change PIN (the customer knows the current PIN)

```http
POST https://10.8.0.1:8443/stm/v1/cards/crd_8101/pin/change
```
**Send**
```json
{ "currentPinBlock": "<PIN block of the current PIN>", "newPinBlock": "<PIN block of the new PIN>",
  "format": "ISO_9564_FORMAT_4", "keySerialNumber": "<from the PIN pad>", "confirmed": true }
```
**Receive** `200`
```json
{ "cardId": "crd_8101", "maskedPan": "4512 •••• •••• 8101", "status": "ACTIVE", "pinChanged": true, "reference": "STM-SVC-10001" }
```
👉 Ask for the new PIN twice on the PIN pad. Send `confirmed: true` only when both entries matched.
👉 The customer gets an SMS. Print a receipt with `reference` if they ask (R-1).

| HTTP | `error.code` | What the kiosk does |
|---|---|---|
| 401 | `PIN_INCORRECT` | Wrong current PIN. `details.attemptsLeft` tries remain. |
| 403 | `PIN_LOCKED` | Third wrong PIN: the card is now locked. Offer **Forgot PIN** (P-3). |
| 422 | `PIN_UNCHANGED` | The new PIN is the same as the old one. |
| 422 | `PIN_POLICY` | Too easy (1234, 1111 …). Ask for another. |
| 422 | `PIN_NOT_CONFIRMED` | The two entries did not match. |
| 403 | `CARD_TEMP_BLOCKED` | Frozen. Unfreeze first (P-5). |

### P-3 · Reset PIN (forgot it, or the card is locked) 🔒 strong token

```http
POST https://10.8.0.1:8443/stm/v1/cards/crd_8105/pin/reset
```
**Send**
```json
{ "newPinBlock": "<PIN block of the new PIN>", "format": "ISO_9564_FORMAT_4", "keySerialNumber": "<from the PIN pad>", "confirmed": true }
```
**Receive** `200`
```json
{ "cardId": "crd_8105", "maskedPan": "4512 •••• •••• 8105", "status": "ACTIVE", "pinReset": true,
  "reference": "STM-SVC-10002", "unlocked": true }
```
👉 Works on `ACTIVE` and `PIN_LOCKED` cards. `unlocked: true` means the card had been locked and works again.
👉 Errors: `STEP_UP_REQUIRED` (section 2), `PIN_POLICY`, `PIN_NOT_CONFIRMED`, `MOBILE_RECENTLY_CHANGED` (section 7).

### P-4 · Temporary block (freeze)

For "I can't find my card" or "I'm travelling". The customer can lift it later. Any token works.

```http
POST https://10.8.0.1:8443/stm/v1/cards/crd_8102/temporary-block
```
**Send** `{ "reason": "MISPLACED" }`  (`MISPLACED`, `SUSPICIOUS`, `TRAVEL` or `OTHER`)
**Receive** `200`
```json
{ "cardId": "crd_8102", "maskedPan": "4512 •••• •••• 8102", "status": "TEMP_BLOCKED", "temporarilyBlocked": true, "reference": "STM-SVC-10003" }
```
👉 `409 CARD_BLOCKED_PERMANENT`: the card is already blocked for good. Offer a replacement.

### P-5 · Unblock (unfreeze) 🔒 strong token

```http
POST https://10.8.0.1:8443/stm/v1/cards/crd_8103/unblock
```
**Send** nothing (or `{}`)
**Receive** `200`
```json
{ "cardId": "crd_8103", "maskedPan": "4512 •••• •••• 8103", "status": "ACTIVE", "unblocked": true, "reference": "STM-SVC-10004" }
```

| HTTP | `error.code` | What the kiosk does |
|---|---|---|
| 409 | `CARD_BLOCKED_PERMANENT` | Lost, stolen or damaged cards never come back (`details.reason`). Offer **Replace card**. |
| 409 | `WRONG_STATE` (`details.nextStep: PIN_RESET`) | The card is locked by wrong PINs, not frozen. Offer **Forgot PIN** (P-3). |
| 403 | `STEP_UP_REQUIRED` | Run V1 to V4, then call again. |

### C-10 · Lost, stolen or damaged: block for good

```http
POST https://10.8.0.1:8443/stm/v1/cards/crd_8102/block
```
**Send** `{ "reason": "LOST" }`  (`LOST`, `STOLEN`, `DAMAGED` or `EXPIRED`)
**Receive** `200`
```json
{ "cardId": "crd_8102", "maskedPan": "4512 •••• •••• 8102", "status": "BLOCKED",
  "blockedAt": "2026-11-10T21:03:16+03:00", "reason": "LOST", "reference": "STM-SVC-10005" }
```
👉 This cannot be undone. Ask "Are you sure?" first, and offer **Freeze** (P-4) as the gentler choice for "I can't
find it".
👉 Then offer a replacement straight away: fee quote C-4 (`service: "CARD_REPLACEMENT"`), order C-5
(`service: "REPLACEMENT"`, `replacesCardId: "crd_8102"`), print, PIN, activate.

---

## 4. Accounts: active, dormant, frozen

```http
GET https://10.8.0.1:8443/stm/v1/customers/CIF00204417/accounts
```
**Receive** `200` (two of four shown)
```json
{
  "accounts": [
    { "accountId": "acc_M1SV", "type": "SAVINGS", "masked": "••• 7001",
      "available": { "amount": "48200.00", "currency": "KES" },
      "status": "ACTIVE", "canTransact": true, "lastActivityAt": "2026-11-05T18:03:05.679Z",
      "hasCard": true, "cards": 3 },
    { "accountId": "acc_M3DM", "type": "SAVINGS", "masked": "••• 7003",
      "available": { "amount": "15300.00", "currency": "KES" },
      "status": "DORMANT", "canTransact": false, "lastActivityAt": "2025-03-10T18:03:05.679Z",
      "statusText": "Dormant: reactivate it to use it", "nextStep": "ACCOUNT_REACTIVATION",
      "hasCard": false, "cards": 0 }
  ]
}
```

| `status` | Meaning | `canTransact` | `nextStep` | The kiosk |
|---|---|---|---|---|
| `ACTIVE` | Used in the last 12 months | `true` | — | Normal |
| `DORMANT` | No customer activity for 12 months | `false` | `ACCOUNT_REACTIVATION` | Grey it out with a **Reactivate** button (M-2) |
| `FROZEN` | Restricted by the bank (court order, fraud review …) | `false` | `TELLER` | Grey it out with "Talk to a teller" |
| `CLOSED` | Closed | `false` | `TELLER` | Hide, or grey out |

- **Money out** of a dormant or frozen account (withdrawal, fees, card order) is refused: `403 ACCOUNT_DORMANT`
  (`details.nextStep: ACCOUNT_REACTIVATION`) or `403 ACCOUNT_RESTRICTED` (`details.offerTeller: true`).
- **Money in** (a cash deposit) is accepted on dormant and frozen accounts. It does not wake a dormant account.
- After **card + PIN**, the answer to S-2 lists only usable accounts in `accounts`. Any others come in
  `restrictedAccounts` (`accountId`, `masked`, `status`, `nextStep`): show them greyed out.
- Add `?eligibleFor=CARD` to get `eligible` and `reason` per account for a card order (C-3).

### M-2 · Reactivate a dormant account 🔒 strong token

```http
POST https://10.8.0.1:8443/stm/v1/accounts/acc_M3DM/reactivate
```
**Send** `{ "confirmed": true }`
**Receive** `200`
```json
{ "accountId": "acc_M3DM", "masked": "••• 7003", "type": "SAVINGS", "status": "ACTIVE", "reactivated": true, "reference": "STM-SVC-10009" }
```
👉 The fact that the customer was re-identified (code + thumbprint) is what makes this allowed. Show a confirm screen
first: "Reactivate Savings ••• 7003?".

| HTTP | `error.code` | What the kiosk does |
|---|---|---|
| 403 | `ACCOUNT_RESTRICTED` | Frozen or closed: the kiosk cannot lift it. Offer "Talk to a teller". |
| 403 | `KYC_REFRESH_REQUIRED` | The customer's details are out of date (`details.flags`). Offer "Talk to a teller". |
| 403 | `STEP_UP_REQUIRED` | Run V1 to V4, then call again. |
| 403 | `MOBILE_RECENTLY_CHANGED` | Section 7. |

---

## 5. Customers from before the Smart Teller: fingerprints and signature

### M-3 · Enrol fingerprints 🔒 strong token

Capture one to four fingers. Each print is checked against the national registry before the bank keeps it, so only
the customer's own fingers can be enrolled.

```http
POST https://10.8.0.1:8443/stm/v1/customers/CIF00204417/biometrics/enrol
```
**Send**
```json
{ "consent": true,
  "fingerprints": [
    { "finger": "RIGHT_THUMB", "template": "<base64>", "quality": 82 },
    { "finger": "LEFT_THUMB",  "template": "<base64>", "quality": 77 } ] }
```
**Receive** `200`
```json
{ "enrolled": true, "fingers": ["LEFT_THUMB", "RIGHT_THUMB"], "enrolledAt": "2026-11-10T18:03:17.721Z", "reference": "STM-SVC-10006" }
```
👉 Show a consent screen first ("The bank will keep your fingerprints to recognise you"). `consent` must be `true`.
👉 From the next visit, V4 answers `"against": "BANK"`.

| HTTP | `error.code` | What the kiosk does |
|---|---|---|
| 422 | `CONSENT_REQUIRED` | The customer did not agree. Go back. |
| 422 | `BIOMETRIC_QUALITY_LOW` | `details.finger` was not clear (quality under 50). Capture that finger again. |
| 422 | `BIOMETRIC_NO_MATCH` | `details.finger` does not match the registry. Nothing was saved. Offer "Talk to a teller". |

### M-6 · Is there a signature on file?

```http
GET https://10.8.0.1:8443/stm/v1/customers/CIF00204417/signature
```
**Receive** `200`
```json
{ "onFile": false, "signatureId": null, "capturedAt": null, "source": null }
```
👉 The image itself never comes back to the kiosk. Staff see it in the portal.
👉 `source`: `BRANCH` (signed at a branch), `STM_ONBOARDING` (account opened at a kiosk), `STM_UPDATE`.

### M-7 · Register or update the signature 🔒 strong token

Use the same pad as screen **B8**.

```http
POST https://10.8.0.1:8443/stm/v1/customers/CIF00204417/signature
```
**Send**
```json
{ "image": "<base64 PNG or JPEG of the signature pad>", "strokes": 4, "capturedWith": "STYLUS" }
```
**Receive** `200`
```json
{ "signatureId": "sig_R1ME4F2F3ATZ", "capturedAt": "2026-11-10T18:03:18.801Z", "replacedPrevious": false, "reference": "STM-SVC-10007" }
```
👉 The previous signature is kept by the bank as superseded; it is never deleted.

### What makes a signature image acceptable (also for account opening, step 14)

The server now looks at the image. It answers `422 SIGNATURE_UNREADABLE` with `details.reason`:

| `details.reason` | Why | Fix |
|---|---|---|
| `EMPTY` | No image data | Send the pad as PNG or JPEG, base64 |
| `TOO_SMALL` | Smaller than 120 × 40 pixels | Export the pad at its real size (for example 600 × 200) |
| `BLANK` | Fewer than 0.2 % of the pixels are ink | The customer tapped "Done" without signing. Keep **Done** disabled until there is a stroke. |
| `FILLED` | More than 60 % of the pixels are ink | Scribbled over. Clear the pad and ask again. |

A transparent PNG with dark strokes, or dark strokes on a white background, both work. `retryable` is `true`: stay
on the pad, show `message`, clear it.

---

## 6. Changing the phone number

The number matters more than anything else on the account, because every code goes to it. Two cases.

### Case A: the customer still has the old phone

1. Verify with V1 to V4 (the code goes to the **old** number). You now hold the strong token.
2. **M-4** with the new number.
3. **B-9** with `purpose: "MOBILE_CHANGE"`: the code goes to the **new** number, proving the customer holds it.
4. **B-10** with that code.
5. **M-5** to make the change.

### Case B: the phone is lost ("I no longer have this phone")

1. **V1** (lookup).
2. Skip the code. Call **B-5** with `"purpose": "MOBILE_CHANGE"`, a **face** and a **fingerprint**:

```http
POST https://10.8.0.1:8443/stm/v1/identity/biometrics/verify
```
**Send**
```json
{ "customerId": "CIF00482913", "lookupRef": "lk_…", "purpose": "MOBILE_CHANGE",
  "face": { "image": "<base64>", "livenessScore": 0.97 },
  "fingerprint": { "finger": "RIGHT_THUMB", "template": "<base64>", "quality": 82 } }
```
**Receive** `200`
```json
{ "face": { "result": "MATCH", "against": "IPRS_PHOTO", "score": 0.992 },
  "fingerprint": { "result": "MATCH", "against": "IPRS", "score": 0.95 },
  "overall": "PASS", "attemptsLeft": 3,
  "customerToken": "cst_wGcPArCdI8X2…",
  "bankEnrolment": { "enrolled": false, "fingers": [], "offerEnrolment": false },
  "allows": ["MOBILE_CHANGE"] }
```
👉 This token works **only** for M-4 and M-5. Everything else answers `403 STEP_UP_REQUIRED`.

3. Then M-4, B-9, B-10, M-5 exactly as in case A.

### M-4 · Start the change

```http
POST https://10.8.0.1:8443/stm/v1/customers/CIF00204417/mobile-change
```
**Send** `{ "newMobile": "0711000999" }`  (also accepted: `+254711000999`, `254711000999`)
**Receive** `200`
```json
{ "changeRef": "mch_QV6QT6489Q3F", "maskedCurrentMobile": "+254 7•• ••• 417", "maskedNewMobile": "+254 7•• ••• 999",
  "nextStep": "OTP", "otpPurpose": "MOBILE_CHANGE" }
```
✅ **Keep** `changeRef`.

| HTTP | `error.code` | What the kiosk does |
|---|---|---|
| 422 | `MOBILE_UNCHANGED` | That is already the number on record. |
| 409 | `MOBILE_IN_USE` | Another customer has this number. Offer "Talk to a teller". |
| 403 | `SIM_SWAP_RISK` | The **new** SIM was swapped in the last 72 hours. Offer "Talk to a teller". |
| 403 | `MOBILE_RECENTLY_CHANGED` | The number was already changed in the last 24 hours. |

### Send and check the code on the new number (B-9, B-10)

```http
POST https://10.8.0.1:8443/stm/v1/otp/send
```
**Send** `{ "purpose": "MOBILE_CHANGE", "changeRef": "mch_QV6QT6489Q3F" }`
**Receive** `201` `{ "otpRef": "otp_2JGBATJK05YD", "maskedMobile": "+254 7•• ••• 999", "length": 6, "expiresInSec": 300, "resendAfterSec": 60 }`

```http
POST https://10.8.0.1:8443/stm/v1/otp/verify
```
**Send** `{ "otpRef": "otp_2JGBATJK05YD", "code": "123456" }`
**Receive** `200` `{ "verified": true, "attemptsLeft": 3 }`

### M-5 · Confirm the change

```http
POST https://10.8.0.1:8443/stm/v1/customers/CIF00204417/mobile-change/mch_QV6QT6489Q3F/confirm
```
**Send** nothing (or `{}`)
**Receive** `200`
```json
{ "changed": true, "maskedMobile": "+254 7•• ••• 999", "holdHours": 24, "reference": "STM-SVC-10008" }
```
👉 An SMS goes to **both** numbers. The one to the old number is how a customer learns of a change they did not make.
👉 Tell the customer: "For your safety, some services are on hold for `holdHours` hours" (section 7).
👉 `409 WRONG_STATE` (`details.nextStep: OTP`): the code on the new number was not verified yet.

---

## 7. The 24-hour hold after a phone-number change

For `holdHours` after M-5 the new number is not yet trusted. During the hold these answer
`403 MOBILE_RECENTLY_CHANGED` with `details.hoursLeft` and `details.offerTeller: true`:

PIN reset (P-3) · unblock (P-5) · reactivation (M-2) · signature (M-7) · card orders (C-5) · a second number change (M-4)

Still allowed: PIN change with the current PIN, freeze, report lost, balance, cash, statements.

👉 Show `message` and offer "Talk to a teller". `mobileHold` in the profile (M-1) tells you in advance, so you can
grey the buttons out and show "Available in `hoursLeft` hours".

---

## 8. Every error in this document

All errors have the usual shape (`error.code`, `message`, `messageSw`, `retryable`, `details`). **Show `message` or
`messageSw` to the customer as is.**

| HTTP | `error.code` | `details` | What the kiosk does |
|---|---|---|---|
| 403 | `STEP_UP_REQUIRED` | `nextStep: CUSTOMER_LOOKUP`, `required` | Run V1 to V4, repeat the call |
| 403 | `CARD_TEMP_BLOCKED` | `nextStep: UNBLOCK`, `cardAction: RETURN` | Offer **Unfreeze** |
| 409 | `CARD_BLOCKED_PERMANENT` | `reason`, `nextStep: CARD_REPLACEMENT` | Offer **Replace card** |
| 403 | `PIN_LOCKED` | `nextStep: PIN_RESET` | Offer **Forgot PIN** |
| 401 | `PIN_INCORRECT` | `attemptsLeft` | Try again |
| 422 | `PIN_UNCHANGED`, `PIN_POLICY`, `PIN_NOT_CONFIRMED` | — | Ask for another PIN |
| 403 | `ACCOUNT_DORMANT` | `accountId`, `nextStep: ACCOUNT_REACTIVATION` | Offer **Reactivate** |
| 403 | `ACCOUNT_RESTRICTED` | `accountStatus`, `offerTeller` | Talk to a teller |
| 403 | `KYC_REFRESH_REQUIRED` | `flags`, `offerTeller` | Talk to a teller |
| 403 | `MOBILE_RECENTLY_CHANGED` | `hoursLeft`, `offerTeller` | Explain the hold; talk to a teller |
| 409 | `MOBILE_IN_USE` | `offerTeller` | Talk to a teller |
| 422 | `MOBILE_UNCHANGED` | — | Ask for a different number |
| 403 | `SIM_SWAP_RISK` | — | Talk to a teller |
| 422 | `SIGNATURE_UNREADABLE` | `reason` | Clear the pad, sign again |
| 422 | `BIOMETRIC_QUALITY_LOW` | `finger` | Capture that finger again |
| 422 | `BIOMETRIC_NO_MATCH` | `attemptsLeft` or `finger`, `offerTeller` | Try again; at 0, talk to a teller |
| 422 | `CONSENT_REQUIRED` | — | Back to the consent screen |
| 403 | `FORBIDDEN` | — | The card or account belongs to someone else. A bug in the kiosk app. |

In the typed client, `errorScreen(e)` maps these to `RETRY_STEP`, `TELLER`, `CARD` and so on.

---

## 9. Test data and the typed client

### Mary Njoki Kariuki (a customer since 2014, from before the Smart Teller)

| | |
|---|---|
| ID number | `25567890` |
| Customer ID | `CIF00204417` |
| Phone on record | `+254733204417` |
| PIN of every card | `2468` → PIN block `042468FFFFFFFFFF` |
| On file | No fingerprints, no signature. KYC current. |

| Account | Number | State |
|---|---|---|
| `acc_M1SV` Savings | `0124417001` | Active, KES 48,200 |
| `acc_M2CU` Current | `0124417002` | Active, KES 9,750 |
| `acc_M3DM` Savings | `0124417003` | **Dormant** (no activity for 20 months) |
| `acc_M4FZ` Current | `0124417004` | **Frozen** (court order) |

| Card | Reader token (`panToken`) | Account | State |
|---|---|---|---|
| `crd_8101` Classic | `tok_4512xxxx8101` | Savings 7001 | Active |
| `crd_8102` Gold | `tok_4512xxxx8102` | Savings 7001 | Active (second card on the same account) |
| `crd_8103` Classic | `tok_4512xxxx8103` | Current 7002 | **Temporarily blocked** (misplaced) |
| `crd_8104` Classic | `tok_4512xxxx8104` | Current 7002 | **Blocked: damaged** |
| `crd_8105` Classic | `tok_4512xxxx8105` | Savings 7001 | **PIN locked** |

Other customers: **Achieng** (ID `32145678`, card `tok_4512xxxx4521`, PIN `2580`) has a signature from her branch and
no fingerprints. **Brian** (ID `27654321`) has a stolen and an expired card, and a SIM swapped 20 hours ago, so no
code can be sent to him. A customer who **opens an account at the kiosk** gets signature and thumbprint on file
at once (the answer to B-12 has `"mandate": { "signatureRegistered": true, "biometricsEnrolled": true }`).

- **OTP:** always `123456`.
- **Fingerprint and face:** any base64 image matches, except for the test identities that are built to fail.
- **Start again:** the demo data changes as you use it (Mary's locked card gets unlocked …). Ask Blue River to reset
  the database, or use the portal's **Simulator** page to see the current state of every customer.

### The typed client (`kiosk-ts`)

```ts
// C2, C3: prove who it is (code + thumbprint)
const who = await stm.lookupCustomer({ by: "ID_DOCUMENT", documentType: "NATIONAL_ID", documentNumber });
const otp = await stm.sendOtp({ purpose: "CARD_SERVICES", lookupRef: who.lookupRef });
await stm.verifyOtp(otp.otpRef, code);
const bio = await stm.verifyBiometrics({ customerId: who.customerId, lookupRef: who.lookupRef,
  fingerprint: { finger: "RIGHT_THUMB", template, quality } });      // sets the customer token

// the menu
const profile = await stm.customerProfile(who.customerId);          // profile.suggestions
const cards = await stm.cards(who.customerId);                      // card.statusText, card.actions
const accounts = await stm.accounts(who.customerId);                // account.status, account.nextStep

// cards
await stm.changePin(cardId, { currentPinBlock, newPinBlock });
await stm.resetPin(cardId, { newPinBlock });
await stm.temporaryBlockCard(cardId, "MISPLACED");
await stm.unblockCard(cardId);
await stm.blockCard(cardId, "LOST");                                // then the replacement flow

// the customer's record
await stm.reactivateAccount(accountId);
await stm.enrolBiometrics(who.customerId, { consent: true, fingerprints: [{ finger: "RIGHT_THUMB", template, quality }] });
await stm.registerSignature(who.customerId, { image: signaturePng, strokes, capturedWith: "STYLUS" });

// phone number
const change = await stm.startMobileChange(who.customerId, newMobile);
const otp2 = await stm.sendOtp({ purpose: "MOBILE_CHANGE", changeRef: change.changeRef });
await stm.verifyOtp(otp2.otpRef, code2);
await stm.confirmMobileChange(who.customerId, change.changeRef);

// a strong service after card + PIN
try { await stm.resetPin(cardId, { newPinBlock }); }
catch (e) { if (e instanceof StmApiError && e.code === "STEP_UP_REQUIRED") { /* run C2, C3 above, then call again */ } else throw e; }
```
