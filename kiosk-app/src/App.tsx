import { useCallback, useEffect, useRef, useState, type ReactNode } from "react";
import {
  errorScreen, StmApiError,
  type JourneyType, type KioskStatus, type Language, type PassportResult, type SessionView,
} from "./stm";
import { IDLE_MS, STATUS_POLL_MS, stm } from "./kiosk/api";
import { LOCALE, STRINGS, type Strings } from "./kiosk/i18n";
import { Frame } from "./kiosk/Layout";
import type { MrzRead } from "./kiosk/scanner";
import { IdleScreen, MenuScreen, OutOfServiceScreen, TimeoutScreen } from "./kiosk/screens/StartScreens";
import { PassportScreen } from "./kiosk/screens/PassportScreen";
import { DetailsScreen } from "./kiosk/screens/DetailsScreen";
import {
  ErrorAScreen, HelpDialog, NextScreenPlaceholder, ProblemScreen, type ProblemKind,
} from "./kiosk/screens/ErrorScreens";

type View = "idle" | "menu" | "session";
type Failure =
  | { kind: "A"; code: string }
  | { kind: "PROBLEM"; problem: ProblemKind; errorRef?: string; retry?: () => void };

/** Only the session id is kept in browser storage, so a reload can resume. Customer data stays in memory. */
const SESSION_KEY = "stm.sessionId";
const store = {
  get: () => { try { return localStorage.getItem(SESSION_KEY); } catch { return null; } },
  set: (id: string) => { try { localStorage.setItem(SESSION_KEY, id); } catch { /* storage unavailable */ } },
  clear: () => { try { localStorage.removeItem(SESSION_KEY); } catch { /* storage unavailable */ } },
};

/** Middleware codes shown next to the form on the details screen instead of on an error screen. */
const INLINE_CODES = new Set(["INVALID_MOBILE", "INVALID_EMAIL", "CONSENT_REQUIRED", "VALIDATION_ERROR"]);

export default function App() {
  const [lang, setLang] = useState<Language>("en");
  const t: Strings = STRINGS[lang];

  const [status, setStatus] = useState<KioskStatus | null>(null);
  const [reachable, setReachable] = useState(true);
  const [view, setView] = useState<View>("idle");
  const [session, setSession] = useState<SessionView | null>(null);
  const [passport, setPassport] = useState<PassportResult | null>(null);
  const [failure, setFailure] = useState<Failure | null>(null);
  const [inlineError, setInlineError] = useState<string | null>(null);
  const [timedOut, setTimedOut] = useState(false);
  const [help, setHelp] = useState(false);
  const [busy, setBusy] = useState(false);

  const sessionRef = useRef(session);
  sessionRef.current = session;

  // ------------------------------------------------------------------ machine status (idle / menu)
  useEffect(() => {
    let alive = true;
    const poll = () => stm.status()
      .then((s) => { if (alive) { setStatus(s); setReachable(true); } })
      .catch(() => { if (alive) setReachable(false); });
    poll();
    const id = setInterval(poll, STATUS_POLL_MS);
    return () => { alive = false; clearInterval(id); };
  }, []);

  // ------------------------------------------------------------------ resume after a reload
  useEffect(() => {
    const id = store.get();
    if (!id) return;
    stm.getSession(id)
      .then((s) => {
        if (s.status === "IN_PROGRESS") { setSession(s); setLang(s.language); setView("session"); }
        else store.clear();
      })
      .catch(() => store.clear());
  }, []);

  // ------------------------------------------------------------------ session end / reset
  const reset = useCallback(() => {
    store.clear();
    setSession(null); setPassport(null); setFailure(null); setInlineError(null);
    setTimedOut(false); setHelp(false); setView("idle"); setLang("en");
  }, []);

  /** Cancel / End Session / timeout: tell the middleware, then wipe the screen. */
  const endSession = useCallback(() => {
    const id = sessionRef.current?.id;
    reset();
    if (id) stm.cancel(id).catch(() => undefined); // already ended is fine
  }, [reset]);

  // ------------------------------------------------------------------ inactivity -> "Are you still there?"
  useEffect(() => {
    if (view === "idle" || timedOut) return;
    let timer = setTimeout(() => setTimedOut(true), IDLE_MS);
    const bump = () => { clearTimeout(timer); timer = setTimeout(() => setTimedOut(true), IDLE_MS); };
    window.addEventListener("pointerdown", bump);
    window.addEventListener("keydown", bump);
    return () => {
      clearTimeout(timer);
      window.removeEventListener("pointerdown", bump);
      window.removeEventListener("keydown", bump);
    };
  }, [view, timedOut]);

  // ------------------------------------------------------------------ calling the middleware

  function fail(e: unknown, retry?: () => void) {
    const code = e instanceof StmApiError ? e.code : "";
    switch (errorScreen(e)) {
      case "A": return setFailure({ kind: "A", code });
      case "OUT_OF_SERVICE": return reset();
      case "SESSION_ENDED": return setFailure({ kind: "PROBLEM", problem: "SESSION_ENDED" });
      case "RETRY": return setFailure({ kind: "PROBLEM", problem: "RETRY", retry });
    }
    if (code === "UNABLE_TO_PROCEED") return setFailure({ kind: "PROBLEM", problem: "CANNOT_PROCEED" });
    if (code === "CUSTOMER_ALREADY_EXISTS") return setFailure({ kind: "PROBLEM", problem: "ALREADY_CUSTOMER" });
    console.error(e);
    setFailure({ kind: "PROBLEM", problem: "GENERIC", retry });
  }

  /** Runs one middleware call with a busy overlay (blocks double taps) and routes failures to the right screen. */
  async function run(fn: () => Promise<void>, opts: { retry?: () => void; inline?: boolean; quiet?: boolean } = {}) {
    if (!opts.quiet) setBusy(true);
    try {
      await fn();
    } catch (e) {
      if (opts.inline && e instanceof StmApiError && INLINE_CODES.has(e.code)) {
        setInlineError(inlineText(e.code, e.message));
      } else {
        fail(e, opts.retry);
      }
    } finally {
      setBusy(false);
    }
  }

  function inlineText(code: string, fallback: string) {
    if (code === "INVALID_MOBILE") return t.mobileInvalid;
    if (code === "INVALID_EMAIL") return t.emailInvalid;
    if (code === "CONSENT_REQUIRED") return t.consentNeeded;
    return fallback;
  }

  /** Every session response goes through here: the server's step decides the next screen. */
  function apply(s: SessionView) {
    if (s.status !== "IN_PROGRESS") return reset();
    store.set(s.id);
    setSession(s);
    setView("session");
  }

  const start = (journey: JourneyType) =>
    run(async () => apply(await stm.createSession({ journey, language: lang })), { retry: () => start(journey) });

  const refresh = () => {
    const id = sessionRef.current?.id;
    if (id) run(async () => apply(await stm.getSession(id)));
  };

  const submitPassport = (mrz: MrzRead) =>
    run(async () => {
      const id = sessionRef.current!.id;
      setPassport(await stm.submitPassport(id, mrz));
      apply(await stm.getSession(id));
    }, { quiet: true });

  const confirmDetails = (mobile: string, email: string, consentAccepted: boolean) =>
    run(async () => {
      setInlineError(null);
      apply(await stm.confirmDetails(session!.id, { mobile, email: email || undefined, consentAccepted }));
    }, { inline: true });

  const goBack = () => run(async () => apply(await stm.back(session!.id)));

  const stillHere = () => {
    setTimedOut(false);
    const id = sessionRef.current?.id;
    if (id) stm.keepAlive(id).catch((e) => fail(e));
  };

  // ------------------------------------------------------------------ which screen

  const outOfService = view !== "session" && (!reachable || status?.inService === false);
  const pill = session?.journey === "DEBIT_CARD" ? t.journeyB : t.journeyA;
  const progress = session
    ? { n: session.stepNumber, total: session.totalSteps, label: t.stepOf(session.stepNumber, session.totalSteps) }
    : { n: 0, total: 0, label: "" };

  let screen: ReactNode;
  if (outOfService) {
    screen = <OutOfServiceScreen t={t} status={status} />;
  } else if (timedOut) {
    screen = <TimeoutScreen t={t} onStillHere={stillHere} onEnd={endSession} />;
  } else if (failure?.kind === "A") {
    screen = <ErrorAScreen t={t} code={failure.code} onTryAgain={() => setFailure(null)} onSupport={() => setHelp(true)} />;
  } else if (failure) {
    screen = (
      <ProblemScreen t={t} kind={failure.problem} errorRef={failure.errorRef ?? session?.errorRef ?? undefined}
                     onRetry={() => { setFailure(null); if (failure.retry) failure.retry(); else refresh(); }}
                     onDebitCard={() => { endSession(); start("DEBIT_CARD"); }}
                     onFinish={endSession} />
    );
  } else if (view === "idle") {
    screen = <IdleScreen t={t} onStart={() => setView("menu")} />;
  } else if (view === "menu" || !session) {
    screen = <MenuScreen t={t} status={status} onCancel={reset}
                         onOpenAccount={() => start("OPEN_ACCOUNT")} onDebitCard={() => start("DEBIT_CARD")} />;
  } else {
    switch (session.step) {
      case "PASSPORT_SCAN":
      case "FIND_ACCOUNT":
        screen = <PassportScreen t={t} pill={pill} progress={progress} onScanned={submitPassport} onCancel={endSession} />;
        break;
      case "DETAILS":
        screen = <DetailsScreen t={t} locale={LOCALE[lang]} pill={pill} progress={progress} passport={passport}
                                serverError={inlineError} onConfirm={confirmDetails} onBack={goBack} />;
        break;
      default:
        // BIOMETRICS, SUPPORTING_DOCS, PRODUCT, ... : next screens to build
        screen = <NextScreenPlaceholder t={t} session={session} pill={pill} progress={progress} onCancel={endSession} />;
    }
  }

  const idleLook = view === "idle" && !outOfService && !timedOut && !failure;

  return (
    <Frame lang={lang} onLang={setLang} onHelp={() => setHelp(true)} variant={idleLook ? "idle" : "light"}>
      {screen}
      {help && (
        <HelpDialog t={t} status={status} canEscalate={!!session}
                    onEscalate={async () => { if (session) apply(await stm.escalate(session.id, "Customer asked for help")); }}
                    onClose={() => setHelp(false)} />
      )}
      {busy && <div className="busy" aria-busy="true"><span className="spinner" /></div>}
    </Frame>
  );
}
