import { useEffect, useState, type ReactNode } from "react";
import { usePortal } from "../context";
import { can } from "../auth";
import type { Application, Capture, CustomerRecord, Decision, SessionDetail, SessionRow, TimelineEntry } from "../api/portal";
import {
  Badge, Button, Card, Drawer, Empty, ErrorNote, Field, fmt, Mono, Pager, Segmented, Spinner, useDebounced, useLoad, usePaging,
} from "../ui/kit";
import { IconCard, IconCash, IconCheck, IconFace, IconFile, IconFingerprint, IconSearch, IconShield, IconVideo, IconX } from "../ui/Icons";
import { CAPTURE_LABEL, CARD_SERVICE_LABEL, END_REASON, FLOW_LABEL, flowLabel, label, SERVICE_KIND, SOURCE_LABEL, STEP_LABEL } from "../ui/labels";

type Filter = "teller" | "active" | "ended" | "all";

export function Sessions({ initialId }: { initialId?: string }) {
  const { portal, openSession } = usePortal();
  const [filter, setFilter] = useState<Filter>("all");
  const [flow, setFlow] = useState("");
  const [q, setQ] = useState("");
  const dq = useDebounced(q.trim());
  const paging = usePaging("sessions", [flow, filter, dq]);
  // Filters, search and paging run on the server, over every session (not only the newest few hundred).
  const all = useLoad(() => portal.sessionsPage({ flow: flow || undefined, q: dq || undefined, view: filter, page: paging.page, size: paging.size }),
    [flow, filter, dq, paging.page, paging.size], 10_000);

  useEffect(() => { if (initialId) openSession(initialId); }, [initialId, openSession]);

  const rows = all.data?.items ?? [];
  const count = (f: Filter) => all.data?.counts?.[f];

  return (
    <Card pad={false}
          title="Customer sessions" subtitle="One row per customer at a kiosk, from “Tap to start” to “Finish”. Updates every 10 seconds."
          actions={
            <div className="toolbar">
              <label className="search"><IconSearch /><input value={q} onChange={(e) => setQ(e.target.value)} placeholder="Name, CIF, kiosk, session…" /></label>
              <select className="select" value={flow} onChange={(e) => setFlow(e.target.value)} aria-label="Service">
                <option value="">All services</option>
                {Object.entries(FLOW_LABEL).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
              </select>
              <Segmented value={filter} onChange={setFilter} options={[
                { value: "teller", label: "Needs a teller", count: count("teller") },
                { value: "active", label: "At the kiosk now", count: count("active") },
                { value: "ended", label: "Finished", count: count("ended") },
                { value: "all", label: "All", count: count("all") },
              ]} />
            </div>
          }>
      {all.error && !all.data ? <div className="card__body"><ErrorNote error={all.error} onRetry={all.reload} /></div>
        : !all.data ? <div className="card__body"><Spinner /></div>
        : rows.length === 0 ? <Empty icon={<IconShield />} title={filter === "teller" ? "Nobody needs a teller" : "No sessions match"}>
            {filter === "teller" ? "Referred applications and customers whose face did not match appear here." : "Try another filter."}
          </Empty>
        : (
          <div className="table-wrap">
            <table className="table">
              <thead>
                <tr><th>Customer</th><th>Service</th><th>What happened</th><th>Status</th><th>Kiosk</th><th>Last activity</th></tr>
              </thead>
              <tbody>
                {rows.map((s) => (
                  <tr key={s.id} onClick={() => openSession(s.id)} className={`clickable ${s.needsTeller ? "is-red" : ""}`}>
                    <td>
                      <div className="cell-main">
                        <span className={`avatar ${s.needsTeller ? "avatar--red" : ""}`}>{initials(s.customerName)}</span>
                        <span>
                          <b>{s.customerName ?? (s.flow === "ACCOUNT_OPENING" ? "New customer" : "Not identified")}</b>
                          <small>{s.customerId ?? s.id} · {s.startTrigger === "CARD" ? "started with a card" : "tap to start"} · {s.language.toUpperCase()}</small>
                        </span>
                      </div>
                    </td>
                    <td>{flowLabel(s.flow)}</td>
                    <td><Activity s={s} /></td>
                    <td>
                      <div className="badges">
                        <Badge value={s.status}>{s.status === "ACTIVE" ? "At the kiosk" : s.status === "EXPIRED" ? "Timed out" : "Finished"}</Badge>
                        {s.needsTeller && <Badge tone="bad">Needs teller</Badge>}
                        {s.cardRetained && <Badge tone="bad">Card retained</Badge>}
                      </div>
                    </td>
                    <td><Mono>{s.terminalCode}</Mono></td>
                    <td className="muted">{fmt.ago(s.lastActivityAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      <Pager data={all.data} onPage={paging.setPage} onSize={paging.setSize} />
    </Card>
  );
}

/** Short chips for what the customer did in the session. */
function Activity({ s }: { s: SessionRow }) {
  const chips: string[] = [];
  if (s.applicationsCount) chips.push(s.applicationStatus ? fmt.words(s.applicationStatus) : fmt.plural(s.applicationsCount, "application"));
  if (s.cardOrdersCount) chips.push(fmt.plural(s.cardOrdersCount, "card"));
  if (s.depositsCount) chips.push(fmt.plural(s.depositsCount, "deposit"));
  if (s.withdrawalsCount) chips.push(fmt.plural(s.withdrawalsCount, "withdrawal"));
  if (s.fxCount) chips.push(fmt.plural(s.fxCount, "forex deal"));
  if (s.statementsCount) chips.push(fmt.plural(s.statementsCount, "statement"));
  if (s.serviceEventsCount) chips.push(fmt.plural(s.serviceEventsCount, "service request"));
  if (s.tellerCallsCount) chips.push(fmt.plural(s.tellerCallsCount, "teller call"));
  if (!chips.length) return <span className="muted">{s.endReason ? label(END_REASON, s.endReason) : "—"}</span>;
  return <span className="muted">{chips.join(" · ")}</span>;
}

export function initials(name: string | null | undefined) {
  if (!name) return "?";
  const p = name.split(/\s+/).filter(Boolean);
  return ((p[0]?.[0] ?? "") + (p.length > 1 ? p[p.length - 1][0] : "")).toUpperCase();
}

// ---------------------------------------------------------------------------------------------- detail drawer

/** Everything about one customer session. Opened from any page. */
export function SessionDrawer({ id, onClose }: { id: string | null; onClose: () => void }) {
  const { portal } = usePortal();
  const s = useLoad(() => (id ? portal.session(id) : Promise.resolve(null)), [id], id ? 8000 : undefined);
  const timeline = useLoad(() => (id ? portal.timeline(id) : Promise.resolve([])), [id]);
  const data = s.data && s.data.id === id ? s.data : null;

  return (
    <Drawer open={!!id} onClose={onClose}
            title={data ? data.customerName ?? data.applications.find((a) => a.fullName)?.fullName ?? (data.flow === "ACCOUNT_OPENING" ? "New customer" : "Customer not identified") : "Session"}
            subtitle={data && (
              <span className="badges">
                <Badge value={data.status}>{data.status === "ACTIVE" ? "At the kiosk" : data.status === "EXPIRED" ? "Timed out" : "Finished"}</Badge>
                <span className="muted">{flowLabel(data.flow)} · <Mono>{data.id}</Mono></span>
              </span>
            )}>
      {s.error && !data ? <ErrorNote error={s.error} onRetry={s.reload} />
        : !data ? <Spinner />
        : <SessionBody s={data} timeline={timeline.data ?? []} onChange={s.reload} />}
    </Drawer>
  );
}

export function SessionBody({ s, timeline, compact = false, onChange }: {
  s: SessionDetail; timeline?: TimelineEntry[]; compact?: boolean; onChange?: () => void;
}) {
  const nothing = !s.applications.length && !s.cardOrders.length && !s.deposits.length && !s.withdrawals.length
    && !s.fxExchanges.length && !s.statements.length && !s.verifications.length && !(s.serviceEvents?.length);
  return (
    <div className="stack stack--sm">
      {!compact && (
        <div className="section">
          <div className="fields fields--3">
            <Field label="Kiosk"><Mono>{s.terminalCode}</Mono></Field>
            <Field label="Started">{fmt.dateTime(s.startedAt)}</Field>
            <Field label="How">{s.startTrigger === "CARD" ? "Inserted a card" : "Tapped to start"}</Field>
            <Field label="Language">{s.language === "sw" ? "Kiswahili" : "English"}</Field>
            <Field label="Customer">{s.customerId ? <Mono>{s.customerId}</Mono> : "—"}</Field>
            <Field label={s.endedAt ? "Ended" : "Last activity"}>{s.endedAt ? `${fmt.dateTime(s.endedAt)} · ${label(END_REASON, s.endReason)}` : fmt.ago(s.lastActivityAt)}</Field>
          </div>
          {s.cardRetained && <div className="callout callout--bad mt"><IconCard /><div><b>Card retained</b><span>The kiosk kept the customer’s card. It goes to the branch for collection.</span></div></div>}
        </div>
      )}

      {s.applications.map((a) => <ApplicationPanel key={a.id} a={a} s={s} onChange={onChange} />)}

      {s.verifications.filter((v) => !v.applicationId).map((v) => (
        <div key={v.id} className="section">
          <div className="h3">Identity check · {fmt.words(v.purpose)}</div>
          <div className="fields fields--3">
            <Field label="Document">{fmt.words(v.documentType)} · {v.country}</Field>
            <Field label="Result"><Badge value={v.result} /></Field>
            <Field label="Registry">{v.registry?.fullName ?? "—"}</Field>
          </div>
        </div>
      ))}

      {s.cardOrders.map((c) => (
        <div key={c.id} className="section">
          <div className="h3 with-icon"><IconCard /> {label(CARD_SERVICE_LABEL, c.service)} · {fmt.words(c.cardProduct)}</div>
          <div className="fields fields--3">
            <Field label="Status"><span className="badges"><Badge value={c.status} />{c.outcome && <Badge value={c.outcome} />}</span></Field>
            <Field label="Card">{c.maskedPan ? <Mono>{c.maskedPan}</Mono> : "Not printed yet"}</Field>
            <Field label="Name on card">{c.nameOnCard}</Field>
            <Field label="Account"><Mono>{c.accountId}</Mono></Field>
            <Field label="Fee">{c.feeAmount ? `${fmt.money(c.feeAmount)}${c.feePosted ? "" : " (not charged yet)"}` : "Waived"}</Field>
            <Field label="Reference">{c.reference ? <Mono>{c.reference}</Mono> : "—"}</Field>
          </div>
          {c.steps && c.steps.length > 0 && <PrintSteps steps={c.steps} />}
        </div>
      ))}

      {(s.deposits.length > 0 || s.withdrawals.length > 0 || s.fxExchanges.length > 0) && (
        <div className="section">
          <div className="h3 with-icon"><IconCash /> Cash</div>
          <table className="table table--dense">
            <thead><tr><th>Type</th><th>Detail</th><th className="num">Amount</th><th>Status</th><th>Reference</th></tr></thead>
            <tbody>
              {s.deposits.map((d) => (
                <tr key={d.id}>
                  <td>Deposit</td>
                  <td>{d.targetLabel}{d.rejectedCount ? <small className="muted"> · {d.rejectedCount} notes returned</small> : null}
                    {d.suspectedCounterfeit ? <small className="dir-dr"> · {d.suspectedCounterfeit} suspect notes kept</small> : null}</td>
                  <td className="num">{fmt.money(d.total, d.currency)}</td>
                  <td><Badge value={d.status} /></td>
                  <td>{d.transactionRef ? <Mono>{d.transactionRef}</Mono> : "—"}</td>
                </tr>
              ))}
              {s.withdrawals.map((w) => (
                <tr key={w.id}>
                  <td>{w.source === "FX_SELL" ? "Forex cash out" : "Withdrawal"}</td>
                  <td>{w.mix.map((m) => `${m.count} × ${m.denomination}`).join(", ")}
                    {w.reverseReason && <small className="muted"> · {fmt.words(w.reverseReason)}</small>}</td>
                  <td className="num">{fmt.money(w.amount, w.currency)}{w.currency !== "KES" && <small className="muted"><br />{fmt.money(w.localAmount)}</small>}</td>
                  <td><Badge value={w.status} /></td>
                  <td><Mono>{w.transactionRef}</Mono></td>
                </tr>
              ))}
              {s.fxExchanges.map((x) => (
                <tr key={x.id}>
                  <td>Forex buy</td>
                  <td>{x.currency} at {x.rate} → {x.destinationType === "CASH" ? "cash" : `account ${x.accountId}`}</td>
                  <td className="num">{fmt.money(x.foreignTotal, x.currency)}<small className="muted"><br />{fmt.money(x.localAmount)}</small></td>
                  <td><Badge value={x.status} /></td>
                  <td>{x.transactionRef ? <Mono>{x.transactionRef}</Mono> : "—"}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {s.serviceEvents?.length > 0 && (
        <div className="section">
          <div className="h3 with-icon"><IconShield /> Card and account services</div>
          <table className="table table--dense">
            <thead><tr><th>What</th><th>On</th><th>Details</th><th>Reference</th><th>When</th></tr></thead>
            <tbody>
              {s.serviceEvents.map((e) => (
                <tr key={e.id}>
                  <td><b>{label(SERVICE_KIND, e.kind)}</b></td>
                  <td>{serviceSubject(e.kind, e.subject)}</td>
                  <td className="muted">{serviceDetail(e.kind, e.detail)}</td>
                  <td>{e.reference ? <Mono>{e.reference}</Mono> : "—"}</td>
                  <td className="muted">{fmt.dateTime(e.createdAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {!compact && s.customerId && s.customerRecord && <OnFile customerId={s.customerId} r={s.customerRecord} />}

      {s.statements.length > 0 && (
        <div className="section">
          <div className="h3 with-icon"><IconFile /> Statements</div>
          <table className="table table--dense">
            <thead><tr><th>Type</th><th>Account</th><th className="num">Fee</th><th>Reference</th><th>When</th></tr></thead>
            <tbody>
              {s.statements.map((x) => (
                <tr key={x.id}>
                  <td>{x.type === "MINI" ? "Mini statement" : `Full statement · ${x.periodMonths} months${x.pages ? ` · ${x.pages} pages` : ""}`}</td>
                  <td><Mono>{x.accountId}</Mono></td>
                  <td className="num">{x.fee ? fmt.money(x.fee) : "Free"}</td>
                  <td><Mono>{x.reference}</Mono></td>
                  <td className="muted">{fmt.dateTime(x.createdAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {!compact && s.receipts.length > 0 && (
        <div className="section">
          <div className="h3">Receipts</div>
          <div className="receipts">
            {s.receipts.map((r) => (
              <div key={r.id}>
                <div className="badges"><Badge value={r.printStatus} /><span className="muted small">{fmt.words(r.delivery)}</span></div>
                <pre className="receipt">{r.lines.join("\n")}</pre>
              </div>
            ))}
          </div>
        </div>
      )}

      {!compact && s.sms.length > 0 && (
        <div className="section">
          <div className="h3">Text messages</div>
          <ul className="recs">
            {s.sms.map((m) => (
              <li key={m.id}><div><b>{fmt.words(m.template)}</b><span className="muted small">{m.body}</span></div><Badge value={m.status} /></li>
            ))}
          </ul>
        </div>
      )}

      {s.tellerCalls.length > 0 && (
        <div className="section">
          <div className="h3 with-icon"><IconVideo /> Teller calls</div>
          <ul className="recs">
            {s.tellerCalls.map((c) => (
              <li key={c.id}>
                <div><b>From screen {c.screen ?? "—"}</b><span className="muted small">{flowLabel(c.flow)} · {c.language.toUpperCase()} · {fmt.dateTime(c.createdAt)}</span></div>
                <Badge value={c.status} />
              </li>
            ))}
          </ul>
        </div>
      )}

      {nothing && !compact && <Empty title="Nothing done yet">The customer has not started a service in this session.</Empty>}

      {!compact && timeline && timeline.length > 0 && (
        <div className="section">
          <div className="h3">Timeline (audit journal)</div>
          <ol className="timeline">
            {timeline.map((e) => (
              <li key={e.seq}>
                <span className={`timeline__dot ${/FAIL|REJECT|REFER|RETAIN|CANCEL/.test(e.eventType) ? "is-red" : /OPENED|CREDITED|COMPLETED|DISPENSED|CLEARED/.test(e.eventType) ? "is-good" : ""}`} />
                <div><b>{fmt.words(e.eventType)}</b>
                  <small>{new Date(e.createdAt).toLocaleTimeString("en-GB")}{summarise(e.payload) ? ` · ${summarise(e.payload)}` : ""}</small></div>
              </li>
            ))}
          </ol>
        </div>
      )}
    </div>
  );
}

function summarise(p: Record<string, unknown>) {
  return Object.entries(p ?? {}).filter(([, v]) => v !== null && typeof v !== "object").slice(0, 4)
    .map(([k, v]) => `${k}: ${String(v)}`).join(" · ");
}

function PrintSteps({ steps }: { steps: { code: string; status: string }[] }) {
  return (
    <ol className="steps mt">
      {steps.map((st) => (
        <li key={st.code} className={st.status === "DONE" ? "is-done" : st.status === "FAILED" ? "is-fail" : st.status === "IN_PROGRESS" ? "is-cur" : ""}>
          <i /><em>{label(STEP_LABEL, st.code)}</em>
        </li>
      ))}
    </ol>
  );
}

// ---------------------------------------------------------------------------------------------- account-opening application

const APP_STEPS: { status: string; label: string }[] = [
  { status: "CONSENTED", label: "Terms" },
  { status: "IDENTITY_CAPTURED", label: "ID scan" },
  { status: "BIOMETRICS_VERIFIED", label: "Face & finger" },
  { status: "KYC_CAPTURED", label: "Details" },
  { status: "PHONE_VERIFIED", label: "Phone" },
  { status: "SIGNATURE_CAPTURED", label: "Signature" },
  { status: "ACCOUNT_OPENED", label: "Account" },
];

/** One account-opening application: the registry record, biometrics, screening, captures and the teller decision. */
export function ApplicationPanel({ a, s, onChange, compact = false }: {
  a: Application; s?: SessionDetail; onChange?: () => void; compact?: boolean;
}) {
  const bios = (s?.biometrics ?? []).filter((b) => b.applicationId === a.id);
  const last = bios[bios.length - 1];
  const reached = APP_STEPS.findIndex((x) => x.status === a.status);
  const stopped = a.status === "REFERRED" ? APP_STEPS.findIndex((x) => x.status === "SIGNATURE_CAPTURED") : reached;

  return (
    <div className={`section ${a.needsTeller ? "section--red" : ""}`}>
      <div className="section__head">
        <div>
          <div className="h3">{a.productName}</div>
          <span className="muted small"><Mono>{a.id}</Mono>{a.reference && <> · <Mono>{a.reference}</Mono></>}</span>
        </div>
        <div className="badges">
          <Badge value={a.status} />
          {a.referralStatus !== "NONE" && a.status !== "REFERRED" && <Badge value={a.referralStatus}>Referral {fmt.words(a.referralStatus).toLowerCase()}</Badge>}
          {a.needsTeller && <Badge tone="bad">Needs teller</Badge>}
        </div>
      </div>

      {!["CANCELLED", "REJECTED", "EXPIRED"].includes(a.status) && (
        <ol className="steps">
          {APP_STEPS.map((st, i) => (
            <li key={st.status} className={i <= stopped ? "is-done" : i === stopped + 1 ? (a.needsTeller ? "is-fail" : "is-cur") : ""}><i /><em>{st.label}</em></li>
          ))}
        </ol>
      )}

      {a.registry && (
        <div className="registry mt">
          <RegistryPhoto captures={a.captures} name={a.registry.fullName} />
          <div className="fields fields--2">
            <Field label="Name (registry)">{a.registry.fullName}</Field>
            <Field label="ID number">{a.idNumberMasked ?? a.registry.idNumber ?? a.registry.passportNumber ?? "—"}</Field>
            <Field label="Date of birth">{fmt.date(a.registry.dateOfBirth)}</Field>
            <Field label="Registry">{a.registry.source} · {a.registry.nationality}</Field>
            {!compact && <Field label="KRA PIN">{a.kraPin ? <Mono>{a.kraPin}</Mono> : "—"}</Field>}
            {!compact && <Field label="Mobile">{a.mobile ?? "—"}{a.otpVerified && <> <IconCheck className="dir-cr" /></>}</Field>}
          </div>
        </div>
      )}

      {(last || a.biometricAttempts > 0) && (
        <div className="bio mt">
          <BioTile icon={<IconFace />} label="Face" result={last?.faceResult ?? null}
                   note={last?.faceScore != null ? `score ${last.faceScore.toFixed(2)} · liveness ${last.livenessScore?.toFixed(2) ?? "—"}` : undefined} />
          <BioTile icon={<IconFingerprint />} label="Fingerprint" result={last?.fingerResult ?? null}
                   note={last ? `${fmt.words(last.finger)} · quality ${last.fingerQuality}` : undefined} />
          <BioTile icon={<IconShield />} label="Attempts" result={a.biometricsPassed ? "PASS" : a.biometricAttempts >= 3 ? "FAIL" : null}
                   note={`${a.biometricAttempts} failed of 3${a.tellerOverride ? ` · ${fmt.words(a.tellerOverride)}` : ""}`} />
        </div>
      )}

      {!compact && (a.screening || a.accountNumber || a.address) && (
        <div className="fields fields--3 mt">
          {a.screening && <Field label="Sanctions"><Badge value={a.screening.sanctions} /></Field>}
          {a.screening && <Field label="PEP"><Badge value={a.screening.pep}>{a.screening.pep === "HIT" ? "Possible match" : "Clear"}</Badge></Field>}
          {a.address && <Field label="Address">{[a.address.line1, a.address.town, a.address.county].filter(Boolean).join(", ")}</Field>}
          {a.accountNumber && <Field label="Account number"><Mono>{a.accountNumber}</Mono></Field>}
          {a.customerId && <Field label="Customer (CIF)"><Mono>{a.customerId}</Mono></Field>}
          {a.cardProduct && <Field label="Card">{fmt.words(a.cardProduct)} · {a.nameOnCard}</Field>}
        </div>
      )}

      {!compact && a.captures.length > 0 && (
        <div className="docs mt">{a.captures.map((c) => <CaptureThumb key={c.id} c={c} />)}</div>
      )}

      {a.decidedBy && (
        <p className="muted small mt">Decided by <b>{a.decidedBy}</b> {fmt.ago(a.decidedAt)}{a.decisionNotes ? `: “${a.decisionNotes}”` : ""}</p>
      )}
      {a.cancelReason && <p className="muted small mt">Cancelled at {fmt.words(a.cancelledAtStep)}: {fmt.words(a.cancelReason)}</p>}

      {a.needsTeller && <div className="mt"><Decisions app={a} onDone={onChange} /></div>}
    </div>
  );
}

function BioTile({ icon, label: l, result, note }: { icon: ReactNode; label: string; result: string | null; note?: string }) {
  const tone = result === "MATCH" || result === "PASS" ? "good" : result === "NO_MATCH" || result === "FAIL" ? "bad" : "warn";
  return (
    <div className={`bio__tile bio__tile--${result ? tone : "neutral"}`}>
      <span className="bio__icon">{icon}</span>
      <span className="bio__label">{l}: {result ? fmt.words(result) : "not yet"}</span>
      {note && <small className="muted">{note}</small>}
    </div>
  );
}

/** The card, account or number a service request was about. */
export function serviceSubject(kind: string, subject: string | null): ReactNode {
  if (!subject) return <span className="muted">—</span>;
  if (kind === "BIOMETRICS_ENROLLED") return subject.split(", ").map((f) => fmt.words(f)).join(", ");
  return <Mono>{subject}</Mono>;
}

/** The few details of a service request worth a glance, in plain words. */
export function serviceDetail(kind: string, d: Record<string, unknown> | null | undefined): string {
  if (!d) return "";
  const bits: string[] = [];
  if (kind === "MOBILE_CHANGED") {
    bits.push(`${d.from} → ${d.to}`);
    bits.push(d.verifiedBy === "BIOMETRIC_ONLY" ? "phone lost: face and thumbprint" : "code and thumbprint");
    if (d.holdHours) bits.push(`${d.holdHours} h hold`);
  }
  if (kind === "PIN_RESET" && d.unlocked) bits.push("card was locked by wrong PINs");
  if ((kind === "CARD_TEMP_BLOCKED" || kind === "CARD_BLOCKED") && d.reason) bits.push(fmt.words(String(d.reason)));
  if (kind === "ACCOUNT_REACTIVATED" && d.dormantSince) bits.push(`no activity since ${d.dormantSince}`);
  if (kind === "BIOMETRICS_ENROLLED" && d.firstEnrolment) bits.push("first enrolment");
  if (kind.startsWith("SIGNATURE") && d.capturedWith) bits.push(`${fmt.words(String(d.capturedWith))}${d.strokes ? `, ${d.strokes} strokes` : ""}`);
  return bits.join(" · ");
}

/** What core banking holds to recognise this customer: fingerprints, specimen signature, phone-number change. */
function OnFile({ customerId, r }: { customerId: string; r: CustomerRecord }) {
  const { portal } = usePortal();
  const [url, setUrl] = useState<string>();
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  useEffect(() => () => { if (url) URL.revokeObjectURL(url); }, [url]);
  if (r.unavailable) return null;
  async function view() {
    setBusy(true);
    try { const u = url ?? await portal.signatureUrl(customerId); setUrl(u); setOpen(true); } catch { /* shown as not available */ } finally { setBusy(false); }
  }
  return (
    <div className="section">
      <div className="h3 with-icon"><IconFingerprint /> On file with the bank</div>
      <div className="fields fields--3">
        <Field label="Fingerprints">
          {r.biometricsEnrolled
            ? <><Badge tone="good">Enrolled</Badge><small className="muted"><br />{(r.fingers ?? []).map((f) => fmt.words(f)).join(", ")} · {label(SOURCE_LABEL, r.biometricsSource)}</small></>
            : <><Badge tone="warn">Not enrolled</Badge><small className="muted"><br />Checked against the national registry only</small></>}
        </Field>
        <Field label="Specimen signature">
          {r.signatureOnFile
            ? <><Badge tone="good">On file</Badge><small className="muted"><br />{fmt.date(r.signatureCapturedAt)} · {label(SOURCE_LABEL, r.signatureSource)}</small>
                <br /><button type="button" className="link" onClick={view} disabled={busy} title="Every view is written to the audit journal">{busy ? "Opening…" : "View signature"}</button></>
            : <Badge tone="warn">None on file</Badge>}
        </Field>
        <Field label="Phone number">{r.mobileChangedAt ? <>Changed {fmt.ago(r.mobileChangedAt)}<small className="muted"><br />{fmt.dateTime(r.mobileChangedAt)}</small></> : "Not changed"}</Field>
      </div>
      {open && url && (
        <div className="lightbox" onClick={() => setOpen(false)}>
          <img src={url} alt="Specimen signature" />
          <span>Specimen signature · viewing is audited · click to close</span>
        </div>
      )}
    </div>
  );
}

function useCapture(id: string | undefined) {
  const { portal } = usePortal();
  const [url, setUrl] = useState<string>();
  useEffect(() => {
    if (!id) return;
    let u: string | undefined;
    let alive = true;
    portal.captureUrl(id).then((x) => { u = x; if (alive) setUrl(x); }).catch(() => undefined);
    return () => { alive = false; if (u) URL.revokeObjectURL(u); };
  }, [id, portal]);
  return url;
}

function RegistryPhoto({ captures, name }: { captures: Capture[]; name: string }) {
  const photo = captures.find((c) => c.kind === "REGISTRY_PHOTO" || c.kind === "IPRS_PHOTO");
  const url = useCapture(photo?.id);
  return <div className="registry__photo">{url ? <img src={url} alt={`Registry photo of ${name}`} /> : initials(name)}</div>;
}

function CaptureThumb({ c }: { c: Capture }) {
  const [open, setOpen] = useState(false);
  const url = useCapture(c.id);
  return (
    <>
      <button type="button" className="doc" onClick={() => setOpen(true)} title="Every view is written to the audit journal">
        <span className="doc__preview">{url ? <img src={url} alt="" /> : <IconFile />}</span>
        <span className="doc__name">{label(CAPTURE_LABEL, c.kind)}</span>
        <small className="muted">{fmt.bytes(c.sizeBytes)}</small>
      </button>
      {open && url && (
        <div className="lightbox" onClick={() => setOpen(false)}>
          <img src={url} alt={label(CAPTURE_LABEL, c.kind)} />
          <span>{label(CAPTURE_LABEL, c.kind)} · viewing is audited · click to close</span>
        </div>
      )}
    </>
  );
}

/** Teller decisions the kiosk is waiting for (B-11 referral, B-5 biometric fallback). */
export function Decisions({ app, onDone }: { app: Application; onDone?: (a: Application) => void }) {
  const { portal, user, toast, confirm } = usePortal();
  const [notes, setNotes] = useState("");
  const [busy, setBusy] = useState<Decision | null>(null);
  if (!can.decide(user.role)) return <p className="muted small">A teller or supervisor decides this application.</p>;

  const referral = app.status === "REFERRED";
  async function go(d: Decision) {
    if (d === "REJECT" && !await confirm({ title: "Reject this application?", confirmLabel: "Reject application", tone: "danger",
      message: "The customer is told to visit a branch. This cannot be undone." })) return;
    setBusy(d);
    try {
      const r = await portal.decide(app.id, d, notes.trim() || undefined);
      toast(d === "REJECT" ? "Application rejected." : referral ? "Referral cleared: the kiosk carries on with the phone check." : "Identity confirmed: the kiosk continues.", "ok");
      onDone?.(r);
    } catch (e) {
      toast((e as Error).message, "error");
    } finally {
      setBusy(null);
    }
  }

  return (
    <div className="decisions">
      <div className={`callout ${referral ? "callout--warn" : "callout--bad"}`}>
        {referral ? <IconShield /> : <IconFace />}
        <div>
          <b>{referral ? "Screening referral" : "Face did not match three times"}</b>
          <span>{referral
            ? `${app.screening?.pep === "HIT" ? "Possible PEP match. " : ""}${app.screening?.sanctions === "HIT" ? "Possible sanctions match. " : ""}Check the person and clear or reject. The kiosk is waiting.`
            : "Talk to the customer on video, compare their face with the registry photo, then confirm or reject."}</span>
        </div>
      </div>
      <textarea className="input textarea" rows={2} placeholder="Notes for the audit journal (optional)" value={notes} onChange={(e) => setNotes(e.target.value)} />
      <div className="decisions__row">
        <Button kind="success" icon={<IconCheck />} disabled={!!busy} onClick={() => go(referral ? "CLEAR_REFERRAL" : "VERIFY_IDENTITY")}>
          {busy && busy !== "REJECT" ? "Saving…" : referral ? "Clear referral" : "Identity confirmed"}
        </Button>
        <Button kind="danger" icon={<IconX />} disabled={!!busy} onClick={() => go("REJECT")}>{busy === "REJECT" ? "Saving…" : "Reject"}</Button>
      </div>
    </div>
  );
}
