/**
 * Types for the Smart Teller Gateway (/stm/v1), following the Smart Teller Core Banking API Specification.
 * Operation codes (D-1, B-4, K-3 …) are the spec's; the kiosk screen codes (A0 … E13) are the prototype's.
 */

// ------------------------------------------------------------------------------------------- common

export type Lang = "en" | "sw";
export type Iso = string;              // ISO-8601 timestamp
export type Decimal = string;          // "12000.00": money and rates are decimal strings, never floats

export interface Money { amount: Decimal; currency: string }
export interface Note { denomination: string; count: number }

/** Every error body: { error: { … } }. Show message (or messageSw) to the customer as is. */
export interface ApiErrorBody {
  error: {
    code: ErrorCode;
    message: string;
    messageSw: string;
    retryable: boolean;
    requestId: string;
    details: Record<string, unknown> | null;
  };
}

export type ErrorCode =
  // transport and auth
  | "VALIDATION_ERROR" | "MISSING_HEADER" | "DEVICE_UNAUTHORIZED" | "SESSION_INVALID" | "CUSTOMER_TOKEN_REQUIRED"
  | "CUSTOMER_TOKEN_INVALID" | "FORBIDDEN" | "WRONG_STATE" | "IDEMPOTENCY_CONFLICT" | "REQUEST_IN_PROGRESS"
  | "IDEMPOTENCY_KEY_REQUIRED" | "SERVICE_UNAVAILABLE" | "CORE_UNAVAILABLE" | "OUT_OF_SERVICE" | "INTERNAL_ERROR"
  | "REQUEST_REJECTED" | "NOT_FOUND"
  // onboarding
  | "IDENTITY_MISMATCH" | "BIOMETRIC_NO_MATCH" | "EXISTING_CUSTOMER" | "KRA_PIN_INVALID" | "SCREENING_REFERRED"
  | "CONSENT_REQUIRED" | "TERMS_OUTDATED" | "APPLICATION_EXPIRED" | "OTP_INCORRECT" | "OTP_EXPIRED"
  | "OTP_TRIES_EXCEEDED" | "RESEND_TOO_SOON" | "SIM_SWAP_RISK"
  // cards
  | "PIN_INCORRECT" | "PIN_TRIES_EXCEEDED" | "PIN_POLICY" | "PIN_NOT_CONFIRMED" | "CARD_BLOCKED" | "CARD_EXPIRED"
  | "ACCOUNT_NOT_ELIGIBLE"
  // cash and forex
  | "INSUFFICIENT_FUNDS" | "LIMIT_EXCEEDED" | "CASSETTE_UNAVAILABLE" | "QUOTE_EXPIRED" | "DEPOSIT_LIMIT_EXCEEDED"
  | "DEPOSIT_ALREADY_CREDITED"
  // support
  | "VIDEO_UNAVAILABLE"
  // not found
  | "ACCOUNT_NOT_FOUND" | "APPLICATION_NOT_FOUND" | "CALL_NOT_FOUND" | "CARD_AUTH_NOT_FOUND" | "CARD_NOT_FOUND"
  | "CUSTOMER_NOT_FOUND" | "DEPOSIT_NOT_FOUND" | "EXCHANGE_NOT_FOUND" | "LOOKUP_NOT_FOUND" | "ORDER_NOT_FOUND"
  | "OTP_NOT_FOUND" | "PAYBILL_NOT_FOUND" | "QUOTE_NOT_FOUND" | "RECEIPT_NOT_FOUND" | "STATEMENT_NOT_FOUND"
  | "TARGET_NOT_FOUND" | "TERMS_NOT_FOUND" | "TRANSACTION_NOT_FOUND" | "VERIFICATION_NOT_FOUND" | "WITHDRAWAL_NOT_FOUND"
  // client side
  | "NETWORK_ERROR"
  | (string & {});

export type ServiceKey = "accountOpening" | "cardServices" | "cashDeposit" | "cashWithdrawal" | "forex" | "statements";
export type Services = Record<ServiceKey, boolean>;

// ------------------------------------------------------------------------------- D: device and session

/** D-1 */
export interface DeviceTokenRequest { deviceId: string; softwareVersion?: string; nonce?: string }
export interface DeviceToken { accessToken: string; expiresIn: number; branchCode: string; branchName: string }

/** D-2 (A0 "Tap to start" or a card in the reader) */
export interface StartSessionRequest { trigger: "TOUCH" | "CARD"; language?: Lang }
export interface SessionStarted { sessionId: string; expiresAt: Iso; inactivityTimeoutSec: number; services: Services }

/** D-4: open items are things the kiosk must finish or give back before the next customer. */
export interface OpenItem { type: string; id: string; status: string; action: string }
export interface SessionEnded { ended: true; openItems: OpenItem[] }
export type EndReason = "CUSTOMER_FINISHED" | "CUSTOMER_CANCELLED" | "TIMEOUT" | (string & {});

/** D-5 */
export interface Tariff { code: string; label: string; fee: Money; includesExcise?: boolean }
export interface Tariffs { tariffs: Tariff[]; version: string }

/** D-6: module → "OK" | "PAPER_LOW" | … or { status, …extra } */
export type ModuleStatus = string | ({ status: string } & Record<string, unknown>);
export interface HealthReport {
  modules: Partial<Record<"cardReader" | "cardPrinter" | "cashRecycler" | "receiptPrinter" | "a4Printer" | "idScanner"
    | "fingerprint" | "camera" | "pinPad", ModuleStatus>> & Record<string, ModuleStatus>;
  reportedAt?: Iso;
}
export interface HealthAccepted { accepted: true; disabledServices: ServiceKey[] }

/** D-7 status; X-6 cassettes */
export interface DeviceStatus { online: boolean; cashRecycler: string; allServicesAvailable: boolean; message: string | null }
export interface CassetteView { currency: string; denominations: string[]; status: "AVAILABLE" | "LOW" | "EMPTY" | (string & {}); maxPerCustomerDaily?: Decimal }

/** D-8 / D-9 "Talk to a teller" */
export interface TellerCallRequest { screen?: string; flow?: string; applicationId?: string; language?: Lang }
export interface CallMedia { provider: "webrtc"; roomUrl: string; room: string; token: string }
export interface TellerCall {
  callId: string;
  status: "QUEUED" | "RINGING" | "CONNECTED" | "ENDED" | "CANCELLED" | "UNAVAILABLE" | (string & {});
  callState?: string;
  estimatedWaitSec?: number;
  /** Set once a teller answers: join this LiveKit room. */
  media: CallMedia | null;
}

/** A-1 electronic journal and A-2 cash positions (device agent). */
export interface JournalEntry { seq: number; at: Iso; event: string; ref?: string; data?: Record<string, unknown> }
export interface JournalUpload { fromSeq: number; toSeq: number; entries: JournalEntry[]; signature: string }
export interface CashPositions {
  event: "END_OF_DAY" | "CASH_IN_TRANSIT" | (string & {});
  cassettes: { id?: string; currency: string; denomination: string; count: number }[];
  retractBin?: number; rejectBin?: number; counterfeitBin?: number;
}
export interface Reconciliation {
  reconciled: boolean;
  variances: { currency: string; expected: Decimal; counted: Decimal; difference: Decimal }[];
  caseId: string | null;
}

export interface WsTicket { ticket: string; expiresInSeconds: number }

// ---------------------------------------------------------------------------- B: account opening

/** B-1 */
export interface Product { code: string; name: string; minOpeningBalance: Money; features: string[]; cardProducts: CardProduct[] }
/** B-2 */
export interface Terms {
  termsId: string; version: string; language: Lang;
  sections: { title: string; body: string }[];
  consents: { code: string; required: boolean; label: string }[];
  audioUrl: string | null;
}

/** B-3 */
export interface StartApplicationRequest {
  productCode: string; termsId: string; consents: { code: string; accepted: boolean }[]; acceptedAt?: Iso;
}
export type ApplicationStatus = "CONSENTED" | "IDENTITY_CAPTURED" | "IDENTITY_VERIFIED" | "BIOMETRICS_VERIFIED" | "KYC_CAPTURED"
  | "REFERRED" | "PHONE_VERIFIED" | "SIGNATURE_CAPTURED" | "ACCOUNT_OPENED" | "REJECTED" | "CANCELLED" | "EXPIRED" | (string & {});
export interface ApplicationStarted { applicationId: string; status: ApplicationStatus; expiresAt: Iso }

/** B-4 */
export type DocumentType = "NATIONAL_ID" | "PASSPORT";
export interface DocumentVerifyRequest {
  applicationId?: string;
  purpose?: "ONBOARDING" | "FOREX";
  documentType: DocumentType;
  country?: string;
  ocr?: { documentNumber?: string; serialNumber?: string; surname?: string; givenNames?: string; dateOfBirth?: string; expiryDate?: string };
  mrz?: { line1: string; line2: string } | null;
  images?: { front?: string; back?: string; uv?: string };   // base64
}
export interface RegistryRecord {
  source: "IPRS" | "IMMIGRATION" | (string & {});
  fullName: string; dateOfBirth: string; gender: string; idNumber?: string; passportNumber?: string;
  placeOfBirth: string | null; nationality: string;
  photo?: string;   // base64 JPEG, for screen B5 "Is this you?"
}
export interface DocumentVerified {
  verificationId: string;
  result: "MATCH";
  documentChecks: { expiry: "PASS" | "FAIL"; tamper: "PASS" | "FAIL" };
  registryRecord: RegistryRecord;
  existingCustomer: boolean;
}

/** B-5: onboarding (applicationId + verificationId) or a returning customer (customerId or lookupRef). */
export interface BiometricRequest {
  applicationId?: string; verificationId?: string; customerId?: string; lookupRef?: string;
  face?: { image: string; livenessScore: number; livenessVendor?: string };
  fingerprint?: { finger: "RIGHT_THUMB" | "LEFT_THUMB" | "RIGHT_INDEX" | "LEFT_INDEX"; template?: string; quality: number };
}
export interface BiometricResult {
  face?: { against: string; score: number; result: "MATCH" | "NO_MATCH" };
  fingerprint?: { against: string; score: number; result: "MATCH" | "NO_MATCH" };
  overall: "PASS" | "FAIL";
  attemptsLeft: number;
  /** Card services (customerId + lookupRef, after the OTP): the token that unlocks C-2 … C-10. */
  customerToken?: string;
}

/** B-7 */
export interface KraResult { valid: boolean; taxpayerName: string; matchesId: boolean; status: string }

/** B-8 */
export interface KycRequest {
  kraPin: string; mobile: string; address: { line1: string; town?: string; county?: string };
  occupation?: string; sourceOfFunds?: string;
}
export interface KycCaptured { applicationId: string; status: ApplicationStatus; screening: { pep: "CLEAR" | "HIT"; sanctions: "CLEAR" | "HIT" } }

/** SIM-swap check of the number a code would go to (the number on record, never one typed at the kiosk). */
export interface SimSwapCheck {
  maskedMobile: string;
  lastSwapAt: Iso | null;          // null: no swap on record
  hoursSinceSwap: number | null;
  risk: "LOW" | "HIGH";            // HIGH: swapped within 72 h; B-9 then answers 403 SIM_SWAP_RISK
  otpAllowed: boolean;
  checkedAt: Iso;
}

/** KYC status of an existing customer. flags are reason codes: KYC_REFRESH_DUE, KRA_PIN_MISSING, PEP, HIGH_RISK, SANCTIONS_REVIEW, ADDRESS_UNVERIFIED … */
export interface KycStatus {
  customerId: string;
  status: "CURRENT" | "DUE_SOON" | "REFRESH_DUE";
  lastReviewedAt: string;          // yyyy-mm-dd
  nextReviewDue: string;
  riskRating: "LOW" | "MEDIUM" | "HIGH";
  kraPinOnFile: boolean;
  kraPinMasked: string | null;
  pep: boolean;
  sanctions: "CLEAR" | (string & {});
  highRisk: boolean;
  flags: string[];
  /** false: send the customer to a teller (refresh due, no KRA PIN, PEP or high risk). */
  selfServiceAllowed: boolean;
}

/** B-9 / B-10 */
export interface OtpSendRequest { purpose: "ONBOARDING_PHONE" | "CARD_SERVICES"; applicationId?: string; lookupRef?: string; mobile?: string; channel?: "SMS" }
export interface OtpSent { otpRef: string; maskedMobile: string; length: number; expiresInSec: number; resendAfterSec: number }
export interface OtpVerified { verified: true; attemptsLeft: number }

/** B-11 */
export interface SignatureRequest { image: string; strokes?: number; capturedWith?: "STYLUS" | "FINGER" }
/** B-12 (Idempotency-Key) */
export interface SubmitRequest { productCode?: string; branchCode?: string; cardProduct?: CardProduct; nameOnCard?: string }
export interface AccountOpened {
  applicationId: string; status: "ACCOUNT_OPENED"; customerId: string;
  account: { accountId: string; accountNumber: string; display: string; product: string; branch: string };
  customerToken: string; cardOrderAllowed: boolean; nameOnCard: string; reference: string; smsSent: boolean;
}
/** B-13 */
export interface ApplicationView {
  applicationId: string; status: ApplicationStatus; nextStep: string | null; expiresAt: Iso; productCode: string;
  accountNumber?: string; referralStatus?: string;
}

// ------------------------------------------------------------------------------ C / S: cards and customers

export type CardProduct = "CLASSIC" | "GOLD";

/** C-1 */
export type LookupRequest =
  | { by: "ACCOUNT_NUMBER"; accountNumber: string }
  | { by: "ID_DOCUMENT"; documentType: DocumentType; documentNumber: string; images?: DocumentVerifyRequest["images"] };
export interface LookupResult {
  lookupRef: string; customerId: string; displayName: string; maskedMobile: string; accountsFound: number;
  verificationRequired: ("OTP" | "BIOMETRIC")[];
}
/** C-2 */
export interface CardView { cardId: string; maskedPan: string; product: CardProduct; status: string; expiry: string; linkedAccountId: string }
/** C-3 */
export interface AccountForCard { accountId: string; type: string; masked: string; available: Money; hasCard: boolean; eligible: boolean; reason?: string }

/** C-4 */
export interface FeeQuoteRequest { service: "CARD_ISSUE" | "CARD_REPLACEMENT"; cardProduct: CardProduct; debitAccountId: string }
export interface FeeQuote {
  quoteId: string; lines: { label: string; amount: Decimal }[]; total: Money; includesExcise: boolean;
  sufficientFunds: boolean; expiresAt: Iso;
}
/** C-5 */
export interface CardOrderRequest {
  service: "LINK_AND_ISSUE" | "REPLACEMENT" | "NEW_CARD_SETUP";
  accountId?: string; cardProduct?: CardProduct; nameOnCard?: string; feeQuoteId?: string; customerAuthorised?: boolean;
  replacesCardId?: string; cardId?: string; applicationId?: string;
}
export interface CardOrder {
  orderId: string; cardId: string; maskedPan: string;
  fee: Money & { posted: boolean; transactionRef: string | null; waived?: boolean };
  personalisation: { payloadForPrinter: string; jobId: string };
  reference: string;
}
export type PrintStep = "PICK_BLANK" | "PRINT_NAME" | "PROGRAM_EMV_CHIP" | "QUALITY_CHECK";
/** C-6 (screen C4 "Printing your card") */
export interface CardOrderProgress {
  orderId: string; status: "PERSONALISING" | "READY" | "DISPENSED" | "RETAINED" | "SPOILED" | (string & {});
  steps: { code: PrintStep; status: "PENDING" | "IN_PROGRESS" | "DONE" | "FAILED" }[];
}
/** C-7 */
export interface DispenseRequest { outcome: "TAKEN" | "RETAINED" | "SPOILED"; qualityCheck?: "PASS" | "FAIL"; cardSerial?: string }
export interface Dispensed { orderId: string; status: string; cardStatus: string }
/** C-8: the PIN block is built by the PIN pad (EPP); never handle clear PINs in the app. */
export interface PinSetRequest { pinBlock: string; format?: string; keySerialNumber?: string; confirmed?: boolean; policyChecks?: Record<string, boolean> }
/** C-9 */
export interface CardActivated {
  cardId: string; status: "ACTIVE"; maskedPan: string; linkedAccount: string;
  limits: { atmDaily: Money; posDaily: Money }; reference: string;
}
/** C-10 */
export interface CardBlocked { cardId: string; status: string; blockedAt: Iso; reason: string }

/** S-1 card inserted */
export interface CardAuthRequest {
  entryMode: "EMV_CHIP" | "MAG_STRIPE" | "CONTACTLESS"; panToken: string; track2Equivalent?: string;
  emv?: { aid?: string; atc?: string };
}
export interface CardAuth { cardAuthRef: string; maskedPan: string; cardStatus: string; pinAttemptsLeft: number; customerFirstName: string }
/** S-2 PIN entered */
export interface PinVerifyRequest { cardAuthRef: string; pinBlock: string; format?: string; keySerialNumber?: string }
export interface PinVerified {
  verified: true; customerToken: string; customerId: string; customerFirstName: string;
  accounts: { accountId: string; type: string; masked: string; primary: boolean }[];
}

// ------------------------------------------------------------------------- S / K / W / X: money

/** S-3 */
export interface Balance { accountId: string; masked: string; type: string; ledger: Money; available: Money; asOf: Iso }

/** S-4 … S-6 statements */
export interface StatementOption {
  type: "MINI" | "FULL"; output: "RECEIPT" | "A4" | (string & {}); transactions?: number; periodsMonths?: number[]; stamped?: boolean; fee: Money;
}
export interface StatementRequest { accountId: string; type: "MINI" | "FULL"; periodMonths?: number; feeAccepted: boolean }
export interface Statement {
  statementId: string; reference: string;
  fee: Money & { posted: boolean; transactionRef: string | null };
  balanceAfterFee: Money;
  lines?: { date: string; narrative: string; amount: Decimal; direction: "DR" | "CR"; balance: Decimal }[];
  pages?: number; documentUrl?: string;
}

/** K-1 */
export type DepositTargetRequest =
  | { type: "ACCOUNT"; accountNumber: string; purpose?: "CASH_DEPOSIT" }
  | { type: "PAYBILL"; paybill: string; accountReference: string; purpose?: "CASH_DEPOSIT" };
export interface DepositTarget {
  valid: true; targetRef: string; accountName: string; masked: string; branch?: string;
  limits: { maxAmount: Money; maxNotes: number };
}
/** K-2 … K-5 */
export interface OpenDepositRequest { targetRef?: string; accountId?: string; currency?: "KES"; depositorMobile?: string }
export interface DepositOpened { depositId: string; status: "AWAITING_CASH"; expiresAt: Iso }
export interface DepositCountRequest {
  accepted: Note[]; rejected?: { count: number; reasons?: string[] }; suspectedCounterfeit?: number; escrowHeld?: boolean;
}
export interface DepositCounted {
  depositId: string; status: "COUNTED"; total: Money; breakdown: (Note & { amount: Decimal })[]; fee: Money;
  returnedNotes: number; suspectedCounterfeit: number;
}
export interface DepositCredited {
  depositId: string; status: "CREDITED"; transactionRef: string; coreRef: string; amount: Money;
  previousBalance?: Money; newBalance?: Money; postedAt: Iso;
}
export interface Cancelled { status: "CANCELLED"; returnCash: boolean; depositId?: string; exchangeId?: string }

/** W-1 */
export interface WithdrawalLimits {
  available: Money; perTransactionMax: Money; dailyRemaining: Money; multipleOf: Decimal; fee: Money; quickAmounts: Decimal[];
}
/** W-2 (KES, or with fxQuoteId: foreign notes the bank sells) */
export interface WithdrawRequest { accountId: string; amount: Money; fxQuoteId?: string | null; proposedMix?: Note[] | null }
export interface WithdrawalAuthorised {
  withdrawalId: string; status: "AUTHORISED"; transactionRef: string; coreRef: string;
  dispense: { mix: Note[]; currency: string }; balanceAfter?: Money; presentTimeoutSec: number;
}
/** W-3 */
export interface WithdrawConfirmRequest { presented?: Note[]; taken: boolean }
/** W-4 */
export interface ReverseRequest { reason: "CASH_RETRACTED" | "DISPENSE_FAILED" | "TIMEOUT" | "CUSTOMER_CANCELLED"; presented?: Note[]; retractedCount?: number }
export interface Reversal {
  withdrawalId?: string; status: "REVERSED" | "PENDING_RECONCILIATION" | "NOTHING_TO_REVERSE" | (string & {});
  reversalRef?: string; amount?: Money; creditBackBy?: Iso; message?: string;
}

/** X-1 … X-5 forex */
export interface FxRates { asOf: Iso; base: "KES"; commissionPct: Decimal; rates: { currency: string; symbol: string; buy: Decimal; sell: Decimal }[] }
export interface FxQuoteRequest {
  direction: "BANK_BUYS" | "BANK_SELLS"; currency: "USD" | "EUR" | "GBP"; identityVerificationId: string;
  foreignAmount?: Decimal; debitAccountId?: string;
}
export interface FxQuote {
  quoteId: string; direction: FxQuoteRequest["direction"]; currency: string; rate: Decimal; commission: Money; expiresAt: Iso;
  foreignAmount?: Money; localAmount?: Money;
}
export interface FxExchangeRequest { quoteId: string; destination: { type: "CASH" } | { type: "ACCOUNT"; accountId: string } }
export interface FxExchangeOpened { exchangeId: string; status: "AWAITING_CASH" }
export interface FxCountRequest {
  currency: string; accepted: (Note & { serials?: string[] })[]; rejected?: { count: number };
}
export interface FxCounted {
  exchangeId: string; foreignTotal: Money; rate: Decimal; localAmount: Money; commission: Money; returnedNotes: number;
  /** Cash out is rounded down to KES 100; this is the exact amount before rounding. */
  roundedFrom?: Money;
}
export interface FxConfirmed {
  exchangeId: string; status: "AUTHORISED" | "COMPLETED" | "CREDITED" | (string & {}); transactionRef: string;
  /** CASH destination: dispense these KES notes, then W-3 with withdrawalId. */
  withdrawalId?: string; dispense?: { mix: Note[]; currency: string }; presentTimeoutSec?: number;
  newBalance?: Money;
}

// --------------------------------------------------------------------------------- R: receipts

/** R-1 */
export interface ReceiptRequest { transactionRef: string; delivery: "PRINT" | "SMS" | "NONE"; language?: Lang }
export interface Receipt { receiptId: string; lines: string[]; qr: string; printStatusUrl: string; sms?: string }
export interface ReceiptStatus { receiptId: string; transactionRef?: string; delivery?: string; printStatus: "PENDING" | "PRINTED" | "FAILED" | "PAPER_OUT" | (string & {}); lines?: string[] }
/** R-2 */
export interface SmsRequest { template: string; mobile: string; language?: Lang; params: Record<string, string> }
export interface SmsQueued { messageId: string; status: "QUEUED" | "SENT" | (string & {}) }

// --------------------------------------------------------------------------------- signaling (WebSocket)

/** What the server pushes to a kiosk on /ws/signaling. COMMAND comes from the portal (Kiosk fleet page). */
export type ServerMessage =
  | { type: "COMMAND"; command: "REBOOT" | "LOCK" | "UNLOCK" | "REFRESH_CONFIG" }
  | { type: "QUEUED"; position: number }
  | { type: "CALL_ASSIGNED"; callId: string; tellerId: string }
  | { type: "CALL_ENDED" }
  | { type: "ERROR"; code: string }
  | { type: "OFFER"; sdp: string }
  | { type: "ANSWER"; sdp: string }
  | { type: "ICE"; candidate: RTCIceCandidateInit | null };

/** Legacy peer-to-peer calls only; "Talk to a teller" now uses D-8 and LiveKit (see TellerCall.media). */
export type KioskMessage =
  | { type: "CALL_REQUEST"; sessionId?: string }
  | { type: "CALL_CANCEL" }
  | { type: "OFFER"; sdp: string }
  | { type: "ANSWER"; sdp: string }
  | { type: "ICE"; candidate: RTCIceCandidateInit | null }
  | { type: "HANGUP" };
