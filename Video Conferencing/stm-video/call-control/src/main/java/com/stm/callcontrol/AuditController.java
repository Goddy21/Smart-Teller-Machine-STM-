package com.stm.callcontrol;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.*;

/** For the Audit & Compliance Explorer. X-Auditor-Id is set by the gateway after it checks the auditor role.
 *  Every recording access and verification is itself written to the call's hash chain. */
@RestController
@RequestMapping("/api/audit")
public class AuditController {
  private final JdbcTemplate jdbc;
  private final AuditLog audit;
  private final RecordingService recordings;
  private final ObjectStore objects;

  public AuditController(JdbcTemplate jdbc, AuditLog audit, RecordingService recordings, ObjectStore objects) {
    this.jdbc = jdbc; this.audit = audit; this.recordings = recordings; this.objects = objects;
  }

  /** Everything about one transaction: calls, the verified event chain, and sealed recordings. */
  @GetMapping("/transactions/{txId}")
  Map<String, Object> byTransaction(@PathVariable String txId, @RequestHeader("X-Auditor-Id") String auditor) {
    var calls = jdbc.queryForList("SELECT * FROM calls WHERE tx_id = ? ORDER BY requested_at", txId);
    List<Map<String, Object>> out = new ArrayList<>();
    for (var c : calls) {
      String id = (String) c.get("call_id");
      Map<String, Object> m = new LinkedHashMap<>(c);
      m.put("chain", audit.verify(id));
      m.put("events", audit.events(id));
      out.add(m);
    }
    var recs = jdbc.queryForList("SELECT track_sid, call_id, participant, track_name, status, object_key, size_bytes, duration_ms, sha256, "
        + "started_at, hashed_at, retention_until FROM recordings WHERE tx_id = ? ORDER BY started_at", txId);
    return Map.of("transaction", txId, "calls", out, "recordings", recs);
  }

  @GetMapping("/recordings/{trackSid}/download-url")
  ResponseEntity<?> download(@PathVariable String trackSid, @RequestHeader("X-Auditor-Id") String auditor) {
    var r = jdbc.queryForList("SELECT call_id, object_key, sha256 FROM recordings WHERE track_sid = ? AND status = 'SEALED'", trackSid);
    if (r.isEmpty()) return ResponseEntity.status(409).body(Map.of("error", "recording not sealed yet"));
    String callId = (String) r.get(0).get("call_id");
    audit.event(callId, "RECORDING_ACCESSED", Map.of("track", trackSid, "auditor", auditor));
    return ResponseEntity.ok(Map.of("url", objects.presign((String) r.get(0).get("object_key"), Duration.ofMinutes(5)),
        "sha256", r.get(0).get("sha256"), "expiresInSeconds", 300));
  }

  @PostMapping("/recordings/{trackSid}/verify")
  ResponseEntity<?> verify(@PathVariable String trackSid, @RequestHeader("X-Auditor-Id") String auditor) throws Exception {
    Map<String, Object> res = recordings.verify(trackSid);
    var r = jdbc.queryForList("SELECT call_id FROM recordings WHERE track_sid = ?", trackSid);
    if (!r.isEmpty()) audit.event((String) r.get(0).get("call_id"), "RECORDING_VERIFIED",
        Map.of("track", trackSid, "auditor", auditor, "match", String.valueOf(res.get("match"))));
    return ResponseEntity.ok(res);
  }
}
