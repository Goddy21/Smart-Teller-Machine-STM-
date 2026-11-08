import { useState } from "react";
import type { PassportResult } from "../../stm";
import type { Strings } from "../i18n";
import { Btn, Panel } from "../Layout";
import { nationality, passportDate } from "../api";

const PREFIX = "+254";

/** Journey A step 2: show what was read from the passport, collect mobile, email and consent. */
export function DetailsScreen({ t, locale, pill, progress, passport, serverError, onConfirm, onBack }: {
  t: Strings;
  locale: string;
  pill: string;
  progress: { n: number; total: number; label: string };
  /** null after an app reload: the passport data is kept in memory only, never in browser storage. */
  passport: PassportResult | null;
  /** Inline error from the middleware (INVALID_MOBILE, INVALID_EMAIL, CONSENT_REQUIRED). */
  serverError: string | null;
  onConfirm: (mobile: string, email: string, consent: boolean) => void;
  onBack: () => void;
}) {
  const [mobile, setMobile] = useState(PREFIX);
  const [email, setEmail] = useState("");
  const [consent, setConsent] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const press = (key: string) => {
    setError(null);
    if (key === "C") return setMobile(PREFIX);
    if (key === "⌫") return setMobile((m) => (m.length > 1 ? m.slice(0, -1) : m));
    setMobile((m) => (m.length < 16 ? m + key : m));
  };

  const submit = () => {
    if (!/^\+\d{9,15}$/.test(mobile)) return setError(t.mobileInvalid);
    if (email && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) return setError(t.emailInvalid);
    if (!consent) return setError(t.consentNeeded);
    onConfirm(mobile, email.trim(), consent);
  };

  const shown = error ?? serverError;

  return (
    <Panel pill={pill} progress={progress} title={t.confirmDetails} subtitle={t.confirmDetailsSub}
           actions={<>
             <Btn onClick={submit}>{t.confirmProceed}</Btn>
             <Btn kind="danger" onClick={onBack}>{t.back}</Btn>
           </>}>
      <div className="details-top">
        <div className="photo">{t.passportPhoto}</div>
        <div className="card facts">
          {passport ? (
            <>
              <Fact label={t.fullName} value={passport.fullName} />
              <Fact label={t.dob} value={passportDate(passport.dateOfBirth, locale)} />
              <Fact label={t.passportNat} value={`${passport.documentNumber} · ${nationality(passport.nationalityCode)}`} />
              <Fact label={t.expiry} value={passportDate(passport.expiryDate, locale)} />
            </>
          ) : (
            <p className="facts__note">{t.passportNotOnScreen}</p>
          )}
        </div>
      </div>

      <div className="fields">
        <label className="field">
          <span>{t.mobile}</span>
          <input value={mobile} readOnly inputMode="none" aria-label={t.mobile} />
        </label>
        <label className="field">
          <span>{t.email}</span>
          <input type="email" value={email} placeholder={t.emailPlaceholder} autoComplete="off"
                 onChange={(e) => { setEmail(e.target.value); setError(null); }} />
        </label>
      </div>

      <div className="keypad">
        {["1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "⌫", "C"].map((k) => (
          <button key={k} type="button" onClick={() => press(k)} aria-label={k === "⌫" ? "Delete" : k === "C" ? "Clear" : k}>
            {k}
          </button>
        ))}
      </div>

      <label className="consent">
        <input type="checkbox" checked={consent} onChange={(e) => { setConsent(e.target.checked); setError(null); }} />
        <span>{t.consent}</span>
      </label>

      {shown && <p className="inline-error" role="alert">{shown}</p>}
    </Panel>
  );
}

function Fact({ label, value }: { label: string; value: string }) {
  return (
    <div className="fact">
      <span className="fact__label">{label}</span>
      <span className="fact__value">{value}</span>
    </div>
  );
}
