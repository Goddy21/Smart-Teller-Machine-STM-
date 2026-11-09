import type {
  AccountForCard, AccountOpened, ApiErrorBody, ApplicationStarted, ApplicationView, Balance, BiometricRequest,
  BiometricResult, Cancelled, CardActivated, CardAuth, CardAuthRequest, CardBlocked, CardOrder, CardOrderProgress,
  CardOrderRequest, CardView, CashPositions, CassetteView, DepositCounted, DepositCountRequest, DepositCredited,
  DepositOpened, DepositTarget, DepositTargetRequest, DeviceStatus, DeviceToken, Dispensed, DispenseRequest,
  DocumentVerified, DocumentVerifyRequest, EndReason, ErrorCode, FeeQuote, FeeQuoteRequest, FxConfirmed, FxCounted,
  FxCountRequest, FxExchangeOpened, FxExchangeRequest, FxQuote, FxQuoteRequest, FxRates, HealthAccepted, HealthReport,
  JournalUpload, KraResult, KycStatus, SimSwapCheck, KycCaptured, KycRequest, Lang, LookupRequest, LookupResult, Money, Note, OtpSendRequest,
  OpenDepositRequest, OtpSent, OtpVerified, PinSetRequest, PinVerified, PinVerifyRequest, PrintStep, Product, Receipt, ReceiptRequest,
  ReceiptStatus, Reconciliation, Reversal, ReverseRequest, SessionEnded, SessionStarted, SignatureRequest, SmsQueued,
  SmsRequest, StartApplicationRequest, StartSessionRequest, Statement, StatementOption, StatementRequest,
  SubmitRequest, Tariffs, TellerCall, TellerCallRequest, Terms, WithdrawalAuthorised, WithdrawalLimits,
  WithdrawConfirmRequest, WithdrawRequest, WsTicket,
} from "./types";

export interface StmClientOptions {
  /** Gateway origin, e.g. "https://stm-gw.bank.local". "" calls the same origin (Vite proxy / same host). */
  baseUrl?: string;
  /** This machine's id, e.g. "STM-NBO-MOI-03". Sent as X-Device-Id on every call. */
  deviceId: string;
  /**
   * D-1 credential. Production uses the mTLS client certificate (leave this out). Development: the terminal token
   * from POST /api/v1/terminals/register.
   */
  provisioningToken?: string | (() => string);
  softwareVersion?: string;
  language?: Lang;
  /** Per-request timeout. Default 30 s. */
  timeoutMs?: number;
  fetch?: typeof fetch;
}

/** Thrown for every non-2xx answer, and for network failures / timeouts (status 0, code NETWORK_ERROR). */
export class StmApiError extends Error {
  readonly status: number;
  readonly code: ErrorCode;
  /** Customer-facing text in Kiswahili (message is English). */
  readonly messageSw: string;
  readonly retryable: boolean;
  readonly requestId: string | null;
  readonly details: Record<string, unknown>;

  constructor(status: number, body: Partial<ApiErrorBody["error"]>, fallback: string) {
    super(body.message ?? fallback);
    this.name = "StmApiError";
    this.status = status;
    this.code = body.code ?? (status === 0 ? "NETWORK_ERROR" : "INTERNAL_ERROR");
    this.messageSw = body.messageSw ?? this.message;
    this.retryable = body.retryable ?? status === 0;
    this.requestId = body.requestId ?? null;
    this.details = body.details ?? {};
  }

  /** The text to show, in the customer's language. */
  text(lang: Lang) {
    return lang === "sw" ? this.messageSw : this.message;
  }
}

/**
 * Which prototype screen a failure belongs to. The kiosk shows the error's own message on it.
 *   RETRY_STEP   same screen, "Try again" (wrong OTP, wrong PIN, face not matched, ID not read)
 *   TELLER       E-screen "Talk to a teller" (referral, attempts used up, identity mismatch)
 *   SERVICE_DOWN E-screen "Service unavailable" (core down, module disabled, video down)
 *   SESSION      back to A0: the session ended or the device must sign in again
 *   CARD         card kept or returned (PIN tries exceeded, blocked, expired)
 *   GENERIC      "Something went wrong" with the reference (requestId)
 */
export type ErrorScreen = "RETRY_STEP" | "TELLER" | "SERVICE_DOWN" | "SESSION" | "CARD" | "GENERIC";

export function errorScreen(e: unknown): ErrorScreen {
  if (!(e instanceof StmApiError)) return "GENERIC";
  switch (e.code) {
    case "OTP_INCORRECT": case "PIN_INCORRECT": case "PIN_POLICY": case "KRA_PIN_INVALID": case "RESEND_TOO_SOON":
    case "QUOTE_EXPIRED": case "LIMIT_EXCEEDED": case "INSUFFICIENT_FUNDS": case "DEPOSIT_LIMIT_EXCEEDED": case "VALIDATION_ERROR":
      return "RETRY_STEP";
    case "IDENTITY_MISMATCH": case "BIOMETRIC_NO_MATCH":
      return (e.details.attemptsLeft as number | undefined) === 0 ? "TELLER" : "RETRY_STEP";
    case "SCREENING_REFERRED": case "OTP_TRIES_EXCEEDED": case "SIM_SWAP_RISK":
      return "TELLER";
    case "CORE_UNAVAILABLE": case "SERVICE_UNAVAILABLE": case "OUT_OF_SERVICE": case "VIDEO_UNAVAILABLE":
    case "CASSETTE_UNAVAILABLE": case "NETWORK_ERROR":
      return "SERVICE_DOWN";
    case "SESSION_INVALID": case "DEVICE_UNAUTHORIZED": case "APPLICATION_EXPIRED":
      return "SESSION";
    case "PIN_TRIES_EXCEEDED": case "CARD_BLOCKED": case "CARD_EXPIRED":
      return "CARD";
    default:
      return "GENERIC";
  }
}

type Q = Record<string, string | number | undefined>;
interface CallOpts { idem?: string; query?: Q; session?: boolean; customer?: boolean }

/** A new Idempotency-Key. Keep it and pass it again when you retry the same money movement after a timeout. */
export const newIdempotencyKey = () => crypto.randomUUID();

/**
 * Typed client for the Smart Teller Gateway (/stm/v1). It keeps the device token (D-1, refreshed before it expires),
 * the session (X-Session-Id, from D-2 until D-4), the customer token (X-Customer-Token, from S-2, B-5 or B-12) and
 * the language (Accept-Language), and sends X-Request-Id on every call.
 *
 *   const stm = new StmClient({ deviceId: "STM-NBO-MOI-03", provisioningToken: import.meta.env.VITE_STM_TOKEN });
 *   await stm.signIn();                              // D-1, once at boot
 *   const s = await stm.startSession({ trigger: "TOUCH" });   // A0 "Tap to start"
 *   ...
 *   await stm.endSession("CUSTOMER_FINISHED");       // E13 "Thank you"
 *
 * Calls that move money, issue a card or charge a fee take an idempotencyKey. Create it when the customer confirms
 * (newIdempotencyKey()) and reuse it for retries of that same step; the gateway then answers with the first result
 * (header Idempotent-Replayed) instead of doing it twice. Every other write gets a key automatically, and is retried
 * once with the same key if the network drops before the answer arrives.
 */
export class StmClient {
  private readonly base: string;
  private readonly opts: StmClientOptions;
  private readonly fetchFn: typeof fetch;
  private token: string | null = null;
  private tokenExpires = 0;
  private signingIn: Promise<DeviceToken> | null = null;

  /** Current customer session (D-2), or null between customers. */
  sessionId: string | null = null;
  /** Set after S-2 (card + PIN), B-5 (card services) or B-12 (new account). Cleared by endSession. */
  customerToken: string | null = null;
  language: Lang;
  branch: { code: string; name: string } | null = null;

  constructor(opts: StmClientOptions) {
    this.opts = opts;
    this.base = (opts.baseUrl ?? "").replace(/\/+$/, "") + "/stm/v1";
    this.language = opts.language ?? "en";
    this.fetchFn = opts.fetch ?? globalThis.fetch.bind(globalThis);
  }

  get deviceId() { return this.opts.deviceId; }

  /** Headers for calls made outside this client, e.g. kiosk-video.js in gateway mode (D-8 / D-9). */
  authHeaders(): Record<string, string> { return this.headers(); }
  get origin() { return this.base.slice(0, -"/stm/v1".length); }

  // ================================================================== D: device and session

  /** D-1. Called for you before the first call and whenever the token is about to expire. */
  async signIn(): Promise<DeviceToken> {
    this.signingIn ??= (async () => {
      const cred = typeof this.opts.provisioningToken === "function" ? this.opts.provisioningToken() : this.opts.provisioningToken;
      const t = await this.raw<DeviceToken>("POST", "/auth/device/token",
        { deviceId: this.opts.deviceId, softwareVersion: this.opts.softwareVersion, nonce: crypto.randomUUID() },
        cred ? { Authorization: `Bearer ${cred}` } : {});
      this.token = t.accessToken;
      this.tokenExpires = Date.now() + (t.expiresIn - 60) * 1000;
      this.branch = { code: t.branchCode, name: t.branchName };
      return t;
    })().finally(() => { this.signingIn = null; });
    return this.signingIn;
  }

  /** D-2. A0 "Tap to start" (TOUCH) or a card in the reader (CARD). services says which home tiles to show. */
  async startSession(r: StartSessionRequest): Promise<SessionStarted> {
    this.sessionId = null;
    this.customerToken = null;
    if (r.language) this.language = r.language;
    const s = await this.call<SessionStarted>("POST", "/sessions", { language: this.language, ...r }, { session: false });
    this.sessionId = s.sessionId;
    return s;
  }

  /** D-3. The language toggle on every screen. */
  async setLanguage(language: Lang) {
    this.language = language;
    if (this.sessionId) await this.call("PATCH", `/sessions/${this.sessionId}`, { language });
  }

  /** D-4. "Finish" / timeout. Do whatever openItems asks (return notes, finish a dispense) before the next customer. */
  async endSession(reason: EndReason = "CUSTOMER_FINISHED", cardRetained = false): Promise<SessionEnded> {
    if (!this.sessionId) return { ended: true, openItems: [] };
    try {
      return await this.call<SessionEnded>("POST", `/sessions/${this.sessionId}/end`, { reason, cardRetained });
    } finally {
      this.sessionId = null;
      this.customerToken = null;
    }
  }

  /** D-5 */
  tariffs() { return this.call<Tariffs>("GET", "/config/tariffs", undefined, { session: false }); }
  /** D-6. Send at boot, every few minutes and whenever a module changes. disabledServices: hide those tiles. */
  reportHealth(h: HealthReport) {
    return this.call<HealthAccepted>("POST", `/devices/${this.deviceId}/health`, { reportedAt: new Date().toISOString(), ...h }, { session: false });
  }
  /** D-7 */
  deviceStatus() { return this.call<DeviceStatus>("GET", `/devices/${this.deviceId}/status`, undefined, { session: false }); }
  /** X-6. Which foreign notes the machine can pay out now. */
  cassettes() { return this.call<{ cassettes: CassetteView[] }>("GET", `/devices/${this.deviceId}/cassettes`).then((r) => r.cassettes); }

  /** D-8. "Talk to a teller" from any screen. Then poll tellerCall() every 2 s; join media when it is set. */
  requestTeller(r: TellerCallRequest) { return this.call<TellerCall>("POST", "/support/teller-calls", { language: this.language, ...r }); }
  /** D-9 */
  tellerCall(callId: string) { return this.call<TellerCall>("GET", `/support/teller-calls/${callId}`); }
  cancelTellerCall(callId: string) { return this.call<TellerCall>("DELETE", `/support/teller-calls/${callId}`); }

  /** A-1. The device agent uploads its electronic journal in signed batches (HMAC-SHA256 with the terminal key). */
  uploadJournal(j: JournalUpload) {
    return this.call<{ signatureValid: boolean; acceptedToSeq: number }>("POST", `/devices/${this.deviceId}/journal`, j, { session: false });
  }
  /** A-2. End of day and after cash-in-transit. */
  reportCashPositions(p: CashPositions) {
    return this.call<Reconciliation>("POST", `/devices/${this.deviceId}/cash-positions`, p, { session: false });
  }

  /** One-time ticket for the signaling WebSocket (remote LOCK / UNLOCK / REBOOT commands). */
  signalingTicket() { return this.call<WsTicket>("POST", "/signaling/ticket", undefined, { session: false }); }

  // ================================================================== B: open an account

  /** B-1 */
  products() { return this.call<{ products: Product[] }>("GET", "/products").then((r) => r.products); }
  /** B-2. In the session language (or lang). */
  terms(productCode: string, lang?: Lang) { return this.call<Terms>("GET", "/legal/terms", undefined, { query: { product: productCode, lang } }); }
  /** B-3. After the customer ticks the consents. */
  startApplication(r: StartApplicationRequest) {
    return this.call<ApplicationStarted>("POST", "/onboarding/applications", { acceptedAt: new Date().toISOString(), ...r });
  }
  /** B-4. ID scan (also for forex: purpose FOREX, no applicationId). 409 EXISTING_CUSTOMER: offer Card services. */
  verifyDocument(r: DocumentVerifyRequest) { return this.call<DocumentVerified>("POST", "/identity/documents/verify", r); }
  /** B-5. Face and fingerprint. 422 BIOMETRIC_NO_MATCH carries details.attemptsLeft; at 0 the portal decides. */
  verifyBiometrics(r: BiometricRequest) {
    return this.call<BiometricResult>("POST", "/identity/biometrics/verify", r).then((b) => {
      if (b.customerToken) this.customerToken = b.customerToken;
      return b;
    });
  }
  /** B-6. "Is this you?" on the registry record. */
  confirmIdentity(applicationId: string, verificationId: string, confirmed = true) {
    return this.call<{ applicationId: string; status: string }>("POST", `/onboarding/applications/${applicationId}/identity-confirm`, { verificationId, confirmed });
  }
  /** B-7 */
  validateKraPin(kraPin: string, idNumber: string) { return this.call<KraResult>("POST", "/kyc/kra-pin/validate", { kraPin, idNumber }); }
  /** B-8. Screening runs here: status REFERRED means wait for the portal (poll application() or offer a teller). */
  captureKyc(applicationId: string, r: KycRequest) { return this.call<KycCaptured>("PATCH", `/onboarding/applications/${applicationId}/kyc`, r); }
  /** B-9 */
  sendOtp(r: OtpSendRequest) { return this.call<OtpSent>("POST", "/otp/send", { channel: "SMS", ...r }); }
  /** B-10 */
  verifyOtp(otpRef: string, code: string) { return this.call<OtpVerified>("POST", "/otp/verify", { otpRef, code }); }
  /** B-11 */
  captureSignature(applicationId: string, r: SignatureRequest) {
    return this.call<{ signatureId: string; status: string }>("POST", `/onboarding/applications/${applicationId}/signature`, r);
  }
  /** B-12. Opens the account. Gives the customer token for the card order (C-5 with applicationId). */
  async submitApplication(applicationId: string, r: SubmitRequest, idempotencyKey: string) {
    const a = await this.call<AccountOpened>("POST", `/onboarding/applications/${applicationId}/submit`, r, { idem: idempotencyKey });
    this.customerToken = a.customerToken;
    return a;
  }
  /** B-13. Poll while REFERRED: the portal's decision moves it on (or to REJECTED). */
  application(applicationId: string) { return this.call<ApplicationView>("GET", `/onboarding/applications/${applicationId}`); }
  /** B-14 */
  cancelApplication(applicationId: string, reason = "CUSTOMER_CANCELLED", atStep?: string) {
    return this.call<{ applicationId: string; status: "CANCELLED" }>("POST", `/onboarding/applications/${applicationId}/cancel`, { reason, atStep });
  }

  // ================================================================== S: card + PIN, C: card services

  /** S-1. Card inserted. 403 CARD_BLOCKED / CARD_EXPIRED carry details.cardAction (RETAIN / RETURN). */
  cardInserted(r: CardAuthRequest) { return this.call<CardAuth>("POST", "/auth/card", r); }
  /** S-2. pinBlock from the PIN pad. Sets the customer token. */
  async verifyPin(r: PinVerifyRequest) {
    const p = await this.call<PinVerified>("POST", "/auth/card/pin-verify", { format: "ISO_9564_FORMAT_4", ...r });
    this.customerToken = p.customerToken;
    return p;
  }

  /** C-1. Card services without a card: account number or ID, then OTP (B-9 purpose CARD_SERVICES) and B-5. */
  lookupCustomer(r: LookupRequest) { return this.call<LookupResult>("POST", "/customers/lookup", r); }
  /** SIM-swap risk of the customer's registered number (lookupRef) or the new customer's number (applicationId). Call before B-9. */
  simSwapCheck(r: { lookupRef: string } | { applicationId: string }) { return this.call<SimSwapCheck>("POST", "/kyc/sim-swap-check", r); }
  /** KYC status: with the customer token, or with the lookupRef from C-1 before the OTP. */
  kycStatus(customerId: string, lookupRef?: string) {
    return this.call<KycStatus>("GET", `/customers/${customerId}/kyc-status`, undefined, { query: { lookupRef }, customer: !lookupRef });
  }
  /** C-2 */
  cards(customerId: string) { return this.call<{ cards: CardView[] }>("GET", `/customers/${customerId}/cards`, undefined, { customer: true }).then((r) => r.cards); }
  /** C-3. eligible and reason are set because the gateway is asked for card eligibility. */
  accountsForCards(customerId: string) {
    return this.call<{ accounts: AccountForCard[] }>("GET", `/customers/${customerId}/accounts`, undefined, { query: { eligibleFor: "CARD" }, customer: true })
      .then((r) => r.accounts);
  }
  /** C-4 */
  quoteFee(r: FeeQuoteRequest) { return this.call<FeeQuote>("POST", "/fees/quote", r, { customer: true }); }
  /** C-5. Charges the fee and starts printing. */
  orderCard(r: CardOrderRequest, idempotencyKey: string) { return this.call<CardOrder>("POST", "/cards/orders", r, { idem: idempotencyKey, customer: true }); }
  /** C-6. Screen C4 "Printing your card": poll every second. */
  cardOrder(orderId: string) { return this.call<CardOrderProgress>("GET", `/cards/orders/${orderId}`); }
  /** Device agent: one printer step finished (optional; without it the gateway simulates the timing). */
  reportPrintStep(orderId: string, code: PrintStep, status: "IN_PROGRESS" | "DONE" | "FAILED") {
    return this.call<CardOrderProgress>("PUT", `/cards/orders/${orderId}/steps`, { code, status });
  }
  /** C-7 */
  cardDispensed(orderId: string, r: DispenseRequest) { return this.call<Dispensed>("POST", `/cards/orders/${orderId}/dispense`, r); }
  /** C-8 */
  setPin(cardId: string, r: PinSetRequest) {
    return this.call<{ cardId: string; pinSet: boolean }>("POST", `/cards/${cardId}/pin`, { format: "ISO_9564_FORMAT_4", confirmed: true, ...r }, { customer: true });
  }
  /** C-9 */
  activateCard(cardId: string) { return this.call<CardActivated>("POST", `/cards/${cardId}/activate`, { channel: "STM" }, { customer: true }); }
  /** C-10 */
  blockCard(cardId: string, reason: "LOST" | "STOLEN" | "DAMAGED" | "EXPIRED") {
    return this.call<CardBlocked>("POST", `/cards/${cardId}/block`, { reason }, { customer: true });
  }

  // ================================================================== S-3 … S-6: balance and statements

  /** S-3 */
  balance(accountId: string) { return this.call<Balance>("GET", `/accounts/${accountId}/balance`, undefined, { customer: true }); }
  /** S-4 */
  statementOptions(accountId: string) {
    return this.call<{ options: StatementOption[] }>("GET", "/statements/options", undefined, { query: { accountId }, customer: true }).then((r) => r.options);
  }
  /** S-5. Charges the fee. MINI returns lines for the receipt; FULL returns documentUrl (S-6) for the A4 printer. */
  statement(r: StatementRequest, idempotencyKey: string) { return this.call<Statement>("POST", "/statements", r, { idem: idempotencyKey, customer: true }); }
  /** S-6. The stamped PDF. */
  async statementDocument(statementId: string): Promise<Blob> {
    const res = await this.send("GET", `/statements/${statementId}/document`, undefined, this.headers({ customer: true }));
    return res.blob();
  }

  // ================================================================== K: cash deposit

  /** K-1. Own account (customer token) or anyone's account / a paybill (no token). */
  validateDepositTarget(r: DepositTargetRequest) {
    return this.call<DepositTarget>("POST", "/accounts/validate", { purpose: "CASH_DEPOSIT", ...r }, { customer: !!this.customerToken });
  }
  /** K-2. Opens the shutter. */
  openDeposit(r: OpenDepositRequest) {
    return this.call<DepositOpened>("POST", "/cash/deposits", { currency: "KES", ...r }, { customer: !!this.customerToken });
  }
  /** K-3. The recycler's count; notes stay in escrow until K-4. */
  depositCounted(depositId: string, r: DepositCountRequest) {
    return this.call<DepositCounted>("PUT", `/cash/deposits/${depositId}/count`, { escrowHeld: true, ...r });
  }
  /** K-4. "Confirm deposit": credits the account, then move escrow to the cassettes. */
  commitDeposit(depositId: string, confirmedTotal: Money, idempotencyKey: string) {
    return this.call<DepositCredited>("POST", `/cash/deposits/${depositId}/commit`, { confirmedTotal }, { idem: idempotencyKey });
  }
  /** K-5. Give the notes back. 409 DEPOSIT_ALREADY_CREDITED: do not return them. */
  cancelDeposit(depositId: string, reason = "CUSTOMER_CANCELLED") {
    return this.call<Cancelled>("POST", `/cash/deposits/${depositId}/cancel`, { reason });
  }

  // ================================================================== W: cash withdrawal

  /** W-1 */
  withdrawalLimits(accountId: string) { return this.call<WithdrawalLimits>("GET", `/accounts/${accountId}/withdrawal-limits`, undefined, { customer: true }); }
  /** W-2. Debits the account and returns the note mix to dispense. Keep the key: W-4 by key needs it after a crash. */
  withdraw(r: WithdrawRequest, idempotencyKey: string) {
    return this.call<WithdrawalAuthorised>("POST", "/cash/withdrawals", r, { idem: idempotencyKey, customer: true });
  }
  /** W-3. Notes taken. */
  withdrawalTaken(withdrawalId: string, presented?: Note[]) {
    return this.call<{ withdrawalId: string; status: "COMPLETED" }>("POST", `/cash/withdrawals/${withdrawalId}/confirm`, { presented, taken: true } satisfies WithdrawConfirmRequest);
  }
  /** W-4. Notes retracted or never presented: credits the customer back. */
  reverseWithdrawal(withdrawalId: string, r: ReverseRequest, idempotencyKey: string) {
    return this.call<Reversal>("POST", `/cash/withdrawals/${withdrawalId}/reverse`, r, { idem: idempotencyKey });
  }
  /** W-4 when W-2's answer was lost (timeout, power cut): same Idempotency-Key as the W-2. */
  reverseWithdrawalByKey(w2IdempotencyKey: string, reason: ReverseRequest["reason"] = "TIMEOUT") {
    return this.call<Reversal>("POST", "/cash/withdrawals/by-idempotency-key/reverse", { reason }, { idem: w2IdempotencyKey });
  }

  // ================================================================== X: foreign exchange

  /** X-1 */
  fxRates() { return this.call<FxRates>("GET", "/fx/rates"); }
  /** X-2. Needs an ID check first (B-4 with purpose FOREX). BANK_SELLS then goes through W-2 with fxQuoteId. */
  fxQuote(r: FxQuoteRequest) { return this.call<FxQuote>("POST", "/fx/quotes", r, { customer: !!this.customerToken }); }
  /** X-3. BANK_BUYS: opens the shutter for foreign notes. */
  fxExchange(r: FxExchangeRequest) { return this.call<FxExchangeOpened>("POST", "/fx/exchanges", r, { customer: r.destination.type === "ACCOUNT" }); }
  /** X-4 */
  fxCounted(exchangeId: string, r: FxCountRequest) { return this.call<FxCounted>("PUT", `/fx/exchanges/${exchangeId}/count`, r); }
  /** X-5. CASH: dispense the KES mix, then W-3 / W-4 with the withdrawalId. ACCOUNT: credited at once. */
  fxConfirm(exchangeId: string, confirmedLocalAmount: Money, idempotencyKey: string) {
    return this.call<FxConfirmed>("POST", `/fx/exchanges/${exchangeId}/confirm`, { confirmedLocalAmount }, { idem: idempotencyKey });
  }
  /** Before X-5: give the foreign notes back. */
  fxCancel(exchangeId: string, reason = "CUSTOMER_CANCELLED") { return this.call<Cancelled>("POST", `/fx/exchanges/${exchangeId}/cancel`, { reason }); }

  // ================================================================== R: receipts

  /** R-1. Lines to print (or an SMS). Works after D-4 too, for the last transaction. */
  receipt(r: ReceiptRequest) { return this.call<Receipt>("POST", "/receipts", { language: this.language, ...r }); }
  /** Device agent: the printer's result. */
  receiptPrinted(receiptId: string, status: "PRINTED" | "FAILED" | "PAPER_OUT") {
    return this.call<ReceiptStatus>("PUT", `/receipts/${receiptId}`, { status });
  }
  receiptStatus(receiptId: string) { return this.call<ReceiptStatus>("GET", `/receipts/${receiptId}`); }
  /** R-2 */
  sms(r: SmsRequest) { return this.call<SmsQueued>("POST", "/notifications/sms", { language: this.language, ...r }); }

  // ================================================================== plumbing

  private headers(o: CallOpts = {}): Record<string, string> {
    const h: Record<string, string> = {
      Authorization: `Bearer ${this.token}`,
      "X-Device-Id": this.opts.deviceId,
      "X-Request-Id": crypto.randomUUID(),
      "Accept-Language": this.language,
    };
    if (o.session !== false && this.sessionId) h["X-Session-Id"] = this.sessionId;
    if (o.customer && this.customerToken) h["X-Customer-Token"] = this.customerToken;
    if (o.idem) h["Idempotency-Key"] = o.idem;
    return h;
  }

  private async call<T>(method: string, path: string, body?: unknown, o: CallOpts = {}): Promise<T> {
    if (!this.token || Date.now() > this.tokenExpires) await this.signIn();
    const qs = o.query ? Object.entries(o.query).filter(([, v]) => v !== undefined && v !== "")
      .map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(String(v))}`).join("&") : "";
    const url = path + (qs ? `?${qs}` : "");
    if (method !== "GET" && !o.idem && path !== "/auth/device/token") o = { ...o, idem: newIdempotencyKey() };
    try {
      return await this.raw<T>(method, url, body, this.headers(o));
    } catch (e) {
      // The answer was lost: send the same request with the same key; the gateway replays it if it already ran.
      if (e instanceof StmApiError && e.code === "NETWORK_ERROR" && method !== "GET") {
        return this.raw<T>(method, url, body, this.headers(o));
      }
      // The device token may have been revoked or expired early: sign in once more and repeat.
      if (e instanceof StmApiError && e.code === "DEVICE_UNAUTHORIZED") {
        await this.signIn();
        return this.raw<T>(method, url, body, this.headers(o));
      }
      throw e;
    }
  }

  private async raw<T>(method: string, path: string, body: unknown, headers: Record<string, string>): Promise<T> {
    const res = await this.send(method, path, body, headers);
    if (res.status === 204) return undefined as T;
    const text = await res.text();
    return (text ? JSON.parse(text) : undefined) as T;
  }

  private async send(method: string, path: string, body: unknown, headers: Record<string, string>): Promise<Response> {
    const ctl = new AbortController();
    const timer = setTimeout(() => ctl.abort(), this.opts.timeoutMs ?? 30_000);
    let res: Response;
    try {
      res = await this.fetchFn(this.base + path, {
        method,
        headers: body !== undefined ? { ...headers, "Content-Type": "application/json" } : headers,
        body: body !== undefined ? JSON.stringify(body) : undefined,
        signal: ctl.signal,
      });
    } catch (e) {
      const timeout = (e as Error).name === "AbortError";
      throw new StmApiError(0, {
        code: "NETWORK_ERROR",
        message: timeout ? "The bank did not answer in time." : "Cannot reach the bank.",
        messageSw: timeout ? "Benki haikujibu kwa wakati." : "Imeshindwa kufikia benki.",
        retryable: true,
      }, "Network error");
    } finally {
      clearTimeout(timer);
    }
    if (!res.ok) {
      let err: Partial<ApiErrorBody["error"]> = {};
      try { err = ((await res.json()) as ApiErrorBody).error ?? {}; } catch { /* not JSON */ }
      throw new StmApiError(res.status, err, `HTTP ${res.status}`);
    }
    return res;
  }
}
