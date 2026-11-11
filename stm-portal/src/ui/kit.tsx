import { useEffect, useRef, useState, type ReactNode } from "react";
import { IconAlert, IconInfo, IconX } from "./Icons";

// ------------------------------------------------------------------ formatting

export const fmt = {
  num: (n: number | null | undefined) => (n == null ? "—" : new Intl.NumberFormat("en-KE").format(n)),
  money: (n: number | null | undefined, cur = "KES") =>
    n == null ? "—" : `${cur} ${new Intl.NumberFormat("en-KE", { minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(n)}`,
  /** "4,825,300" -> "KES 4.83M" for tiles where the full figure would not fit. */
  moneyShort: (n: number | null | undefined, cur = "KES") =>
    n == null ? "—" : `${cur} ${new Intl.NumberFormat("en", { notation: "compact", maximumFractionDigits: 2 }).format(n)}`,
  dateTime: (iso: string | null | undefined) => {
    if (!iso) return "—";
    const d = new Date(iso);
    return `${dmy(d.getDate(), d.getMonth(), d.getFullYear())}, ${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`;
  },
  /** Calendar date ("1990-03-14" or a timestamp) -> "14 Mar 1990". */
  date: (iso: string | null | undefined) => {
    if (!iso) return "—";
    const d = iso.length === 10 ? new Date(`${iso}T00:00:00Z`) : new Date(iso);
    return iso.length === 10 ? dmy(d.getUTCDate(), d.getUTCMonth(), d.getUTCFullYear()) : dmy(d.getDate(), d.getMonth(), d.getFullYear());
  },
  plural: (n: number, word: string) => `${n} ${word}${n === 1 ? "" : "s"}`,
  ago: (iso: string | null | undefined) => {
    if (!iso) return "never";
    const s = Math.round((Date.now() - new Date(iso).getTime()) / 1000);
    if (s < 45) return "just now";
    if (s < 3600) return `${Math.round(s / 60)} min ago`;
    if (s < 86400) return `${Math.round(s / 3600)} h ago`;
    return `${Math.round(s / 86400)} d ago`;
  },
  words: (s: string | null | undefined) => (s ? s.replace(/_/g, " ").toLowerCase().replace(/^\w/, (c) => c.toUpperCase()) : "—"),
  bytes: (n: number | null | undefined) => (n == null ? "—" : n < 1024 ? `${n} B` : n < 1048576 ? `${(n / 1024).toFixed(1)} KB` : `${(n / 1048576).toFixed(1)} MB`),
  short: (id: string | null | undefined) => (id ? id.slice(0, 8).toUpperCase() : "—"),
};

const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
function dmy(d: number, m: number, y: number) {
  return `${String(d).padStart(2, "0")} ${MONTHS[m]} ${y}`;
}

// ------------------------------------------------------------------ status tones

export type Tone = "good" | "warn" | "bad" | "info" | "neutral" | "brand";

const TONES: Record<string, Tone> = {
  // sessions
  ACTIVE: "info", ENDED: "neutral", EXPIRED: "neutral",
  // applications
  CONSENTED: "info", IDENTITY_CAPTURED: "info", BIOMETRICS_VERIFIED: "info", KYC_CAPTURED: "info", PHONE_VERIFIED: "info",
  SIGNATURE_CAPTURED: "info", REFERRED: "warn", ACCOUNT_OPENED: "good", REJECTED: "bad", CANCELLED: "neutral",
  PENDING: "warn", CLEARED: "good", NONE: "neutral",
  // identity and biometrics
  MATCH: "good", NO_MATCH: "bad", PASS: "good", FAIL: "bad", NOT_FOUND: "bad", CLEAR: "good", HIT: "warn",
  // cards
  PERSONALISING: "info", PRINTING: "info", READY: "info", DONE: "good", DISPENSED: "good", TAKEN: "good", RETAINED: "bad", SPOILED: "bad", FAILED: "bad", ORDERED: "info",
  BLOCKED: "bad", EXPIRED_CARD: "neutral", TEMP_BLOCKED: "warn", PIN_LOCKED: "warn", INACTIVE_PIN_REQUIRED: "info",
  // accounts
  DORMANT: "warn", FROZEN: "bad", CLOSED: "neutral",
  // cash
  AWAITING_CASH: "info", AUTHORISED: "info", PENDING_RECONCILIATION: "warn", UNKNOWN: "warn", COUNTED: "info", CREDITED: "good", RETURNED: "neutral", COMPLETED: "good", PRESENTED: "info",
  RETRACTED: "warn", REVERSED: "warn", QUOTED: "info", DISPENSING: "info",
  // messaging and receipts
  QUEUED: "neutral", SENT: "good", DELIVERED: "good", PRINTED: "good",
  // calls
  RINGING: "warn", CONNECTED: "good", ABANDONED: "neutral", MISSED: "warn",
  // fleet
  OK: "good", IN_SERVICE: "good", LOCKED: "bad", WARNING: "warn", DEGRADED: "warn", OUT_OF_SERVICE: "bad", MAINTENANCE: "warn",
  PAPER_LOW: "warn", PAPER_OUT: "bad", JAMMED: "bad", OFFLINE: "bad", REVOKED: "bad", DISABLED: "bad",
};

export function toneOf(v: string | null | undefined): Tone {
  return (v && TONES[v]) || "neutral";
}

export function Badge({ value, tone, children }: { value?: string | null; tone?: Tone; children?: ReactNode }) {
  const t = tone ?? toneOf(value);
  return <span className={`badge badge--${t}`}><span className="badge__dot" />{children ?? fmt.words(value)}</span>;
}

// ------------------------------------------------------------------ layout pieces

export function Card({ title, subtitle, actions, children, className = "", pad = true }: {
  title?: ReactNode; subtitle?: ReactNode; actions?: ReactNode; children: ReactNode; className?: string; pad?: boolean;
}) {
  return (
    <section className={`card ${className}`}>
      {(title || actions) && (
        <header className="card__head">
          <div>
            {title && <h2 className="card__title">{title}</h2>}
            {subtitle && <p className="card__sub">{subtitle}</p>}
          </div>
          {actions && <div className="card__actions">{actions}</div>}
        </header>
      )}
      <div className={pad ? "card__body" : ""}>{children}</div>
    </section>
  );
}

export function Kpi({ label, value, hint, icon, tone = "brand", onClick }: {
  label: string; value: ReactNode; hint?: ReactNode; icon: ReactNode; tone?: Tone; onClick?: () => void;
}) {
  const Tag = onClick ? "button" : "div";
  return (
    <Tag className={`kpi kpi--${tone}`} onClick={onClick} type={onClick ? "button" : undefined}>
      <span className="kpi__icon">{icon}</span>
      <span className="kpi__label">{label}</span>
      <span className="kpi__value">{value}</span>
      {hint && <span className="kpi__hint">{hint}</span>}
    </Tag>
  );
}

export function Button({ kind = "primary", size = "md", icon, children, onClick, disabled, type = "button", title, form }: {
  kind?: "primary" | "secondary" | "ghost" | "danger" | "success"; size?: "sm" | "md" | "lg"; icon?: ReactNode;
  children?: ReactNode; onClick?: () => void; disabled?: boolean; type?: "button" | "submit"; title?: string;
  /** id of the form this button submits, when it sits outside it (a dialog's footer). */
  form?: string;
}) {
  return (
    <button type={type} form={form} className={`btn btn--${kind} btn--${size}`} onClick={onClick} disabled={disabled} title={title}>
      {icon}{children && <span>{children}</span>}
    </button>
  );
}

export function Empty({ icon, title, children }: { icon?: ReactNode; title: string; children?: ReactNode }) {
  return (
    <div className="empty">
      {icon && <div className="empty__icon">{icon}</div>}
      <div className="empty__title">{title}</div>
      {children && <div className="empty__text">{children}</div>}
    </div>
  );
}

export function Spinner({ label = "Loading…" }: { label?: string }) {
  return <div className="spinner-row"><span className="spinner" />{label}</div>;
}

export function ErrorNote({ error, onRetry }: { error: unknown; onRetry?: () => void }) {
  const msg = error instanceof Error ? error.message : String(error);
  return (
    <div className="error-note" role="alert">
      <span>{msg}</span>
      {onRetry && <Button kind="ghost" size="sm" onClick={onRetry}>Retry</Button>}
    </div>
  );
}

export function Drawer({ open, title, subtitle, onClose, children, footer }: {
  open: boolean; title: ReactNode; subtitle?: ReactNode; onClose: () => void; children: ReactNode; footer?: ReactNode;
}) {
  useEffect(() => {
    if (!open) return;
    const k = (e: KeyboardEvent) => { if (e.key === "Escape") onClose(); };
    window.addEventListener("keydown", k);
    return () => window.removeEventListener("keydown", k);
  }, [open, onClose]);
  if (!open) return null;
  return (
    <div className="drawer-wrap" onClick={onClose}>
      <aside className="drawer" role="dialog" aria-modal="true" onClick={(e) => e.stopPropagation()}>
        <header className="drawer__head">
          <div>
            <h2 className="drawer__title">{title}</h2>
            {subtitle && <div className="drawer__sub">{subtitle}</div>}
          </div>
          <button type="button" className="icon-btn" onClick={onClose} aria-label="Close"><IconX /></button>
        </header>
        <div className="drawer__body">{children}</div>
        {footer && <footer className="drawer__foot">{footer}</footer>}
      </aside>
    </div>
  );
}

/** A centred dialog. Escape and a click outside close it (not while busy). */
export function Modal({ open, title, icon, tone = "info", onClose, children, footer, size = "md", busy = false }: {
  open: boolean; title: ReactNode; icon?: ReactNode; tone?: "info" | "warn" | "danger" | "good"; onClose: () => void;
  children: ReactNode; footer?: ReactNode; size?: "sm" | "md" | "lg"; busy?: boolean;
}) {
  const box = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!open) return;
    const before = document.activeElement as HTMLElement | null;
    const k = (e: KeyboardEvent) => { if (e.key === "Escape" && !busy) onClose(); };
    window.addEventListener("keydown", k);
    // Focus the first field, or the main button, so Enter and typing work at once.
    const first = box.current?.querySelector<HTMLElement>("[data-autofocus], input:not([type=hidden]), select, textarea") ?? box.current?.querySelector<HTMLElement>(".modal__foot .btn:last-child");
    first?.focus();
    return () => { window.removeEventListener("keydown", k); before?.focus?.(); };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);
  if (!open) return null;
  return (
    <div className="modal-wrap" onMouseDown={(e) => { if (e.target === e.currentTarget && !busy) onClose(); }}>
      <div ref={box} className={`modal modal--${size}`} role="dialog" aria-modal="true">
        <header className="modal__head">
          {icon && <span className={`modal__icon modal__icon--${tone}`}>{icon}</span>}
          <h2 className="modal__title">{title}</h2>
          <button type="button" className="icon-btn" onClick={onClose} disabled={busy} aria-label="Close"><IconX /></button>
        </header>
        <div className="modal__body">{children}</div>
        {footer && <footer className="modal__foot">{footer}</footer>}
      </div>
    </div>
  );
}

export interface ConfirmOptions {
  title: string;
  /** What will happen, in plain words. */
  message: ReactNode;
  /** The button that goes ahead, e.g. "Lock kiosk". */
  confirmLabel: string;
  cancelLabel?: string;
  /** danger: red button for things that cannot be undone or that interrupt a customer. */
  tone?: "info" | "warn" | "danger";
}

/** The portal's replacement for the browser's confirm(): opened through usePortal().confirm(...). */
export function ConfirmDialog({ options, onAnswer }: { options: ConfirmOptions | null; onAnswer: (yes: boolean) => void }) {
  const tone = options?.tone ?? "info";
  return (
    <Modal open={!!options} size="sm" tone={tone} icon={tone === "info" ? <IconInfo /> : <IconAlert />} title={options?.title ?? ""}
           onClose={() => onAnswer(false)}
           footer={<>
             <Button kind="ghost" onClick={() => onAnswer(false)}>{options?.cancelLabel ?? "Cancel"}</Button>
             <Button kind={tone === "danger" ? "danger" : "primary"} onClick={() => onAnswer(true)}>{options?.confirmLabel}</Button>
           </>}>
      <div className="modal__text">{options?.message}</div>
    </Modal>
  );
}

export function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="field">
      <span className="field__label">{label}</span>
      <span className="field__value">{children}</span>
    </div>
  );
}

export function Segmented<T extends string>({ value, options, onChange }: {
  value: T; options: { value: T; label: string; count?: number }[]; onChange: (v: T) => void;
}) {
  return (
    <div className="seg" role="tablist">
      {options.map((o) => (
        <button key={o.value} type="button" role="tab" aria-selected={o.value === value}
                className={o.value === value ? "is-on" : ""} onClick={() => onChange(o.value)}>
          {o.label}{o.count != null && <span className="seg__count">{o.count}</span>}
        </button>
      ))}
    </div>
  );
}

export function Mono({ children, title }: { children: ReactNode; title?: string }) {
  return <code className="mono" title={title}>{children}</code>;
}

// ------------------------------------------------------------------ data loading

/** Loads data, re-loads on `deps` change and every `pollMs` (if set). Keeps the last good data while refreshing. */
export function useLoad<T>(load: () => Promise<T>, deps: unknown[], pollMs?: number) {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [loading, setLoading] = useState(true);
  const loadRef = useRef(load);
  loadRef.current = load;
  const [tick, setTick] = useState(0);

  useEffect(() => {
    let alive = true;
    const run = () => {
      setLoading(true);
      loadRef.current()
        .then((d) => { if (alive) { setData(d); setError(null); } })
        .catch((e) => { if (alive) setError(e); })
        .finally(() => { if (alive) setLoading(false); });
    };
    run();
    const id = pollMs ? setInterval(run, pollMs) : undefined;
    return () => { alive = false; if (id) clearInterval(id); };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...deps, tick, pollMs]);

  return { data, error, loading, reload: () => setTick((t) => t + 1), setData };
}

// ------------------------------------------------------------------ paging

/** A server page: items plus where they sit in the whole list. counts: totals per filter tab, when the list has tabs. */
export interface Page<T> {
  items: T[];
  total: number;
  page: number;
  size: number;
  pages: number;
  counts?: Record<string, number> | null;
}

const PAGE_SIZES = [25, 50, 100];

/**
 * Page and page size for one list. The page goes back to 1 when `resetOn` changes (a new filter or search).
 * The size is remembered per list in this browser.
 */
export function usePaging(key: string, resetOn: unknown[], defaultSize = 25) {
  const [page, setPage] = useState(1);
  const [size, setSizeState] = useState(() => {
    try { const v = Number(localStorage.getItem(`stm.pageSize.${key}`)); return PAGE_SIZES.includes(v) ? v : defaultSize; } catch { return defaultSize; }
  });
  const first = useRef(true);
  useEffect(() => {
    if (first.current) { first.current = false; return; }
    setPage(1);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, resetOn);
  const setSize = (n: number) => {
    setSizeState(n); setPage(1);
    try { localStorage.setItem(`stm.pageSize.${key}`, String(n)); } catch { /* per-browser convenience only */ }
  };
  return { page, size, setPage, setSize };
}

/** The text after it stops changing for `ms` (for search boxes that query the server). */
/** One page of a list that is already in the browser, in the shape Pager takes. */
export function pageOf<T>(all: T[], page: number, size: number): Page<T> {
  const pages = Math.max(1, Math.ceil(all.length / size));
  const p = Math.min(Math.max(1, page), pages);
  return { items: all.slice((p - 1) * size, p * size), total: all.length, page: p, size, pages };
}

/** Paging for a list the browser already holds (a few hundred rows at most): same Pager, remembered page size. */
export function useClientPage<T>(all: T[] | null | undefined, key: string, resetOn: unknown[] = [], defaultSize = 25) {
  const p = usePaging(key, resetOn, defaultSize);
  const data = all ? pageOf(all, p.page, p.size) : null;
  return { data, rows: data?.items ?? [], setPage: p.setPage, setSize: p.setSize };
}

export function useDebounced<T>(value: T, ms = 350) {
  const [v, setV] = useState(value);
  useEffect(() => { const t = setTimeout(() => setV(value), ms); return () => clearTimeout(t); }, [value, ms]);
  return v;
}

/** "Showing 26–50 of 1,240" with page size and Previous / Next. Hidden when there is nothing to page. */
export function Pager({ data, onPage, onSize, compact }: {
  data: Page<unknown> | null | undefined; onPage: (p: number) => void; onSize?: (n: number) => void; compact?: boolean;
}) {
  if (!data || data.total === 0) return null;
  const from = (data.page - 1) * data.size + 1;
  const to = Math.min(data.total, data.page * data.size);
  const n = (x: number) => x.toLocaleString("en-KE");
  return (
    <div className={`pager ${compact ? "pager--compact" : ""}`}>
      <span className="pager__info">{compact ? `${n(from)}–${n(to)} of ${n(data.total)}` : <>Showing <b>{n(from)}–{n(to)}</b> of <b>{n(data.total)}</b></>}</span>
      {onSize && !compact && (
        <label className="pager__size">Rows
          <select className="select select--sm" value={data.size} onChange={(e) => onSize(Number(e.target.value))}>
            {PAGE_SIZES.map((x) => <option key={x} value={x}>{x}</option>)}
          </select>
        </label>
      )}
      <span className="pager__nav">
        <button type="button" className="btn btn--secondary btn--sm" disabled={data.page <= 1} onClick={() => onPage(1)} aria-label="First page" title="First page">«</button>
        <button type="button" className="btn btn--secondary btn--sm" disabled={data.page <= 1} onClick={() => onPage(data.page - 1)}>Previous</button>
        <span className="pager__page">Page {n(data.page)} of {n(data.pages)}</span>
        <button type="button" className="btn btn--secondary btn--sm" disabled={data.page >= data.pages} onClick={() => onPage(data.page + 1)}>Next</button>
        <button type="button" className="btn btn--secondary btn--sm" disabled={data.page >= data.pages} onClick={() => onPage(data.pages)} aria-label="Last page" title="Last page">»</button>
      </span>
    </div>
  );
}

/**
 * A small actions menu. The list is fixed-positioned under (or above) its button so the table's scroll box cannot clip it.
 * Closes on a choice, an outside click, Escape, or scrolling.
 */
export function RowMenu({ label, children }: { label: string; children: ReactNode }) {
  const [pos, setPos] = useState<{ top?: number; bottom?: number; right: number } | null>(null);
  const btn = useRef<HTMLButtonElement>(null);
  useEffect(() => {
    if (!pos) return;
    const close = (e: Event) => { if (e.type !== "mousedown" || !(e.target as Element).closest?.(".menu__list, .menu__btn")) setPos(null); };
    const esc = (e: KeyboardEvent) => { if (e.key === "Escape") setPos(null); };
    window.addEventListener("mousedown", close); window.addEventListener("scroll", close, true); window.addEventListener("resize", close);
    window.addEventListener("keydown", esc);
    return () => { window.removeEventListener("mousedown", close); window.removeEventListener("scroll", close, true);
      window.removeEventListener("resize", close); window.removeEventListener("keydown", esc); };
  }, [pos]);
  function toggle() {
    if (pos) return setPos(null);
    const r = btn.current!.getBoundingClientRect();
    const right = window.innerWidth - r.right;
    setPos(window.innerHeight - r.bottom < 260 ? { bottom: window.innerHeight - r.top + 4, right } : { top: r.bottom + 4, right });
  }
  return (
    <>
      <button ref={btn} type="button" className="btn btn--ghost btn--sm menu__btn" aria-haspopup="menu" aria-expanded={!!pos} onClick={toggle}>{label}</button>
      {pos && <div className="menu__list" role="menu" style={{ position: "fixed", ...pos }} onClick={() => setPos(null)}>{children}</div>}
    </>
  );
}
