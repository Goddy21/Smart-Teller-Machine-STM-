import { useState } from "react";
import { usePortal } from "../context";
import { Badge, Card, Empty, ErrorNote, fmt, Mono, Segmented, Spinner, useLoad } from "../ui/kit";
import { IconShield } from "../ui/Icons";
import { ApplicationPanel, initials } from "./Sessions";

type Filter = "teller" | "open" | "opened" | "all";

/** Account-opening applications (B-3 … B-14). The ones the kiosk is waiting on are at the top. */
export function Applications() {
  const { portal } = usePortal();
  const [filter, setFilter] = useState<Filter>("teller");
  const [sel, setSel] = useState<string | null>(null);
  const list = useLoad(() => portal.applications(filter === "teller" ? { needsTeller: true } : filter === "opened" ? { status: "ACCOUNT_OPENED" } : {}),
    [filter], 8000);
  const rows = (list.data ?? []).filter((a) => filter !== "open" || !["ACCOUNT_OPENED", "CANCELLED", "REJECTED", "EXPIRED"].includes(a.status));
  const current = sel ?? rows[0]?.id ?? null;

  return (
    <div className="grid-2-1 grid-2-1--rev">
      <Card pad={false} title="Applications" subtitle="Open an account at the kiosk"
            actions={<Segmented value={filter} onChange={(f) => { setFilter(f); setSel(null); }} options={[
              { value: "teller", label: "Kiosk waiting" },
              { value: "open", label: "In progress" },
              { value: "opened", label: "Opened" },
              { value: "all", label: "All" },
            ]} />}>
        {list.error && !list.data ? <div className="card__body"><ErrorNote error={list.error} onRetry={list.reload} /></div>
          : !list.data ? <div className="card__body"><Spinner /></div>
          : rows.length === 0 ? <Empty icon={<IconShield />} title={filter === "teller" ? "No kiosk is waiting for a teller" : "No applications"}>
              {filter === "teller" ? "Screening referrals and failed face matches appear here as they happen." : undefined}
            </Empty>
          : (
            <ul className="pick">
              {rows.map((a) => (
                <li key={a.id}>
                  <button type="button" className={a.id === current ? "is-on" : ""} onClick={() => setSel(a.id)}>
                    <span className={`avatar ${a.status === "REFERRED" || a.biometricAttempts >= 3 ? "avatar--red" : ""}`}>{initials(a.fullName)}</span>
                    <span className="pick__text">
                      <b>{a.fullName ?? "Not identified yet"}</b>
                      <small>{a.terminalCode} · {fmt.ago(a.updatedAt)}{a.accountNumber ? ` · ${a.accountNumber}` : ""}</small>
                    </span>
                    <Badge value={a.status} />
                  </button>
                </li>
              ))}
            </ul>
          )}
      </Card>
      {current ? <Detail id={current} onChange={list.reload} /> : <Card><Empty title="Pick an application" /></Card>}
    </div>
  );
}

function Detail({ id, onChange }: { id: string; onChange: () => void }) {
  const { portal, openSession } = usePortal();
  const app = useLoad(() => portal.application(id), [id], 6000);
  const session = useLoad(() => (app.data ? portal.session(app.data.sessionId) : Promise.resolve(null)), [app.data?.sessionId, app.data?.updatedAt]);
  if (app.error && !app.data) return <ErrorNote error={app.error} onRetry={app.reload} />;
  if (!app.data || app.data.id !== id) return <Spinner />;
  const a = app.data;
  return (
    <div className="stack stack--sm">
      <div className="section__head">
        <span className="muted small">Kiosk <Mono>{a.terminalCode}</Mono> · started {fmt.dateTime(a.createdAt)} · expires {fmt.dateTime(a.expiresAt)}</span>
        <button type="button" className="link" onClick={() => openSession(a.sessionId)}>Open the session</button>
      </div>
      <ApplicationPanel a={a} s={session.data ?? undefined} onChange={() => { app.reload(); session.reload(); onChange(); }} />
    </div>
  );
}
