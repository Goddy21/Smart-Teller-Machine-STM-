# Smart Teller Gateway and portal API: endpoints and payloads

Base URL: `http://<middleware>:8080`. Kiosk calls are under **`/stm/v1`**, portal calls under **`/api/v1/portal`**.
The payloads below are real requests and answers recorded from the demo profile (IDs and timestamps vary; long
base64 values are shortened). Swagger UI with every schema: `http://localhost:8080/swagger-ui.html`.
Typed client: `clients/kiosk-ts`. End-to-end example of every flow: `scripts/demo-journey.ps1`.
Postman: import `docs/STM-Gateway.postman_collection.json` (run folder 0 · Setup first).

## Headers on every `/stm/v1` call

| Header | When | Value |
|---|---|---|
| `Authorization` | always | `Bearer <device token>` from D-1 (15 min) |
| `X-Device-Id` | always | the machine, e.g. `STM-NBO-MOI-03`; must match the token |
| `X-Request-Id` | always | a new UUID per call (echoed in errors and the audit journal) |
| `Accept-Language` | always | `en` or `sw`: messages, product names, receipts |
| `X-Session-Id` | from D-2 to D-4 | the `sessionId` from D-2 |
| `X-Customer-Token` | the customer's own accounts and cards | from S-2, B-5 (card services) or B-12 |
| `Idempotency-Key` | **required** on B-12, C-5, S-5, K-4, W-2, W-4, X-5; **accepted on every other write** (POST, PUT, PATCH, DELETE) | a UUID made when the customer confirms; reuse it when retrying. Same key + same body replays the first answer (header `Idempotent-Replayed: true`), including a business error; same key + another body is 409 `IDEMPOTENCY_CONFLICT`; server errors are not stored, so a retry runs again |

Each endpoint below says which of the last three it needs.

**Money** is always `{"amount": "12000.00", "currency": "KES"}` (decimal string). **Notes** are
`{"denomination": "1000", "count": 4}`.

**Errors** (any 4xx/5xx):
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
Show `message` / `messageSw` to the customer as is. Common codes: `VALIDATION_ERROR` 400, `MISSING_HEADER` 400,
`DEVICE_UNAUTHORIZED` 401, `SESSION_INVALID` 401, `CUSTOMER_TOKEN_REQUIRED` 401, `FORBIDDEN` 403, `WRONG_STATE` 409,
`SERVICE_UNAVAILABLE` / `CORE_UNAVAILABLE` 503.

## Contents

- D · Device and session: D-1 … D-9, X-6, signaling ticket
- B · Open an account: B-1 … B-14
- C / S · Cards, card + PIN: S-1, S-2, C-1 … C-10
- P / M · Card care and existing customers: P-1 … P-5, M-1 … M-7
- S · Balance and statements: S-3 … S-6
- K · Cash deposit: K-1 … K-5
- W · Cash withdrawal: W-1 … W-4
- X · Foreign exchange: X-1 … X-5
- R · Receipts and SMS: R-1, R-2
- A · Device agent: A-1, A-2
- Portal API (`/api/v1/portal`)

## Typical order of calls

| Journey | Calls |
|---|---|
| Open an account | D-2 → B-1 → B-2 → B-3 → B-4 → B-5 → B-6 → B-7 → B-8 → B-9 → B-10 → B-11 → B-12 → C-5 (applicationId) → C-6 … → C-7 → C-8 → C-9 → R-1 → D-4 |
| Card services, no card | D-2 → C-1 → KYC status → SIM-swap check → B-9 (CARD_SERVICES) → B-10 → B-5 (customerId + lookupRef) → C-2 → C-3 → C-4 → C-5 → C-6 … → C-7 → C-8 → C-9 → D-4 |
| Card care (PIN, freeze, lost) | verify as above (or S-1 → S-2 for P-2, P-4, C-10) → M-1 → C-2 → P-2 / P-3 / P-4 / P-5 / C-10 → D-4 |
| Existing customer's record | verify as above → M-1 → M-3 (fingerprints) → M-7 (signature) → M-2 (dormant account) → D-4 |
| Change phone number | verify as above (phone lost: C-1 → B-5 with `purpose: MOBILE_CHANGE`, face + thumbprint) → M-4 → B-9 (MOBILE_CHANGE) → B-10 → M-5 → D-4 |
| Withdrawal | D-2 (CARD) → S-1 → S-2 → S-3 → W-1 → W-2 → dispense → W-3 (or W-4) → R-1 → D-4 |
| Deposit | D-2 → K-1 → K-2 → K-3 → K-4 (or K-5) → R-1 → D-4 |
| Forex, bank buys | D-2 → X-1 → B-4 (FOREX) → X-2 → X-3 → X-4 → X-5 → W-3 → D-4 |
| Statement | D-2 (CARD) → S-1 → S-2 → S-4 → S-5 → S-6 (FULL) → D-4 |
| Help on any screen | D-8 → D-9 (poll) → join `media` → D-9 DELETE to cancel |

## D · Device and session

### D-1 · Device token

`POST /stm/v1/auth/device/token`

Production: mTLS (certificate CN = deviceId), no Authorization. Development: `Authorization: Bearer <terminal token>` from `POST /api/v1/terminals/register` (header `X-Provisioning-Secret`). No `X-Session-Id`.

Request:
```json
{
  "deviceId": "STM-NBO-MOI-03",
  "softwareVersion": "2.0.0",
  "nonce": "c0a8…"
}
```
Response `200`:
```json
{
  "accessToken": "dvt_…",
  "expiresIn": 900,
  "branchCode": "012",
  "branchName": "Moi Avenue"
}
```
### D-2 · Start a customer session (A0)

`POST /stm/v1/sessions`

`trigger`: TOUCH or CARD. `services` decides which A1 tiles to show. No `X-Session-Id` yet.

Request:
```json
{
  "trigger": "TOUCH",
  "language": "en"
}
```
Response `201`:
```json
{
  "sessionId": "ses_W963M9YN9ASC",
  "expiresAt": "2026-11-06T11:53:01.918Z",
  "inactivityTimeoutSec": 60,
  "services": {
    "accountOpening": true,
    "cardServices": true,
    "cashDeposit": true,
    "cashWithdrawal": true,
    "forex": true,
    "statements": true
  }
}
```
### D-3 · Change language

`PATCH /stm/v1/sessions/{sessionId}`  ·  needs `X-Session-Id`

Request:
```json
{
  "language": "sw"
}
```
Response `200`:
```json
{
  "language": "sw",
  "sessionId": "ses_W963M9YN9ASC"
}
```
### D-4 · End the session

`POST /stm/v1/sessions/{sessionId}/end`  ·  needs `X-Session-Id`

`openItems` lists what the kiosk must finish first (notes in escrow, a card in the printer): `{type, id, status, action}`. Allowed on an ended session.

Request:
```json
{
  "reason": "CUSTOMER_FINISHED",
  "cardRetained": false
}
```
Response `200`:
```json
{
  "openItems": [],
  "ended": true
}
```
### D-5 · Tariffs

`GET /stm/v1/config/tariffs`

Response `200`:
```json
{
  "tariffs": [
    {
      "code": "CARD_ISSUE_CLASSIC",
      "label": "Classic debit card",
      "fee": {
        "amount": "400.00",
        "currency": "KES"
      },
      "includesExcise": true
    }
  ],
  "version": "2026-11-01"
}
```
### D-6 · Health report

`POST /stm/v1/devices/{deviceId}/health`

Session optional. A module that is not OK switches off the services that need it (`disabledServices`).

Request:
```json
{
  "modules": {
    "cardReader": "OK",
    "cardPrinter": {
      "status": "OK",
      "blankStock": 214
    },
    "cashRecycler": "OK",
    "receiptPrinter": {
      "status": "PAPER_LOW"
    },
    "a4Printer": "OK",
    "idScanner": "OK",
    "fingerprint": "OK",
    "camera": "OK",
    "pinPad": "OK"
  },
  "reportedAt": "2026-11-06T12:05:00+03:00"
}
```
Response `200`:
```json
{
  "accepted": true,
  "disabledServices": []
}
```
### D-7 · Device status

`GET /stm/v1/devices/{deviceId}/status`

Another device's id gives 403 FORBIDDEN.

Response `200`:
```json
{
  "online": true,
  "cashRecycler": "READY",
  "allServicesAvailable": true,
  "message": null
}
```
### X-6 · Cash available (cassettes)

`GET /stm/v1/devices/{deviceId}/cassettes`  ·  needs `X-Session-Id`

Response `200`:
```json
{
  "cassettes": [
    {
      "currency": "USD",
      "denominations": [
        "50"
      ],
      "status": "AVAILABLE",
      "maxPerCustomerDaily": "1000.00"
    }
  ]
}
```
### D-8 · Talk to a teller

`POST /stm/v1/support/teller-calls`  ·  needs `X-Session-Id`

503 VIDEO_UNAVAILABLE when call-control is down.

Request:
```json
{
  "screen": "B6",
  "flow": "ACCOUNT_OPENING",
  "applicationId": "app_875VHG8KC6SA",
  "language": "en"
}
```
Response `201`:
```json
{
  "callId": "f1c6588e-0552-490c-bc88-fac5bace2d8f",
  "status": "QUEUED",
  "estimatedWaitSec": 45,
  "media": null
}
```
### D-9 · Teller call status

`GET /stm/v1/support/teller-calls/{callId}`  ·  needs `X-Session-Id`

Poll every 2 s. When a teller answers: `status: CONNECTED`, `media: {provider: "webrtc", roomUrl, room, token}` (join the LiveKit room).

Response `200`:
```json
{
  "callId": "f1c6588e-0552-490c-bc88-fac5bace2d8f",
  "status": "QUEUED",
  "callState": "QUEUED",
  "media": null
}
```
### D-9 · Cancel the teller call

`DELETE /stm/v1/support/teller-calls/{callId}`  ·  needs `X-Session-Id`

Response `200`:
```json
{
  "callId": "f1c6588e-0552-490c-bc88-fac5bace2d8f",
  "status": "CANCELLED"
}
```
### — · Signaling ticket (remote commands)

`POST /stm/v1/signaling/ticket`

Then open `ws://<host>/ws/signaling?ticket=…` within 30 s. Server pushes `{type: "COMMAND", command: LOCK|UNLOCK|REBOOT|REFRESH_CONFIG}`.

Response `200`:
```json
{
  "ticket": "…",
  "expiresInSeconds": 30
}
```

## B · Open an account

### B-1 · Products (B1)

`GET /stm/v1/products`  ·  needs `X-Session-Id`

Query `channel` (default STM), `category` (default ACCOUNT). Names follow `Accept-Language`.

Response `200`:
```json
{
  "products": [
    {
      "code": "SAV_PERSONAL",
      "name": "Akaunti ya Akiba Binafsi",
      "minOpeningBalance": {
        "amount": "0.00",
        "currency": "KES"
      },
      "features": [
        "Hakuna kiwango cha chini cha kufungua"
      ],
      "cardProducts": [
        "CLASSIC"
      ]
    }
  ]
}
```
### B-2 · Terms and consents (B2)

`GET /stm/v1/legal/terms?product=SAV_PERSONAL`  ·  needs `X-Session-Id`

`product` is required; `lang` optional (default: session language).

Response `200`:
```json
{
  "termsId": "TC-ACC-2026.11",
  "version": "2026.11",
  "language": "en",
  "sections": [
    {
      "title": "1. About these terms",
      "body": "These terms govern the account you are opening today and the…"
    }
  ],
  "consents": [
    {
      "code": "ACCOUNT_TERMS",
      "required": true,
      "label": "I agree to the account terms"
    }
  ],
  "audioUrl": "https://cdn.example-bank.co.ke/terms/TC-ACC-2026.11-en.mp3"
}
```
### B-3 · Start the application

`POST /stm/v1/onboarding/applications`  ·  needs `X-Session-Id`

422 CONSENT_REQUIRED if a required consent is missing; 409 TERMS_OUTDATED for an old termsId.

Request:
```json
{
  "productCode": "SAV_PERSONAL",
  "termsId": "TC-ACC-2026.11",
  "consents": [
    {
      "code": "ACCOUNT_TERMS",
      "accepted": true
    }
  ],
  "acceptedAt": "2026-11-06T12:07:41+03:00"
}
```
Response `201`:
```json
{
  "expiresAt": "2026-11-06T11:53:02.003Z",
  "status": "CONSENTED",
  "applicationId": "app_PS03612K55WY"
}
```
Seen in testing: `400 MISSING_HEADER`, `422 CONSENT_REQUIRED`

### B-4 · Verify the ID document (B3)

`POST /stm/v1/identity/documents/verify`  ·  needs `X-Session-Id`

`documentType`: NATIONAL_ID or PASSPORT. For forex use `purpose: "FOREX"` and no applicationId. 409 EXISTING_CUSTOMER (send to Card services), 422 IDENTITY_MISMATCH (`details.attemptsLeft`).

Request:
```json
{
  "applicationId": "app_PS03612K55WY",
  "documentType": "NATIONAL_ID",
  "country": "KE",
  "ocr": {
    "documentNumber": "29876543",
    "serialNumber": "240098765",
    "surname": "KAMAU",
    "givenNames": "AMARA NJERI",
    "dateOfBirth": "1996-07-22"
  },
  "mrz": null,
  "images": {
    "front": "<base64>",
    "back": "<base64>",
    "uv": "<base64>"
  }
}
```
Response `200`:
```json
{
  "verificationId": "idv_WR8PNGAVWY2G",
  "result": "MATCH",
  "documentChecks": {
    "expiry": "PASS",
    "tamper": "PASS"
  },
  "registryRecord": {
    "source": "IPRS",
    "fullName": "Amara Njeri Kamau",
    "dateOfBirth": "1996-07-22",
    "gender": "F",
    "idNumber": "29876543",
    "placeOfBirth": "Nyeri",
    "nationality": "KEN",
    "photo": "<base64 JPEG>"
  },
  "existingCustomer": false
}
```
Seen in testing: `409 EXISTING_CUSTOMER`, `422 IDENTITY_MISMATCH`

### B-5 · Face and fingerprint (B4)

`POST /stm/v1/identity/biometrics/verify`  ·  needs `X-Session-Id`

Account opening: applicationId + verificationId. Card services: customerId + lookupRef (after the OTP), and the answer carries `customerToken` (source `VERIFIED_IDENTITY`) and `bankEnrolment` `{enrolled, fingers, offerEnrolment}`; the thumbprint is matched against the bank's enrolled prints when there are any (`fingerprint.against: BANK`), otherwise against IPRS. Phone lost: customerId + lookupRef + `"purpose": "MOBILE_CHANGE"` with face **and** fingerprint, no OTP; the token (source `BIOMETRIC_ONLY`, `allows: ["MOBILE_CHANGE"]`) works only for M-4 and M-5. 422 BIOMETRIC_NO_MATCH with `details.attemptsLeft`; at 0 a teller decides in the portal.

Request:
```json
{
  "applicationId": "app_PS03612K55WY",
  "verificationId": "idv_WR8PNGAVWY2G",
  "face": {
    "image": "<base64>",
    "livenessScore": 0.97,
    "livenessVendor": "sdk"
  },
  "fingerprint": {
    "finger": "RIGHT_THUMB",
    "template": "<base64>",
    "quality": 82
  }
}
```
Response `200`:
```json
{
  "face": {
    "against": "IPRS_PHOTO",
    "score": 0.992,
    "result": "MATCH"
  },
  "fingerprint": {
    "against": "IPRS",
    "score": 0.95,
    "result": "MATCH"
  },
  "overall": "PASS",
  "attemptsLeft": 3
}
```
Seen in testing: `409 WRONG_STATE`, `422 BIOMETRIC_NO_MATCH`

### B-6 · Confirm identity (B5 "Is this you?")

`POST /stm/v1/onboarding/applications/{applicationId}/identity-confirm`  ·  needs `X-Session-Id`

Request:
```json
{
  "verificationId": "idv_WR8PNGAVWY2G",
  "confirmed": true
}
```
Response `200`:
```json
{
  "applicationId": "app_PS03612K55WY",
  "status": "IDENTITY_VERIFIED"
}
```
Seen in testing: `409 WRONG_STATE`

### B-7 · Validate the KRA PIN

`POST /stm/v1/kyc/kra-pin/validate`  ·  needs `X-Session-Id`

422 KRA_PIN_INVALID.

Request:
```json
{
  "kraPin": "A029876543K",
  "idNumber": "29876543"
}
```
Response `200`:
```json
{
  "valid": true,
  "taxpayerName": "AMARA NJERI KAMAU",
  "matchesId": true,
  "status": "ACTIVE"
}
```
Seen in testing: `422 KRA_PIN_INVALID`

### B-8 · KYC details (B6)

`PATCH /stm/v1/onboarding/applications/{applicationId}/kyc`  ·  needs `X-Session-Id`

Screening runs here. `status: REFERRED` = wait for the portal decision (poll B-13) or offer a teller.

Request:
```json
{
  "kraPin": "A029876543K",
  "mobile": "0712 345 678",
  "address": {
    "line1": "Moi Avenue",
    "town": "Nairobi",
    "county": "Nairobi"
  }
}
```
Response `200`:
```json
{
  "applicationId": "app_PS03612K55WY",
  "status": "KYC_CAPTURED",
  "screening": {
    "pep": "CLEAR",
    "sanctions": "CLEAR"
  }
}
```
### B-9 · Send OTP (B7)

`POST /stm/v1/otp/send`  ·  needs `X-Session-Id`

`purpose`: ONBOARDING_PHONE (with applicationId), CARD_SERVICES (with lookupRef: the code goes to the number on record) or MOBILE_CHANGE (with changeRef from M-4: the code goes to the new number). 403 SCREENING_REFERRED, 403 SIM_SWAP_RISK (the SIM was swapped within 72 h), 429 RESEND_TOO_SOON (60 s). Demo code: 123456.

Request:
```json
{
  "purpose": "ONBOARDING_PHONE",
  "applicationId": "app_PS03612K55WY",
  "mobile": "+254712345678",
  "channel": "SMS"
}
```
Response `201`:
```json
{
  "otpRef": "otp_KFH22DMP4E28",
  "maskedMobile": "+254 7•• ••• 678",
  "length": 6,
  "expiresInSec": 300,
  "resendAfterSec": 60
}
```
Seen in testing: `403 SCREENING_REFERRED`, `429 RESEND_TOO_SOON`

### B-10 · Verify OTP

`POST /stm/v1/otp/verify`  ·  needs `X-Session-Id`

401 OTP_INCORRECT (`details.attemptsLeft`), OTP_EXPIRED, OTP_TRIES_EXCEEDED.

Request:
```json
{
  "otpRef": "otp_KFH22DMP4E28",
  "code": "123456"
}
```
Response `200`:
```json
{
  "verified": true,
  "attemptsLeft": 2
}
```
Seen in testing: `401 OTP_INCORRECT`

### B-11 · Signature (B8)

`POST /stm/v1/onboarding/applications/{applicationId}/signature`  ·  needs `X-Session-Id`

Request:
```json
{
  "image": "<base64>",
  "strokes": 14,
  "capturedWith": "STYLUS"
}
```
Response `200`:
```json
{
  "signatureId": "sig_GV76ZD1DKS13",
  "status": "SIGNATURE_CAPTURED"
}
```
The image is checked: PNG or JPEG, at least 120 × 40 pixels, with visible strokes. Otherwise 422 `SIGNATURE_UNREADABLE`
(`details.reason`: EMPTY, TOO_SMALL, BLANK, FILLED; retryable). At B-12 it becomes the customer's specimen signature.

### B-12 · Open the account (B9)

`POST /stm/v1/onboarding/applications/{applicationId}/submit`  ·  needs `X-Session-Id`, `Idempotency-Key`

Returns `customerToken` for the card order (C-5 with applicationId: fee waived).

Request:
```json
{
  "productCode": "SAV_PERSONAL",
  "branchCode": "012",
  "cardProduct": "CLASSIC",
  "nameOnCard": "AMARA N KAMAU"
}
```
Response `201`:
```json
{
  "applicationId": "app_PS03612K55WY",
  "status": "ACCOUNT_OPENED",
  "customerId": "CIF00482914",
  "account": {
    "accountId": "acc_XER71C0YH7PC",
    "accountNumber": "012345671000",
    "display": "0123 4567 1000",
    "product": "SAV_PERSONAL",
    "branch": "Moi Avenue (012)"
  },
  "customerToken": "cst_Y8FvL1NB7OCkUMT42dxJErPfyh3proXQ3NKWhKU5Apo",
  "cardOrderAllowed": true,
  "nameOnCard": "AMARA N KAMAU",
  "reference": "STM-ACC-10001",
  "smsSent": true,
  "mandate": {
    "signatureRegistered": true,
    "biometricsEnrolled": true
  }
}
```
`mandate`: the signature (B-11) and the thumbprint that passed B-5 were stored in core banking with the account.

Seen in testing: `409 IDEMPOTENCY_CONFLICT`

### B-13 · Application status

`GET /stm/v1/onboarding/applications/{applicationId}`  ·  needs `X-Session-Id`

Poll while REFERRED; the portal's decision moves it on (or REJECTED).

Response `200`:
```json
{
  "applicationId": "app_PS03612K55WY",
  "status": "ACCOUNT_OPENED",
  "nextStep": "CARD",
  "expiresAt": "2026-11-06T11:53:02.003Z",
  "productCode": "SAV_PERSONAL",
  "accountNumber": "012345671000"
}
```
### B-14 · Cancel the application

`POST /stm/v1/onboarding/applications/{applicationId}/cancel`  ·  needs `X-Session-Id`

Request:
```json
{
  "reason": "CUSTOMER_CANCELLED",
  "atStep": "KYC"
}
```
Response `200`:
```json
{
  "applicationId": "app_…",
  "status": "CANCELLED",
  "dataRetention": "DELETED_EXCEPT_AUDIT"
}
```

## C / S · Cards, card + PIN

### S-1 · Card inserted (D1)

`POST /stm/v1/auth/card`  ·  needs `X-Session-Id`

403 with `details.cardAction` (RETAIN or RETURN): CARD_BLOCKED (lost, stolen, damaged: RETAIN), CARD_EXPIRED (RETURN), CARD_TEMP_BLOCKED (frozen by the customer: RETURN, `nextStep: UNBLOCK`), PIN_LOCKED (locked by wrong PINs earlier: RETURN, `nextStep: PIN_RESET`).

Request:
```json
{
  "entryMode": "EMV_CHIP",
  "panToken": "tok_4512xxxx4521",
  "track2Equivalent": "<encrypted>",
  "emv": {
    "aid": "A0000000031010",
    "atc": "0042"
  }
}
```
Response `200`:
```json
{
  "cardAuthRef": "ca_TT0VBQ67WECD",
  "maskedPan": "4512 •••• •••• 4521",
  "cardStatus": "ACTIVE",
  "pinAttemptsLeft": 3,
  "customerFirstName": "Achieng"
}
```
Seen in testing: `403 CARD_BLOCKED`, `403 CARD_EXPIRED`

### S-2 · Verify PIN

`POST /stm/v1/auth/card/pin-verify`  ·  needs `X-Session-Id`

`pinBlock` from the PIN pad (demo: clear ISO field, 2580 → 042580FFFFFFFFFF). 401 PIN_INCORRECT; 403 PIN_TRIES_EXCEEDED after 3 (`cardAction: RETAIN`). Returns `customerToken` (source `CARD_PIN`). `accounts` holds the accounts that can transact; dormant, frozen or closed ones come in `restrictedAccounts` (`accountId`, `type`, `masked`, `status`, `nextStep`).

Request:
```json
{
  "cardAuthRef": "ca_TT0VBQ67WECD",
  "pinBlock": "042580FFFFFFFFFF",
  "format": "ISO_9564_FORMAT_4",
  "keySerialNumber": "FFFF9876543210E00009"
}
```
Response `200`:
```json
{
  "verified": true,
  "customerToken": "cst_fE6T6qvnTqof-2ig6fNUwF4QI1YV--iWGnv7ttIQHTs",
  "customerId": "CIF00482913",
  "customerFirstName": "Achieng",
  "accounts": [
    {
      "accountId": "acc_2LM8",
      "type": "SAVINGS",
      "masked": "••• 4521",
      "primary": true
    }
  ]
}
```
Seen in testing: `401 PIN_INCORRECT`

### C-1 · Find the customer (C2)

`POST /stm/v1/customers/lookup`  ·  needs `X-Session-Id`

`by`: ACCOUNT_NUMBER (accountNumber) or ID_DOCUMENT (documentType, documentNumber). Then B-9/B-10 with lookupRef and B-5 with customerId + lookupRef.

Request:
```json
{
  "by": "ACCOUNT_NUMBER",
  "accountNumber": "0123454521"
}
```
Response `200`:
```json
{
  "lookupRef": "lk_ZWTJ2ZTVKR3V",
  "customerId": "CIF00482913",
  "displayName": "A. W. Otieno",
  "maskedMobile": "+254 7•• ••• 678",
  "accountsFound": 3,
  "verificationRequired": [
    "OTP"
  ]
}
```
### — · SIM-swap check

`POST /stm/v1/kyc/sim-swap-check`  ·  needs `X-Session-Id`

Checks the number on record: core banking's for a lookup, the B-8 number for an application. Call it before B-9. `risk: HIGH` (swap within 72 h, `stm.kyc.sim-swap-block-hours`) means B-9 will refuse with 403 `SIM_SWAP_RISK` (`details.offerTeller: true`): send the customer to a teller. Or `{"applicationId": "app_…"}`.

Request:
```json
{
  "lookupRef": "lk_ZWTJ2ZTVKR3V"
}
```
Response `200`:
```json
{
  "maskedMobile": "+254 7•• ••• 654",
  "lastSwapAt": "2026-11-08T16:43:00Z",
  "hoursSinceSwap": 20,
  "risk": "HIGH",
  "otpAllowed": false,
  "checkedAt": "2026-11-09T15:43:35+03:00"
}
```
### — · KYC status

`GET /stm/v1/customers/{customerId}/kyc-status?lookupRef=lk_…`  ·  needs `X-Session-Id`

With the customer token, or with the `lookupRef` from C-1 in this session (before the OTP). `status`: CURRENT, DUE_SOON (within 30 days) or REFRESH_DUE. Review cycle by risk rating: LOW 3 years, MEDIUM 2, HIGH 1. `flags` are reason codes (KYC_REFRESH_DUE, KRA_PIN_MISSING, PEP, HIGH_RISK, SANCTIONS_REVIEW, plus core flags such as ADDRESS_UNVERIFIED). `selfServiceAllowed: false` means send the customer to a teller.

Response `200`:
```json
{
  "customerId": "CIF00391144",
  "status": "REFRESH_DUE",
  "lastReviewedAt": "2023-09-09",
  "nextReviewDue": "2025-09-09",
  "riskRating": "MEDIUM",
  "kraPinOnFile": false,
  "kraPinMasked": null,
  "pep": false,
  "sanctions": "CLEAR",
  "highRisk": false,
  "flags": [
    "KYC_REFRESH_DUE",
    "KRA_PIN_MISSING",
    "ADDRESS_UNVERIFIED"
  ],
  "selfServiceAllowed": false
}
```
### C-2 · Customer's cards

`GET /stm/v1/customers/{customerId}/cards`  ·  needs `X-Session-Id`, `X-Customer-Token`

Every card the customer holds (several per customer and per account are possible), each with its state in words and the `actions` the kiosk may offer. See P-1 for the fields. 401 CUSTOMER_TOKEN_REQUIRED; another customer's id gives 403.

Response `200`:
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
      "actions": [
        "PIN_CHANGE",
        "PIN_RESET",
        "TEMPORARY_BLOCK",
        "BLOCK_AND_REPLACE"
      ]
    },
    {
      "cardId": "crd_8104",
      "maskedPan": "4512 •••• •••• 8104",
      "product": "CLASSIC",
      "status": "BLOCKED",
      "statusText": "Blocked: damaged",
      "reason": "DAMAGED",
      "since": "2026-10-31T18:12:24.177Z",
      "expiry": "2027-06",
      "expired": false,
      "expiresSoon": false,
      "pinSet": true,
      "pinAttemptsLeft": 3,
      "linkedAccountId": "acc_M2CU",
      "linkedAccount": "Current ••• 7002",
      "accountStatus": "ACTIVE",
      "usable": false,
      "actions": [
        "REPLACEMENT"
      ]
    }
  ]
}
```
### C-3 · Accounts (C4)

`GET /stm/v1/customers/{customerId}/accounts[?eligibleFor=CARD]`  ·  needs `X-Session-Id`, `X-Customer-Token`

Every account with its `status` (ACTIVE, DORMANT, FROZEN, CLOSED), `canTransact`, and for the ones that cannot transact `statusText` and `nextStep` (ACCOUNT_REACTIVATION → M-2, or TELLER). `cards` counts the cards on the account. With `eligibleFor=CARD` each account also has `eligible` and `reason`.

Response `200`:
```json
{
  "accounts": [
    {
      "accountId": "acc_M1SV",
      "type": "SAVINGS",
      "masked": "••• 7001",
      "available": {
        "amount": "48200.00",
        "currency": "KES"
      },
      "status": "ACTIVE",
      "canTransact": true,
      "lastActivityAt": "2026-11-05T18:12:24.177Z",
      "hasCard": true,
      "cards": 3
    },
    {
      "accountId": "acc_M3DM",
      "type": "SAVINGS",
      "masked": "••• 7003",
      "available": {
        "amount": "15300.00",
        "currency": "KES"
      },
      "status": "DORMANT",
      "canTransact": false,
      "lastActivityAt": "2025-03-10T18:12:24.177Z",
      "statusText": "Dormant: reactivate it to use it",
      "nextStep": "ACCOUNT_REACTIVATION",
      "hasCard": false,
      "cards": 0
    }
  ]
}
```
### C-4 · Fee quote

`POST /stm/v1/fees/quote`  ·  needs `X-Session-Id`, `X-Customer-Token`

`service`: CARD_ISSUE or CARD_REPLACEMENT.

Request:
```json
{
  "service": "CARD_ISSUE",
  "cardProduct": "GOLD",
  "debitAccountId": "acc_6TR3"
}
```
Response `200`:
```json
{
  "quoteId": "fq_GFJVXC65TY7M",
  "lines": [
    {
      "label": "Card issuance (Gold)",
      "amount": "450.00"
    }
  ],
  "total": {
    "amount": "600.00",
    "currency": "KES"
  },
  "includesExcise": true,
  "sufficientFunds": true,
  "expiresAt": "2026-11-06T14:33:20.425077517+03:00"
}
```
### C-5 · Order the card (C5)

`POST /stm/v1/cards/orders`  ·  needs `X-Session-Id`, `X-Customer-Token`, `Idempotency-Key`

`service`: LINK_AND_ISSUE, REPLACEMENT (replacesCardId), NEW_CARD_SETUP (cardId). Pass `feeQuoteId` + `customerAuthorised: true` when a fee applies; `applicationId` right after B-12 (fee waived).

Request:
```json
{
  "service": "LINK_AND_ISSUE",
  "accountId": "acc_XER71C0YH7PC",
  "cardProduct": "CLASSIC",
  "nameOnCard": "AMARA N KAMAU",
  "applicationId": "app_PS03612K55WY"
}
```
Response `201`:
```json
{
  "orderId": "co_ZK7JCH825EY1",
  "cardId": "crd_7910PPJSPNB0",
  "maskedPan": "4512 •••• •••• 2979",
  "fee": {
    "posted": false,
    "transactionRef": null,
    "amount": "0.00",
    "currency": "KES",
    "waived": true
  },
  "personalisation": {
    "payloadForPrinter": "<printer payload>",
    "jobId": "pj_AB7NY2ZAWR1A"
  },
  "reference": "STM-CRD-10002"
}
```
Seen in testing: `409 WRONG_STATE`

### C-6 · Printing progress

`GET /stm/v1/cards/orders/{orderId}`  ·  needs `X-Session-Id`

Poll every second. Steps PICK_BLANK, PRINT_NAME, PROGRAM_EMV_CHIP, QUALITY_CHECK; `status` READY when done.

Response `200`:
```json
{
  "orderId": "co_ZK7JCH825EY1",
  "status": "PERSONALISING",
  "steps": [
    {
      "code": "PICK_BLANK",
      "status": "IN_PROGRESS"
    }
  ]
}
```
### — · Report a printer step (device agent)

`PUT /stm/v1/cards/orders/{orderId}/steps`  ·  needs `X-Session-Id`

Optional; without it the gateway simulates the timing.

Request:
```json
{
  "code": "PRINT_NAME",
  "status": "DONE"
}
```
Response `200`:
```json
{
  "orderId": "co_…",
  "status": "PERSONALISING",
  "steps": [
    {
      "code": "PICK_BLANK",
      "status": "DONE"
    },
    {
      "code": "PRINT_NAME",
      "status": "DONE"
    }
  ]
}
```
### C-7 · Card dispensed

`POST /stm/v1/cards/orders/{orderId}/dispense`  ·  needs `X-Session-Id`

`outcome`: TAKEN, RETAINED or SPOILED (the card is destroyed at the CMS).

Request:
```json
{
  "outcome": "TAKEN",
  "qualityCheck": "PASS",
  "cardSerial": "CS-00921844"
}
```
Response `200`:
```json
{
  "orderId": "co_ZK7JCH825EY1",
  "status": "DISPENSED",
  "cardStatus": "INACTIVE_PIN_REQUIRED"
}
```
### C-8 · Set the PIN (B10 / C6)

`POST /stm/v1/cards/{cardId}/pin`  ·  needs `X-Session-Id`, `X-Customer-Token`

422 PIN_POLICY (e.g. 1234, 1111).

Request:
```json
{
  "pinBlock": "044826FFFFFFFFFF",
  "format": "ISO_9564_FORMAT_4",
  "keySerialNumber": "FFFF9876543210E00008",
  "confirmed": true,
  "policyChecks": {
    "notSequential": true,
    "notRepeated": true
  }
}
```
Response `200`:
```json
{
  "cardId": "crd_7910PPJSPNB0",
  "pinSet": true
}
```
Seen in testing: `409 WRONG_STATE`, `422 PIN_POLICY`

### C-9 · Activate the card

`POST /stm/v1/cards/{cardId}/activate`  ·  needs `X-Session-Id`, `X-Customer-Token`

Request:
```json
{
  "channel": "STM"
}
```
Response `200`:
```json
{
  "cardId": "crd_7910PPJSPNB0",
  "status": "ACTIVE",
  "maskedPan": "4512 •••• •••• 2979",
  "linkedAccount": "Savings ••• 1000",
  "limits": {
    "atmDaily": {
      "amount": "40000.00",
      "currency": "KES"
    },
    "posDaily": {
      "amount": "150000.00",
      "currency": "KES"
    }
  },
  "reference": "STM-CRD-10002"
}
```
### C-10 · Block a card

`POST /stm/v1/cards/{cardId}/block`  ·  needs `X-Session-Id`, `X-Customer-Token`

`reason`: LOST, STOLEN, DAMAGED or EXPIRED. Permanent: the card can only be replaced (C-5 REPLACEMENT). For a freeze the customer can lift, use P-4. Sends an SMS.

Request:
```json
{
  "reason": "LOST"
}
```
Response `200`:
```json
{
  "cardId": "crd_8102",
  "maskedPan": "4512 •••• •••• 8102",
  "status": "BLOCKED",
  "blockedAt": "2026-11-10T21:12:34.466892056+03:00",
  "reason": "LOST",
  "reference": "STM-SVC-10005"
}
```

## P / M · Card care and existing customers

The step-by-step guide with screens is `docs/CARD-AND-CUSTOMER-SERVICES.md`. The customer token has a source:
`CARD_PIN` (S-2), `VERIFIED_IDENTITY` (C-1 + B-9 + B-10 + B-5: code and thumbprint) or `BIOMETRIC_ONLY` (B-5 with
`purpose: MOBILE_CHANGE`, phone lost: face and thumbprint, no code). Calls marked **strong** need `VERIFIED_IDENTITY`
and answer 403 `STEP_UP_REQUIRED` otherwise. A `BIOMETRIC_ONLY` token works only for M-4 and M-5. For 24 hours after a
phone-number change (`stm.services.mobile-change-hold-hours`) P-3, P-5, M-2, M-4, M-7 and C-5 answer 403
`MOBILE_RECENTLY_CHANGED`. Every change is recorded (portal: Cards → Service requests) and sends an SMS.

### P-1 · Card status

`GET /stm/v1/cards/{cardId}/status`  ·  needs `X-Session-Id`, `X-Customer-Token`

The same object as each entry of C-2. `status`: INACTIVE_PIN_REQUIRED, ACTIVE, TEMP_BLOCKED, BLOCKED, PIN_LOCKED, EXPIRED. `statusText` is in the session language. `actions` lists what the kiosk may offer: PIN_CHANGE, PIN_RESET, TEMPORARY_BLOCK, UNBLOCK, BLOCK_AND_REPLACE, REPLACEMENT, NEW_CARD_SETUP. `usable` is false when the card or its account cannot transact.

Response `200`:
```json
{
  "cardId": "crd_8104",
  "maskedPan": "4512 •••• •••• 8104",
  "product": "CLASSIC",
  "status": "BLOCKED",
  "statusText": "Blocked: damaged",
  "reason": "DAMAGED",
  "since": "2026-10-31T18:12:24.177Z",
  "expiry": "2027-06",
  "expired": false,
  "expiresSoon": false,
  "pinSet": true,
  "pinAttemptsLeft": 3,
  "linkedAccountId": "acc_M2CU",
  "linkedAccount": "Current ••• 7002",
  "accountStatus": "ACTIVE",
  "usable": false,
  "actions": [
    "REPLACEMENT"
  ]
}
```
### P-2 · Change PIN

`POST /stm/v1/cards/{cardId}/pin/change`  ·  needs `X-Session-Id`, `X-Customer-Token`

The customer knows the current PIN. Any token. A wrong current PIN counts towards the three tries.

Request:
```json
{
  "currentPinBlock": "042468FFFFFFFFFF",
  "newPinBlock": "049731FFFFFFFFFF",
  "format": "ISO_9564_FORMAT_4",
  "keySerialNumber": "FFFF9876543210E00009",
  "confirmed": true
}
```
Response `200`:
```json
{
  "cardId": "crd_8101",
  "maskedPan": "4512 •••• •••• 8101",
  "status": "ACTIVE",
  "pinChanged": true,
  "reference": "STM-SVC-10001"
}
```
Errors: 401 `PIN_INCORRECT` (`attemptsLeft`), 403 `PIN_LOCKED` at 0, 422 `PIN_UNCHANGED`, `PIN_POLICY`, `PIN_NOT_CONFIRMED`, 403 `CARD_TEMP_BLOCKED`, 409 `CARD_BLOCKED_PERMANENT`

### P-3 · Reset PIN

`POST /stm/v1/cards/{cardId}/pin/reset`  ·  needs `X-Session-Id`, `X-Customer-Token` (**strong**)

Forgot the PIN, or the card is PIN_LOCKED. `unlocked` is true when the card had been locked.

Request:
```json
{
  "newPinBlock": "048642FFFFFFFFFF",
  "format": "ISO_9564_FORMAT_4",
  "keySerialNumber": "FFFF9876543210E00009",
  "confirmed": true
}
```
Response `200`:
```json
{
  "cardId": "crd_8105",
  "maskedPan": "4512 •••• •••• 8105",
  "status": "ACTIVE",
  "pinReset": true,
  "reference": "STM-SVC-10002",
  "unlocked": true
}
```
Errors: 403 `STEP_UP_REQUIRED`, 403 `MOBILE_RECENTLY_CHANGED`, 422 `PIN_POLICY`, `PIN_NOT_CONFIRMED`

### P-4 · Temporary block

`POST /stm/v1/cards/{cardId}/temporary-block`  ·  needs `X-Session-Id`, `X-Customer-Token`

A freeze the customer can lift (P-5). `reason`: MISPLACED (default), SUSPICIOUS, TRAVEL or OTHER. Any token. For a lost or stolen card use C-10.

Request:
```json
{
  "reason": "TRAVEL"
}
```
Response `200`:
```json
{
  "cardId": "crd_8102",
  "maskedPan": "4512 •••• •••• 8102",
  "status": "TEMP_BLOCKED",
  "temporarilyBlocked": true,
  "reference": "STM-SVC-10003"
}
```
Errors: 409 `CARD_BLOCKED_PERMANENT`

### P-5 · Unblock

`POST /stm/v1/cards/{cardId}/unblock`  ·  needs `X-Session-Id`, `X-Customer-Token` (**strong**)

Lifts a temporary block. No body.

Response `200`:
```json
{
  "cardId": "crd_8103",
  "maskedPan": "4512 •••• •••• 8103",
  "status": "ACTIVE",
  "unblocked": true,
  "reference": "STM-SVC-10004"
}
```
Errors: 409 `CARD_BLOCKED_PERMANENT` (`reason`, `nextStep: CARD_REPLACEMENT`), 409 `WRONG_STATE` (`nextStep: PIN_RESET`) for a PIN-locked card, 403 `STEP_UP_REQUIRED`, 403 `MOBILE_RECENTLY_CHANGED`

### M-1 · Customer profile

`GET /stm/v1/customers/{customerId}/profile`  ·  needs `X-Session-Id`, `X-Customer-Token`

What core banking holds for the customer and what the kiosk should offer (`suggestions`: ENROL_BIOMETRICS, REGISTER_SIGNATURE, REACTIVATE_ACCOUNT, KYC_REFRESH_WITH_TELLER). `verifiedBy` is the token source.

Response `200`:
```json
{
  "customerId": "CIF00204417",
  "firstName": "Mary",
  "maskedMobile": "+254 7•• ••• 417",
  "mobileChangedAt": null,
  "mobileHold": {
    "hoursLeft": 0,
    "active": false
  },
  "verifiedBy": "VERIFIED_IDENTITY",
  "kyc": {
    "status": "CURRENT",
    "nextReviewDue": "2028-09-10",
    "kraPinOnFile": true,
    "selfServiceAllowed": true,
    "flags": []
  },
  "biometrics": {
    "enrolled": false,
    "fingers": [],
    "enrolledAt": null,
    "source": null
  },
  "signature": {
    "onFile": false,
    "capturedAt": null,
    "source": null
  },
  "accounts": {
    "total": 4,
    "restricted": 1,
    "dormant": 1,
    "active": 2
  },
  "cards": {
    "active": 2,
    "total": 5,
    "needAttention": 3
  },
  "suggestions": [
    "ENROL_BIOMETRICS",
    "REGISTER_SIGNATURE",
    "REACTIVATE_ACCOUNT"
  ]
}
```
### M-2 · Reactivate a dormant account

`POST /stm/v1/accounts/{accountId}/reactivate`  ·  needs `X-Session-Id`, `X-Customer-Token` (**strong**)

An account is DORMANT after 12 months without customer activity (`stm.accounts.dormant-after-months`). Debits answer 403 `ACCOUNT_DORMANT`; deposits are accepted. Reactivation needs current KYC.

Request:
```json
{
  "confirmed": true
}
```
Response `200`:
```json
{
  "accountId": "acc_M3DM",
  "masked": "••• 7003",
  "type": "SAVINGS",
  "status": "ACTIVE",
  "reactivated": true,
  "reference": "STM-SVC-10006"
}
```
Errors: 403 `ACCOUNT_RESTRICTED` (frozen or closed: a teller), 403 `KYC_REFRESH_REQUIRED`, 403 `STEP_UP_REQUIRED`, 403 `MOBILE_RECENTLY_CHANGED`

### M-3 · Enrol fingerprints

`POST /stm/v1/customers/{customerId}/biometrics/enrol`  ·  needs `X-Session-Id`, `X-Customer-Token` (**strong**)

One to four fingers. Each must have quality 50 or more and match the national registry. After this, B-5 matches against the bank (`against: BANK`).

Request:
```json
{
  "consent": true,
  "fingerprints": [
    {
      "finger": "RIGHT_THUMB",
      "template": "<base64>",
      "quality": 82
    },
    {
      "finger": "LEFT_THUMB",
      "template": "<base64>",
      "quality": 77
    }
  ]
}
```
Response `200`:
```json
{
  "enrolled": true,
  "fingers": [
    "LEFT_THUMB",
    "RIGHT_THUMB"
  ],
  "enrolledAt": "2026-11-10T18:12:35.745Z",
  "reference": "STM-SVC-10007"
}
```
Errors: 422 `CONSENT_REQUIRED`, 422 `BIOMETRIC_QUALITY_LOW` (`finger`), 422 `BIOMETRIC_NO_MATCH` (`finger`)

### M-4 · Start a phone-number change

`POST /stm/v1/customers/{customerId}/mobile-change`  ·  needs `X-Session-Id`, `X-Customer-Token` (**strong**, or the phone-lost token)

Then B-9 with `{"purpose": "MOBILE_CHANGE", "changeRef": …}` (the code goes to the new number), B-10, and M-5.

Request:
```json
{
  "newMobile": "0711000999"
}
```
Response `200`:
```json
{
  "changeRef": "mch_008FAR5R11RJ",
  "maskedCurrentMobile": "+254 7•• ••• 417",
  "maskedNewMobile": "+254 7•• ••• 999",
  "nextStep": "OTP",
  "otpPurpose": "MOBILE_CHANGE"
}
```
Errors: 422 `MOBILE_UNCHANGED`, 409 `MOBILE_IN_USE`, 403 `SIM_SWAP_RISK` (new SIM swapped within 72 h), 403 `MOBILE_RECENTLY_CHANGED`

### M-5 · Confirm the phone-number change

`POST /stm/v1/customers/{customerId}/mobile-change/{changeRef}/confirm`  ·  needs `X-Session-Id`, `X-Customer-Token` (**strong**, or the phone-lost token)

Changes the number in core banking and texts both the old and the new number. No body.

Response `200`:
```json
{
  "changed": true,
  "maskedMobile": "+254 7•• ••• 999",
  "holdHours": 24,
  "reference": "STM-SVC-10009"
}
```
Errors: 409 `WRONG_STATE` (`nextStep: OTP`) until the code on the new number is verified, 404 `MOBILE_CHANGE_NOT_FOUND`

### M-6 · Signature on file

`GET /stm/v1/customers/{customerId}/signature`  ·  needs `X-Session-Id`, `X-Customer-Token`

Whether core banking holds a specimen signature. The image is never returned to the kiosk. `source`: BRANCH, STM_ONBOARDING, STM_UPDATE.

Response `200`:
```json
{
  "onFile": true,
  "signatureId": "sig_R1ME4F2F3ATZ",
  "capturedAt": "2026-11-10T18:03:18.801Z",
  "source": "STM_UPDATE"
}
```
### M-7 · Register or update the signature

`POST /stm/v1/customers/{customerId}/signature`  ·  needs `X-Session-Id`, `X-Customer-Token` (**strong**)

PNG or JPEG, at least 120 × 40 pixels, with visible strokes (the same check as B-11). The previous signature is kept as superseded.

Request:
```json
{
  "image": "<base64>",
  "strokes": 4,
  "capturedWith": "STYLUS"
}
```
Response `200`:
```json
{
  "signatureId": "sig_4S1GDTJ9JJV4",
  "capturedAt": "2026-11-10T18:12:36.808Z",
  "replacedPrevious": false,
  "reference": "STM-SVC-10008"
}
```
Errors: 422 `SIGNATURE_UNREADABLE` (`reason`: EMPTY, TOO_SMALL, BLANK, FILLED), 403 `STEP_UP_REQUIRED`, 403 `MOBILE_RECENTLY_CHANGED`

## S · Balance and statements

### S-3 · Balance

`GET /stm/v1/accounts/{accountId}/balance`  ·  needs `X-Session-Id`, `X-Customer-Token`

Response `200`:
```json
{
  "accountId": "acc_2LM8",
  "masked": "••• 4521",
  "type": "SAVINGS",
  "ledger": {
    "amount": "35350.00",
    "currency": "KES"
  },
  "available": {
    "amount": "35350.00",
    "currency": "KES"
  },
  "asOf": "2026-11-06T14:23:36.342022422+03:00"
}
```
### S-4 · Statement options (D2)

`GET /stm/v1/statements/options?accountId=acc_2LM8`  ·  needs `X-Session-Id`, `X-Customer-Token`

FULL also has `periodsMonths` (e.g. [1,3,6]) and `stamped: true`.

Response `200`:
```json
{
  "options": [
    {
      "type": "MINI",
      "output": "RECEIPT",
      "transactions": 10,
      "fee": {
        "amount": "30.00",
        "currency": "KES"
      }
    }
  ]
}
```
### S-5 · Statement (D3)

`POST /stm/v1/statements`  ·  needs `X-Session-Id`, `X-Customer-Token`, `Idempotency-Key`

`type`: MINI (returns `lines` for the receipt) or FULL with `periodMonths` (returns `pages` and `documentUrl`).

Request:
```json
{
  "accountId": "acc_2LM8",
  "type": "MINI",
  "feeAccepted": true
}
```
Response `201`:
```json
{
  "statementId": "st_73TGH9ZF2FCN",
  "reference": "STM-STMT-10004",
  "fee": {
    "posted": true,
    "transactionRef": "FT26310VR65U",
    "amount": "30.00",
    "currency": "KES"
  },
  "balanceAfterFee": {
    "amount": "35320.00",
    "currency": "KES"
  },
  "lines": [
    {
      "date": "2026-11-06",
      "narrative": "Mini statement fee STM-NBO-MOI-03",
      "amount": "30.00",
      "direction": "DR",
      "balance": "35320.00"
    }
  ]
}
```
### S-6 · Statement PDF

`GET /stm/v1/statements/{statementId}/document`  ·  needs `X-Session-Id`, `X-Customer-Token`

Returns `application/pdf` (stamped A4).


## K · Cash deposit

### K-1 · Validate the deposit target (E2)

`POST /stm/v1/accounts/validate`  ·  needs `X-Session-Id`

`type`: ACCOUNT (accountNumber) or PAYBILL (paybill 522522 / 888880, accountReference). No customer token needed.

Request:
```json
{
  "type": "ACCOUNT",
  "accountNumber": "0123457780",
  "purpose": "CASH_DEPOSIT"
}
```
Response `200`:
```json
{
  "valid": true,
  "targetRef": "tg_00W7AQG6DW7H",
  "accountName": "Achieng W. Otieno",
  "masked": "Current ••• 7780",
  "branch": "Moi Avenue",
  "limits": {
    "maxAmount": {
      "amount": "500000.00",
      "currency": "KES"
    },
    "maxNotes": 100
  }
}
```
### K-2 · Open the shutter (E3)

`POST /stm/v1/cash/deposits`  ·  needs `X-Session-Id`

Request:
```json
{
  "targetRef": "tg_00W7AQG6DW7H",
  "currency": "KES",
  "depositorMobile": "+254712345678"
}
```
Response `201`:
```json
{
  "expiresAt": "2026-11-06T14:28:38.371311139+03:00",
  "status": "AWAITING_CASH",
  "depositId": "dp_JBPXKA3QMFWC"
}
```
### K-3 · Notes counted (E4–E6)

`PUT /stm/v1/cash/deposits/{depositId}/count`  ·  needs `X-Session-Id`

Notes stay in escrow. 422 DEPOSIT_LIMIT_EXCEEDED (100 notes / KES 500,000).

Request:
```json
{
  "accepted": [
    {
      "denomination": "1000",
      "count": 10
    }
  ],
  "rejected": {
    "count": 3,
    "reasons": [
      "UNRECOGNISED"
    ]
  },
  "suspectedCounterfeit": 0,
  "escrowHeld": true
}
```
Response `200`:
```json
{
  "depositId": "dp_JBPXKA3QMFWC",
  "status": "COUNTED",
  "total": {
    "amount": "12000.00",
    "currency": "KES"
  },
  "breakdown": [
    {
      "denomination": "1000",
      "count": 10,
      "amount": "10000.00"
    }
  ],
  "fee": {
    "amount": "0.00",
    "currency": "KES"
  },
  "returnedNotes": 3,
  "suspectedCounterfeit": 0
}
```
Seen in testing: `422 DEPOSIT_LIMIT_EXCEEDED`

### K-4 · Confirm the deposit (E7)

`POST /stm/v1/cash/deposits/{depositId}/commit`  ·  needs `X-Session-Id`, `Idempotency-Key`

`confirmedTotal` must equal the counted total.

Request:
```json
{
  "confirmedTotal": {
    "amount": "12000.00",
    "currency": "KES"
  }
}
```
Response `200`:
```json
{
  "depositId": "dp_JBPXKA3QMFWC",
  "status": "CREDITED",
  "transactionRef": "STM-DEP-10006",
  "coreRef": "FT26310YCQ5X",
  "amount": {
    "amount": "12000.00",
    "currency": "KES"
  },
  "previousBalance": {
    "amount": "12240.50",
    "currency": "KES"
  },
  "newBalance": {
    "amount": "24240.50",
    "currency": "KES"
  },
  "postedAt": "2026-11-06T11:23:38.435Z"
}
```
Seen in testing: `409 WRONG_STATE`

### K-5 · Cancel: return the notes

`POST /stm/v1/cash/deposits/{depositId}/cancel`  ·  needs `X-Session-Id`

409 DEPOSIT_ALREADY_CREDITED after K-4.

Request:
```json
{
  "reason": "CUSTOMER_CANCELLED"
}
```
Response `200`:
```json
{
  "depositId": "dp_…",
  "status": "CANCELLED",
  "returnCash": true
}
```

## W · Cash withdrawal

### W-1 · Limits and quick amounts (E8)

`GET /stm/v1/accounts/{accountId}/withdrawal-limits`  ·  needs `X-Session-Id`, `X-Customer-Token`

Response `200`:
```json
{
  "available": {
    "amount": "35220.00",
    "currency": "KES"
  },
  "perTransactionMax": {
    "amount": "40000.00",
    "currency": "KES"
  },
  "dailyRemaining": {
    "amount": "40000.00",
    "currency": "KES"
  },
  "multipleOf": "100",
  "fee": {
    "amount": "0.00",
    "currency": "KES"
  },
  "quickAmounts": [
    "2000"
  ]
}
```
### W-2 · Withdraw (E9)

`POST /stm/v1/cash/withdrawals`  ·  needs `X-Session-Id`, `X-Customer-Token`, `Idempotency-Key`

Debits at once and returns the note mix. For foreign notes the bank sells: add `fxQuoteId` (X-2 with direction BANK_SELLS). 422 LIMIT_EXCEEDED / INSUFFICIENT_FUNDS, 409 CASSETTE_UNAVAILABLE. Keep the Idempotency-Key until W-3/W-4.

Request:
```json
{
  "accountId": "acc_2LM8",
  "amount": {
    "amount": "5000.00",
    "currency": "KES"
  },
  "fxQuoteId": null,
  "proposedMix": null
}
```
Response `201`:
```json
{
  "withdrawalId": "wd_N6AMP8A1Q50Q",
  "status": "AUTHORISED",
  "transactionRef": "STM-WDL-10009",
  "coreRef": "FT26310CDN1W",
  "dispense": {
    "mix": [
      {
        "denomination": "1000",
        "count": 4
      }
    ],
    "currency": "KES"
  },
  "balanceAfter": {
    "amount": "30220.00",
    "currency": "KES"
  },
  "presentTimeoutSec": 30
}
```
Seen in testing: `400 VALIDATION_ERROR`, `422 LIMIT_EXCEEDED`

### W-3 · Notes taken

`POST /stm/v1/cash/withdrawals/{withdrawalId}/confirm`  ·  needs `X-Session-Id`

Request:
```json
{
  "presented": [
    {
      "denomination": "1000",
      "count": 4
    }
  ],
  "taken": true
}
```
Response `200`:
```json
{
  "withdrawalId": "wd_N6AMP8A1Q50Q",
  "status": "COMPLETED"
}
```
### W-4 · Reverse (retracted / not dispensed)

`POST /stm/v1/cash/withdrawals/{withdrawalId}/reverse`  ·  needs `X-Session-Id`, `Idempotency-Key`

`reason`: CASH_RETRACTED, DISPENSE_FAILED, TIMEOUT, CUSTOMER_CANCELLED. With `retract-credit: AFTER_COUNT` the status is PENDING_RECONCILIATION.

Request:
```json
{
  "reason": "CASH_RETRACTED",
  "presented": [
    {
      "denomination": "1000",
      "count": 2
    }
  ],
  "retractedCount": 2
}
```
Response `200`:
```json
{
  "withdrawalId": "wd_5Z8V8EYVT8FW",
  "status": "REVERSED",
  "reversalRef": "STM-RTR-10011",
  "amount": {
    "amount": "2000.00",
    "currency": "KES"
  },
  "creditBackBy": "2026-11-06T11:23:40.688Z"
}
```
### W-4 · Reverse when W-2's answer was lost

`POST /stm/v1/cash/withdrawals/by-idempotency-key/reverse`  ·  needs `X-Session-Id`, `Idempotency-Key`

Send the **same Idempotency-Key as the W-2**.

Request:
```json
{
  "reason": "TIMEOUT"
}
```
Response `200`:
```json
{
  "message": "No withdrawal with this Idempotency-Key reached core.",
  "status": "NOTHING_TO_REVERSE"
}
```

## X · Foreign exchange

### X-1 · Rates (E10)

`GET /stm/v1/fx/rates`  ·  needs `X-Session-Id`

Response `200`:
```json
{
  "asOf": "2026-11-06T11:22:58.521Z",
  "base": "KES",
  "commissionPct": "0",
  "rates": [
    {
      "currency": "EUR",
      "symbol": "€",
      "buy": "150.20",
      "sell": "151.10"
    }
  ]
}
```
### X-2 · Quote (E11)

`POST /stm/v1/fx/quotes`  ·  needs `X-Session-Id`

Needs `identityVerificationId` from B-4 (purpose FOREX). BANK_BUYS: customer brings foreign notes. BANK_SELLS: add `foreignAmount` (and `debitAccountId` with a customer token), then W-2 with `fxQuoteId`.

Request:
```json
{
  "direction": "BANK_BUYS",
  "currency": "USD",
  "identityVerificationId": "idv_ABKT2HRK5FP5"
}
```
Response `201`:
```json
{
  "quoteId": "fxq_VVZX64FZWNEX",
  "direction": "BANK_BUYS",
  "currency": "USD",
  "rate": "130.50",
  "commission": {
    "amount": "0.00",
    "currency": "KES"
  },
  "expiresAt": "2026-11-06T14:28:41.295250222+03:00"
}
```
Seen in testing: `409 CASSETTE_UNAVAILABLE`

### X-3 · Start the exchange

`POST /stm/v1/fx/exchanges`  ·  needs `X-Session-Id`

`destination`: `{type: "CASH"}` or `{type: "ACCOUNT", accountId}` (customer token).

Request:
```json
{
  "quoteId": "fxq_VVZX64FZWNEX",
  "destination": {
    "type": "CASH"
  }
}
```
Response `201`:
```json
{
  "status": "AWAITING_CASH",
  "exchangeId": "fx_FGVVW3WSYZ3W"
}
```
### X-4 · Foreign notes counted (E12)

`PUT /stm/v1/fx/exchanges/{exchangeId}/count`  ·  needs `X-Session-Id`

Cash-out is rounded down to KES 100 (`roundedFrom` shows the exact value).

Request:
```json
{
  "currency": "USD",
  "accepted": [
    {
      "denomination": "100",
      "count": 2,
      "serials": [
        "PB12345678A"
      ]
    }
  ],
  "rejected": {
    "count": 0
  }
}
```
Response `200`:
```json
{
  "exchangeId": "fx_FGVVW3WSYZ3W",
  "foreignTotal": {
    "amount": "200.00",
    "currency": "USD"
  },
  "rate": "130.50",
  "localAmount": {
    "amount": "26100.00",
    "currency": "KES"
  },
  "commission": {
    "amount": "0.00",
    "currency": "KES"
  },
  "returnedNotes": 0
}
```
### X-5 · Confirm

`POST /stm/v1/fx/exchanges/{exchangeId}/confirm`  ·  needs `X-Session-Id`, `Idempotency-Key`

CASH: dispense `dispense.mix`, then W-3 (or W-4) with `withdrawalId`. ACCOUNT: `status: CREDITED`, `newBalance`.

Request:
```json
{
  "confirmedLocalAmount": {
    "amount": "26100.00",
    "currency": "KES"
  }
}
```
Response `200`:
```json
{
  "exchangeId": "fx_FGVVW3WSYZ3W",
  "status": "AUTHORISED",
  "transactionRef": "STM-FX-10012",
  "withdrawalId": "wd_9XH35TB37Z1T",
  "dispense": {
    "mix": [
      {
        "denomination": "1000",
        "count": 26
      }
    ],
    "currency": "KES"
  },
  "presentTimeoutSec": 30
}
```
### — · Cancel: return the foreign notes

`POST /stm/v1/fx/exchanges/{exchangeId}/cancel`  ·  needs `X-Session-Id`

Before X-5.

Request:
```json
{
  "reason": "CUSTOMER_CANCELLED"
}
```
Response `200`:
```json
{
  "exchangeId": "fx_…",
  "status": "CANCELLED",
  "returnCash": true
}
```

## R · Receipts and SMS

### R-1 · Receipt

`POST /stm/v1/receipts`  ·  needs `X-Session-Id`

`delivery`: PRINT, SMS or NONE. Works after D-4 for that session's last transaction. SMS adds `sms: "QUEUED"`.

Request:
```json
{
  "transactionRef": "STM-CRD-10002",
  "delivery": "PRINT"
}
```
Response `201`:
```json
{
  "receiptId": "rc_KJ00TW94Y16Y",
  "lines": [
    "BLUE RIVER SMART TELLER  STM-NBO-MOI-03"
  ],
  "qr": "https://bank.example/r/rc_KJ00TW94Y16Y",
  "printStatusUrl": "/stm/v1/receipts/rc_KJ00TW94Y16Y"
}
```
### — · Printer result (device agent)

`PUT /stm/v1/receipts/{receiptId}`  ·  needs `X-Session-Id`

`status`: PRINTED, FAILED or PAPER_OUT.

Request:
```json
{
  "status": "PRINTED"
}
```
Response `200`:
```json
{
  "receiptId": "rc_…",
  "printStatus": "PRINTED"
}
```
### — · Receipt status

`GET /stm/v1/receipts/{receiptId}`  ·  needs `X-Session-Id`

Response `200`:
```json
{
  "receiptId": "rc_…",
  "transactionRef": "STM-DEP-10006",
  "delivery": "PRINT",
  "printStatus": "PRINTED",
  "lines": [
    "BLUE RIVER SMART TELLER  STM-NBO-MOI-03",
    "…"
  ]
}
```
### R-2 · SMS

`POST /stm/v1/notifications/sms`  ·  needs `X-Session-Id`

Templates e.g. GENERIC_RECEIPT; OTPs only through B-9.

Request:
```json
{
  "template": "GENERIC_RECEIPT",
  "mobile": "0722000111",
  "language": "sw",
  "params": {
    "ref": "STM-TEST",
    "summary": "Majaribio"
  }
}
```
Response `202`:
```json
{
  "status": "QUEUED",
  "messageId": "sms_2E5XGJK3VSKS"
}
```

## A · Device agent

### A-1 · Upload the electronic journal

`POST /stm/v1/devices/{deviceId}/journal`

`signature` = hex HMAC-SHA256 of the entries with the terminal key. Session optional.

Request:
```json
{
  "fromSeq": 18201,
  "toSeq": 18202,
  "entries": [
    {
      "seq": 18201,
      "at": "2026-11-06T09:12:03Z",
      "event": "SHUTTER_OPEN",
      "ref": "dp_JBPXKA3QMFWC"
    }
  ],
  "signature": "<hex HMAC-SHA256>"
}
```
Response `200`:
```json
{
  "signatureValid": true,
  "acceptedToSeq": 18202
}
```
### A-2 · Cash positions (reconciliation)

`POST /stm/v1/devices/{deviceId}/cash-positions`

`event`: END_OF_DAY or CASH_IN_TRANSIT. A variance opens a case (`caseId`).

Request:
```json
{
  "event": "END_OF_DAY",
  "cassettes": [
    {
      "id": "C0",
      "currency": "KES",
      "denomination": "1000",
      "count": 778
    }
  ],
  "retractBin": 2,
  "rejectBin": 3,
  "counterfeitBin": 0
}
```
Response `200`:
```json
{
  "reconciled": true,
  "variances": [
    {
      "currency": "EUR",
      "expected": "5500.00",
      "counted": "5500.00",
      "difference": "0.00"
    }
  ],
  "caseId": null
}
```
## Portal API (`/api/v1/portal`)

Sign in with `POST /api/v1/auth/login` `{"username": "…", "password": "…"}` → `{"token": "…", "expiresAt": "…", "user": {…}}`,
then send `Authorization: Bearer <token>`. In the demo profile scripts can send `X-Portal-Key: dev-portal-key` instead.
Also `GET /api/v1/auth/me`, `POST /api/v1/auth/logout`, `POST /api/v1/auth/password` `{currentPassword, newPassword}`.

| Method and path | Who | What it returns / does |
|---|---|---|
| `GET /overview` | all | `totals` (sessionsToday, accountsOpenedToday, referralsWaiting, identityHelpWaiting, depositsTodayKes, withdrawalsTodayKes, reversalsPending, cashVariances, terminalsOnline …), `last14Days[]`, `byFlow[]`, `applicationFunnel[]` |
| `GET /sessions?status=&flow=&q=` | all | session rows: `id, terminalCode, startTrigger, language, status, flow, customerId, customerName, startedAt, endedAt, endReason, applicationsCount, depositsCount, withdrawalsCount, fxCount, statementsCount, cardOrdersCount, tellerCallsCount, needsTeller` |
| `GET /sessions/{id}` | all | the row plus `applications[]`, `verifications[]`, `biometrics[]`, `cardOrders[]`, `deposits[]`, `withdrawals[]`, `fxExchanges[]`, `statements[]`, `receipts[]`, `sms[]`, `tellerCalls[]` |
| `GET /sessions/{id}/timeline` | all | audit events of the session |
| `GET /applications?status=&needsTeller=true` | all | application rows (`fullName, status, referralStatus, biometricAttempts, accountNumber, …`) |
| `GET /applications/{id}` | all | full application: registry record, screening, consents, captures, decision |
| `POST /applications/{id}/decision` | TELLER, SUPERVISOR | body `{"decision": "CLEAR_REFERRAL" \| "VERIFY_IDENTITY" \| "REJECT", "notes": "…"}`; CLEAR_REFERRAL only when REFERRED, VERIFY_IDENTITY only after 3 failed face matches. Returns the application |
| `GET /captures/{id}` | all | the image (registry photo, face, signature); every view is audited |
| `GET /transactions?kind=DEPOSIT\|WITHDRAWAL\|FX&status=` | all | `id, kind, terminalCode, sessionId, status, currency, amount, localAmount, party, transactionRef, coreRef, reversalRef, …` |
| `GET /postings?refs=FT…,FT…` | all | core-banking postings: `coreRef, accountId (or GL), direction DR/CR, amount, currency, narrative, balanceAfter` |
| `POST /terminals` `{terminalCode, branchCode, branchName?, name?, location?, notes?}` | ADMIN | adds a kiosk; the answer has its terminal `token` once. A new branch is created when `branchName` is given |
| `PUT /terminals/{code}` | ADMIN | branch, name, location, notes (the kiosk ID cannot change) |
| `POST /terminals/{code}/rotate-token` | ADMIN | replaces the terminal token (answer has the new `token` once); the old one stops working and the kiosk is signed out |
| `PUT /terminals/{code}/status` `{status: ACTIVE \| DISABLED}` | ADMIN | a disabled kiosk cannot sign in; ACTIVE also restores a removed kiosk |
| `DELETE /terminals/{code}` | ADMIN | deletes a kiosk that never served a customer; one with history is retired (`outcome: DELETED \| RETIRED`) |
| `GET /card-orders?status=` | all | card orders with steps, fee and customer |
| `GET /service-events?kind=&q=&page=&size=` | all | card and account changes made at the kiosks: PIN_CHANGED, PIN_RESET, CARD_TEMP_BLOCKED, CARD_UNBLOCKED, CARD_BLOCKED, ACCOUNT_REACTIVATED, BIOMETRICS_ENROLLED, MOBILE_CHANGED, SIGNATURE_REGISTERED, SIGNATURE_UPDATED |
| `GET /customers/{customerId}/signature` | all | the specimen signature image held by core banking (each view is audited); `GET /sessions/{id}` adds `serviceEvents` and `customerRecord` |
| `GET /reconciliation` | SUPERVISOR, AUDITOR, ADMIN | `positions[]` (A-2 reports with variances and caseId), `cassettes[]` (cash now, per currency), `pendingReversals[]` |
| `GET /device-journal?terminal=&ref=` | SUPERVISOR, AUDITOR, ADMIN | A-1 entries with `signatureValid` |
| `GET /sms` | all | SMS outbox (numbers and codes masked) |
| `GET /terminals`, `GET /terminals/registry` | all | live state (modules, disabledServices, serviceMode, cassetteCounts, alarms) and registry |
| `GET /telemetry/stream` | all | server-sent events `terminal-update` |
| `POST /terminals/{code}/commands` | SUPERVISOR, ADMIN | `{"command": "LOCK" \| "UNLOCK" \| "REBOOT" \| "REFRESH_CONFIG"}` |
| `GET /reference` | all | `products[]`, `tariffs[]`, `fxRates[]`, `terms[]`, `branches[]` |
| `PUT /fx-rates/{currency}` | SUPERVISOR, ADMIN | `{"buy": 130.75, "sell": 131.40}` (sell must be above buy) |
| `GET /audit/journal?entityId=&eventType=&beforeSeq=&limit=`, `GET /audit/verify`, `GET /audit/event-types` | SUPERVISOR, AUDITOR, ADMIN | hash-chained audit journal and its check (`{valid, checked, brokenAtSeq}`) |
| `GET /simulator`, `PUT /simulator` | all / SUPERVISOR, ADMIN | demo back ends; PUT `{"coreDown": true, "latencyMs": 500}` |
| `GET /simulator/customers?q=`, `GET /simulator/accounts/{id}/postings`, `GET /simulator/gl` | all | simulated core customers, account postings, GL balances |
| `GET/POST /users`, `PUT /users/{username}`, `POST /users/{username}/reset-password` | ADMIN | portal accounts |
| `POST /signaling/ticket` | TELLER, SUPERVISOR | WebSocket ticket for the teller console |

Example decision:
```http
POST /api/v1/portal/applications/app_YT5WZRJ7YET6/decision
Authorization: Bearer <portal token>
Content-Type: application/json

{"decision": "CLEAR_REFERRAL", "notes": "PEP name match reviewed on video: different person"}
```
→ `200` with the application: `"status": "KYC_CAPTURED", "referralStatus": "CLEARED", "decidedBy": "sup.wanjiru"`. The
kiosk sees the new status on its next B-13 poll.
