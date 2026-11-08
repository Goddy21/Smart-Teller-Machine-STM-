import { useState, type FormEvent, type ReactNode } from "react";
import { authApi, type Session, type User } from "../auth";
import type { ApiError } from "../api/http";
import logo from "../assets/blueriver-logo.png";
import mark from "../assets/blueriver-mark.png";

/** STM portal sign-in. Accounts are created by a portal administrator (Users page). */
export function Login({ onSignedIn, notice }: { onSignedIn: (s: Session) => void; notice?: string }) {
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [show, setShow] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (!username.trim() || !password) return setError("Enter your username and password.");
    setBusy(true);
    setError("");
    try {
      const r = await authApi.login(username.trim(), password);
      onSignedIn({ token: r.token, user: r.user });
    } catch (err) {
      const e = err as ApiError;
      setError(e.code === "SERVICE_DOWN" ? "The STM middleware is not running."
        : e.code === "FORBIDDEN" ? "The middleware refused the request before checking your password (403). This portal's address is probably not in stm.cors-allowed-origins."
        : e.message);
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
          <input value={username} onChange={(e) => setUsername(e.target.value)} autoComplete="username" autoFocus spellCheck={false} />
        </label>
        <label className="auth__field">
          <span>Password</span>
          <span className="auth__pw">
            <input type={show ? "text" : "password"} value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="current-password" />
            <button type="button" onClick={() => setShow(!show)} aria-label={show ? "Hide password" : "Show password"}>{show ? "Hide" : "Show"}</button>
          </span>
        </label>
        {error && <div className="auth__error" role="alert">{error}</div>}
        <button type="submit" className="auth__submit" disabled={busy}>{busy ? "Signing in…" : "Sign in"}</button>
        <p className="auth__help">Forgot your password or locked out? Ask a portal administrator to reset it.</p>
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
        <button type="submit" className="auth__submit" disabled={busy}>{busy ? "Saving…" : "Save password"}</button>
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
