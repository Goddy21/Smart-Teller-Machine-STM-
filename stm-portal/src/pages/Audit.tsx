import { Fragment, useEffect, useState, type FormEvent } from "react";
import { usePortal } from "../context";
import type { JournalEntry } from "../api/portal";
import type { Recording, TransactionEvidence } from "../api/calls";
import { Badge, Button, Card, Empty, ErrorNote, fmt, Mono, Pager, Segmented, Spinner, useDebounced, useLoad, usePaging } from "../ui/kit";
import { IconChevronRight, IconDownload, IconSearch, IconShield, IconVideo } from "../ui/Icons";

export function Audit({ initialEntity }: { initialEntity?: string }) {
  const [tab, setTab] = useState<"journal" | "device" | "sms" | "video">("journal");
  return (
    <div className="stack">
      <ChainStatus />
      <Segmented value={tab} onChange={setTab} options={[
        { value: "journal", label: "Audit journal" },
        { value: "device", label: "Kiosk electronic journal" },
        { value: "sms", label: "Text messages" },
        { value: "video", label: "Video call evidence" },
      ]} />
      {tab === "journal" && <Journal initialEntity={initialEntity} />}
      {tab === "device" && <DeviceJournal />}
      {tab === "sms" && <SmsLog />}
      {tab === "video" && <VideoEvidence />}
    </div>
  );
}

function ChainStatus() {
  const { portal } = usePortal();
  const v = useLoad(() => portal.verifyAudit(), []);
  const ok = v.data?.valid;
  return (
    <section className={`chain ${v.data ? (ok ? "chain--ok" : "chain--bad") : ""}`}>
      <span className="chain__icon"><IconShield /></span>
      <div className="chain__text">
        <b>{!v.data ? "Checking the audit chain…" : ok ? "Audit journal intact" : `Chain broken at entry #${v.data.brokenAtSeq}`}</b>
        <span>{v.data
          ? ok ? `All ${fmt.num(v.data.checked)} entries re-hashed and linked to the one before. Nothing was edited or removed.`
            : "An entry was changed or deleted after it was written. Escalate to compliance and IT security."
          : "Every entry carries the hash of the one before it; this recomputes them all."}</span>
      </div>
      <Button kind={ok === false ? "danger" : "secondary"} onClick={v.reload} disabled={v.loading}>{v.loading ? "Verifying…" : "Verify again"}</Button>
      {v.error ? <ErrorNote error={v.error} /> : null}
    </section>
  );
}

function Journal({ initialEntity }: { initialEntity?: string }) {
  const { portal, openSession } = usePortal();
  const [entity, setEntity] = useState(initialEntity ?? "");
  const [type, setType] = useState("");
  const [query, setQuery] = useState({ entity: initialEntity ?? "", type: "" });
  const [rows, setRows] = useState<JournalEntry[]>([]);
  const [more, setMore] = useState(true);
  const [open, setOpen] = useState<number | null>(null);
  const types = useLoad(() => portal.eventTypes(), []);
  const first = useLoad(() => portal.journal({ entityId: query.entity, eventType: query.type, limit: 50 }), [query.entity, query.type]);

  useEffect(() => { if (first.data) { setRows(first.data); setMore(first.data.length === 50); } }, [first.data]);

  async function loadMore() {
    const last = rows[rows.length - 1];
    if (!last) return;
    const next = await portal.journal({ entityId: query.entity, eventType: query.type, beforeSeq: last.seq, limit: 50 });
    setRows((r) => [...r, ...next]);
    setMore(next.length === 50);
  }
  const submit = (e: FormEvent) => { e.preventDefault(); setQuery({ entity: entity.trim(), type }); };

  return (
    <Card pad={false} title="Audit journal" subtitle="Append-only. Identifiers are masked; PINs and fingerprints are never written here."
          actions={
            <form className="toolbar" onSubmit={submit}>
              <label className="search"><IconSearch /><input value={entity} onChange={(e) => setEntity(e.target.value)} placeholder="Session or transaction ID" /></label>
              <select className="select" value={type} onChange={(e) => { setType(e.target.value); setQuery({ entity: entity.trim(), type: e.target.value }); }}>
                <option value="">All events</option>
                {(types.data ?? []).map((t) => <option key={t} value={t}>{fmt.words(t)}</option>)}
              </select>
              <Button kind="secondary" type="submit">Filter</Button>
            </form>
          }>
      {first.error && !first.data ? <div className="card__body"><ErrorNote error={first.error} onRetry={first.reload} /></div>
        : !first.data ? <div className="card__body"><Spinner /></div>
        : rows.length === 0 ? <Empty icon={<IconShield />} title="No entries match" />
        : (
          <>
            <div className="table-wrap">
              <table className="table table--dense">
                <thead><tr><th /><th>#</th><th>Event</th><th>Entity</th><th>Kiosk</th><th>Hash</th><th>When</th></tr></thead>
                <tbody>
                  {rows.map((r) => (
                    <Fragment key={r.seq}>
                      <tr className="clickable" onClick={() => setOpen(open === r.seq ? null : r.seq)}>
                        <td className="chev"><IconChevronRight style={{ transform: open === r.seq ? "rotate(90deg)" : undefined }} /></td>
                        <td className="muted">{r.seq}</td>
                        <td><Badge tone={eventTone(r.eventType)}>{fmt.words(r.eventType)}</Badge></td>
                        <td><Mono title={r.entityId}>{r.entityId.length > 14 ? `${r.entityId.slice(0, 13)}…` : r.entityId}</Mono></td>
                        <td>{r.terminalCode ?? <span className="muted">—</span>}</td>
                        <td><Mono title={r.hash}>{r.hash.slice(0, 10)}…</Mono></td>
                        <td className="muted">{fmt.dateTime(r.createdAt)}</td>
                      </tr>
                      {open === r.seq && (
                        <tr className="expand">
                          <td colSpan={7}>
                            <div className="journal-detail">
                              <pre>{pretty(r.payload)}</pre>
                              <div>
                                <p><span className="muted">Previous hash</span><br /><Mono>{r.prevHash}</Mono></p>
                                <p><span className="muted">This entry’s hash</span><br /><Mono>{r.hash}</Mono></p>
                                {/^[0-9a-f-]{36}$/i.test(r.entityId) && <Button kind="ghost" size="sm" onClick={() => openSession(r.entityId)}>Open session</Button>}
                              </div>
                            </div>
                          </td>
                        </tr>
                      )}
                    </Fragment>
                  ))}
                </tbody>
              </table>
            </div>
            {more && <div className="card__foot"><Button kind="ghost" onClick={loadMore}>Load older entries</Button></div>}
          </>
        )}
    </Card>
  );
}

function eventTone(t: string) {
  if (/DECLINED|FAILED|REJECT|IDENTITY_DECLINED|SCRUBBED/.test(t)) return "bad" as const;
  if (/ESCALAT|TELLER|REMOTE|VIEWED|OVERRID/.test(t)) return "warn" as const;
  if (/OPENED|ISSUED|COMMITTED|COMPLETED|VERIFIED|APPROVED/.test(t)) return "good" as const;
  return "info" as const;
}
function pretty(s: string) {
  try { return JSON.stringify(JSON.parse(s), null, 2); } catch { return s; }
}

// ---------------------------------------------------------------------------------------------- video evidence

function VideoEvidence() {
  const { teller, toast } = usePortal();
  const [ref, setRef] = useState("");
  const [busy, setBusy] = useState(false);
  const [ev, setEv] = useState<TransactionEvidence | null>(null);
  const [err, setErr] = useState<unknown>(null);

  async function search(e: FormEvent) {
    e.preventDefault();
    if (!ref.trim()) return;
    setBusy(true); setErr(null);
    try { setEv(await teller.api.evidence(ref.trim())); } catch (x) { setErr(x); setEv(null); } finally { setBusy(false); }
  }
  async function download(r: Recording) {
    try {
      const d = await teller.api.downloadUrl(r.track_sid);
      window.open(d.url, "_blank", "noopener");
      toast(`Download link valid for ${Math.round(d.expiresInSeconds / 60)} minutes. This access was logged.`, "info");
    } catch (x) { toast((x as Error).message, "error"); }
  }
  async function verify(r: Recording) {
    try {
      const v = await teller.api.verifyRecording(r.track_sid);
      toast(v.match ? "The stored file matches its sealed SHA-256." : `Mismatch: ${v.reason ?? "the file differs from its sealed hash"}`, v.match ? "ok" : "error");
    } catch (x) { toast((x as Error).message, "error"); }
  }

  return (
    <div className="stack">
      <Card title="Find a call by transaction" subtitle="Calls, their hash-chained event log and the sealed recordings. Every access is itself logged.">
        <form className="toolbar" onSubmit={search}>
          <label className="search search--wide"><IconSearch /><input value={ref} onChange={(e) => setRef(e.target.value)} placeholder="Transaction or kiosk session ID shown on the call" /></label>
          <Button type="submit" disabled={busy}>{busy ? "Searching…" : "Search"}</Button>
        </form>
        {err ? <div className="mt"><ErrorNote error={err} /></div> : null}
      </Card>

      {ev && (ev.calls.length === 0 ? (
        <Card><Empty icon={<IconVideo />} title="No calls for this reference" /></Card>
      ) : ev.calls.map((c) => (
        <Card key={c.call_id} title={<span className="with-icon"><IconVideo /> Call {c.call_id.slice(0, 8)}</span>}
              subtitle={`Kiosk ${c.terminal_id} · teller ${c.teller_id ?? "—"} · ${fmt.dateTime(c.requested_at)}`}
              actions={<div className="badges"><Badge value={c.final_state ?? "IN_PROGRESS"} /><Badge tone={c.chain.ok ? "good" : "bad"}>{c.chain.ok ? `Chain intact · ${c.chain.events} events` : `Chain broken at #${c.chain.firstBadSeq}`}</Badge></div>}>
          <div className="grid-2">
            <div>
              <h3 className="h3">Event log</h3>
              <ol className="timeline">
                {c.events.map((e) => (
                  <li key={e.seq}><span className="timeline__dot" /><div><b>{fmt.words(e.type)}</b><small>{fmt.dateTime(e.at)}</small></div></li>
                ))}
              </ol>
            </div>
            <div>
              <h3 className="h3">Recordings</h3>
              {ev.recordings.filter((r) => r.call_id === c.call_id).length === 0 ? <p className="muted">No recordings for this call.</p> : (
                <ul className="recs">
                  {ev.recordings.filter((r) => r.call_id === c.call_id).map((r) => (
                    <li key={r.track_sid}>
                      <div>
                        <b>{fmt.words(r.participant)} · {r.track_name}</b>
                        <small className="muted">{fmt.bytes(r.size_bytes)} · {r.duration_ms ? `${Math.round(r.duration_ms / 1000)} s` : "—"} · kept until {fmt.date(r.retention_until)}</small>
                      </div>
                      <Badge value={r.status} />
                      <Button size="sm" kind="ghost" icon={<IconDownload />} disabled={r.status !== "SEALED"} onClick={() => download(r)}>Open</Button>
                      <Button size="sm" kind="ghost" icon={<IconShield />} disabled={r.status !== "SEALED"} onClick={() => verify(r)}>Verify</Button>
                    </li>
                  ))}
                </ul>
              )}
            </div>
          </div>
        </Card>
      )))}
    </div>
  );
}

/** A-1: the kiosk's own electronic journal, uploaded in signed batches. */
function DeviceJournal() {
  const { portal } = usePortal();
  const [ref, setRef] = useState("");
  const dref = useDebounced(ref.trim());
  const paging = usePaging("device-journal", [dref], 50);
  const j = useLoad(() => portal.deviceJournalPage({ ref: dref || undefined, page: paging.page, size: paging.size }), [dref, paging.page, paging.size], 20_000);
  const items = j.data?.items ?? [];
  return (
    <Card pad={false} title="Kiosk electronic journal" subtitle="Each entry is signed by the kiosk (HMAC). A bad signature means the batch was altered."
          actions={<label className="search"><IconSearch /><input value={ref} onChange={(e) => setRef(e.target.value)} placeholder="Transaction reference…" /></label>}>
      {j.error && !j.data ? <div className="card__body"><ErrorNote error={j.error} onRetry={j.reload} /></div>
        : !j.data ? <div className="card__body"><Spinner /></div>
        : items.length === 0 ? <Empty icon={<IconShield />} title={dref ? "No entries match" : "No journal entries"} />
        : (
          <div className="table-wrap">
            <table className="table table--dense">
              <thead><tr><th>Kiosk time</th><th>Kiosk</th><th className="num">Seq</th><th>Event</th><th>Reference</th><th>Data</th><th>Signature</th></tr></thead>
              <tbody>
                {items.map((e) => (
                  <tr key={`${e.terminalCode}-${e.seq}`} className={e.signatureValid ? "" : "is-red"}>
                    <td className="muted">{fmt.dateTime(e.at)}</td>
                    <td><Mono>{e.terminalCode}</Mono></td>
                    <td className="num"><Mono>{e.seq}</Mono></td>
                    <td>{fmt.words(e.event)}</td>
                    <td>{e.ref ? <Mono>{e.ref}</Mono> : "—"}</td>
                    <td className="muted small">{e.data ? JSON.stringify(e.data).slice(0, 80) : "—"}</td>
                    <td>{e.signatureValid ? <Badge tone="good">Valid</Badge> : <Badge tone="bad">Invalid</Badge>}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      <Pager data={j.data} onPage={paging.setPage} onSize={paging.setSize} />
    </Card>
  );
}

/** R-2 and OTP messages, as the SMS gateway received them (codes masked). */
function SmsLog() {
  const { portal, openSession } = usePortal();
  const paging = usePaging("sms", [], 50);
  const m = useLoad(() => portal.smsPage({ page: paging.page, size: paging.size }), [paging.page, paging.size], 20_000);
  const items = m.data?.items ?? [];
  return (
    <Card pad={false} title="Text messages" subtitle="Receipts and one-time codes sent to customers. Codes and numbers are masked.">
      {m.error && !m.data ? <div className="card__body"><ErrorNote error={m.error} onRetry={m.reload} /></div>
        : !m.data ? <div className="card__body"><Spinner /></div>
        : items.length === 0 ? <Empty title="No messages yet" />
        : (
          <div className="table-wrap">
            <table className="table table--dense">
              <thead><tr><th>When</th><th>To</th><th>Message</th><th>Status</th></tr></thead>
              <tbody>
                {items.map((x) => (
                  <tr key={x.id} className={x.sessionId ? "clickable" : ""} onClick={() => x.sessionId && openSession(x.sessionId)}>
                    <td className="muted">{fmt.dateTime(x.createdAt)}</td>
                    <td><Mono>{x.mobile}</Mono><small className="muted"> {x.language.toUpperCase()}</small></td>
                    <td><b>{fmt.words(x.template)}</b><small className="muted"><br />{x.body}</small></td>
                    <td><Badge value={x.status} /></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      <Pager data={m.data} onPage={paging.setPage} onSize={paging.setSize} />
    </Card>
  );
}
