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
  cardPrinter: "Card printer",
  cashRecycler: "Cash recycler",
  receiptPrinter: "Receipt printer",
  a4Printer: "A4 printer",
  idScanner: "ID scanner",
  fingerprint: "Fingerprint",
  camera: "Camera",
  pinPad: "PIN pad",
};

export const CARD_SERVICE_LABEL: Record<string, string> = {
  NEW_CARD: "New card",
  LINK_AND_ISSUE: "Link and issue",
  REPLACE: "Replacement",
  REPLACE_CARD: "Replacement",
  ACCOUNT_OPENING: "With new account",
};

export const STEP_LABEL: Record<string, string> = {
  PICK_BLANK: "Pick blank",
  PRINT_NAME: "Print name",
  PROGRAM_EMV_CHIP: "Program chip",
  QUALITY_CHECK: "Quality check",
};

export const label = (map: Record<string, string>, k: string | null | undefined) =>
  !k ? "—" : map[k] ?? k.replace(/_/g, " ").toLowerCase().replace(/^\w/, (c) => c.toUpperCase());
