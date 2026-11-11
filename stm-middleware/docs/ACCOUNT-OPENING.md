# Opening a bank account at the Smart Teller

**For the kiosk app developer.** This covers screens **B1 to B10**. For each screen it gives the full URL, what to
send, what comes back, and what to keep for the next step.

---

## 0. Before you start

| | |
|---|---|
| **Server** | `https://10.8.0.1:8443` |
| **Base URL** | `https://10.8.0.1:8443/stm/v1` |
| **Network** | WireGuard VPN only. Your machine and the STM each need a VPN profile from Blue River. |
| **HTTPS** | Private certificate. Install `stm-server-ca.crt` as a trusted root (or run `install-server-certificate.ps1` as Administrator). |
| **Device ID** | `STM-NBO-MOI-03` (branch `012`, Moi Avenue) |
| **Terminal token** | Sent to you separately. Keep it secret. |
| **Try it** | Postman: `postman/STM-Gateway.postman_collection.json`, folders 0 to 3 |
| **Typed client** | `kiosk-ts/` wraps every call below |

### The whole journey at a glance

| # | Screen | Method | Full URL |
|---|---|---|---|
| 1 | Boot | `POST` | `https://10.8.0.1:8443/stm/v1/auth/device/token` |
| 2 | A0 → A1 | `POST` | `https://10.8.0.1:8443/stm/v1/sessions` |
| 3 | B1 | `GET` | `https://10.8.0.1:8443/stm/v1/products` |
| 4 | B2 | `GET` | `https://10.8.0.1:8443/stm/v1/legal/terms?product={productCode}` |
| 5 | B2 | `POST` | `https://10.8.0.1:8443/stm/v1/onboarding/applications` |
| 6 | B3 | `POST` | `https://10.8.0.1:8443/stm/v1/identity/documents/verify` |
| 7 | B4 | `POST` | `https://10.8.0.1:8443/stm/v1/identity/biometrics/verify` |
| 8 | B5 | `POST` | `https://10.8.0.1:8443/stm/v1/onboarding/applications/{applicationId}/identity-confirm` |
| 9 | B6 | `POST` | `https://10.8.0.1:8443/stm/v1/kyc/kra-pin/validate` |
| 10 | B6 | `PATCH` | `https://10.8.0.1:8443/stm/v1/onboarding/applications/{applicationId}/kyc` |
| 11 | B6 → B7 | `POST` | `https://10.8.0.1:8443/stm/v1/kyc/sim-swap-check` *(optional, recommended)* |
| 12 | B7 | `POST` | `https://10.8.0.1:8443/stm/v1/otp/send` |
| 13 | B7 | `POST` | `https://10.8.0.1:8443/stm/v1/otp/verify` |
| 14 | B8 | `POST` | `https://10.8.0.1:8443/stm/v1/onboarding/applications/{applicationId}/signature` |
| 15 | B9 | `POST` | `https://10.8.0.1:8443/stm/v1/onboarding/applications/{applicationId}/submit` |
| 16 | B9 | `POST` | `https://10.8.0.1:8443/stm/v1/cards/orders` |
| 17 | B9 | `GET` | `https://10.8.0.1:8443/stm/v1/cards/orders/{orderId}` *(every second)* |
| 18 | B9 | `POST` | `https://10.8.0.1:8443/stm/v1/cards/orders/{orderId}/dispense` |
| 19 | B10 | `POST` | `https://10.8.0.1:8443/stm/v1/cards/{cardId}/pin` |
| 20 | B10 | `POST` | `https://10.8.0.1:8443/stm/v1/cards/{cardId}/activate` |
| 21 | B10 | `POST` | `https://10.8.0.1:8443/stm/v1/receipts` |
| 22 | Finish | `POST` | `https://10.8.0.1:8443/stm/v1/sessions/{sessionId}/end` |

### What you carry from step to step

```
accessToken     ← step 1   → every call (Authorization header). Renew before 15 minutes.
sessionId       ← step 2   → every call (X-Session-Id header) until step 22
applicationId   ← step 5   → steps 6 to 16
verificationId  ← step 6   → steps 7 and 8
otpRef          ← step 12  → step 13
customerToken   ← step 15  → steps 16 to 20 (X-Customer-Token header)
accountId       ← step 15  → step 16
orderId, cardId ← step 16  → steps 17 to 20
reference       ← step 20  → step 21
```

---

## 1. Headers

### On every call (after step 1)

```http
Authorization:   Bearer <accessToken>
X-Device-Id:     STM-NBO-MOI-03
X-Request-Id:    <a new UUID for each call>
Accept-Language: en                      ← or sw; error messages follow it
X-Session-Id:    <sessionId>             ← from step 2 until step 22
Content-Type:    application/json
```

### Only on some calls

| Header | When |
|---|---|
| `X-Customer-Token: <customerToken>` | Steps 16 to 20 (after the account is opened) |
| `Idempotency-Key: <UUID>` | **Required** on steps 15 and 16. Accepted on every other write. Make it when the customer taps the button, and **send the same key again if you retry**, so the server never does it twice. |

---

## 2. The calls, screen by screen

### Step 1 · Boot: sign the machine in (D-1)

At boot, and again before the 15-minute token expires.

```http
POST https://10.8.0.1:8443/stm/v1/auth/device/token
Authorization: Bearer <terminal token>
Content-Type: application/json
```
**Send**
```json
{ "deviceId": "STM-NBO-MOI-03", "softwareVersion": "2.0.0", "nonce": "8c1d5f0e-6b7a-4e0d-9a51-2f3c4d5e6f70" }
```
**Receive** `200`
```json
{ "accessToken": "dvt_…", "expiresIn": 900, "branchCode": "012", "branchName": "Moi Avenue" }
```
✅ **Keep** `accessToken`. Use it as `Authorization: Bearer <accessToken>` on every call from now on.

---

### Step 2 · A0 → A1: start the session (D-2)

When the customer taps the screen.

```http
POST https://10.8.0.1:8443/stm/v1/sessions
```
**Send**
```json
{ "trigger": "TOUCH", "language": "en" }
```
**Receive** `201`
```json
{
  "sessionId": "ses_W963M9YN9ASC",
  "expiresAt": "2026-11-06T11:53:01Z",
  "inactivityTimeoutSec": 60,
  "services": { "accountOpening": true, "cardServices": true, "cashDeposit": true,
                "cashWithdrawal": true, "forex": true, "statements": true }
}
```
✅ **Keep** `sessionId`. Send it as `X-Session-Id` until step 22.
👉 Show the **"Open an account"** tile only if `services.accountOpening` is `true`.
👉 After `inactivityTimeoutSec` with no touch, show "Are you still there?". A session lasts at most 30 minutes.

**EN/SW toggle** (any time):
```http
PATCH https://10.8.0.1:8443/stm/v1/sessions/{sessionId}
```
```json
{ "language": "sw" }
```

---

### Step 3 · B1: select account type (B-1)

```http
GET https://10.8.0.1:8443/stm/v1/products
```
**Receive** `200`
```json
{
  "products": [
    { "code": "SAV_PERSONAL", "name": "Personal Savings Account",
      "minOpeningBalance": { "amount": "0.00", "currency": "KES" },
      "features": ["No minimum opening balance", "Free mobile and internet banking", "Debit card issued today"],
      "cardProducts": ["CLASSIC", "GOLD"] },
    { "code": "CUR_PERSONAL", "name": "Current Account", "…": "…" }
  ]
}
```
✅ **Keep** the chosen `code` (for example `SAV_PERSONAL`).
👉 Names and features come in the session language.

---

### Step 4 · B2: get the terms (B-2)

```http
GET https://10.8.0.1:8443/stm/v1/legal/terms?product=SAV_PERSONAL
```
**Receive** `200`
```json
{
  "termsId": "TC-ACC-2026.11",
  "version": "2026.11",
  "language": "en",
  "sections": [ { "title": "1. About these terms", "body": "These terms govern the account you are opening today…" } ],
  "consents": [
    { "code": "ACCOUNT_TERMS",           "required": true, "label": "I agree to the account terms" },
    { "code": "DATA_PROCESSING_DPA2019", "required": true, "label": "…" },
    { "code": "BIOMETRIC_PROCESSING",    "required": true, "label": "…" }
  ],
  "audioUrl": "https://…/TC-ACC-2026.11-en.mp3"
}
```
✅ **Keep** `termsId`.
👉 Draw one tick box per item in `consents`, using its `label`. Enable **Continue** when every `required` box is ticked.

---

### Step 5 · B2: accept the terms, which creates the application (B-3)

When the customer taps **Continue**.

```http
POST https://10.8.0.1:8443/stm/v1/onboarding/applications
```
**Send**
```json
{
  "productCode": "SAV_PERSONAL",
  "termsId": "TC-ACC-2026.11",
  "acceptedAt": "2026-11-06T12:07:41+03:00",
  "consents": [
    { "code": "ACCOUNT_TERMS",           "accepted": true },
    { "code": "DATA_PROCESSING_DPA2019", "accepted": true },
    { "code": "BIOMETRIC_PROCESSING",    "accepted": true }
  ]
}
```
**Receive** `201`
```json
{ "applicationId": "app_PS03612K55WY", "status": "CONSENTED", "expiresAt": "2026-11-06T11:53:02Z" }
```
✅ **Keep** `applicationId`. The application expires after 30 minutes.

---

### Step 6 · B3: scan the ID document (B-4)

Send what the scanner read, with the images in base64.

```http
POST https://10.8.0.1:8443/stm/v1/identity/documents/verify
```
**Send** (Kenyan ID)
```json
{
  "applicationId": "app_PS03612K55WY",
  "documentType": "NATIONAL_ID",
  "country": "KE",
  "ocr": { "documentNumber": "29876543", "serialNumber": "240098765", "surname": "KAMAU",
           "givenNames": "AMARA NJERI", "dateOfBirth": "1996-07-22" },
  "mrz": null,
  "images": { "front": "<base64>", "back": "<base64>", "uv": "<base64>" }
}
```
For a passport: `"documentType": "PASSPORT"` and `"mrz": { "line1": "…", "line2": "…" }`.

**Receive** `200`
```json
{
  "verificationId": "idv_WR8PNGAVWY2G",
  "result": "MATCH",
  "documentChecks": { "expiry": "PASS", "tamper": "PASS" },
  "registryRecord": {
    "source": "IPRS", "fullName": "Amara Njeri Kamau", "dateOfBirth": "1996-07-22", "gender": "F",
    "idNumber": "29876543", "placeOfBirth": "Nyeri", "nationality": "KEN", "photo": "<base64 JPEG>"
  },
  "existingCustomer": false
}
```
✅ **Keep** `verificationId` and `registryRecord`. Screen B5 shows the registry details.

---

### Step 7 · B4: face and fingerprint (B-5)

```http
POST https://10.8.0.1:8443/stm/v1/identity/biometrics/verify
```
**Send**
```json
{
  "applicationId": "app_PS03612K55WY",
  "verificationId": "idv_WR8PNGAVWY2G",
  "face":        { "image": "<base64>", "livenessScore": 0.97, "livenessVendor": "sdk" },
  "fingerprint": { "finger": "RIGHT_THUMB", "template": "<base64>", "quality": 82 }
}
```
**Receive** `200`
```json
{
  "face":        { "against": "IPRS_PHOTO", "score": 0.992, "result": "MATCH" },
  "fingerprint": { "against": "IPRS",       "score": 0.95,  "result": "MATCH" },
  "overall": "PASS",
  "attemptsLeft": 3
}
```
👉 `finger` is one of `RIGHT_THUMB`, `LEFT_THUMB`, `RIGHT_INDEX` or `LEFT_INDEX`. `livenessScore` is 0 to 1, from your liveness SDK.

---

### Step 8 · B5: customer confirms the registry details (B-6)

Show `registryRecord.fullName`, date of birth, ID number and `photo`.

```http
POST https://10.8.0.1:8443/stm/v1/onboarding/applications/app_PS03612K55WY/identity-confirm
```
**Send** ("Confirm")
```json
{ "verificationId": "idv_WR8PNGAVWY2G", "confirmed": true }
```
**Receive** `200`
```json
{ "applicationId": "app_PS03612K55WY", "status": "IDENTITY_VERIFIED" }
```
👉 "Edit / rescan": send `"confirmed": false`. The status goes back to `CONSENTED`; return to B3.

---

### Step 9 · B6: check the KRA PIN as it is typed (B-7)

As soon as 11 characters are entered.

```http
POST https://10.8.0.1:8443/stm/v1/kyc/kra-pin/validate
```
**Send**
```json
{ "kraPin": "A029876543K", "idNumber": "29876543" }
```
**Receive** `200`
```json
{ "valid": true, "taxpayerName": "AMARA NJERI KAMAU", "matchesId": true, "status": "ACTIVE" }
```

---

### Step 10 · B6: save the KYC form (B-8)

```http
PATCH https://10.8.0.1:8443/stm/v1/onboarding/applications/app_PS03612K55WY/kyc
```
**Send**
```json
{
  "kraPin": "A029876543K",
  "mobile": "0712 345 678",
  "address": { "line1": "Moi Avenue", "town": "Nairobi", "county": "Nairobi" },
  "occupation": "Teacher",
  "sourceOfFunds": "Salary"
}
```
**Receive** `200`
```json
{ "applicationId": "app_PS03612K55WY", "status": "KYC_CAPTURED", "screening": { "pep": "CLEAR", "sanctions": "CLEAR" } }
```
👉 `mobile` accepts `07…`, `01…` or `+254…`. `occupation` and `sourceOfFunds` are optional.
⚠️ **If `status` is `REFERRED`, stop here.** See *Screening referral* in section 3.

---

### Step 11 · B6 → B7: SIM-swap check (optional, recommended)

This tells you before B7 whether a code can be texted. A SIM swapped in the last 72 hours blocks the OTP.

```http
POST https://10.8.0.1:8443/stm/v1/kyc/sim-swap-check
```
**Send**
```json
{ "applicationId": "app_PS03612K55WY" }
```
**Receive** `200`
```json
{ "maskedMobile": "+254 7•• ••• 678", "lastSwapAt": "2026-01-20T08:00:00Z", "hoursSinceSwap": 7000,
  "risk": "LOW", "otpAllowed": true }
```
👉 `otpAllowed: false` (`risk: "HIGH"`): don't go to B7. Show the message and offer "Talk to a teller".

---

### Step 12 · B7: send the code by SMS (B-9)

```http
POST https://10.8.0.1:8443/stm/v1/otp/send
```
**Send**
```json
{ "purpose": "ONBOARDING_PHONE", "applicationId": "app_PS03612K55WY", "channel": "SMS" }
```
**Receive** `201`
```json
{ "otpRef": "otp_KFH22DMP4E28", "maskedMobile": "+254 7•• ••• 678", "length": 6, "expiresInSec": 300, "resendAfterSec": 60 }
```
✅ **Keep** `otpRef`.
👉 Show `maskedMobile` and draw `length` boxes. Enable **Resend** after `resendAfterSec`; resend is this same call again.

---

### Step 13 · B7: check the code (B-10)

```http
POST https://10.8.0.1:8443/stm/v1/otp/verify
```
**Send**
```json
{ "otpRef": "otp_KFH22DMP4E28", "code": "123456" }
```
**Receive** `200`
```json
{ "verified": true, "attemptsLeft": 2 }
```
👉 On the test server every code is `123456`.

---

### Step 14 · B8: digital signature (B-11)

```http
POST https://10.8.0.1:8443/stm/v1/onboarding/applications/app_PS03612K55WY/signature
```
**Send**
```json
{ "image": "<base64 PNG of the signature pad>", "strokes": 14, "capturedWith": "STYLUS" }
```
**Receive** `200`
```json
{ "signatureId": "sig_GV76ZD1DKS13", "status": "SIGNATURE_CAPTURED" }
```
👉 `capturedWith` is `STYLUS` or `FINGER`.
👉 The server looks at the image. Send the pad as a PNG or JPEG of at least 120 × 40 pixels (for example 600 × 200),
with visible strokes. A blank, tiny or scribbled-over image answers `422 SIGNATURE_UNREADABLE`
(`details.reason`: `EMPTY`, `TOO_SMALL`, `BLANK` or `FILLED`): clear the pad and ask the customer to sign again.
Keep **Done** disabled until there is at least one stroke.
👉 This becomes the customer's **specimen signature** at the bank when the account is opened (step 15).

---

### Step 15 · B9: open the account (B-12) 🔑

Make the `Idempotency-Key` when the customer taps **"Open my account"**. Keep it until you get an answer.

```http
POST https://10.8.0.1:8443/stm/v1/onboarding/applications/app_PS03612K55WY/submit
Idempotency-Key: 3f6c0d0e-2b1a-4c5d-8e9f-0a1b2c3d4e5f
```
**Send**
```json
{ "productCode": "SAV_PERSONAL", "branchCode": "012", "cardProduct": "CLASSIC", "nameOnCard": "AMARA N KAMAU" }
```
**Receive** `201`
```json
{
  "applicationId": "app_PS03612K55WY",
  "status": "ACCOUNT_OPENED",
  "customerId": "CIF00482914",
  "account": { "accountId": "acc_XER71C0YH7PC", "accountNumber": "012345671000", "display": "0123 4567 1000",
               "product": "SAV_PERSONAL", "branch": "Moi Avenue (012)" },
  "customerToken": "cst_Y8Fv…",
  "cardOrderAllowed": true,
  "nameOnCard": "AMARA N KAMAU",
  "reference": "STM-ACC-10001",
  "smsSent": true,
  "mandate": { "signatureRegistered": true, "biometricsEnrolled": true }
}
```
✅ **Keep** `customerToken` (send it as `X-Customer-Token` from now on) and `account.accountId`.
👉 Show `account.display` to the customer.
👉 `mandate`: the signature from step 14 and the thumbprint from step 7 are now on the bank's records with the
account, so on later visits the customer is recognised against the bank (see `CARD-AND-CUSTOMER-SERVICES.md`).
👉 `nameOnCard`: capital letters, spaces and `. ' -`, at most 26 characters.
👉 No answer or `503`? Retry with the **same** `Idempotency-Key`. The account is never opened twice.

---

### Step 16 · B9: order the card (C-5) 🔑

Free with a new account, because `applicationId` is included.

```http
POST https://10.8.0.1:8443/stm/v1/cards/orders
X-Customer-Token: cst_Y8Fv…
Idempotency-Key: 9a1b2c3d-4e5f-6a7b-8c9d-0e1f2a3b4c5d
```
**Send**
```json
{
  "service": "LINK_AND_ISSUE",
  "accountId": "acc_XER71C0YH7PC",
  "cardProduct": "CLASSIC",
  "nameOnCard": "AMARA N KAMAU",
  "applicationId": "app_PS03612K55WY"
}
```
**Receive** `201`
```json
{
  "orderId": "co_ZK7JCH825EY1",
  "cardId": "crd_7910PPJSPNB0",
  "maskedPan": "4512 •••• •••• 2979",
  "fee": { "amount": "0.00", "currency": "KES", "waived": true, "posted": false, "transactionRef": null },
  "personalisation": { "payloadForPrinter": "<printer payload>", "jobId": "pj_AB7NY2ZAWR1A" },
  "reference": "STM-CRD-10002"
}
```
✅ **Keep** `orderId`, `cardId` and `reference`.
👉 Send `personalisation.payloadForPrinter` to the card printer.

---

### Step 17 · B9: "Printing your card" progress (C-6)

Poll **every second** until `status` is `READY`.

```http
GET https://10.8.0.1:8443/stm/v1/cards/orders/co_ZK7JCH825EY1
X-Customer-Token: cst_Y8Fv…
```
**Receive** `200`
```json
{
  "orderId": "co_ZK7JCH825EY1",
  "status": "PERSONALISING",
  "steps": [
    { "code": "PICK_BLANK",       "status": "DONE" },
    { "code": "PRINT_NAME",       "status": "IN_PROGRESS" },
    { "code": "PROGRAM_EMV_CHIP", "status": "PENDING" },
    { "code": "QUALITY_CHECK",    "status": "PENDING" }
  ]
}
```
👉 Draw the progress bar from `steps`. When `status` is `READY`, present the card.

---

### Step 18 · B9: the customer takes the card (C-7)

```http
POST https://10.8.0.1:8443/stm/v1/cards/orders/co_ZK7JCH825EY1/dispense
X-Customer-Token: cst_Y8Fv…
```
**Send**
```json
{ "outcome": "TAKEN", "qualityCheck": "PASS", "cardSerial": "CS-00921844" }
```
**Receive** `200`
```json
{ "orderId": "co_ZK7JCH825EY1", "status": "DISPENSED", "cardStatus": "INACTIVE_PIN_REQUIRED" }
```
👉 `outcome` can also be `RETAINED` (not taken, pulled back in) or `SPOILED` (printing failed). Both cancel the card.

---

### Step 19 · B10: set the PIN (C-8)

The PIN pad builds the PIN block. The app never sees the PIN.

```http
POST https://10.8.0.1:8443/stm/v1/cards/crd_7910PPJSPNB0/pin
X-Customer-Token: cst_Y8Fv…
```
**Send**
```json
{
  "pinBlock": "<from the PIN pad>",
  "format": "ISO_9564_FORMAT_4",
  "keySerialNumber": "FFFF9876543210E00008",
  "confirmed": true,
  "policyChecks": { "notSequential": true, "notRepeated": true }
}
```
**Receive** `200`
```json
{ "cardId": "crd_7910PPJSPNB0", "pinSet": true }
```

---

### Step 20 · B10: activate the card (C-9)

```http
POST https://10.8.0.1:8443/stm/v1/cards/crd_7910PPJSPNB0/activate
X-Customer-Token: cst_Y8Fv…
```
**Send**
```json
{ "channel": "STM" }
```
**Receive** `200`
```json
{
  "cardId": "crd_7910PPJSPNB0",
  "status": "ACTIVE",
  "maskedPan": "4512 •••• •••• 2979",
  "linkedAccount": "Savings ••• 1000",
  "limits": { "atmDaily": { "amount": "40000.00", "currency": "KES" },
              "posDaily": { "amount": "150000.00", "currency": "KES" } },
  "reference": "STM-CRD-10002"
}
```

---

### Step 21 · B10: receipt (R-1)

```http
POST https://10.8.0.1:8443/stm/v1/receipts
```
**Send**
```json
{ "transactionRef": "STM-CRD-10002", "delivery": "PRINT" }
```
**Receive** `201`
```json
{
  "receiptId": "rc_KJ00…",
  "lines": ["BLUE RIVER SMART TELLER  STM-NBO-MOI-03", "06 NOV 2026 13:59", "…"],
  "qr": "https://bank.example/r/rc_KJ00…",
  "printStatusUrl": "/stm/v1/receipts/rc_KJ00…"
}
```
👉 Print each item in `lines` on its own line, and `qr` as a QR code. `delivery` can also be `SMS` or `NONE`.

---

### Step 22 · Finish: end the session (D-4)

```http
POST https://10.8.0.1:8443/stm/v1/sessions/ses_W963M9YN9ASC/end
```
**Send**
```json
{ "reason": "CUSTOMER_FINISHED", "cardRetained": false }
```
**Receive** `200`
```json
{ "ended": true, "openItems": [] }
```
👉 If `openItems` is not empty (for example a card still in the printer), deal with it before the next customer.

---

## 3. When something goes wrong

Every error has the same shape. **Show `message` (English) or `messageSw` (Kiswahili) to the customer as is.**

```json
{
  "error": {
    "code": "BIOMETRIC_NO_MATCH",
    "message": "Your face or thumbprint did not match the registry record.",
    "messageSw": "Uso au alama ya kidole chako haikulingana na rekodi ya sajili.",
    "retryable": true,
    "requestId": "6f1c…",
    "details": { "attemptsLeft": 2 }
  }
}
```

| Step | HTTP | `error.code` | What the kiosk does |
|---|---|---|---|
| 5 | 422 | `CONSENT_REQUIRED` | A required box wasn't ticked. Stay on B2. |
| 5 | 409 | `TERMS_OUTDATED` | Reload the terms (step 4) and ask again. |
| 6 | 409 | `EXISTING_CUSTOMER` | Already a customer. Show the message and offer **Card services**. |
| 6 | 422 | `IDENTITY_MISMATCH` | Rescan. When `details.attemptsLeft` is 0, offer "Talk to a teller". |
| 7 | 422 | `BIOMETRIC_NO_MATCH` | Try again. At `attemptsLeft` 0, see *Face does not match* below. |
| 9, 10 | 422 | `KRA_PIN_INVALID` | Mark the KRA PIN field; let the customer correct it. |
| 10 | 200 | *(status `REFERRED`)* | See *Screening referral* below. |
| 12 | 429 | `RESEND_TOO_SOON` | Keep **Resend** disabled for `details.retryAfterSec` seconds. |
| 12 | 403 | `SIM_SWAP_RISK` | SIM swapped recently, so no code was sent. Offer "Talk to a teller". |
| 12 | 403 | `SCREENING_REFERRED` | The referral was skipped. Go back to the waiting screen. |
| 13 | 401 | `OTP_INCORRECT` | Clear the boxes and try again (`details.attemptsLeft`). |
| 13 | 401 | `OTP_EXPIRED` | Send a new code (step 12). |
| 13 | 403 | `OTP_TRIES_EXCEEDED` | Too many wrong codes. Offer "Talk to a teller" or cancel. |
| 14 | 422 | `SIGNATURE_UNREADABLE` | Blank, tiny or scribbled-over signature (`details.reason`). Clear the pad and sign again. |
| 15 | 503 | `CORE_UNAVAILABLE` *(or no answer)* | Retry step 15 with the **same** `Idempotency-Key`. |
| 19 | 422 | `PIN_POLICY` | The PIN is too easy (for example 1234 or 1111). Ask for another. |
| any | 409 | `APPLICATION_EXPIRED` | More than 30 minutes have passed. Start again. |
| any | 409 | `WRONG_STATE` | A step was called out of order. Read the application status and go to its `nextStep` (below). |
| any | 401 | `SESSION_INVALID` | The session ended. Go back to A0. |
| any | 401 | `DEVICE_UNAUTHORIZED` | Sign in again (step 1), then repeat the call. |
| any | 503 | `SERVICE_UNAVAILABLE` | Show "Service unavailable" and go back to A1. |

---

### Screening referral (step 10 returns `REFERRED`)

The customer's name matched a PEP or sanctions list, so a teller must review it in the portal.

1. Show "We need a moment to check your details", with **Talk to a teller** and **Cancel**.
2. Poll **every 5 seconds**:
   ```http
   GET https://10.8.0.1:8443/stm/v1/onboarding/applications/app_PS03612K55WY
   ```
   ```json
   { "applicationId": "app_…", "status": "REFERRED", "nextStep": "TELLER", "referral": "PENDING",
     "expiresAt": "…", "productCode": "SAV_PERSONAL" }
   ```
3. `status: "KYC_CAPTURED"` (`nextStep: "OTP"`): cleared, continue at step 12.
   `status: "REJECTED"`: show the "visit a branch" screen and end the session (step 22).

### Face does not match (step 7, `attemptsLeft` 0)

1. Show "We couldn't confirm it's you", with **Talk to a teller** and **Cancel**.
2. The teller compares the face with the registry photo on video and confirms or rejects in the portal.
3. Poll the application as above. `status: "BIOMETRICS_VERIFIED"` (`nextStep: "REVIEW"`): continue at step 8.
   `REJECTED`: end.

### "Talk to a teller" from any screen (D-8, D-9)

**Request a teller**
```http
POST https://10.8.0.1:8443/stm/v1/support/teller-calls
```
```json
{ "screen": "B4", "flow": "ACCOUNT_OPENING", "applicationId": "app_PS03612K55WY", "language": "en" }
```
```json
201 { "callId": "f1c6588e-…", "status": "QUEUED", "estimatedWaitSec": 45, "media": null }
```

**Poll every 2 seconds.** If polling stops for 10 seconds, the call is dropped.
```http
GET https://10.8.0.1:8443/stm/v1/support/teller-calls/{callId}
```
```json
200 { "callId": "f1c6588e-…", "status": "CONNECTED",
      "media": { "provider": "webrtc", "roomUrl": "wss://10.8.0.1:8443/livekit", "room": "call-…", "token": "…" } }
```
👉 When `status` is `CONNECTED`, join the room with `video/kiosk-video.js` (gateway mode). Use the `roomUrl` from the answer; don't hard-code it.

**Cancel the call**
```http
DELETE https://10.8.0.1:8443/stm/v1/support/teller-calls/{callId}
```
👉 `503 VIDEO_UNAVAILABLE`: the video service is down. Say so and offer to try later.

### Customer cancels or walks away

```http
POST https://10.8.0.1:8443/stm/v1/onboarding/applications/app_PS03612K55WY/cancel
```
```json
{ "reason": "CUSTOMER_CANCELLED", "atStep": "KYC" }
```
```json
200 { "applicationId": "app_…", "status": "CANCELLED", "dataRetention": "DELETED_EXCEPT_AUDIT" }
```
Then end the session (step 22) with `"reason": "CUSTOMER_CANCELLED"`, or `"TIMEOUT"` after the inactivity prompt.

### After a crash or reload: where was I?

```http
GET https://10.8.0.1:8443/stm/v1/onboarding/applications/{applicationId}
```

| `status` | `nextStep` | Go to |
|---|---|---|
| `CONSENTED` | `ID_SCAN` | B3 (step 6) |
| `IDENTITY_CAPTURED` | `BIOMETRICS` | B4 (step 7) |
| `BIOMETRICS_VERIFIED` | `REVIEW` | B5 (step 8) |
| `IDENTITY_VERIFIED` | `KYC` | B6 (step 9) |
| `REFERRED` | `TELLER` | Waiting screen |
| `KYC_CAPTURED` | `OTP` | B7 (step 12) |
| `PHONE_VERIFIED` | `SIGNATURE` | B8 (step 14) |
| `SIGNATURE_CAPTURED` | `SUBMIT` | B9 (step 15) |
| `ACCOUNT_OPENED` | `CARD` | B9 (step 16) |

---

## 4. Test data (the server simulates the bank)

| ID number | Surname, date of birth | What happens |
|---|---|---|
| `29876543` | KAMAU, 1996-07-22 | Everything succeeds. KRA PIN `A029876543K`. Works once per database. |
| `30222333` | NDEGWA, 1985-11-30 | Referred at step 10 (PEP). A teller clears it in the portal. |
| `30111222` | MWANGI, 1990-01-09 | The face never matches. After 3 tries a teller confirms. |
| `30333444` | OUMA, 1992-05-17 | KRA PIN `A030333444P` is inactive (`KRA_PIN_INVALID`). |
| `30444555` | ODHIAMBO, 1988-08-02 | The document fails the tamper check (`IDENTITY_MISMATCH`). |
| `32145678` | OTIENO, 1994-03-14 | Already a customer (`EXISTING_CUSTOMER`). |

- **OTP:** always `123456`.
- **PIN block without a real PIN pad:** `0` + PIN length + PIN, padded with `F` to 16 characters.
  PIN `4826` → `044826FFFFFFFFFF`. **Never use this on a real machine.**
