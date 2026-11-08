package com.stm.callcontrol;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.*;

public class AuditChainCheck {
  /** In-memory stand-in for the call_events table: just enough SQL matching for the statements AuditLog issues. */
  static class FakeJdbc extends JdbcTemplate {
    final List<Map<String,Object>> rows = new ArrayList<>();
    boolean failInserts = false;
    @Override public int update(String sql, Object... a) {
      if (sql.startsWith("INSERT INTO call_events")) {
        if (failInserts) throw new RuntimeException("database down");
        Map<String,Object> r = new LinkedHashMap<>();
        r.put("call_id", a[0]); r.put("seq", a[1]); r.put("type", a[2]); r.put("at", a[3]);
        r.put("payload", a[4]); r.put("prev_hash", a[5]); r.put("entry_hash", a[6]); rows.add(r);
      }
      return 1;
    }
    @Override public List<Map<String,Object>> queryForList(String sql, Object... a) {
      if (sql.startsWith("SELECT pg_advisory")) return List.of();
      List<Map<String,Object>> mine = new ArrayList<>();
      for (var r : rows) if (r.get("call_id").equals(a[0])) mine.add(r);
      mine.sort(Comparator.comparingInt(r -> (Integer) r.get("seq")));
      if (sql.contains("ORDER BY seq DESC LIMIT 1")) return mine.isEmpty() ? List.of() : List.of(mine.get(mine.size() - 1));
      return mine;
    }
  }
  static void check(boolean ok, String what) { if (!ok) { System.out.println("FAIL " + what); System.exit(1); } System.out.println("PASS " + what); }

  public static void main(String[] args) {
    FakeJdbc db = new FakeJdbc();
    AuditLog log = new AuditLog(db, new TransactionTemplate(), new ObjectMapper());
    log.callRequested("c1", "tx1", "T1", "en");
    log.event("c1", "STATE_RINGING", Map.of("from", "QUEUED", "to", "RINGING", "teller", "a"));
    log.event("c1", "RECORDING_NOTICE", Map.of("accepted", true, "terminal", "T1"));
    log.event("c1", "DECISION", Map.of("teller", "a", "approve", true, "amount", 900, "recordingHealthy", true));
    log.event("c1", "STATE_ENDED", Map.of("from", "CONNECTED", "to", "ENDED", "teller", "a"));
    check(db.rows.size() == 5 && "GENESIS".equals(db.rows.get(0).get("prev_hash")), "5 events appended; first links to GENESIS");
    check(Boolean.TRUE.equals(log.verify("c1").get("ok")), "untouched chain verifies");

    log.event("c2", "REQUESTED", Map.of("terminal", "T2"));
    check("GENESIS".equals(db.rows.get(5).get("prev_hash")), "a second call starts its own chain");

    String saved = (String) db.rows.get(2).get("payload");
    db.rows.get(2).put("payload", saved.replace("true", "false"));
    var v = log.verify("c1");
    check(Boolean.FALSE.equals(v.get("ok")) && Integer.valueOf(3).equals(v.get("firstBadSeq")), "edited payload on event 3 detected at seq 3");
    db.rows.get(2).put("payload", saved);

    var removed = db.rows.remove(1);
    v = log.verify("c1");
    check(Boolean.FALSE.equals(v.get("ok")), "deleting event 2 detected: " + v);
    db.rows.add(1, removed);

    String h = (String) db.rows.get(4).get("entry_hash");
    db.rows.get(4).put("entry_hash", "0".repeat(64));
    v = log.verify("c1");
    check(Boolean.FALSE.equals(v.get("ok")) && Integer.valueOf(5).equals(v.get("firstBadSeq")), "forged final hash detected at seq 5");
    db.rows.get(4).put("entry_hash", h);
    check(Boolean.TRUE.equals(log.verify("c1").get("ok")), "chain verifies again once restored");

    db.failInserts = true;
    boolean threw = false;
    try { log.event("c3", "REQUESTED", Map.of("terminal", "T3")); } catch (Exception e) { threw = true; }
    check(!threw, "a database failure raises an ALERT but does not throw into the live call");

    // vectors for the cross-language check (auditors will recompute these outside Java)
    String[] ts = {"2026-11-04T10:15:30Z", "2026-11-04T10:15:30.100Z", "2026-11-04T10:15:30.123400Z", "2026-11-04T10:15:30.123456Z", "2026-11-04T10:15:30.000001Z"};
    for (String t : ts) System.out.println("VECTOR|" + t + "|" + Instant.parse(t) + "|" + AuditLog.hash("GENESIS", "c1", 1, "REQUESTED", Instant.parse(t), "{\"a\":\"b\"}"));
  }
}
