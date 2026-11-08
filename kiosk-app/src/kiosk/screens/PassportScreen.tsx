import { useEffect, useRef, useState } from "react";
import type { DemoPersona } from "../../stm";
import type { Strings } from "../i18n";
import { Btn, Panel, ScannerArt } from "../Layout";
import { DEMO, stm } from "../api";
import { demoScan, UNREADABLE_MRZ, type MrzRead } from "../scanner";

type Phase = { kind: "waiting" } | { kind: "scanning"; percent: number } | { kind: "checking" };

/**
 * Journey A step 1 and Journey B step 1 ("find your account" by passport).
 * The scanner produces the MRZ lines; onScanned sends them to the middleware.
 */
export function PassportScreen({ t, pill, progress, onScanned, onCancel }: {
  t: Strings;
  pill: string;
  progress: { n: number; total: number; label: string };
  onScanned: (mrz: MrzRead) => Promise<void>;
  onCancel: () => void;
}) {
  const [phase, setPhase] = useState<Phase>({ kind: "waiting" });
  const [personas, setPersonas] = useState<DemoPersona[]>([]);
  const abort = useRef<AbortController | null>(null);

  useEffect(() => {
    if (!DEMO) return;
    stm.demoPersonas().then((r) => setPersonas(r.personas.filter((p) => p.mrzLine1))).catch(() => setPersonas([]));
    return () => abort.current?.abort();
  }, []);

  async function scan(mrz: MrzRead) {
    abort.current = new AbortController();
    try {
      const read = await demoScan(mrz, (percent) => setPhase({ kind: "scanning", percent }), abort.current.signal);
      setPhase({ kind: "checking" });
      await onScanned(read);
    } catch {
      // cancelled, or the parent switched to an error screen
    } finally {
      setPhase({ kind: "waiting" });
    }
  }

  const busy = phase.kind !== "waiting";
  const percent = phase.kind === "scanning" ? phase.percent : phase.kind === "checking" ? 100 : 0;

  return (
    <Panel pill={pill} progress={progress} title={t.scanPassport} subtitle={t.scanPassportSub}
           actions={<Btn kind="danger" onClick={() => { abort.current?.abort(); onCancel(); }}>{t.cancel}</Btn>}>
      <div className="passport-grid">
        <div className="card card--art"><ScannerArt label={t.faceDownGlass} /></div>

        {DEMO && !busy && (
          <div className="card demo">
            <div className="demo__title">{t.demoPick}</div>
            <div className="demo__list">
              {personas.map((p) => (
                <button key={p.customerId} type="button" className="demo__item"
                        onClick={() => scan({ mrzLine1: p.mrzLine1!, mrzLine2: p.mrzLine2! })}>
                  <strong>{p.fullName}</strong>
                  <span>{p.customerId} · {p.scenario}</span>
                </button>
              ))}
              <button type="button" className="demo__item demo__item--bad" onClick={() => scan(UNREADABLE_MRZ)}>
                <strong>{t.demoUnreadable}</strong>
                <span>→ Error A</span>
              </button>
            </div>
          </div>
        )}
      </div>

      <div className={`preview ${busy ? "is-busy" : ""}`}>
        <span className="preview__label">{t.livePreview}</span>
        <span className="preview__text">
          {phase.kind === "waiting" ? t.waitingPassport : phase.kind === "scanning" ? t.readingMrz : t.checkingPassport}
        </span>
        {busy && <span className="preview__line" />}
      </div>
      <div className="bar"><span style={{ width: `${percent}%` }} /></div>
      <div className="bar__label">{busy ? `${percent}% · ${t.keepStill}` : " "}</div>
    </Panel>
  );
}
