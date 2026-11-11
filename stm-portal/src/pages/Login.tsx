import { useEffect, useState, type FormEvent, type ReactNode } from "react";
import { authApi, type LoginResult, type Session, type User } from "../auth";
import type { ApiError } from "../api/http";
import logo from "../assets/blueriver-logo.png";
import mark from "../assets/blueriver-mark.png";

/** Error text for sign-in failures that are about the connection rather than the person. */
function signInError(err: unknown) {
  const e = err as ApiError;
  return e.code === "SERVICE_DOWN" ? "The STM middleware is not running."
    : e.code === "FORBIDDEN" ? "The middleware refused the request before checking your password (403). This portal's address is probably not in stm.cors-allowed-origins."
    : e.message;
}

/**
 * STM portal sign-in. Accounts are created by a portal administrator (Users page).
 * Accounts with an email address get a second step: a 6-digit code sent by email.
 */
export function Login({ onSignedIn, notice }: { onSignedIn: (s: Session) => void; notice?: string }) {
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [show, setShow] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [otp, setOtp] = useState<LoginResult | null>(null);

  function finish(r: LoginResult) {
    if (r.otpRequired && r.challenge) { setOtp(r); return; }
    if (r.token && r.user) onSignedIn({ token: r.token, user: r.user });
  }

  if (otp?.challenge) {
    return <CodeStep first={otp} onSignedIn={finish} onBack={() => { setOtp(null); setPassword(""); }} />;
  }

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (!username.trim() || !password) return setError("Enter your username and password.");
    setBusy(true);
    setError("");
    try {
      finish(await authApi.login(username.trim(), password));
    } catch (err) {
      setError(signInError(err));
      setPassword("");
    } finally {
      setBusy(false);
    }
  }

  return (
    <AuthShell>
      <form className="auth__form" onSubmit={submit}>
        <h1>Sign in</h1>
        <p className="auth__lead">Use your STM portal account.</p>
        {notice && <div className="auth__notice">{notice}</div>}
        <label className="auth__field">
          <span>Username</span>
          <input value={username} onChange={(e) => setUsername(e.target.value)} autoComplete="username" autoFocus spellCheck={false} readOnly={busy} />
        </label>
        <label className="auth__field">
          <span>Password</span>
          <span className="auth__pw">
            <input type={show ? "text" : "password"} value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="current-password" readOnly={busy} />
            <button type="button" onClick={() => setShow(!show)} aria-label={show ? "Hide password" : "Show password"}>{show ? "Hide" : "Show"}</button>
          </span>
        </label>
        {error && <div className="auth__error" role="alert">{error}</div>}
        <button type="submit" className={`auth__submit ${busy ? "is-busy" : ""}`} disabled={busy} aria-busy={busy}>
          {busy ? <><span className="auth__spin" aria-hidden="true" />Signing in…</> : "Sign in"}
        </button>
        {busy && <SlowHint />}
        <p className="auth__help">Forgot your password or locked out? Ask a portal administrator to reset it.</p>
      </form>
    </AuthShell>
  );
}

/** After a second, explain the wait: the server may be emailing a sign-in code. */
function SlowHint() {
  const [show, setShow] = useState(false);
  useEffect(() => { const t = setTimeout(() => setShow(true), 900); return () => clearTimeout(t); }, []);
  return show ? <p className="auth__wait" role="status">Checking your password and sending your sign-in code. This can take a few seconds…</p> : null;
}

/** Second step: the code from the email. "Send a new code" unlocks after resendAfterSec. */
function CodeStep({ first, onSignedIn, onBack }: { first: LoginResult; onSignedIn: (r: LoginResult) => void; onBack: () => void }) {
  const [challenge, setChallenge] = useState(first);
  const [code, setCode] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [info, setInfo] = useState("");
  const [resending, setResending] = useState(false);
  const [sentAt, setSentAt] = useState(() => Date.now());
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => { const id = setInterval(() => setNow(Date.now()), 1000); return () => clearInterval(id); }, []);

  const wait = Math.max(0, Math.ceil(((challenge.resendAfterSec ?? 60) * 1000 - (now - sentAt)) / 1000));
  const left = Math.max(0, Math.ceil(((challenge.expiresInSec ?? 300) * 1000 - (now - sentAt)) / 1000));

  async function submit(e: FormEvent) {
    e.preventDefault();
    const c = code.replace(/\s/g, "");
    if (!/^\d{6}$/.test(c)) return setError("Enter the 6-digit code from the email.");
    setBusy(true); setError(""); setInfo("");
    try {
      onSignedIn(await authApi.verifyOtp(challenge.challenge!, c));
    } catch (err) {
      const code = (err as ApiError).code;
      setError(signInError(err));
      setCode("");
      if (code === "OTP_EXPIRED" || code === "OTP_TRIES_EXCEEDED") setTimeout(onBack, 2500);
    } finally {
      setBusy(false);
    }
  }

  async function resend() {
    setBusy(true); setResending(true); setError(""); setInfo("");
    try {
      const r = await authApi.resendOtp(challenge.challenge!);
      setChallenge(r); setSentAt(Date.now()); setCode("");
      setInfo(`A new code was sent to ${r.maskedEmail}. The old one no longer works.`);
    } catch (err) {
      setError(signInError(err));
    } finally {
      setBusy(false); setResending(false);
    }
  }

  return (
    <AuthShell>
      <form className="auth__form" onSubmit={submit}>
        <h1>Check your email</h1>
        <p className="auth__lead">We sent a 6-digit sign-in code to <b>{challenge.maskedEmail}</b>.</p>
        <label className="auth__field">
          <span>Sign-in code</span>
          <input className="auth__code" value={code} onChange={(e) => setCode(e.target.value.replace(/[^\d\s]/g, "").slice(0, 7))}
                 inputMode="numeric" autoComplete="one-time-code" autoFocus placeholder="••••••" aria-describedby="code-help" />
        </label>
        <p id="code-help" className="auth__help auth__help--left">
          {left > 0 ? `The code expires in ${Math.floor(left / 60)}:${String(left % 60).padStart(2, "0")}.` : "The code has expired. Ask for a new one."}
          {" "}Not in your inbox? Check the spam folder.
        </p>
        {info && <div className="auth__notice" role="status">{info}</div>}
        {error && <div className="auth__error" role="alert">{error}</div>}
        <button type="submit" className={`auth__submit ${busy ? "is-busy" : ""}`} disabled={busy || left === 0} aria-busy={busy}>
          {busy ? <><span className="auth__spin" aria-hidden="true" />{resending ? "Sending a new code…" : "Checking the code…"}</> : "Sign in"}
        </button>
        <div className="auth__row">
          <button type="button" className="auth__link" onClick={onBack}>Use another account</button>
          <button type="button" className="auth__link" onClick={resend} disabled={busy || wait > 0}>
            {wait > 0 ? `Send a new code (${wait}s)` : "Send a new code"}
          </button>
        </div>
      </form>
    </AuthShell>
  );
}

/** First sign-in (or after a reset): the middleware refuses every portal call until the password is changed. */
export function ChangePassword({ token, user, onDone, onCancel, forced }: {
  token: string; user: User; onDone: (u: User) => void; onCancel: () => void; forced: boolean;
}) {
  const [current, setCurrent] = useState("");
  const [next, setNext] = useState("");
  const [again, setAgain] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  const rules = [
    { ok: next.length >= 10, text: "At least 10 characters" },
    { ok: /[A-Za-z]/.test(next) && /\d/.test(next), text: "Letters and at least one number" },
    { ok: next.length > 0 && !next.toLowerCase().includes(user.username.toLowerCase()), text: "Does not contain your username" },
    { ok: next.length > 0 && next === again, text: "Both new passwords match" },
  ];

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (!rules.every((r) => r.ok)) return setError("The new password does not meet the rules yet.");
    setBusy(true);
    setError("");
    try {
      onDone(await authApi.changePassword(token, current, next));
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <AuthShell>
      <form className="auth__form" onSubmit={submit}>
        <h1>{forced ? "Choose your password" : "Change password"}</h1>
        <p className="auth__lead">
          {forced ? `Welcome, ${user.displayName}. Replace the temporary password you were given.` : "Other devices signed in as you will be signed out."}
        </p>
        <label className="auth__field"><span>{forced ? "Temporary password" : "Current password"}</span>
          <input type="password" value={current} onChange={(e) => setCurrent(e.target.value)} autoComplete="current-password" autoFocus /></label>
        <label className="auth__field"><span>New password</span>
          <input type="password" value={next} onChange={(e) => setNext(e.target.value)} autoComplete="new-password" /></label>
        <label className="auth__field"><span>New password again</span>
          <input type="password" value={again} onChange={(e) => setAgain(e.target.value)} autoComplete="new-password" /></label>
        <ul className="auth__rules">
          {rules.map((r) => <li key={r.text} className={r.ok ? "is-ok" : ""}><span>{r.ok ? "✓" : "•"}</span>{r.text}</li>)}
        </ul>
        {error && <div className="auth__error" role="alert">{error}</div>}
        <button type="submit" className={`auth__submit ${busy ? "is-busy" : ""}`} disabled={busy} aria-busy={busy}>
          {busy ? <><span className="auth__spin" aria-hidden="true" />Saving…</> : "Save password"}
        </button>
        <button type="button" className="auth__link" onClick={onCancel}>{forced ? "Sign out" : "Cancel"}</button>
      </form>
    </AuthShell>
  );
}

function AuthShell({ children }: { children: ReactNode }) {
  return (
    <div className="auth">
      <div className="auth__glow auth__glow--red" />
      <div className="auth__glow auth__glow--blue" />
      <img className="auth__mark" src={mark} alt="" />
      <main className="auth__card">
        <div className="auth__stripe" />
        <header className="auth__brand">
          <span className="plate"><img src={logo} alt="Blue River Technology Solutions" /></span>
          <div>
            <b>STM Portal</b>
            <small>Smart Teller Machine operations</small>
          </div>
        </header>
        {children}
      </main>
      <p className="auth__foot">For authorised bank staff. Sign-ins and actions are recorded in the audit journal.</p>
    </div>
  );
}
