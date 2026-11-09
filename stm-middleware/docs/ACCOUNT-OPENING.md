# Opening a bank account at the Smart Teller: kiosk integration

For the kiosk app developer. It covers prototype screens **B1 to B10**: every call, in order, with the payload to send,
the answer to expect and what to do when something goes wrong. The full API is in `docs/API.md`. To try every call
with real data, use the Postman collection `docs/STM-Gateway.postman_collection.json` (folders 0, 1, 2 and 3).
The typed client in `clients/kiosk-ts` wraps all of these calls.

Base URL: `https://<middleware>/stm/v1` (development: `http://<laptop>:8080/stm/v1`).

## 1. Before the customer arrives

**Sign the machine in (D-1)**, at boot and again before the 15-minute token expires.

```http
POST /stm/v1/auth/device/token
Authorization: Bearer <terminal token>        ← development only; production uses the machine's mTLS certificate
Content-Type: application/json

{ "deviceId": "STM-NBO-MOI-03", "softwareVersion": "2.0.0", "nonce": "<uuid>" }
```
```json
200 { "accessToken": "dvt_…", "expiresIn": 900, "branchCode": "012", "branchName": "Moi Avenue" }
```

**Headers on every call after that:**

| Header | Value |
|---|---|
| `Authorization` | `Bearer <accessToken>` |
| `X-Device-Id` | `STM-NBO-MOI-03` |
| `X-Request-Id` | a new UUID for each call |
| `Accept-Language` | `en` or `sw` (the customer's choice; error messages follow it) |
| `X-Session-Id` | the `sessionId` from D-2, from the start of the session to its end |
| `X-Customer-Token` | only after B-12, for the card steps |
| `Idempotency-Key` | only on B-12 and C-5: a UUID made when the customer confirms, **reused unchanged if you retry** |

## 2. The happy path, screen by screen

### A0 → A1 · Start the session (D-2)
```http
POST /stm/v1/sessions
{ "trigger": "TOUCH", "language": "en" }
```
```json
201 { "sessionId": "ses_W963M9YN9ASC", "expiresAt": "2026-11-06T11:53:01Z", "inactivityTimeoutSec": 60,
      "services": { "accountOpening": true, "cardServices": true, "cashDeposit": true,
                    "cashWithdrawal": true, "forex": true, "statements": true } }
```
Show the "Open an account" tile only if `services.accountOpening` is true. The session lasts at most 30 minutes. Show
your own "Are you still there?" prompt after `inactivityTimeoutSec` with no touch.
Language toggle: `PATCH /stm/v1/sessions/{sessionId}` `{ "language": "sw" }`.

### B1 · Select account type (B-1)
```http
GET /stm/v1/products
```
```json
200 { "products": [ { "code": "SAV_PERSONAL", "name": "Personal Savings Account",
                      "minOpeningBalance": { "amount": "0.00", "currency": "KES" },
                      "features": ["No minimum opening balance", "Free mobile and internet banking", "Debit card issued today"],
                      "cardProducts": ["CLASSIC", "GOLD"] },
                    { "code": "CUR_PERSONAL", "name": "Current Account", … } ] }
```
Names and features come in the session language.

### B2 · Terms and consent (B-2, then B-3)
```http
GET /stm/v1/legal/terms?product=SAV_PERSONAL
```
```json
200 { "termsId": "TC-ACC-2026.11", "version": "2026.11", "language": "en",
      "sections": [ { "title": "1. About these terms", "body": "These terms govern the account you are opening today…" } ],
      "consents": [ { "code": "ACCOUNT_TERMS", "required": true, "label": "I agree to the account terms" },
                    { "code": "DATA_PROCESSING_DPA2019", "required": true, "label": "…" },
                    { "code": "BIOMETRIC_PROCESSING", "required": true, "label": "…" } ],
      "audioUrl": "https://…/TC-ACC-2026.11-en.mp3" }
```
Show one tick box per consent, using its `label`. Enable "Continue" when every `required` box is ticked, then:
```http
POST /stm/v1/onboarding/applications
{ "productCode": "SAV_PERSONAL", "termsId": "TC-ACC-2026.11", "acceptedAt": "2026-11-06T12:07:41+03:00",
  "consents": [ { "code": "ACCOUNT_TERMS", "accepted": true },
                { "code": "DATA_PROCESSING_DPA2019", "accepted": true },
                { "code": "BIOMETRIC_PROCESSING", "accepted": true } ] }
```
```json
201 { "applicationId": "app_PS03612K55WY", "status": "CONSENTED", "expiresAt": "2026-11-06T11:53:02Z" }
```
Keep `applicationId`. The application expires after 30 minutes.

### B3 · ID document capture (B-4)
Send what the ID scanner read (OCR for a Kenyan ID, MRZ for a passport) and the images as base64.
```http
POST /stm/v1/identity/documents/verify
{ "applicationId": "app_PS03612K55WY", "documentType": "NATIONAL_ID", "country": "KE",
  "ocr": { "documentNumber": "29876543", "serialNumber": "240098765", "surname": "KAMAU",
           "givenNames": "AMARA NJERI", "dateOfBirth": "1996-07-22" },
  "mrz": null,
  "images": { "front": "<base64>", "back": "<base64>", "uv": "<base64>" } }
```
For a passport use `"documentType": "PASSPORT"` and `"mrz": { "line1": "…", "line2": "…" }`.
```json
200 { "verificationId": "idv_WR8PNGAVWY2G", "result": "MATCH",
      "documentChecks": { "expiry": "PASS", "tamper": "PASS" },
      "registryRecord": { "source": "IPRS", "fullName": "Amara Njeri Kamau", "dateOfBirth": "1996-07-22", "gender": "F",
                          "idNumber": "29876543", "placeOfBirth": "Nyeri", "nationality": "KEN",
                          "photo": "<base64 JPEG>" },
      "existingCustomer": false }
```
Keep `verificationId` and `registryRecord`, because screen B5 shows them.

### B4 · Biometric authentication (B-5)
```http
POST /stm/v1/identity/biometrics/verify
{ "applicationId": "app_PS03612K55WY", "verificationId": "idv_WR8PNGAVWY2G",
  "face": { "image": "<base64>", "livenessScore": 0.97, "livenessVendor": "sdk" },
  "fingerprint": { "finger": "RIGHT_THUMB", "template": "<base64>", "quality": 82 } }
```
```json
200 { "face": { "against": "IPRS_PHOTO", "score": 0.992, "result": "MATCH" },
      "fingerprint": { "against": "IPRS", "score": 0.95, "result": "MATCH" },
      "overall": "PASS", "attemptsLeft": 3 }
```
`finger`: RIGHT_THUMB, LEFT_THUMB, RIGHT_INDEX or LEFT_INDEX. `livenessScore` is 0 to 1, from your liveness SDK.

### B5 · Review IPRS details (B-6)
Show `registryRecord.fullName`, date of birth, ID number and `photo`. "Confirm":
```http
POST /stm/v1/onboarding/applications/app_PS03612K55WY/identity-confirm
{ "verificationId": "idv_WR8PNGAVWY2G", "confirmed": true }
```
```json
200 { "applicationId": "app_PS03612K55WY", "status": "IDENTITY_VERIFIED" }
```
"Edit / rescan" sends `"confirmed": false`. The status goes back to `CONSENTED`; return to B3.

### B6 · KYC form (B-7, then B-8)
Check the KRA PIN as soon as it is typed (11 characters):
```http
POST /stm/v1/kyc/kra-pin/validate
{ "kraPin": "A029876543K", "idNumber": "29876543" }
```
```json
200 { "valid": true, "taxpayerName": "AMARA NJERI KAMAU", "matchesId": true, "status": "ACTIVE" }
```
Then save the form:
```http
PATCH /stm/v1/onboarding/applications/app_PS03612K55WY/kyc
{ "kraPin": "A029876543K", "mobile": "0712 345 678",
  "address": { "line1": "Moi Avenue", "town": "Nairobi", "county": "Nairobi" },
  "occupation": "Teacher", "sourceOfFunds": "Salary" }
```
```json
200 { "applicationId": "app_PS03612K55WY", "status": "KYC_CAPTURED", "screening": { "pep": "CLEAR", "sanctions": "CLEAR" } }
```
`mobile` accepts `07…`, `01…` or `+254…`. `occupation` and `sourceOfFunds` are optional.
**If `status` is `REFERRED`, do not continue.** See "Screening referral" below.

### B7 · Phone verification (B-9, then B-10)
```http
POST /stm/v1/otp/send
{ "purpose": "ONBOARDING_PHONE", "applicationId": "app_PS03612K55WY", "channel": "SMS" }
```
```json
201 { "otpRef": "otp_KFH22DMP4E28", "maskedMobile": "+254 7•• ••• 678", "length": 6, "expiresInSec": 300, "resendAfterSec": 60 }
```
Show `maskedMobile`, draw `length` boxes and enable "Resend" after `resendAfterSec`. Resend is the same call again.
```http
POST /stm/v1/otp/verify
{ "otpRef": "otp_KFH22DMP4E28", "code": "123456" }
```
```json
200 { "verified": true, "attemptsLeft": 2 }
```
In the demo profile every code is `123456`.

### B8 · Digital signature (B-11)
```http
POST /stm/v1/onboarding/applications/app_PS03612K55WY/signature
{ "image": "<base64 PNG of the signature pad>", "strokes": 14, "capturedWith": "STYLUS" }
```
```json
200 { "signatureId": "sig_GV76ZD1DKS13", "status": "SIGNATURE_CAPTURED" }
```
`capturedWith`: STYLUS or FINGER.

### B9 · Account created (B-12), then the card (C-5, C-6, C-7)
Open the account. Make the `Idempotency-Key` when the customer taps "Open my account", and keep it until you get an answer.
```http
POST /stm/v1/onboarding/applications/app_PS03612K55WY/submit
Idempotency-Key: 3f6c0d0e-…
{ "productCode": "SAV_PERSONAL", "branchCode": "012", "cardProduct": "CLASSIC", "nameOnCard": "AMARA N KAMAU" }
```
```json
201 { "applicationId": "app_PS03612K55WY", "status": "ACCOUNT_OPENED", "customerId": "CIF00482914",
      "account": { "accountId": "acc_XER71C0YH7PC", "accountNumber": "012345671000", "display": "0123 4567 1000",
                   "product": "SAV_PERSONAL", "branch": "Moi Avenue (012)" },
      "customerToken": "cst_Y8Fv…", "cardOrderAllowed": true, "nameOnCard": "AMARA N KAMAU",
      "reference": "STM-ACC-10001", "smsSent": true }
```
Show `account.display`. From now on send `X-Customer-Token: <customerToken>`. `nameOnCard`: capital letters,
spaces, `. ' -`, at most 26 characters.

Order the card (free with a new account, because `applicationId` is given):
```http
POST /stm/v1/cards/orders
X-Customer-Token: cst_Y8Fv…
Idempotency-Key: 9a1b…
{ "service": "LINK_AND_ISSUE", "accountId": "acc_XER71C0YH7PC", "cardProduct": "CLASSIC",
  "nameOnCard": "AMARA N KAMAU", "applicationId": "app_PS03612K55WY" }
```
```json
201 { "orderId": "co_ZK7JCH825EY1", "cardId": "crd_7910PPJSPNB0", "maskedPan": "4512 •••• •••• 2979",
      "fee": { "amount": "0.00", "currency": "KES", "waived": true, "posted": false, "transactionRef": null },
      "personalisation": { "payloadForPrinter": "<printer payload>", "jobId": "pj_AB7NY2ZAWR1A" },
      "reference": "STM-CRD-10002" }
```
Send `personalisation.payloadForPrinter` to the card printer. Then poll every second for the "Printing your card" bar:
```http
GET /stm/v1/cards/orders/co_ZK7JCH825EY1
```
```json
200 { "orderId": "co_ZK7JCH825EY1", "status": "PERSONALISING",
      "steps": [ { "code": "PICK_BLANK", "status": "DONE" }, { "code": "PRINT_NAME", "status": "IN_PROGRESS" },
                 { "code": "PROGRAM_EMV_CHIP", "status": "PENDING" }, { "code": "QUALITY_CHECK", "status": "PENDING" } ] }
```
When `status` is `READY`, present the card. When the customer takes it:
```http
POST /stm/v1/cards/orders/co_ZK7JCH825EY1/dispense
{ "outcome": "TAKEN", "qualityCheck": "PASS", "cardSerial": "CS-00921844" }
```
```json
200 { "orderId": "co_ZK7JCH825EY1", "status": "DISPENSED", "cardStatus": "INACTIVE_PIN_REQUIRED" }
```
`outcome`: TAKEN, RETAINED (not taken, pulled back in) or SPOILED (printing failed). Both of the last two cancel the card.

### B10 · Instant PIN creation (C-8, C-9), receipt (R-1), finish (D-4)
The PIN pad builds the PIN block. The app never sees the PIN.
```http
POST /stm/v1/cards/crd_7910PPJSPNB0/pin
X-Customer-Token: cst_Y8Fv…
{ "pinBlock": "<from the PIN pad>", "format": "ISO_9564_FORMAT_4", "keySerialNumber": "FFFF9876543210E00008",
  "confirmed": true, "policyChecks": { "notSequential": true, "notRepeated": true } }
```
```json
200 { "cardId": "crd_7910PPJSPNB0", "pinSet": true }
```
```http
POST /stm/v1/cards/crd_7910PPJSPNB0/activate
X-Customer-Token: cst_Y8Fv…
{ "channel": "STM" }
```
```json
200 { "cardId": "crd_7910PPJSPNB0", "status": "ACTIVE", "maskedPan": "4512 •••• •••• 2979",
      "linkedAccount": "Savings ••• 1000",
      "limits": { "atmDaily": { "amount": "40000.00", "currency": "KES" }, "posDaily": { "amount": "150000.00", "currency": "KES" } },
      "reference": "STM-CRD-10002" }
```
```http
POST /stm/v1/receipts
{ "transactionRef": "STM-CRD-10002", "delivery": "PRINT" }
```
```json
201 { "receiptId": "rc_KJ00…", "lines": ["BLUE RIVER SMART TELLER  STM-NBO-MOI-03", "06 NOV 2026 13:59", "…"],
      "qr": "https://bank.example/r/rc_KJ00…", "printStatusUrl": "/stm/v1/receipts/rc_KJ00…" }
```
Print `lines` one per line, with `qr` as a QR code. `delivery` can also be SMS or NONE. Then end the session:
```http
POST /stm/v1/sessions/ses_W963M9YN9ASC/end
{ "reason": "CUSTOMER_FINISHED", "cardRetained": false }
```
```json
200 { "ended": true, "openItems": [] }
```

## 3. When things go wrong

Every error has the same shape. Show `message` (or `messageSw` in Kiswahili) to the customer as is:
```json
{ "error": { "code": "BIOMETRIC_NO_MATCH", "message": "Your face or thumbprint did not match the registry record.",
             "messageSw": "Uso au alama ya kidole chako haikulingana na rekodi ya sajili.",
             "retryable": true, "requestId": "6f1c…", "details": { "attemptsLeft": 2 } } }
```

| Screen | Answer | What the kiosk does |
|---|---|---|
| B2 | 422 `CONSENT_REQUIRED` | A required box was not ticked. Stay on B2. |
| B2 | 409 `TERMS_OUTDATED` | Reload the terms (B-2) and ask again. |
| B3 | 409 `EXISTING_CUSTOMER` | The person already banks with us. Show the message and offer **Card services**. |
| B3 | 422 `IDENTITY_MISMATCH`, `details.attemptsLeft` | The document does not match the registry, or it failed the tamper check. Rescan. At 0, offer "Talk to a teller". |
| B4 | 422 `BIOMETRIC_NO_MATCH`, `details.attemptsLeft` | Try again. At 0, see "Face does not match" below. |
| B6 | 422 `KRA_PIN_INVALID` | Mark the KRA PIN field and let the customer correct it. |
| B6 | 200 with `status: "REFERRED"` | See "Screening referral" below. |
| B7 | 429 `RESEND_TOO_SOON`, `details.retryAfterSec` | Keep "Resend" disabled for that many seconds. |
| B7 | 401 `OTP_INCORRECT`, `details.attemptsLeft` | Clear the boxes and try again. |
| B7 | 401 `OTP_EXPIRED` | Send a new code (B-9). |
| B7 | 403 `OTP_TRIES_EXCEEDED` | Too many wrong codes. Offer "Talk to a teller" or cancel. |
| B7 | 403 `SCREENING_REFERRED` | You skipped the referral. Go back to the waiting screen. |
| B9 | 503 `CORE_UNAVAILABLE`, or no answer | Retry B-12 with the **same** Idempotency-Key. The account is never opened twice. |
| B10 | 422 `PIN_POLICY` | The PIN is too easy (for example 1234 or 1111). Ask for another. |
| any | 409 `APPLICATION_EXPIRED` | More than 30 minutes. Start again. |
| any | 409 `WRONG_STATE` | A step was called out of order. Read B-13 and go to its `nextStep`. |
| any | 401 `SESSION_INVALID` | The session ended. Go back to A0. |
| any | 401 `DEVICE_UNAUTHORIZED` | Sign the machine in again (D-1), then repeat the call. |
| any | 503 `SERVICE_UNAVAILABLE` / `CORE_UNAVAILABLE` | Show "Service unavailable" and go back to A1. |

### Screening referral (B6 answer `REFERRED`)
The customer's name matched a PEP or sanctions list. A teller must review it in the portal (Applications page).
1. Show a waiting screen ("We need a moment to check your details") with "Talk to a teller" and "Cancel".
2. Poll B-13 every 5 seconds:
   ```http
   GET /stm/v1/onboarding/applications/app_PS03612K55WY
   ```
   ```json
   200 { "applicationId": "app_…", "status": "REFERRED", "nextStep": "TELLER", "referral": "PENDING", "expiresAt": "…", "productCode": "SAV_PERSONAL" }
   ```
3. If the teller clears it, `status` becomes `KYC_CAPTURED` with `nextStep: "OTP"`: continue at B7.
   If the teller rejects it, `status` becomes `REJECTED`: show the "visit a branch" screen and end the session.

### Face does not match (B4, `attemptsLeft` 0)
1. Show "We couldn't confirm it's you" with **"Talk to a teller"** (see below) and "Cancel".
2. The teller talks to the customer on video, compares the face with the registry photo and confirms or rejects in the portal.
3. Poll B-13. `status: "BIOMETRICS_VERIFIED"` (`nextStep: "REVIEW"`): continue at B5. `REJECTED`: end.

### "Talk to a teller" from any screen (D-8, D-9)
```http
POST /stm/v1/support/teller-calls
{ "screen": "B4", "flow": "ACCOUNT_OPENING", "applicationId": "app_PS03612K55WY", "language": "en" }
```
```json
201 { "callId": "f1c6588e-…", "status": "QUEUED", "estimatedWaitSec": 45, "media": null }
```
Poll `GET /stm/v1/support/teller-calls/{callId}` every 2 seconds. **Keep polling while waiting.** If polling stops
for 10 seconds, the call is dropped. When `status` is `CONNECTED`, `media` holds `{ "provider": "webrtc",
"roomUrl": "ws://…:7880", "room": "call-…", "token": "…" }`. Join the LiveKit room with `kiosk-video.js` in gateway
mode. `DELETE /stm/v1/support/teller-calls/{callId}` cancels. 503 `VIDEO_UNAVAILABLE` means no video service:
say so and offer to try later.

### Customer cancels or walks away
```http
POST /stm/v1/onboarding/applications/app_PS03612K55WY/cancel
{ "reason": "CUSTOMER_CANCELLED", "atStep": "KYC" }
```
```json
200 { "applicationId": "app_…", "status": "CANCELLED", "dataRetention": "DELETED_EXCEPT_AUDIT" }
```
Then D-4 with `"reason": "CUSTOMER_CANCELLED"` (or `"TIMEOUT"` after the inactivity prompt). If D-4 lists
`openItems` (for example a card still in the printer), deal with them before the next customer.

### After a crash or reload
Read `GET /stm/v1/onboarding/applications/{applicationId}` and go to the screen for `nextStep`:

| `status` | `nextStep` | Screen |
|---|---|---|
| CONSENTED | ID_SCAN | B3 |
| IDENTITY_CAPTURED | BIOMETRICS | B4 |
| BIOMETRICS_VERIFIED | REVIEW | B5 |
| IDENTITY_VERIFIED | KYC | B6 |
| REFERRED | TELLER | waiting screen |
| KYC_CAPTURED | OTP | B7 |
| PHONE_VERIFIED | SIGNATURE | B8 |
| SIGNATURE_CAPTURED | SUBMIT | B9 (B-12) |
| ACCOUNT_OPENED | CARD | B9 (card) |

## 4. Test identities (demo profile)

| ID number | Surname, date of birth | What happens |
|---|---|---|
| 29876543 | KAMAU, 1996-07-22 | Everything succeeds (KRA PIN A029876543K). Works once per database. |
| 30222333 | NDEGWA, 1985-11-30 | Referred at B6 (PEP). A teller clears it in the portal. |
| 30111222 | MWANGI, 1990-01-09 | The face never matches. After 3 tries a teller confirms. |
| 30333444 | OUMA, 1992-05-17 | KRA PIN A030333444P is inactive (`KRA_PIN_INVALID`). |
| 30444555 | ODHIAMBO, 1988-08-02 | The document fails the tamper check (`IDENTITY_MISMATCH`). |
| 32145678 | OTIENO, 1994-03-14 | Already a customer (`EXISTING_CUSTOMER`). |

OTP: `123456`. PIN block in the demo (no real PIN pad): `0` + PIN length + PIN, padded with F to 16 characters,
for example PIN 4826 → `044826FFFFFFFFFF`. Never use this on a real machine.
