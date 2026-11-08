package com.stm.callcontrol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One egress per published track: kiosk face, kiosk ID cam, kiosk mic, teller mic. Each file is written to the
 *  Object Lock bucket by Egress, then sealed here (SHA-256 recorded in Postgres) and linked to the transaction. */
@Service
public class RecordingService {
  private static final Logger log = LoggerFactory.getLogger(RecordingService.class);
  static final Set<String> TRACKS = Set.of("face", "id-cam", "mic", "teller-cam", "kiosk-screen");   // teller-cam and kiosk-screen are recorded but not required for health
  static final int EXPECTED_TRACKS = 4;
  private static final String HEALTHY = "'PENDING','STARTING','ACTIVE','COMPLETE','HASHING','SEALED'";

  private final JdbcTemplate jdbc;
  private final CallStore calls;
  private final LiveKitTokens tokens;
  private final AuditLog audit;
  private final ObjectStore objects;
  private final ObjectMapper om;
  private final HttpClient http = HttpClient.newHttpClient();
  private final ExecutorService pool = Executors.newFixedThreadPool(4);

  @Value("${livekit.api-url}") String apiUrl;
  @Value("${storage.egress-endpoint}") String endpoint;   // as the Egress worker sees it
  @Value("${storage.region}") String region;
  @Value("${storage.bucket}") String bucket;
  @Value("${storage.access-key}") String accessKey;
  @Value("${storage.secret-key}") String secretKey;
  @Value("${recording.retention-years}") int retentionYears;
  @Value("${recording.enabled:true}") boolean enabled;

  public RecordingService(JdbcTemplate jdbc, CallStore calls, LiveKitTokens tokens, AuditLog audit, ObjectStore objects, ObjectMapper om) {
    this.jdbc = jdbc; this.calls = calls; this.tokens = tokens; this.audit = audit; this.objects = objects; this.om = om;
  }

  public void onWebhook(JsonNode n) {
    switch (n.path("event").asText()) {
      case "track_published" -> onTrackPublished(n);
      case "egress_started", "egress_updated", "egress_ended" -> onEgress(n.path("egressInfo"));
      default -> { }
    }
  }

  private void onTrackPublished(JsonNode n) {
    String room = n.path("room").path("name").asText();
    String name = n.path("track").path("name").asText();
    String sid = n.path("track").path("sid").asText();
    String who = n.path("participant").path("identity").asText();
    if (!room.startsWith("call-") || !TRACKS.contains(name) || sid.isEmpty()) return;
    if (!enabled) { log.warn("recording disabled (recording.enabled=false): track {} {} of {} is NOT recorded", name, sid, room); return; }
    String callId = room.substring(5);
    Call c = calls.get(callId);
    if (c == null) { log.error("ALERT track published for unknown call {}", callId); return; }
    String key = "recordings/" + c.txId + "/" + callId + "/" + who.replace(':', '-') + "-" + name + "-" + sid;
    // Idempotent: LiveKit may deliver a webhook twice. Only the first insert starts an egress.
    int ins = jdbc.update("INSERT INTO recordings(track_sid, call_id, tx_id, participant, track_name, status, object_key, retention_until) "
        + "VALUES (?,?,?,?,?,'PENDING',?, now() + make_interval(years => ?)) ON CONFLICT DO NOTHING",
        sid, callId, c.txId, who, name, key, retentionYears);
    if (ins == 1) pool.submit(() -> startEgress(room, callId, sid, key));
  }

  private void startEgress(String room, String callId, String sid, String key) {
    try {
      Map<String, Object> s3 = Map.of("access_key", accessKey, "secret", secretKey, "region", region,
          "endpoint", endpoint, "bucket", bucket, "force_path_style", true);
      Map<String, Object> body = Map.of("room_name", room, "track_id", sid, "file", Map.of("filepath", key, "s3", s3));
      HttpRequest req = HttpRequest.newBuilder(URI.create(apiUrl + "/twirp/livekit.Egress/StartTrackEgress"))
          .header("Authorization", "Bearer " + tokens.recordToken(room)).header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(om.writeValueAsString(body))).build();
      HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
      if (resp.statusCode() / 100 != 2) throw new IllegalStateException("egress start HTTP " + resp.statusCode() + " " + resp.body());
      JsonNode j = om.readTree(resp.body());
      String eid = j.path("egressId").asText(j.path("egress_id").asText(""));
      jdbc.update("UPDATE recordings SET egress_id = ?, status = 'STARTING' WHERE track_sid = ? AND status = 'PENDING'", eid, sid);
      audit.event(callId, "RECORDING_STARTED", Map.of("track", sid, "egress", eid));
    } catch (Exception e) {
      log.error("ALERT egress start failed call={} track={}", callId, sid, e);
      jdbc.update("UPDATE recordings SET status = 'START_FAILED', error = ? WHERE track_sid = ?", String.valueOf(e.getMessage()), sid);
      audit.event(callId, "RECORDING_START_FAILED", Map.of("track", sid));
    }
  }

  private void onEgress(JsonNode info) {
    String eid = info.path("egressId").asText();
    String status = info.path("status").asText();
    if (eid.isEmpty()) return;
    switch (status) {
      case "EGRESS_ACTIVE" -> jdbc.update("UPDATE recordings SET status = 'ACTIVE' WHERE egress_id = ? AND status IN ('PENDING','STARTING')", eid);
      case "EGRESS_COMPLETE" -> {
        JsonNode f = info.path("fileResults").path(0);
        if (f.isMissingNode()) f = info.path("file");
        String key = f.path("filename").asText("");   // Egress may append an extension, so take its real key
        jdbc.update("UPDATE recordings SET status = 'COMPLETE', object_key = COALESCE(NULLIF(?, ''), object_key), "
            + "size_bytes = ?, duration_ms = ? WHERE egress_id = ? AND status IN ('PENDING','STARTING','ACTIVE')",
            key, f.path("size").asLong(0), f.path("duration").asLong(0) / 1_000_000, eid);
      }
      case "EGRESS_FAILED", "EGRESS_ABORTED", "EGRESS_LIMIT_REACHED" -> {
        log.error("ALERT recording {} egress={} {}", status, eid, info.path("error").asText());
        jdbc.update("UPDATE recordings SET status = 'FAILED', error = ? WHERE egress_id = ?", status + " " + info.path("error").asText(), eid);
        jdbc.queryForList("SELECT call_id, track_sid FROM recordings WHERE egress_id = ?", eid)
            .forEach(r -> audit.event((String) r.get("call_id"), "RECORDING_FAILED", Map.of("track", (String) r.get("track_sid"), "status", status)));
      }
      default -> { }
    }
  }

  /** Seal finished files. SKIP LOCKED lets every node run this safely; stale claims (a node died) are retaken after 10 minutes. */
  @Scheduled(fixedDelay = 15_000)
  void hashPending() {
    List<Map<String, Object>> rows = jdbc.queryForList("""
        UPDATE recordings SET status = 'HASHING', hash_claimed_at = now()
         WHERE track_sid IN (SELECT track_sid FROM recordings
                              WHERE status = 'COMPLETE' OR (status = 'HASHING' AND hash_claimed_at < now() - interval '10 minutes')
                              ORDER BY started_at LIMIT 5 FOR UPDATE SKIP LOCKED)
        RETURNING track_sid, call_id, object_key""");
    for (var r : rows) {
      String sid = (String) r.get("track_sid"), callId = (String) r.get("call_id"), key = (String) r.get("object_key");
      try {
        ObjectStore.Digest d = objects.sha256(key);
        jdbc.update("UPDATE recordings SET status = 'SEALED', sha256 = ?, size_bytes = ?, hashed_at = now() WHERE track_sid = ?", d.sha256(), d.size(), sid);
        audit.event(callId, "RECORDING_SEALED", Map.of("track", sid, "key", key, "sha256", d.sha256(), "bytes", d.size()));
      } catch (Exception e) {
        log.error("ALERT hashing failed track={} key={}", sid, key, e);
        jdbc.update("UPDATE recordings SET status = CASE WHEN hash_attempts >= 4 THEN 'HASH_FAILED' ELSE 'COMPLETE' END, "
            + "hash_attempts = hash_attempts + 1, error = ? WHERE track_sid = ?", String.valueOf(e.getMessage()), sid);
      }
    }
  }

  /** True when the newest recording of each of the four expected tracks is in a good state. */
  public boolean healthy(String callId) {
    Integer n = jdbc.queryForObject("SELECT count(*) FROM (SELECT DISTINCT ON (participant, track_name) status FROM recordings "
        + "WHERE call_id = ? AND track_name NOT IN ('teller-cam', 'kiosk-screen') ORDER BY participant, track_name, started_at DESC) t WHERE status IN (" + HEALTHY + ")", Integer.class, callId);
    return n != null && n >= EXPECTED_TRACKS;
  }

  /** Re-hash the stored object and compare with the sealed hash. */
  public Map<String, Object> verify(String trackSid) throws Exception {
    var r = jdbc.queryForList("SELECT object_key, sha256 FROM recordings WHERE track_sid = ? AND status = 'SEALED'", trackSid);
    if (r.isEmpty()) return Map.of("match", false, "reason", "not sealed");
    String want = (String) r.get(0).get("sha256");
    String got = objects.sha256((String) r.get(0).get("object_key")).sha256();
    return Map.of("match", want.equals(got), "expected", want, "actual", got);
  }
}
