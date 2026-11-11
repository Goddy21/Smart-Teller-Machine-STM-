import type { Page } from "../ui/kit";
import { ApiError, request, send, readSse } from "./http";
import { bearer, type Role } from "../auth";

/**
 * Client for the STM middleware portal API (/api/v1/portal, signed-in user's Bearer token).
 * The data model follows the Smart Teller Core Banking API spec: customer sessions, account-opening applications,
 * card orders, cash deposits and withdrawals, forex, statements, receipts, teller calls.
 */

export type Flow = "ACCOUNT_OPENING" | "CARD_SERVICES" | "STATEMENT" | "CASH_DEPOSIT" | "CASH_WITHDRAWAL" | "FOREX";

export interface SessionRow {
  id: string;
  terminalCode: string;
  startTrigger: "TOUCH" | "CARD";
  language: "en" | "sw";
  status: "ACTIVE" | "ENDED" | "EXPIRED";
  flow: Flow | null;
  customerId: string | null;
  customerName: string | null;
  startedAt: string;
  lastActivityAt: string;
  endedAt: string | null;
  endReason: string | null;
  cardRetained: boolean;
  applicationsCount: number;
  applicationStatus: string | null;
  cardOrdersCount: number;
  depositsCount: number;
  withdrawalsCount: number;
  fxCount: number;
  statementsCount: number;
  serviceEventsCount: number;
  tellerCallsCount: number;
  needsTeller: boolean;
}

export interface Capture { id: string; kind: string; ownerType: string; mimeType: string; sizeBytes: number; createdAt: string }

export interface Registry {
  source: string; fullName: string; dateOfBirth: string; gender: string; idNumber?: string; passportNumber?: string;
  placeOfBirth: string | null; nationality: string;
}

export interface Application {
  id: string;
  sessionId: string;
  terminalCode: string;
  productCode: string;
  productName: string;
  termsId: string;
  consents: { code: string; accepted: boolean }[];
  acceptedAt: string;
  status: string;
  verificationId: string | null;
  identityAttempts: number;
  biometricsPassed: boolean;
  biometricAttempts: number;
  identityConfirmed: boolean;
  fullName: string | null;
  idNumberMasked?: string;
  kraPin: string | null;
  mobile: string | null;
  address: { line1: string; town?: string; county?: string } | null;
  occupation: string | null;
  sourceOfFunds: string | null;
  screening: { sanctions: string; pep: string } | null;
  otpVerified: boolean;
  signatureId: string | null;
  customerId: string | null;
  accountId: string | null;
  accountNumber: string | null;
  cardProduct: string | null;
  nameOnCard: string | null;
  reference: string | null;
  referralStatus: "NONE" | "PENDING" | "CLEARED" | "REJECTED";
  tellerOverride: string | null;
  decidedBy: string | null;
  decidedAt: string | null;
  decisionNotes: string | null;
  cancelReason: string | null;
  cancelledAtStep: string | null;
  createdAt: string;
  updatedAt: string;
  expiresAt: string;
  registry: Registry | null;
  captures: Capture[];
  needsTeller: boolean;
}

export interface ApplicationRow {
  id: string; sessionId: string; terminalCode: string; productCode: string; status: string; fullName: string | null;
  referralStatus: string; tellerOverride: string | null; biometricAttempts: number; identityAttempts: number;
  accountNumber: string | null; customerId: string | null; createdAt: string; updatedAt: string; expiresAt: string;
}

export interface CardOrder {
  id: string; service: string; accountId: string; cardProduct: string; nameOnCard: string; maskedPan: string | null;
  cardId: string | null; feeAmount: number; feePosted: boolean; feeRef: string | null; status: string; outcome: string | null;
  steps?: { code: string; status: string }[]; replacesCardId: string | null; reference: string | null;
  createdAt: string; updatedAt: string;
  terminalCode?: string; sessionId?: string; customerId?: string; customerName?: string | null; applicationId?: string | null;
}

/** A change a customer made at a kiosk: PIN change or reset, card freeze, reactivation, phone number, enrolment, signature. */
export interface ServiceEvent {
  id: string; kind: string; subject: string | null; detail: Record<string, unknown> | null; reference: string | null; createdAt: string;
  sessionId?: string; terminalCode?: string; customerId?: string; customerName?: string | null;
}
/** What core banking holds to recognise the customer. */
export interface CustomerRecord {
  unavailable?: boolean;
  biometricsEnrolled?: boolean; fingers?: string[]; biometricsSource?: string | null; biometricsEnrolledAt?: string | null;
  signatureOnFile?: boolean; signatureSource?: string | null; signatureCapturedAt?: string | null; mobileChangedAt?: string | null;
}

export interface SessionDetail extends SessionRow {
  serviceEvents: ServiceEvent[];
  customerRecord?: CustomerRecord;
  applications: Application[];
  verifications: {
    id: string; applicationId: string | null; purpose: string; documentType: string; country: string; result: string;
    documentChecks: { expiry: string; tamper: string }; registry: Registry | null; registrySource: string | null;
    existingCustomer: boolean; existingCustomerId: string | null; createdAt: string;
  }[];
  biometrics: {
    id: string; applicationId: string | null; customerId: string | null; faceResult: string | null; faceScore: number | null;
    livenessScore: number | null; finger: string; fingerResult: string; fingerScore: number; fingerQuality: number;
    overall: "PASS" | "FAIL"; attemptsLeft: number; createdAt: string;
  }[];
  cardOrders: CardOrder[];
  deposits: {
    id: string; targetLabel: string; currency: string; status: string; total: number | null; fee: number;
    accepted: { denomination: string; count: number }[] | null; rejectedCount: number; suspectedCounterfeit: number;
    transactionRef: string | null; coreRef: string | null; newBalance: number | null; cancelReason: string | null;
    createdAt: string; postedAt: string | null;
  }[];
  withdrawals: {
    id: string; source: string; accountId: string | null; currency: string; amount: number; localAmount: number;
    mix: { denomination: string; count: number }[]; status: string; transactionRef: string; coreRef: string | null;
    reversalRef: string | null; reverseReason: string | null; creditBackBy: string | null; createdAt: string; updatedAt: string;
  }[];
  fxExchanges: {
    id: string; currency: string; rate: number; destinationType: string; accountId: string | null; status: string;
    foreignTotal: number | null; localAmount: number | null; transactionRef: string | null; serials: string[] | null; createdAt: string;
  }[];
  statements: { id: string; accountId: string; type: string; periodMonths: number | null; fee: number; feeRef: string | null; pages: number | null; reference: string; createdAt: string }[];
  receipts: { id: string; transactionRef: string; delivery: string; printStatus: string; lines: string[]; createdAt: string }[];
  sms: { id: string; template: string; body: string; status: string; createdAt: string }[];
  tellerCalls: { id: string; screen: string | null; flow: string | null; applicationId: string | null; language: string; status: string; createdAt: string; updatedAt: string }[];
}

export type Decision = "CLEAR_REFERRAL" | "VERIFY_IDENTITY" | "REJECT";

export interface TerminalView {
  terminalCode: string;
  branchCode: string;
  status: string | null;
  cassetteCounts: Record<string, number>;
  cardStock: number | null;
  alarms: string[];
  lastSeen: string | null;
  online: boolean;
  modules: Record<string, string | { status: string; blankStock?: number }>;
  disabledServices: string[];
  serviceMode: "IN_SERVICE" | "LOCKED";
  softwareVersion: string | null;
  /** Last D-6 health report; null = the kiosk app has never sent one (modules, card stock and alarms are then unknown). */
  healthReportedAt: string | null;
  /** Last call to the gateway of any kind (sign-in, a customer session …). */
  lastContactAt: string | null;
  /** The remote-command WebSocket is open (Lock, Reboot … reach the kiosk at once). */
  commandChannel: boolean;
}

export interface TerminalRow {
  terminalCode: string; branchCode: string; branchName: string | null; status: string; serviceMode: "IN_SERVICE" | "LOCKED";
  softwareVersion: string | null; createdAt: string; sessionsToday: number; sessionsTotal: number; lastSignIn: string | null;
  name: string | null; location: string | null; notes: string | null; tokenRotatedAt: string | null; retiredAt: string | null;
}
/** What an administrator fills in for a kiosk. branchName is only needed for a branch that does not exist yet. */
export interface TerminalDetails { branchCode: string; branchName?: string; name?: string; location?: string; notes?: string }
/** The terminal token is in here once: when the kiosk is created or its token is replaced. */
export interface TerminalCreated { terminalCode: string; branchCode: string; token: string }

export type TerminalCommand = "REBOOT" | "LOCK" | "UNLOCK" | "REFRESH_CONFIG";

export interface TxRow {
  id: string; kind: "DEPOSIT" | "WITHDRAWAL" | "FX"; terminalCode: string; sessionId: string; status: string; currency: string;
  amount: number; localAmount: number; party: string; transactionRef: string | null; coreRef: string | null;
  reversalRef: string | null; notesReturned: number | null; suspectedCounterfeit: number | null; createdAt: string; updatedAt: string;
}

export interface Posting {
  id: number; coreRef: string; accountId: string; direction: "DR" | "CR"; amount: number; currency: string; narrative: string;
  balanceAfter: number | null; reversalOf: string | null; postedAt: string;
}

export interface VerifyResult { valid: boolean; checked: number; brokenAtSeq: number | null }

export interface JournalEntry {
  seq: number; eventType: string; entityId: string; terminalCode: string | null; payload: string; prevHash: string;
  hash: string; createdAt: string;
}

export interface TimelineEntry {
  seq: number; eventType: string; entityId: string; terminalCode: string | null; payload: Record<string, unknown>;
  hash: string; createdAt: string;
}

export interface Overview {
  totals: {
    sessionsToday: number; sessionsActive: number; accountsOpened: number; accountsOpenedToday: number;
    referralsWaiting: number; identityHelpWaiting: number; cardsIssued: number; cardsAttention: number; deposits: number;
    depositsTodayKes: number; withdrawals: number; withdrawalsTodayKes: number; fxDeals: number; reversalsPending: number;
    cashStuck: number; cashVariances: number; terminals: number; terminalsOnline: number; terminalsLocked: number;
    tellerCallsToday: number; smsToday: number; auditEntries: number;
  };
  last14Days: { day: string; accountOpening: number; cardServices: number; cash: number; forex: number; statements: number; browseOnly: number }[];
  byFlow: { key: string; count: number }[];
  applicationFunnel: { key: string; count: number }[];
  sessionOutcomes: { key: string; count: number }[];
  generatedAt: string;
}

export interface Reference {
  products: {
    code: string; category: string; name: string; nameSw: string | null; currency: string; accountType: string;
    minOpeningBalance: number; features: string[]; cardProducts: string[]; waiveCardFee: boolean; active: boolean; accountsOpened: number;
  }[];
  tariffs: { code: string; label: string; fee: number; currency: string; includesExcise: boolean; version: string; lines: { label: string; amount: number }[] | null }[];
  fxRates: FxRate[];
  terms: { termsId: string; language: string; version: string; productCode: string | null; active: boolean; audioUrl: string | null; sections: number; consents: { code: string; required: boolean; label: string }[] }[];
  branches: { code: string; name: string; terminals: number }[];
}

export interface FxRate { currency: string; symbol: string; buy: number; sell: number; updatedAt: string }

export interface Reconciliation {
  positions: {
    id: number; terminalCode: string; event: string; cassettes: { currency: string; denomination: string; count: number }[];
    retractBin: number; rejectBin: number; counterfeitBin: number; reconciled: boolean;
    variances: { currency: string; expected: string; counted: string; difference: string }[]; caseId: string | null; createdAt: string;
  }[];
  cassettes: { terminalCode: string; currency: string; value: number; notes: number; notesByDenomination: { denomination: number; count: number }[] }[];
  pendingReversals: { id: string; terminalCode: string; source: string; currency: string; amount: number; localAmount: number; transactionRef: string; reversalRef: string; reverseReason: string; creditBackBy: string; createdAt: string }[];
}

export interface DeviceJournalRow { terminalCode: string; seq: number; at: string; event: string; ref: string | null; data: unknown; signatureValid: boolean; receivedAt: string }
export interface SmsRow { id: string; sessionId: string | null; template: string; mobile: string; language: string; body: string; status: string; createdAt: string }

export interface Simulator {
  coreDown: boolean; latencyMs: number; fixedOtp: string | null; devPinBlockExample: { pin: string; pinBlock: string };
  identities: { documentNumber: string; documentType: string; country: string; surname: string; givenNames: string; dateOfBirth: string; gender: string; kraPin: string | null; kraStatus: string; scenario: string; notes: string | null }[];
  paybills: { paybill: string; name: string }[];
}
export interface SimCustomer {
  id: string; idNumber: string; fullName: string; mobile: string; kraPin: string | null; branch: string; createdAt: string;
  customerSince: string | null; mobileChangedAt: string | null; fingersEnrolled: number; signatureSource: string | null;
  accounts: { id: string; number: string; type: string; available: number; ledger: number; currency: string; status: string;
              statusReason: string | null; lastActivityAt: string | null }[] | null;
  cards: { id: string; maskedPan: string; product: string; status: string; reason: string | null; expiry: string; accountId: string }[] | null;
}

const BASE = "/api/v1/portal";

export interface EmailRow {
  id: number;
  to: string;
  subject: string;
  body: string | null;
  purpose: string;
  status: "SENT" | "FAILED" | "SIMULATED";
  error: string | null;
  createdAt: string;
}

export interface PortalUserRow {
  username: string;
  displayName: string;
  email: string | null;
  role: Role;
  branchCode: string | null;
  languages: string[];
  status: "ACTIVE" | "DISABLED" | "ARCHIVED";
  mustChangePassword: boolean;
  locked: boolean;
  lastLoginAt: string | null;
  createdAt: string;
  createdBy: string | null;
  activeSessions: number;
  archivedAt: string | null;
}

/** temporaryPassword is null when it was emailed (emailedTo); emailError when emailing failed and it is shown instead. */
export interface UserCreated {
  user: PortalUserRow;
  temporaryPassword: string | null;
  emailedTo: string | null;
  emailError: string | null;
}

export interface UserActivity {
  at: string;
  event: string;
  subject: string;
  details: Record<string, unknown>;
}

/**
 * Every call carries the signed-in user's session token. A 401 means the session ended (idle timeout, sign-out
 * elsewhere, account disabled): onSessionEnded sends the user back to the sign-in screen.
 */
export class PortalApi {
  private readonly token: string;
  private readonly onSessionEnded: () => void;

  constructor(token: string, onSessionEnded: () => void) {
    this.token = token;
    this.onSessionEnded = onSessionEnded;
  }

  private h() {
    return bearer(this.token);
  }

  private async guard<T>(p: Promise<T>): Promise<T> {
    try {
      return await p;
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) this.onSessionEnded();
      throw e;
    }
  }

  private get<T>(path: string, query?: Record<string, string | number | boolean | undefined | null>) {
    const qs = query
      ? new URLSearchParams(Object.entries(query).filter(([, v]) => v !== undefined && v !== null && v !== "")
          .map(([k, v]) => [k, String(v)])).toString()
      : "";
    return this.guard(request<T>(`${BASE}${path}${qs ? `?${qs}` : ""}`, { headers: this.h() }));
  }

  private post<T>(path: string, body?: unknown) {
    return this.guard(request<T>(`${BASE}${path}`, { method: "POST", body, headers: this.h() }));
  }

  private put<T>(path: string, body?: unknown) {
    return this.guard(request<T>(`${BASE}${path}`, { method: "PUT", body, headers: this.h() }));
  }

  private del<T>(path: string) {
    return this.guard(request<T>(`${BASE}${path}`, { method: "DELETE", headers: this.h() }));
  }

  overview() { return this.get<Overview>("/overview"); }

  sessions(q: { status?: string; flow?: string; q?: string } = {}) { return this.get<SessionRow[]>("/sessions", q); }
  /** One page of sessions; counts holds the totals for the teller / active / ended / all tabs. */
  sessionsPage(q: { flow?: string; q?: string; view?: string; page: number; size: number }) { return this.get<Page<SessionRow>>("/sessions", q); }
  session(id: string) { return this.get<SessionDetail>(`/sessions/${encodeURIComponent(id)}`); }
  timeline(id: string) { return this.get<TimelineEntry[]>(`/sessions/${encodeURIComponent(id)}/timeline`); }

  applications(q: { status?: string; needsTeller?: boolean } = {}) { return this.get<ApplicationRow[]>("/applications", q); }
  applicationsPage(q: { view?: string; q?: string; page: number; size: number }) { return this.get<Page<ApplicationRow>>("/applications", q); }
  application(id: string) { return this.get<Application>(`/applications/${encodeURIComponent(id)}`); }
  /** The middleware records the signed-in user as the one who decided. */
  decide(id: string, decision: Decision, notes?: string) {
    return this.post<Application>(`/applications/${encodeURIComponent(id)}/decision`, { decision, notes });
  }

  /** Fetches an ID image, face, registry photo or signature as an object URL. Every view is audited. */
  async captureUrl(id: string): Promise<string> {
    const res = await this.guard(send(`${BASE}/captures/${encodeURIComponent(id)}`, { headers: this.h() }));
    return URL.createObjectURL(await res.blob());
  }

  terminals() { return this.get<TerminalView[]>("/terminals"); }
  registry() { return this.get<TerminalRow[]>("/terminals/registry"); }
  createTerminal(terminalCode: string, d: TerminalDetails) { return this.post<TerminalCreated>("/terminals", { terminalCode, ...d }); }
  updateTerminal(terminalCode: string, d: TerminalDetails) {
    return this.put<{ terminalCode: string; branchCode: string; branchChanged: boolean }>(`/terminals/${encodeURIComponent(terminalCode)}`, d);
  }
  rotateTerminalToken(terminalCode: string) { return this.post<TerminalCreated>(`/terminals/${encodeURIComponent(terminalCode)}/rotate-token`); }
  setTerminalStatus(terminalCode: string, status: "ACTIVE" | "DISABLED") {
    return this.put<{ terminalCode: string; status: string }>(`/terminals/${encodeURIComponent(terminalCode)}/status`, { status });
  }
  removeTerminal(terminalCode: string) {
    return this.del<{ terminalCode: string; outcome: "DELETED" | "RETIRED" }>(`/terminals/${encodeURIComponent(terminalCode)}`);
  }
  command(terminalCode: string, command: TerminalCommand) {
    return this.post<{ applied: boolean; pushed: boolean }>(`/terminals/${encodeURIComponent(terminalCode)}/commands`, { command });
  }
  /** Live fleet updates. Resolves when the stream ends. */
  telemetryStream(onUpdate: (t: TerminalView) => void, signal: AbortSignal, onOpen?: () => void) {
    return readSse(`${BASE}/telemetry/stream`, this.h(), { "terminal-update": (d) => onUpdate(d as TerminalView) }, signal, onOpen);
  }

  transactions(q: { kind?: string; status?: string } = {}) { return this.get<TxRow[]>("/transactions", q); }
  transactionsPage(q: { kind?: string; q?: string; page: number; size: number }) { return this.get<Page<TxRow>>("/transactions", q); }
  /** Today's totals and open exceptions over all transactions. */
  transactionsSummary() { return this.get<{ depositedToday: number; withdrawnToday: number; fxToday: number; exceptions: number }>("/transactions/summary"); }
  postings(coreRefs: string) { return this.get<Posting[]>("/postings", { refs: coreRefs }); }
  cardOrders(status?: string) { return this.get<CardOrder[]>("/card-orders", { status }); }
  cardOrdersPage(q: { status?: string; q?: string; page: number; size: number }) { return this.get<Page<CardOrder>>("/card-orders", q); }
  serviceEventsPage(q: { kind?: string; q?: string; page: number; size: number }) { return this.get<Page<ServiceEvent>>("/service-events", q); }
  /** The customer's specimen signature as an object URL. Every view is audited. */
  async signatureUrl(customerId: string): Promise<string> {
    const res = await this.guard(send(`${BASE}/customers/${encodeURIComponent(customerId)}/signature`, { headers: this.h() }));
    return URL.createObjectURL(await res.blob());
  }

  reconciliation() { return this.get<Reconciliation>("/reconciliation"); }
  deviceJournal(q: { terminal?: string; ref?: string } = {}) { return this.get<DeviceJournalRow[]>("/device-journal", q); }
  deviceJournalPage(q: { ref?: string; page: number; size: number }) { return this.get<Page<DeviceJournalRow>>("/device-journal", q); }
  sms() { return this.get<SmsRow[]>("/sms"); }
  smsPage(q: { page: number; size: number }) { return this.get<Page<SmsRow>>("/sms", q); }

  verifyAudit() { return this.get<VerifyResult>("/audit/verify"); }
  journal(q: { entityId?: string; eventType?: string; beforeSeq?: number; limit?: number }) {
    return this.get<JournalEntry[]>("/audit/journal", q);
  }
  /** from / to: yyyy-mm-dd, both inclusive (East Africa Time). */
  journalPage(q: { entityId?: string; eventType?: string; terminal?: string; from?: string; to?: string; page: number; size: number }) {
    return this.get<Page<JournalEntry>>("/audit/journal", q);
  }
  eventTypes() { return this.get<string[]>("/audit/event-types"); }

  reference() { return this.get<Reference>("/reference"); }
  setRate(currency: string, buy: number, sell: number) { return this.put<FxRate>(`/fx-rates/${currency}`, { buy, sell }); }

  // ---- simulated back ends (demo profile)
  simulator() { return this.get<Simulator>("/simulator"); }
  setSimulator(s: { coreDown?: boolean; latencyMs?: number }) { return this.put<Simulator>("/simulator", s); }
  simCustomers(q?: string) { return this.get<SimCustomer[]>("/simulator/customers", { q }); }
  simPostings(accountId: string) { return this.get<Posting[]>(`/simulator/accounts/${encodeURIComponent(accountId)}/postings`); }
  simGl() { return this.get<{ gl: string; currency: string; balance: number; postings: number }[]>("/simulator/gl"); }

  // ---- account administration (ADMIN)
  users(includeArchived = false) { return this.get<PortalUserRow[]>("/users", includeArchived ? { includeArchived: "true" } : undefined); }
  createUser(u: { username: string; displayName: string; email?: string; role: Role; branchCode?: string; languages: string[]; sendEmail?: boolean }) {
    return this.post<UserCreated>("/users", u);
  }
  removeUser(username: string) {
    return this.del<{ username: string; outcome: "DELETED" | "ARCHIVED"; message: string }>(`/users/${encodeURIComponent(username)}`);
  }
  signOutUser(username: string) { return this.post<{ sessionsEnded: number }>(`/users/${encodeURIComponent(username)}/sign-out`); }
  userActivity(username: string) { return this.get<UserActivity[]>(`/users/${encodeURIComponent(username)}/activity`); }
  updateUser(username: string, u: Partial<{ displayName: string; email: string; role: Role; branchCode: string; languages: string[]; status: "ACTIVE" | "DISABLED" }>) {
    return this.put<PortalUserRow>(`/users/${encodeURIComponent(username)}`, u);
  }
  emails(q: { page: number; size: number }) {
    return this.get<{ live: boolean; emails: EmailRow[]; total: number; page: number; size: number; pages: number }>("/users/emails", q);
  }
  resetPassword(username: string, sendEmail = false) {
    return this.post<UserCreated>(`/users/${encodeURIComponent(username)}/reset-password`, { sendEmail });
  }
}
