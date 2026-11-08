/**
 * Shapes of the STM middleware kiosk API (Tier 2), mirrored from the Java DTOs.
 * Dates (LocalDate) arrive as "YYYY-MM-DD" strings, timestamps as ISO-8601 strings, BigDecimal as numbers.
 * Unions end in `(string & {})` where the server may add values later: autocomplete still works,
 * and an unknown value does not break the build.
 */

type Open<T extends string> = T | (string & {});

// ------------------------------------------------------------------ enums

export type JourneyType = "OPEN_ACCOUNT" | "DEBIT_CARD";
export type Language = "en" | "sw" | "fr";

/** Server-side step. Render the screen that matches it. */
export type Step =
  // Journey A: open account
  | "PASSPORT_SCAN" | "DETAILS" | "BIOMETRICS" | "REVIEW_PENDING" | "SUPPORTING_DOCS"
  | "PRODUCT" | "AGREEMENT" | "ACCOUNT_OPENED"
  // Journey B: debit card
  | "FIND_ACCOUNT" | "CARD_PERSONALISE" | "CARD_ISSUING"
  // Both
  | "COMPLETE";

export type SessionStatus = Open<
  "IN_PROGRESS" | "COMPLETED" | "CANCELLED" | "TIMED_OUT" | "ABANDONED" | "DECLINED" | "REJECTED"
>;
export type BiometricStatus = Open<"PENDING" | "MATCHED" | "FAILED" | "OVERRIDDEN">;
export type CardStatus = Open<"NONE" | "RESERVED" | "ISSUED" | "DISPENSE_FAILED" | "CANCELLED">;
export type Tier = Open<"BASIC" | "STANDARD">;
export type LookupMethod = "ACCOUNT_NUMBER" | "ID_NUMBER" | "PASSPORT_NUMBER" | "CARD_TOKEN";
export type SignatureMethod = "DIGITAL" | "PAPER";
export type DocumentType = "PROOF_OF_ADDRESS" | "SIGNED_AGREEMENT";
export type NotificationChannel = "EMAIL" | "SMS";

// ------------------------------------------------------------------ requests

export interface CreateSessionRequest { journey: JourneyType; language?: Language }
export interface PassportRequest { mrzLine1: string; mrzLine2: string }
export interface DetailsRequest { mobile: string; email?: string; consentAccepted: boolean }
export interface LookupRequest { method: LookupMethod; value: string }
/** imageBase64: JPEG/PNG without the "data:" prefix. */
export interface FaceRequest { imageBase64: string; livenessPassed: boolean }
/** templateBase64: ISO/ANSI template from the reader. Checked and discarded, never stored. */
export interface FingerprintRequest { templateBase64: string; finger?: string }
export interface SignRequest {
  method: SignatureMethod;
  /** Required for DIGITAL. PNG without the "data:" prefix. */
  signaturePngBase64?: string;
  accepted: boolean;
  /** Value of the X-Agreement-Version header from getAgreement(). */
  agreementVersion: string;
}
/** encryptedPinBlock comes straight from the encrypting PIN pad. Never send a clear PIN. */
export interface CardIssueRequest { embossedName: string; encryptedPinBlock: string; pinKeyId?: string }
export interface DispenseResultRequest { success: boolean; errorCode?: string }
export interface HardwareFaultRequest { device: string; errorCode?: string }

// ------------------------------------------------------------------ responses

export interface AccountView { accountNo: string; branch: string; productCode: string; currency: string }
export interface CardView { status: CardStatus; maskedPan: string | null; embossedName: string | null }

export interface SessionView {
  id: string;
  journey: JourneyType;
  status: SessionStatus;
  step: Step;
  stepNumber: number;
  totalSteps: number;
  language: Language;
  identityVerified: boolean;
  faceStatus: BiometricStatus;
  faceAttemptsRemaining: number;
  fingerStatus: BiometricStatus;
  fingerAttemptsRemaining: number;
  reviewPending: boolean;
  escalated: boolean;
  tier: Tier | null;
  productCode: string | null;
  account: AccountView | null;
  card: CardView | null;
  errorRef: string | null;
}

export interface PassportResult {
  documentNumber: string;
  surname: string;
  givenNames: string;
  fullName: string;
  dateOfBirth: string;
  /** ICAO 3-letter code, e.g. "KEN". The kiosk renders the label ("Kenyan"). */
  nationalityCode: string;
  sex: string;
  expiryDate: string;
  nextStep: Step;
  /** Journey B: masked account found for this passport. */
  accountMasked: string | null;
}

export interface LookupResult { accountMasked: string; nextStep: Step }

export interface BiometricResponse {
  modality: "FACE" | "FINGERPRINT";
  matched: boolean;
  attempt: number;
  attemptsRemaining: number;
  /** true after the last failed attempt: show Error B with "Connect to video teller". */
  referToTeller: boolean;
  identityVerified: boolean;
  nextStep: Step;
}

export interface DocumentAccepted { id: string; type: DocumentType; sha256: string; sizeBytes: number }

export interface CardIssuanceResponse {
  cardRef: string;
  maskedPan: string;
  expiry: string;
  /** Hand this to the card dispenser via the local eCAT service. */
  personalisationPayload: string;
}

export interface FaultResponse { errorRef: string; accountNo: string | null; branchName: string | null; helpline: string | null }

export interface KioskStatus {
  inService: boolean;
  accountOpeningAvailable: boolean;
  cardIssuanceAvailable: boolean;
  reason: string | null;
  branchName: string | null;
  helpline: string | null;
}

export interface Product {
  code: string;
  name: string;
  currency: string;
  interestRate: number | null;
  minOpeningBalance: number;
  monthlyFee: number;
  overdraft: boolean;
}

export interface Agreement { html: string; version: string }

export interface ReceiptLine { label: string; value: string }
export interface Receipt { language: Language; title: string; lines: ReceiptLine[]; footer: string }

export interface Heartbeat {
  status: Open<"OK" | "DEGRADED" | "OUT_OF_SERVICE" | "MAINTENANCE">;
  cassetteCounts?: Record<string, number>;
  cardStock?: number;
  alarms?: string[];
}

export interface WsTicket { ticket: string; expiresInSeconds: number }

/** Demo profile only: GET /api/v1/kiosk/demo/personas */
export interface DemoPersona {
  customerId: string;
  fullName: string;
  scenario: string;
  existingCustomer: boolean;
  accountNo: string | null;
  nationalId: string | null;
  passportNumber: string | null;
  mrzLine1?: string;
  mrzLine2?: string;
}

// ------------------------------------------------------------------ deposits (cash recycler)

export type TxState = "INITIATED" | "CASH_ESCROWED" | "CREDIT_PENDING" | "UNKNOWN_OUTCOME" | "COMPENSATING"
  | "COMMITTED" | "ROLLED_BACK" | "CANCELLED" | "FAILED";

export interface CreateTxRequest { customerId: string; accountNo: string; currency: string }

export interface TxView {
  id: string;
  state: TxState;
  customerId: string;
  accountNo: string;
  currency: string;
  amount: number | null;
  bankReference: string | null;
  failureReason: string | null;
  /** true: the recycler must hand the escrowed cash back. */
  cashReturnRequired: boolean;
}

// ------------------------------------------------------------------ errors

/** Body of every non-2xx response. */
export interface ApiErrorBody { code: string; message: string; timestamp: string }

export type ErrorCode = Open<
  | "DOCUMENT_UNREADABLE" | "DOCUMENT_EXPIRED" | "NOT_ELIGIBLE" | "UNABLE_TO_PROCEED" | "CUSTOMER_ALREADY_EXISTS"
  | "CUSTOMER_NOT_FOUND" | "ACCOUNT_NOT_FOUND" | "ACCOUNT_MISMATCH" | "ACCOUNT_OPENING_DECLINED"
  | "BIOMETRIC_ATTEMPTS_EXCEEDED" | "IDENTITY_NOT_VERIFIED" | "TOO_MANY_ATTEMPTS"
  | "CONSENT_REQUIRED" | "INVALID_MOBILE" | "INVALID_EMAIL" | "INVALID_IMAGE" | "INVALID_FINGERPRINT"
  | "INVALID_SIGNATURE" | "INVALID_DOCUMENT_TYPE" | "INVALID_FILE_SIZE" | "UNSUPPORTED_FILE" | "FILE_TOO_LARGE"
  | "DOCUMENT_REQUIRED" | "SIGNED_AGREEMENT_REQUIRED" | "PRODUCT_REQUIRED" | "UNKNOWN_PRODUCT"
  | "ACCEPTANCE_REQUIRED" | "AGREEMENT_VERSION_MISMATCH" | "NOT_SIGNED"
  | "INVALID_NAME_OPTION" | "INVALID_PIN_BLOCK" | "NO_CARD_RESERVED" | "CARD_ISSUANCE_UNAVAILABLE"
  | "CARD_SERVICE_UNAVAILABLE" | "CARD_NOT_FOUND"
  | "BANK_UNAVAILABLE" | "NOTIFICATION_UNAVAILABLE" | "NO_CONTACT_ON_FILE"
  | "OUT_OF_SERVICE" | "SESSION_ENDED" | "SESSION_NOT_FOUND" | "ILLEGAL_STEP" | "CANNOT_GO_BACK"
  | "CONCURRENT_UPDATE" | "IN_FLIGHT" | "NOT_CLEARED"
  | "VALIDATION_ERROR" | "MISSING_HEADER" | "MALFORMED_REQUEST" | "INVALID_PARAMETER" | "REQUEST_REJECTED"
  | "TOO_MANY_TICKETS" | "UNAUTHORIZED" | "NETWORK_ERROR" | "INTERNAL_ERROR"
>;

// ------------------------------------------------------------------ signaling WebSocket

/** Messages the server sends to the kiosk. */
export type ServerMessage =
  | { type: "QUEUED"; position: number }
  | { type: "CALL_ASSIGNED"; callId: string; tellerId: string }
  | { type: "CALL_ENDED" }
  | { type: "COMMAND"; command: "REBOOT" | "LOCK" | "UNLOCK" | "REFRESH_CONFIG" }
  | { type: "SESSION_UPDATED"; sessionId: string; step: Step; status: SessionStatus }
  | { type: "ERROR"; code: string }
  | { type: "OFFER"; sdp: string }
  | { type: "ANSWER"; sdp: string }
  | { type: "ICE"; candidate: RTCIceCandidateInit | null };

/** Messages the kiosk may send. OFFER / ANSWER / ICE are relayed verbatim to the teller. */
export type KioskMessage =
  | { type: "CALL_REQUEST"; sessionId?: string }
  | { type: "CALL_CANCEL" }
  | { type: "OFFER"; sdp: string }
  | { type: "ANSWER"; sdp: string }
  | { type: "ICE"; candidate: RTCIceCandidateInit | null }
  | { type: "HANGUP" };
