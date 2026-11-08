import { useEffect, useRef, useState, type ReactNode } from "react";
import type { Language } from "../stm";
import { LOCALE, STRINGS } from "./i18n";
import logo from "../assets/blueriver-logo.png";

const LANGS: Language[] = ["en", "sw", "fr"];

function useClock() {
  const [now, setNow] = useState(() => new Date());
  useEffect(() => {
    const id = setInterval(() => setNow(new Date()), 10_000);
    return () => clearInterval(id);
  }, []);
  return now;
}

interface FrameProps {
  lang: Language;
  onLang: (l: Language) => void;
  onHelp: () => void;
  /** "idle" = full-bleed gradient attract screen with a transparent header. */
  variant?: "light" | "idle";
  children: ReactNode;
}

/** Header (date, logo, language, help) plus the page body. Every screen sits inside this. */
export function Frame({ lang, onLang, onHelp, variant = "light", children }: FrameProps) {
  const t = STRINGS[lang];
  const now = useClock();
  const locale = LOCALE[lang];
  const date = now.toLocaleDateString(locale, { weekday: "short", month: "short", day: "numeric", year: "numeric" });
  const time = now.toLocaleTimeString(locale, { hour: "2-digit", minute: "2-digit", hour12: false });

  return (
    <div className={`frame frame--${variant}`}>
      <header className="header">
        <div className="clock">{date} <span className="clock__bar">|</span> {time}</div>
        <img className="logo" src={logo} alt="Blue River Technology Solutions" />
        <div className="header__actions">
          <LanguagePicker lang={lang} onLang={onLang} />
          <button type="button" className="hbtn hbtn--help" onClick={(e) => { e.stopPropagation(); onHelp(); }}>
            <HelpIcon /> {t.needHelp}
          </button>
        </div>
      </header>
      <main className="body">{children}</main>
    </div>
  );
}

function LanguagePicker({ lang, onLang }: { lang: Language; onLang: (l: Language) => void }) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!open) return;
    const close = (e: PointerEvent) => { if (!ref.current?.contains(e.target as Node)) setOpen(false); };
    window.addEventListener("pointerdown", close);
    return () => window.removeEventListener("pointerdown", close);
  }, [open]);

  return (
    <div className="lang" ref={ref} onClick={(e) => e.stopPropagation()}>
      <button type="button" className="hbtn" onClick={() => setOpen(!open)} aria-expanded={open}>
        <GlobeIcon /> {STRINGS[lang].langName} <span className="caret">▾</span>
      </button>
      {open && (
        <ul className="lang__menu" role="listbox">
          {LANGS.map((l) => (
            <li key={l}>
              <button type="button" className={l === lang ? "is-active" : ""}
                      onClick={() => { onLang(l); setOpen(false); }}>
                {STRINGS[l].langName}
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

interface PanelProps {
  pill: string;
  progress?: { n: number; total: number; label: string };
  title: string;
  subtitle?: ReactNode;
  /** Buttons pinned to the bottom of the left column. */
  actions?: ReactNode;
  /** Right-hand content. */
  children?: ReactNode;
}

/** The two-column layout used by every screen after the idle screen. */
export function Panel({ pill, progress, title, subtitle, actions, children }: PanelProps) {
  return (
    <div className="panel">
      <section className="panel__left">
        <div className="pill">{pill}</div>
        {progress && (
          <>
            <div className="progress" aria-hidden="true">
              {Array.from({ length: progress.total }, (_, i) => (
                <span key={i} className={i < progress.n ? "is-done" : ""} />
              ))}
            </div>
            <div className="progress__label">{progress.label}</div>
          </>
        )}
        <h1 className="title">{title}</h1>
        {subtitle && <p className="subtitle">{subtitle}</p>}
        <div className="panel__actions">{actions}</div>
      </section>
      <section className="panel__right">{children}</section>
    </div>
  );
}

export function Btn({ kind = "primary", onClick, disabled, children }: {
  kind?: "primary" | "secondary" | "danger";
  onClick?: () => void;
  disabled?: boolean;
  children: ReactNode;
}) {
  return (
    <button type="button" className={`btn btn--${kind}`} onClick={onClick} disabled={disabled}>{children}</button>
  );
}

export function Banner({ children }: { children: ReactNode }) {
  return <div className="banner"><WarnIcon /> <span>{children}</span></div>;
}

/** Passport on the scanner glass, from the designs. */
export function ScannerArt({ label }: { label: string }) {
  return (
    <svg className="scanner-art" viewBox="0 0 500 220" role="img" aria-label={label}>
      <path d="M62 30 h402 l-22 130 H40 Z" fill="#dde6f2" stroke="#1b1f7a" strokeWidth="5" strokeLinejoin="round" />
      <path d="M244 30 L176 160" stroke="#1b1f7a" strokeWidth="5" />
      <rect x="78" y="62" width="70" height="70" rx="8" fill="#91a5c2" transform="skewX(-8)" />
      <rect x="2" y="140" width="496" height="78" rx="12" fill="#1b1f7a" />
      <text x="250" y="187" textAnchor="middle" fill="#fff" fontSize="22" fontFamily="inherit">{label}</text>
    </svg>
  );
}

// ---------------------------------------------------------------------------------------------------- icons

const icon = { width: "1em", height: "1em", viewBox: "0 0 24 24", fill: "none", stroke: "currentColor", strokeWidth: 2,
  strokeLinecap: "round" as const, strokeLinejoin: "round" as const, "aria-hidden": true };

export const GlobeIcon = () => (
  <svg {...icon}><circle cx="12" cy="12" r="10" /><path d="M2 12h20M12 2a15 15 0 0 1 0 20M12 2a15 15 0 0 0 0 20" /></svg>
);
export const HelpIcon = () => (
  <svg {...icon}><circle cx="12" cy="12" r="10" /><path d="M9.1 9a3 3 0 0 1 5.8 1c0 2-3 3-3 3M12 17h.01" /></svg>
);
export const WarnIcon = () => (
  <svg {...icon}><path d="M10.3 3.9 1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0zM12 9v4M12 17h.01" /></svg>
);
export const PlusBoxIcon = () => (
  <svg viewBox="0 0 64 52" aria-hidden="true"><rect x="3" y="3" width="58" height="46" rx="7" fill="none" stroke="currentColor" strokeWidth="6" /><path d="M32 14v24M20 26h24" stroke="currentColor" strokeWidth="6" strokeLinecap="round" /></svg>
);
export const CardIcon = () => (
  <svg viewBox="0 0 64 46" aria-hidden="true"><rect x="3" y="3" width="58" height="40" rx="6" fill="none" stroke="currentColor" strokeWidth="6" /><path d="M3 16h58" stroke="currentColor" strokeWidth="6" /></svg>
);
export const AlertCircleIcon = () => (
  <svg viewBox="0 0 100 100" aria-hidden="true"><circle cx="50" cy="50" r="44" fill="none" stroke="currentColor" strokeWidth="9" /><path d="M50 26v32" stroke="currentColor" strokeWidth="9" strokeLinecap="round" /><circle cx="50" cy="72" r="5.5" fill="currentColor" /></svg>
);
