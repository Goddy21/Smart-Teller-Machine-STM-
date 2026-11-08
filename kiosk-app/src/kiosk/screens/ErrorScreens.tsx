import { useState } from "react";
import type { KioskStatus, SessionView } from "../../stm";
import type { Strings } from "../i18n";
import { Banner, Btn, Panel, ScannerArt } from "../Layout";

/** Error A: passport could not be read, has expired, or is not accepted. Session stays at the passport step. */
export function ErrorAScreen({ t, code, onTryAgain, onSupport }: {
  t: Strings;
  code: string;
  onTryAgain: () => void;
  onSupport: () => void;
}) {
  const [cleaning, setCleaning] = useState(false);
  const banner = code === "DOCUMENT_EXPIRED" ? t.errorAExpired : code === "NOT_ELIGIBLE" ? t.errorANotEligible : t.errorABanner;
  return (
    <Panel pill={t.errorA} title={t.errorATitle} subtitle={t.errorASub}
           actions={<>
             <Btn onClick={onTryAgain}>{t.tryAgain}</Btn>
             <Btn kind="secondary" onClick={() => setCleaning(true)}>{t.cleanGlass}</Btn>
             <Btn kind="danger" onClick={onSupport}>{t.callSupport}</Btn>
           </>}>
      <Banner>{banner}</Banner>
      <div className="two">
        <div className="card card--art"><ScannerArt label={t.faceDownGlass} /></div>
        <div className="card tips">
          <strong>{cleaning ? t.cleanGlassHint : t.errorATips}</strong>
          {!cleaning && (
            <ul>
              <li>{t.errorATip1}</li>
              <li>{t.errorATip2}</li>
              <li>{t.errorATip3}</li>
            </ul>
          )}
        </div>
      </div>
    </Panel>
  );
}

export type ProblemKind = "CANNOT_PROCEED" | "ALREADY_CUSTOMER" | "RETRY" | "SESSION_ENDED" | "GENERIC";

/** Every other failure. UNABLE_TO_PROCEED stays deliberately vague (possible sanctions hit: no tipping-off). */
export function ProblemScreen({ t, kind, errorRef, onRetry, onDebitCard, onFinish }: {
  t: Strings;
  kind: ProblemKind;
  errorRef?: string;
  onRetry: () => void;
  onDebitCard: () => void;
  onFinish: () => void;
}) {
  const text = {
    CANNOT_PROCEED: t.cannotProceed,
    ALREADY_CUSTOMER: t.alreadyCustomer,
    RETRY: t.retryLater,
    SESSION_ENDED: t.sessionEnded,
    GENERIC: t.retryLater,
  }[kind];
  return (
    <Panel pill={t.problem} title={t.problem} subtitle={t.problemSub}
           actions={<>
             {kind === "ALREADY_CUSTOMER" && <Btn onClick={onDebitCard}>{t.getDebitCard}</Btn>}
             {(kind === "RETRY" || kind === "GENERIC") && <Btn onClick={onRetry}>{t.tryAgain}</Btn>}
             <Btn kind="danger" onClick={onFinish}>{t.finish}</Btn>
           </>}>
      <Banner>{text}</Banner>
      {errorRef && <p className="ref">Ref: {errorRef}</p>}
    </Panel>
  );
}

/** Stand-in for steps whose screens are not built yet, so a demo journey never dead-ends silently. */
export function NextScreenPlaceholder({ t, session, pill, progress, onCancel }: {
  t: Strings;
  session: SessionView;
  pill: string;
  progress: { n: number; total: number; label: string };
  onCancel: () => void;
}) {
  return (
    <Panel pill={pill} progress={progress} title={t.comingNext} subtitle={t.comingNextSub(session.step)}
           actions={<Btn kind="danger" onClick={onCancel}>{t.cancel}</Btn>}>
      <div className="card">
        <pre className="session-dump">{JSON.stringify(session, null, 2)}</pre>
      </div>
    </Panel>
  );
}

/** "Need Help?" overlay. Inside a session it can flag the session for a video teller. */
export function HelpDialog({ t, status, canEscalate, onEscalate, onClose }: {
  t: Strings;
  status: KioskStatus | null;
  canEscalate: boolean;
  onEscalate: () => Promise<void>;
  onClose: () => void;
}) {
  const [sent, setSent] = useState(false);
  return (
    <div className="overlay" role="dialog" aria-modal="true" onClick={onClose}>
      <div className="dialog" onClick={(e) => e.stopPropagation()}>
        <h2>{t.helpTitle}</h2>
        <p>{t.helpBody}</p>
        <p className="card__strong">
          {t.nearestBranch}: {status?.branchName || "—"} · {t.helpline}: {status?.helpline || "—"}
        </p>
        {sent && <p className="ok">{t.tellerNotified}</p>}
        <div className="dialog__actions">
          {canEscalate && !sent && (
            <Btn onClick={() => onEscalate().then(() => setSent(true)).catch(() => undefined)}>{t.connectTeller}</Btn>
          )}
          <Btn kind="secondary" onClick={onClose}>{t.close}</Btn>
        </div>
      </div>
    </div>
  );
}
