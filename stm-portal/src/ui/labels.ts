import type { Flow } from "../api/portal";

/** Words used in the Smart Teller prototype, so staff and customers talk about the same things. */
export const FLOW_LABEL: Record<Flow, string> = {
  ACCOUNT_OPENING: "Open an account",
  CARD_SERVICES: "Card services",
  STATEMENT: "Statement",
  CASH_DEPOSIT: "Cash deposit",
  CASH_WITHDRAWAL: "Cash withdrawal",
  FOREX: "Foreign exchange",
};

export const flowLabel = (f: string | null | undefined) => (f ? FLOW_LABEL[f as Flow] ?? f : "Browsing only");

export const END_REASON: Record<string, string> = {
  CUSTOMER_FINISHED: "Customer finished",
  CUSTOMER_CANCELLED: "Customer cancelled",
  TIMEOUT: "Timed out",
  IDLE_TIMEOUT: "Timed out (idle)",
  CARD_RETAINED: "Card retained",
  DEVICE_FAULT: "Kiosk fault",
  EXPIRED: "Expired",
  SUPERSEDED: "A new session started on the kiosk",
};

export const CAPTURE_LABEL: Record<string, string> = {
  ID_FRONT: "ID front",
  ID_BACK: "ID back",
  PASSPORT: "Passport page",
  REGISTRY_PHOTO: "Registry photo",
  IPRS_PHOTO: "Registry photo",
  FACE: "Live face",
  SIGNATURE: "Signature",
  FINGERPRINT: "Fingerprint",
  SNAPSHOT: "Teller snapshot",
};

export const SERVICE_LABEL: Record<string, string> = {
  accountOpening: "Account opening",
  cardServices: "Card services",
  cashDeposit: "Cash deposit",
  cashWithdrawal: "Cash withdrawal",
  forex: "Forex",
  statements: "Statements",
};

export const MODULE_LABEL: Record<string, string> = {
  cardReader: "Card reader",
  pinPad: "PIN pad (EPP)",
  passportScanner: "Passport / ID scanner",
  idScanner: "ID scanner",
  fingerprint: "Fingerprint scanner",
  camera: "Camera",
  receiptPrinter: "Receipt printer",
  a4Printer: "A4 printer",
  cardPrinter: "Card printer",
  cashRecycler: "Cash recycler",
  touchScreen: "Touch screen",
  upperDisplay: "Upper display",
  handset: "Handset",
  signaturePad: "Signature pad",
  qrReader: "QR reader",
  safeDoor: "Safe door",
  ups: "UPS / power",
  network: "Network",
};

/** The modules every STM in the pilot has (from the hardware), in the order they sit on the machine. */
export const STM_MODULES = ["upperDisplay", "touchScreen", "passportScanner", "receiptPrinter", "cardReader", "pinPad", "fingerprint",
  "cashRecycler", "handset", "camera", "a4Printer", "cardPrinter"];

/** Services a faulty module switches off (same rule as the middleware's D-6 handling). */
export const MODULE_NEEDS: Record<string, string[]> = {
  cardReader: ["cardServices", "statements", "cashWithdrawal"],
  cardPrinter: ["cardServices", "accountOpening"],
  cashRecycler: ["cashDeposit", "cashWithdrawal", "forex"],
  idScanner: ["accountOpening", "forex"],
  passportScanner: ["accountOpening", "forex"],
  fingerprint: ["accountOpening", "cardServices"],
  camera: ["accountOpening"],
  pinPad: ["accountOpening", "cardServices", "statements", "cashWithdrawal"],
  touchScreen: ["accountOpening", "cardServices", "cashDeposit", "cashWithdrawal", "forex", "statements"],
};

/** Statuses that keep a module usable (the middleware's WARNINGS list). */
export const MODULE_USABLE = ["OK", "READY", "PAPER_LOW", "LOW", "WARNING"];
export const moduleTone = (s: string | null | undefined): "good" | "warn" | "bad" | "neutral" =>
  !s ? "neutral" : s === "OK" || s === "READY" ? "good" : MODULE_USABLE.includes(s) ? "warn" : "bad";

export const OPERATOR_ROLE: Record<string, string> = {
  CUSTODIAN: "Cash custodian", CIT: "Cash-in-transit", ENGINEER: "Engineer", SUPERVISOR: "Branch supervisor",
};
export const OPERATOR_FUNCTION: Record<string, string> = {
  CASH_REPLENISH: "Replenish cash", CASH_POSITION: "Cash position report", CLEAR_BINS: "Clear reject/retract bins",
  LOAD_CARD_STOCK: "Load blank cards", LOAD_PAPER: "Load paper", VIEW_STATUS: "View device status", DIAGNOSTICS: "Diagnostics",
  MODULE_RESET: "Reset modules", SERVICE_MODE: "Service mode", VIEW_LOGS: "View logs", END_OF_DAY: "End of day",
};

export const CARD_SERVICE_LABEL: Record<string, string> = {
  NEW_CARD: "New card",
  LINK_AND_ISSUE: "Link and issue",
  REPLACE: "Replacement",
  REPLACE_CARD: "Replacement",
  ACCOUNT_OPENING: "With new account",
};

/** Card and account changes customers make at a kiosk (service_events.kind). */
export const SERVICE_KIND: Record<string, string> = {
  PIN_CHANGED: "PIN changed",
  PIN_RESET: "PIN reset",
  CARD_TEMP_BLOCKED: "Card temporarily blocked",
  CARD_UNBLOCKED: "Card unblocked",
  CARD_BLOCKED: "Card blocked",
  ACCOUNT_REACTIVATED: "Account reactivated",
  BIOMETRICS_ENROLLED: "Fingerprints enrolled",
  MOBILE_CHANGED: "Phone number changed",
  SIGNATURE_REGISTERED: "Signature registered",
  SIGNATURE_UPDATED: "Signature updated",
};
export const SOURCE_LABEL: Record<string, string> = {
  BRANCH: "at a branch", STM_ONBOARDING: "at account opening on a kiosk", STM_ENROLMENT: "at a kiosk", STM_UPDATE: "at a kiosk",
};

export const STEP_LABEL: Record<string, string> = {
  PICK_BLANK: "Pick blank",
  PRINT_NAME: "Print name",
  PROGRAM_EMV_CHIP: "Program chip",
  QUALITY_CHECK: "Quality check",
};

export const label = (map: Record<string, string>, k: string | null | undefined) =>
  !k ? "—" : map[k] ?? k.replace(/_/g, " ").toLowerCase().replace(/^\w/, (c) => c.toUpperCase());
