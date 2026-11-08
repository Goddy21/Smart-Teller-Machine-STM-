package com.stm.callcontrol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.Set;

/** X-Terminal-Id / X-Teller-Id are injected by the Terminal API Gateway after mTLS / session validation.
 *  Bind this service to the internal network only; never expose it directly. */
@RestController
public class CallController {
  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CallController.class);
  record NewCall(String lang, String txId) {}
  record Presence(boolean available, Set<String> langs) {}
  record Decision(boolean approve, long amount) {}
  record Consent(boolean accepted) {}

  private final CallService svc;
  private final LiveKitTokens tokens;
  private final OrchestratorClient orch;
  @Value("${callcontrol.teller-limit}") long tellerLimit;

  private final RecordingService recording;
  private final AuditLog audit;
  @Value("${recording.required-above}") long recordingRequiredAbove;

  public CallController(CallService svc, LiveKitTokens tokens, OrchestratorClient orch, RecordingService recording, AuditLog audit) {
    this.svc = svc; this.tokens = tokens; this.orch = orch; this.recording = recording; this.audit = audit;
  }

  // ---- kiosk ----
  @PostMapping("/api/calls")
  Map<String, Object> create(@RequestHeader("X-Terminal-Id") String terminal, @RequestBody NewCall b) {
    var c = svc.request(terminal, b.lang(), b.txId());
    return Map.of("callId", c.id, "state", c.state);
  }
  /** The call's state, for a gateway that polls on the kiosk's behalf (STM middleware D-8). */
  @GetMapping("/api/calls/{id}")
  Map<String, Object> status(@PathVariable String id, @RequestHeader("X-Terminal-Id") String terminal) {
    var c = svc.get(id);
    if (c == null || !c.terminalId.equals(terminal)) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND);
    return Map.of("callId", c.id, "state", c.state, "txId", c.txId == null ? "" : c.txId, "createdAt", c.createdAt);
  }
  @GetMapping(path = "/api/calls/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  SseEmitter kioskEvents(@PathVariable String id, @RequestHeader("X-Terminal-Id") String terminal) { return svc.kioskStream(id, terminal); }
  @DeleteMapping("/api/calls/{id}")
  void cancel(@PathVariable String id, @RequestHeader("X-Terminal-Id") String terminal) { svc.abandon(id, terminal); }
  /** The kiosk reports that the customer saw the recording notice and chose to continue. */
  @PostMapping("/api/calls/{id}/consent")
  void consent(@PathVariable String id, @RequestHeader("X-Terminal-Id") String terminal, @RequestBody Consent b) {
    var call = svc.get(id);
    if (call == null || !call.terminalId.equals(terminal)) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND);
    audit.event(id, "RECORDING_NOTICE", Map.of("accepted", b.accepted(), "terminal", terminal));
  }
  /** The kiosk's upper "teller display" screen (dev: opened by scripts/open-teller-display-on-stm.cmd). */
  @GetMapping(path = "/api/terminals/me/display-events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  SseEmitter displayEvents(@RequestHeader("X-Terminal-Id") String terminal) { return svc.displayStream(terminal); }
  @PostMapping("/api/calls/{id}/token")
  Map<String, Object> refresh(@PathVariable String id, @RequestHeader("X-Terminal-Id") String terminal) { return svc.refreshKioskToken(id, terminal); }

  // ---- teller ----
  @PutMapping("/api/tellers/me/presence")
  void presence(@RequestHeader("X-Teller-Id") String teller, @RequestBody Presence p) { svc.setPresence(teller, p.available(), p.langs()); }
  @GetMapping(path = "/api/tellers/me/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  SseEmitter tellerEvents(@RequestHeader("X-Teller-Id") String teller) { return svc.tellerStream(teller); }
  @PostMapping("/api/tellers/me/heartbeat")
  Map<String, Object> heartbeat(@RequestHeader("X-Teller-Id") String teller) { return svc.heartbeat(teller); }
  @PostMapping("/api/calls/{id}/accept")
  Map<String, Object> accept(@PathVariable String id, @RequestHeader("X-Teller-Id") String teller) { return svc.accept(id, teller); }
  @PostMapping("/api/calls/{id}/decline")
  void decline(@PathVariable String id, @RequestHeader("X-Teller-Id") String teller) { svc.decline(id, teller); }
  @PostMapping("/api/calls/{id}/end")
  void end(@PathVariable String id, @RequestHeader("X-Teller-Id") String teller) { svc.end(id, teller); }

  /** Approvals never travel over the video channel. The orchestrator re-validates everything on its side. */
  @PostMapping("/api/calls/{id}/decision")
  ResponseEntity<?> decide(@PathVariable String id, @RequestHeader("X-Teller-Id") String teller, @RequestBody Decision d) {
    var c = svc.get(id);
    if (c == null || !teller.equals(c.tellerId) || c.state != CallState.CONNECTED)
      return ResponseEntity.status(409).body(Map.of("error", "call not active for this teller"));
    if (d.approve() && d.amount() > tellerLimit)
      return ResponseEntity.status(403).body(Map.of("error", "amount exceeds teller limit; supervisor override required"));
    boolean recOk = recording.healthy(id);
    if (!recOk && d.approve() && d.amount() >= recordingRequiredAbove) {
      audit.event(id, "DECISION_BLOCKED", Map.of("teller", teller, "amount", d.amount(), "reason", "recording not healthy"));
      return ResponseEntity.status(409).body(Map.of("error", "recording is not active; this approval is blocked. Reconnect or escalate."));
    }
    if (!recOk) log.error("ALERT recording unhealthy, low-value decision proceeding call={}", id);
    audit.event(id, "DECISION", Map.of("teller", teller, "approve", d.approve(), "amount", d.amount(), "recordingHealthy", recOk));
    orch.approve(c.txId, id, teller, d.approve(), d.amount());
    return ResponseEntity.ok(Map.of("status", "submitted"));
  }

  // ---- LiveKit webhooks ----
  @PostMapping("/webhooks/livekit")
  ResponseEntity<Void> webhook(@RequestHeader(value = "Authorization", required = false) String auth, @RequestBody String body) throws Exception {
    if (!tokens.verifyWebhook(auth, body)) return ResponseEntity.status(401).build();
    JsonNode n = new ObjectMapper().readTree(body);
    svc.onLiveKitEvent(n.path("event").asText(), n.path("room").path("name").asText(), n.path("participant").path("identity").asText());
    recording.onWebhook(n);
    return ResponseEntity.ok().build();
  }
}
