import { useEffect, useState } from "react";
import type { KioskStatus } from "../../stm";
import type { Strings } from "../i18n";
import { AlertCircleIcon, Btn, CardIcon, Panel, PlusBoxIcon } from "../Layout";
import { TIMEOUT_SECONDS } from "../api";

/** Attract screen. Any touch opens the main menu. */
export function IdleScreen({ t, onStart }: { t: Strings; onStart: () => void }) {
  return (
    <button type="button" className="idle" onClick={onStart} aria-label={t.idleTitle}>
      <span className="idle__bubble idle__bubble--a" />
      <span className="idle__bubble idle__bubble--b" />
      <span className="idle__bubble idle__bubble--c" />
      <span className="idle__card">
        <span className="idle__bank">{t.bankName}</span>
        <span className="idle__chip" />
        <span className="idle__pan">•••• •••• •••• ••••</span>
        <span className="idle__holder">AMARA N. OKAFOR</span>
      </span>
      <span className="idle__title">{t.idleTitle}</span>
      <span className="idle__sub">{t.idleSubtitle}</span>
    </button>
  );
}

export function MenuScreen({ t, status, onOpenAccount, onDebitCard, onCancel }: {
  t: Strings;
  status: KioskStatus | null;
  onOpenAccount: () => void;
  onDebitCard: () => void;
  onCancel: () => void;
}) {
  const accountOk = status?.accountOpeningAvailable ?? true;
  const cardOk = status?.cardIssuanceAvailable ?? true;
  return (
    <Panel pill={t.mainMenu} title={t.selectService} subtitle={t.selectServiceSub}
           actions={<Btn kind="danger" onClick={onCancel}>{t.cancel}</Btn>}>
      <div className="tiles">
        <button type="button" className="tile tile--blue" onClick={onOpenAccount} disabled={!accountOk}>
          <span className="tile__icon tile__icon--dark"><PlusBoxIcon /></span>
          <span className="tile__title">{t.openAccount}</span>
          <span className="tile__sub">{accountOk ? t.openAccountSub : t.unavailable}</span>
        </button>
        <button type="button" className="tile tile--red" onClick={onDebitCard} disabled={!cardOk}>
          <span className="tile__icon tile__icon--red"><CardIcon /></span>
          <span className="tile__title">{t.debitCard}</span>
          <span className="tile__sub">{cardOk ? t.debitCardSub : t.unavailable}</span>
        </button>
      </div>
    </Panel>
  );
}

export function OutOfServiceScreen({ t, status }: { t: Strings; status: KioskStatus | null }) {
  const branch = status?.branchName || "—";
  const helpline = status?.helpline || "—";
  return (
    <Panel pill={t.outOfService} title={t.outOfServiceTitle} subtitle={t.outOfServiceSub}>
      <div className="card card--center">
        <span className="big-icon"><AlertCircleIcon /></span>
        <p className="card__strong">{t.nearestBranch}: {branch} · {t.helpline}: {helpline}</p>
      </div>
    </Panel>
  );
}

/** "Are you still there?" with the 20 s countdown. Reaching 0 ends the session. */
export function TimeoutScreen({ t, onStillHere, onEnd }: { t: Strings; onStillHere: () => void; onEnd: () => void }) {
  const [left, setLeft] = useState(TIMEOUT_SECONDS);
  useEffect(() => {
    const id = setInterval(() => setLeft((s) => s - 1), 1000);
    return () => clearInterval(id);
  }, []);
  useEffect(() => { if (left <= 0) onEnd(); }, [left, onEnd]);

  return (
    <Panel pill={t.sessionTimeout} title={t.stillThere} subtitle={t.stillThereSub}
           actions={<>
             <Btn onClick={onStillHere}>{t.stillHere}</Btn>
             <Btn kind="danger" onClick={onEnd}>{t.endSession}</Btn>
           </>}>
      <div className="countdown" role="timer" aria-live="polite">{Math.max(0, left)}</div>
    </Panel>
  );
}
