import type {
  Agreement, ApiErrorBody, BiometricResponse, CardIssuanceResponse, CardIssueRequest, CreateSessionRequest,
  CreateTxRequest, DemoPersona, DetailsRequest, DispenseResultRequest, DocumentAccepted, DocumentType, ErrorCode,
  FaceRequest, FaultResponse, FingerprintRequest, HardwareFaultRequest, Heartbeat, KioskStatus, LookupRequest,
  LookupResult, NotificationChannel, PassportRequest, PassportResult, Product, Receipt, SessionView, SignRequest,
  TxView, WsTicket,
} from "./types";

export interface StmClientOptions {
  /** Middleware origin, e.g. "http://localhost:8080". Leave "" to call the same origin (Vite proxy / same host). */
  baseUrl?: string;
  /** Terminal token from POST /api/v1/terminals/register. A function lets you rotate it without a new client. */
  token: string | (() => string);
  /** Per-request timeout. Default 30 s (bank calls can be slow). */
  timeoutMs?: number;
  fetch?: typeof fetch;
}

/** Thrown for every non-2xx response, and for network failures / timeouts (status 0). */
export class StmApiError extends Error {
  readonly status: number;
  readonly code: ErrorCode;

  constructor(status: number, code: ErrorCode, message: string) {
    super(message);
    this.name = "StmApiError";
    this.status = status;
    this.code = code;
  }
}

/**
 * Which error screen of the designs a failure maps to.
 * A = "Couldn't read the document", B = "Verification unsuccessful", C = "Temporary hardware issue".
 * Note: a biometric non-match is NOT an error. It is a 200 with matched=false; see BiometricResponse.
 */
export type ErrorScreen = "A" | "B" | "C" | "OUT_OF_SERVICE" | "SESSION_ENDED" | "RETRY" | "GENERIC";

export function errorScreen(e: unknown): ErrorScreen {
  if (!(e instanceof StmApiError)) return "GENERIC";
  switch (e.code) {
    case "DOCUMENT_UNREADABLE": case "DOCUMENT_EXPIRED": case "NOT_ELIGIBLE":
      return "A";
    case "BIOMETRIC_ATTEMPTS_EXCEEDED": case "IDENTITY_NOT_VERIFIED": case "ACCOUNT_MISMATCH":
      return "B";
    case "CARD_ISSUANCE_UNAVAILABLE": case "CARD_SERVICE_UNAVAILABLE":
      return "C";
    case "OUT_OF_SERVICE":
      return "OUT_OF_SERVICE";
    case "SESSION_ENDED": case "SESSION_NOT_FOUND":
      return "SESSION_ENDED";
    case "BANK_UNAVAILABLE": case "CONCURRENT_UPDATE": case "IN_FLIGHT": case "NETWORK_ERROR":
      return "RETRY";
    default:
      return "GENERIC";
  }
}

type Query = Record<string, string | undefined>;

/**
 * Typed client for every kiosk endpoint of the STM middleware.
 *
 *   const stm = new StmClient({ baseUrl: import.meta.env.VITE_STM_URL ?? "", token: import.meta.env.VITE_STM_TOKEN });
 *   let s = await stm.createSession({ journey: "OPEN_ACCOUNT", language: "en" });
 *   await stm.submitPassport(s.id, { mrzLine1, mrzLine2 });
 *   s = await stm.getSession(s.id);   // switch (s.step) { ... }
 */
export class StmClient {
  private readonly base: string;
  private readonly tokenFn: () => string;
  private readonly timeoutMs: number;
  private readonly fetchFn: typeof fetch;

  constructor(opts: StmClientOptions) {
    this.base = (opts.baseUrl ?? "").replace(/\/+$/, "");
    this.tokenFn = typeof opts.token === "function" ? opts.token : () => opts.token as string;
    this.timeoutMs = opts.timeoutMs ?? 30_000;
    this.fetchFn = opts.fetch ?? globalThis.fetch.bind(globalThis);
  }

  get baseUrl(): string {
    return this.base;
  }

  // ---------------------------------------------------------------- machine status and catalogue

  /** Poll on the idle screen. inService=false shows "Temporarily out of service". */
  status() { return this.json<KioskStatus>("GET", "/api/v1/kiosk/status"); }

  products() { return this.json<Product[]>("GET", "/api/v1/kiosk/products"); }

  /** Send every ~30 s and whenever hardware state changes. */
  heartbeat(hb: Heartbeat) { return this.json<void>("POST", "/api/v1/kiosk/telemetry", hb); }

  // ---------------------------------------------------------------- session lifecycle

  /**
   * Starts a customer visit. Pass the same idempotencyKey when retrying after a network error,
   * so a double tap or a retry never creates two sessions.
   */
  createSession(r: CreateSessionRequest, idempotencyKey: string = crypto.randomUUID()) {
    return this.json<SessionView>("POST", "/api/v1/kiosk/sessions", r, { "Idempotency-Key": idempotencyKey });
  }

  /** Use after a reload to find out which screen the customer was on. */
  getSession(id: string) { return this.json<SessionView>("GET", this.s(id)); }

  /** "I'm Still Here" on the timeout screen. */
  keepAlive(id: string) { return this.json<SessionView>("POST", this.s(id, "/keepalive")); }

  back(id: string) { return this.json<SessionView>("POST", this.s(id, "/back")); }

  /** Cancel / End Session. */
  cancel(id: string) { return this.json<SessionView>("POST", this.s(id, "/cancel")); }

  /** Done / Finish Session. */
  complete(id: string) { return this.json<SessionView>("POST", this.s(id, "/complete")); }

  /** Flags the session for a teller. Then send CALL_REQUEST on the signaling socket. */
  escalate(id: string, reason?: string) {
    return this.json<SessionView>("POST", this.s(id, "/escalate"), reason ? { reason } : undefined);
  }

  // ---------------------------------------------------------------- identity

  /** A1 / B1: MRZ lines as read by the scanner via the local eCAT service. */
  submitPassport(id: string, r: PassportRequest) {
    return this.json<PassportResult>("POST", this.s(id, "/passport"), r);
  }

  /** A2: phone, email, consent tick box. */
  confirmDetails(id: string, r: DetailsRequest) {
    return this.json<SessionView>("POST", this.s(id, "/details"), r);
  }

  /** B1 alternative to scanning a passport. */
  lookupAccount(id: string, r: LookupRequest) {
    return this.json<LookupResult>("POST", this.s(id, "/account/lookup"), r);
  }

  submitFace(id: string, r: FaceRequest) {
    return this.json<BiometricResponse>("POST", this.s(id, "/biometrics/face"), r);
  }

  submitFingerprint(id: string, r: FingerprintRequest) {
    return this.json<BiometricResponse>("POST", this.s(id, "/biometrics/fingerprint"), r);
  }

  // ---------------------------------------------------------------- A4 supporting documents

  /** Max 6 MB. PROOF_OF_ADDRESS on step 4; SIGNED_AGREEMENT before signing with method PAPER. */
  uploadDocument(id: string, type: DocumentType, file: Blob, fileName = "document") {
    const form = new FormData();
    form.append("file", file, fileName);
    return this.json<DocumentAccepted>("POST", this.s(id, "/documents"), form, {}, { type });
  }

  acceptSupportingDocs(id: string) {
    return this.json<SessionView>("POST", this.s(id, "/supporting-documents/accept"));
  }

  /** "Skip: Basic account tier". */
  skipSupportingDocs(id: string) {
    return this.json<SessionView>("POST", this.s(id, "/supporting-documents/skip"));
  }

  // ---------------------------------------------------------------- A5-A7 product, agreement, account

  selectProduct(id: string, productCode: string) {
    return this.json<SessionView>("PUT", this.s(id, "/product"), { productCode });
  }

  continueToAgreement(id: string) {
    return this.json<SessionView>("POST", this.s(id, "/product/continue"));
  }

  /** Printable HTML plus the version you must echo back in sign(). */
  async getAgreement(id: string): Promise<Agreement> {
    const res = await this.raw("GET", this.s(id, "/agreement"));
    return { html: await res.text(), version: res.headers.get("X-Agreement-Version") ?? "" };
  }

  /** "Confirm Signature". Opens the account; the response is at step ACCOUNT_OPENED with account filled in. */
  sign(id: string, r: SignRequest) {
    return this.json<SessionView>("POST", this.s(id, "/agreement/sign"), r);
  }

  /** Retry after 503 BANK_UNAVAILABLE on sign(). Safe to repeat. */
  openAccount(id: string) {
    return this.json<SessionView>("POST", this.s(id, "/account"));
  }

  // ---------------------------------------------------------------- cards

  /** Step 7: "Yes, Issue Card Now" (true) / "No, Complete Account Opening Only" (false). */
  cardOffer(id: string, issueNow: boolean) {
    return this.json<SessionView>("POST", this.s(id, "/card/offer"), { issueNow });
  }

  cardNameOptions(id: string) {
    return this.json<string[]>("GET", this.s(id, "/card/name-options"));
  }

  /** "Confirm & Dispense". Returns the payload for the dispenser. */
  issueCard(id: string, r: CardIssueRequest) {
    return this.json<CardIssuanceResponse>("POST", this.s(id, "/card/issue"), r);
  }

  /** Report the dispenser outcome. success=false leads to Error C (card cancelled, queued at the branch). */
  dispenseResult(id: string, r: DispenseResultRequest) {
    return this.json<SessionView>("POST", this.s(id, "/card/dispense-result"), r);
  }

  hardwareFault(id: string, r: HardwareFaultRequest) {
    return this.json<FaultResponse>("POST", this.s(id, "/hardware-fault"), r);
  }

  // ---------------------------------------------------------------- receipt

  receipt(id: string) { return this.json<Receipt>("GET", this.s(id, "/receipt")); }

  sendSummary(id: string, channel: NotificationChannel) {
    return this.json<void>("POST", this.s(id, "/summary"), { channel });
  }

  // ---------------------------------------------------------------- deposits (cash recycler)

  createTransaction(r: CreateTxRequest, idempotencyKey: string = crypto.randomUUID()) {
    return this.json<TxView>("POST", "/api/v1/kiosk/transactions", r, { "Idempotency-Key": idempotencyKey });
  }
  getTransaction(id: string) { return this.json<TxView>("GET", `/api/v1/kiosk/transactions/${enc(id)}`); }
  escrow(id: string, amount: number) {
    return this.json<TxView>("POST", `/api/v1/kiosk/transactions/${enc(id)}/escrow`, { amount });
  }
  commitTransaction(id: string) { return this.json<TxView>("POST", `/api/v1/kiosk/transactions/${enc(id)}/commit`); }
  cancelTransaction(id: string) { return this.json<TxView>("POST", `/api/v1/kiosk/transactions/${enc(id)}/cancel`); }

  // ---------------------------------------------------------------- signaling + demo

  /** One-time ticket for the signaling WebSocket. KioskSignaling calls this for you. */
  signalingTicket() { return this.json<WsTicket>("POST", "/api/v1/kiosk/signaling/ticket"); }

  /** Demo profile only: personas with ready-made MRZ lines. */
  demoPersonas() { return this.json<{ personas: DemoPersona[] } & Record<string, unknown>>("GET", "/api/v1/kiosk/demo/personas"); }

  // ---------------------------------------------------------------- plumbing

  private s(id: string, suffix = "") {
    return `/api/v1/kiosk/sessions/${enc(id)}${suffix}`;
  }

  private async json<T>(method: string, path: string, body?: unknown,
                        headers: Record<string, string> = {}, query?: Query): Promise<T> {
    const res = await this.raw(method, path, body, headers, query);
    const text = await res.text();
    return (text ? JSON.parse(text) : undefined) as T;
  }

  private async raw(method: string, path: string, body?: unknown,
                    headers: Record<string, string> = {}, query?: Query): Promise<Response> {
    const h: Record<string, string> = { Authorization: `Bearer ${this.tokenFn()}`, Accept: "application/json", ...headers };
    let payload: BodyInit | undefined;
    if (body instanceof FormData) {
      payload = body; // the browser sets the multipart boundary
    } else if (body !== undefined) {
      h["Content-Type"] = "application/json";
      payload = JSON.stringify(body);
    }

    let url = `${this.base}${path}`;
    if (query) {
      const qs = new URLSearchParams(Object.entries(query).filter(([, v]) => v !== undefined) as [string, string][]);
      if ([...qs].length) url += `?${qs}`;
    }

    const ctrl = new AbortController();
    const timer = setTimeout(() => ctrl.abort(), this.timeoutMs);
    let res: Response;
    try {
      res = await this.fetchFn(url, { method, headers: h, body: payload, signal: ctrl.signal });
    } catch (e) {
      const timedOut = (e as Error)?.name === "AbortError";
      throw new StmApiError(0, "NETWORK_ERROR", timedOut ? "Request timed out" : "Middleware unreachable");
    } finally {
      clearTimeout(timer);
    }

    if (!res.ok) {
      let err: Partial<ApiErrorBody> | null = null;
      try { err = JSON.parse(await res.text()); } catch { /* empty or non-JSON body */ }
      const code = err?.code ?? (res.status === 401 ? "UNAUTHORIZED" : res.status === 403 ? "FORBIDDEN" : `HTTP_${res.status}`);
      throw new StmApiError(res.status, code, err?.message ?? (res.statusText || code));
    }
    return res;
  }
}

function enc(s: string) {
  return encodeURIComponent(s);
}
