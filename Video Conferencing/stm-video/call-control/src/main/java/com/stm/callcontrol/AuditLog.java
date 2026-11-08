package com.stm.callcontrol;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Per-call hash chain: entry_hash = SHA-256(prev_hash | call | seq | type | time | payload).
 *  Editing or removing any event breaks every later hash. The table also rejects UPDATE/DELETE/TRUNCATE.
 *  Audit failures are logged as ALERTs but never abort a live call (that would strand cash mid-transaction). */
@Component
public class AuditLog {
  private static final Logger log = LoggerFactory.getLogger(AuditLog.class);
  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final ObjectMapper om;

  public AuditLog(JdbcTemplate jdbc, TransactionTemplate tx, ObjectMapper om) { this.jdbc = jdbc; this.tx = tx; this.om = om; }

  public void callRequested(String callId, String txId, String terminalId, String lang) {
    safe(() -> jdbc.update("INSERT INTO calls(call_id, tx_id, terminal_id, lang) VALUES (?,?,?,?) ON CONFLICT DO NOTHING", callId, txId, terminalId, lang));
    event(callId, "REQUESTED", Map.of("terminal", terminalId, "tx", txId, "lang", lang));
  }

  /** Called after every successful state change. {@code before} is the snapshot taken before the move. */
  public void onState(Call before, CallState to) {
    event(before.id, "STATE_" + to, Map.of("from", before.state.name(), "to", to.name(), "teller", before.tellerId == null ? "" : before.tellerId));
    safe(() -> {
      if (to == CallState.CONNECTED)
        jdbc.update("UPDATE calls SET connected_at = COALESCE(connected_at, now()), teller_id = ? WHERE call_id = ?", before.tellerId, before.id);
      if (to == CallState.ENDED || to == CallState.ABANDONED)
        jdbc.update("UPDATE calls SET ended_at = now(), final_state = ? WHERE call_id = ?", to.name(), before.id);
    });
  }

  public void event(String callId, String type, Map<String, Object> payload) {
    safe(() -> tx.executeWithoutResult(s -> append(callId, type, payload)));
  }

  private void append(String callId, String type, Map<String, Object> payload) {
    try {
      jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtext(?)) AS l", callId);   // serialises writers per call
      var last = jdbc.queryForList("SELECT seq, entry_hash FROM call_events WHERE call_id = ? ORDER BY seq DESC LIMIT 1", callId);
      int seq = last.isEmpty() ? 1 : ((Number) last.get(0).get("seq")).intValue() + 1;
      String prev = last.isEmpty() ? "GENESIS" : (String) last.get(0).get("entry_hash");
      Instant at = Instant.now().truncatedTo(ChronoUnit.MICROS);   // Postgres keeps microseconds; hash must match
      String json = om.writeValueAsString(new TreeMap<>(payload));
      String hash = hash(prev, callId, seq, type, at, json);
      jdbc.update("INSERT INTO call_events(call_id, seq, type, at, payload, prev_hash, entry_hash) VALUES (?,?,?,?,?,?,?)",
          callId, seq, type, java.sql.Timestamp.from(at), json, prev, hash);
    } catch (Exception e) { throw new IllegalStateException(e); }
  }

  /** Recompute the whole chain. Returns ok plus the first sequence number that does not match, if any. */
  public Map<String, Object> verify(String callId) {
    var rows = jdbc.queryForList("SELECT seq, type, at, payload, prev_hash, entry_hash FROM call_events WHERE call_id = ? ORDER BY seq", callId);
    String prev = "GENESIS";
    for (var r : rows) {
      int seq = ((Number) r.get("seq")).intValue();
      Instant at = ((java.sql.Timestamp) r.get("at")).toInstant();
      String want = hash(prev, callId, seq, (String) r.get("type"), at, (String) r.get("payload"));
      if (!prev.equals(r.get("prev_hash")) || !want.equals(r.get("entry_hash")))
        return Map.of("ok", false, "events", rows.size(), "firstBadSeq", seq);
      prev = want;
    }
    return Map.of("ok", true, "events", rows.size());
  }

  public List<Map<String, Object>> events(String callId) {
    return jdbc.queryForList("SELECT seq, type, at, payload, entry_hash FROM call_events WHERE call_id = ? ORDER BY seq", callId);
  }

  static String hash(String prev, String callId, int seq, String type, Instant at, String payload) {
    try {
      byte[] d = MessageDigest.getInstance("SHA-256")
          .digest((prev + "|" + callId + "|" + seq + "|" + type + "|" + at + "|" + payload).getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(d);
    } catch (Exception e) { throw new IllegalStateException(e); }
  }

  private void safe(Runnable r) {
    try { r.run(); } catch (Exception e) { log.error("ALERT audit write failed", e); }
  }
}
