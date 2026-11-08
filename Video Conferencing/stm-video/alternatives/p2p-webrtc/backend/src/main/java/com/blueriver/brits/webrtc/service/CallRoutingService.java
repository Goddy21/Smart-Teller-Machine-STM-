package com.blueriver.brits.webrtc.service;

import com.blueriver.brits.webrtc.dto.SignalingMessage;
import com.blueriver.brits.webrtc.entity.CallSession;
import com.blueriver.brits.webrtc.entity.CallStatus;
import com.blueriver.brits.webrtc.websocket.SignalingPublisher;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Queue, teller assignment, ring timeout and call lifecycle. Single node: all mutation is synchronized, and signaling
 *  sockets are node-local, so run one instance (or add sticky routing plus a shared broker before scaling out). */
@Service
public class CallRoutingService {
  private static final Logger log = LoggerFactory.getLogger(CallRoutingService.class);

  private final CallSessionService sessions;
  private final SignalingPublisher out;
  private final ApplicationEventPublisher events;
  private final Deque<CallSession> queue = new ArrayDeque<>();
  private final Map<String, Boolean> tellers = new HashMap<>();   // tellerId -> available for new calls
  private final ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor();

  @Value("${brits.webrtc.ring-timeout-seconds:20}") long ringSeconds;
  @Value("${brits.webrtc.max-queue-wait-seconds:120}") long maxWaitSeconds;
  @Value("${brits.webrtc.reconnect-grace-seconds:30}") long graceSeconds;

  public CallRoutingService(CallSessionService sessions, SignalingPublisher out, ApplicationEventPublisher events) {
    this.sessions = sessions; this.out = out; this.events = events;
  }

  @PostConstruct void start() { timers.scheduleWithFixedDelay(this::expireWaiting, 5, 5, TimeUnit.SECONDS); }
  @PreDestroy void stop() { timers.shutdownNow(); }

  // ------------------------------------------------------------------ kiosk side
  public synchronized CallSession request(String terminalId, String txId) {
    if (sessions.activeForTerminal(terminalId).isPresent())
      throw new ResponseStatusException(HttpStatus.CONFLICT, "this terminal already has an active call");
    CallSession c = sessions.create(terminalId, txId);
    queue.addLast(c);
    out.send(c.kioskIdentity(), SignalingMessage.queued(c.id, queue.size()));
    dispatch();
    return c;
  }

  public synchronized int positionOf(CallSession c) {
    int i = 1;
    for (CallSession q : queue) { if (q == c) return i; i++; }
    return 0;
  }

  // ------------------------------------------------------------------ teller side
  public synchronized void setPresence(String tellerId, boolean available) {
    if (available && sessions.activeForTeller(tellerId).isPresent()) return;   // in a call: stays busy
    tellers.put(tellerId, available);
    dispatch();
  }

  public synchronized List<Map<String, Object>> snapshotQueue() {
    List<Map<String, Object>> rows = new ArrayList<>();
    LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
    sessions.activeCalls().stream()
        .filter(c -> c.status == CallStatus.QUEUED || c.status == CallStatus.RINGING)
        .sorted(Comparator.comparing(c -> c.requestedAt))
        .forEach(c -> rows.add(Map.of("callId", c.id, "terminalId", c.terminalId, "status", c.status.name(),
            "waitingSeconds", Duration.between(c.requestedAt, now).toSeconds())));
    return rows;
  }

  public synchronized void accept(String callId, String tellerId) {
    CallSession c = sessions.require(callId);
    if (c.status != CallStatus.RINGING || !tellerId.equals(c.tellerId))
      throw new ResponseStatusException(HttpStatus.CONFLICT, "call is not ringing for this teller");
    c.status = CallStatus.ACCEPTED;
    c.acceptedAt = LocalDateTime.now(ZoneOffset.UTC);
    sessions.update(c);
    // Only now does the kiosk start its camera and send an offer: no customer media before the teller accepts.
    out.send(c.tellerIdentity(), SignalingMessage.simple("CALL_ACCEPTED", c.id));
    out.send(c.kioskIdentity(), SignalingMessage.simple("CALL_ACCEPTED", c.id));
  }

  public synchronized void reject(String callId, String tellerId) {
    CallSession c = sessions.require(callId);
    if (c.status != CallStatus.RINGING || !tellerId.equals(c.tellerId))
      throw new ResponseStatusException(HttpStatus.CONFLICT, "call is not ringing for this teller");
    tellers.put(tellerId, true);   // still available, but this call will not be offered to them again
    requeue(c);
  }

  // ------------------------------------------------------------------ shared lifecycle
  public synchronized void end(String callId, String from, String reason) {
    CallSession c = sessions.require(callId);
    boolean kiosk = from.equals(c.kioskIdentity());
    if (!kiosk && !from.equals(c.tellerIdentity())) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    if (!kiosk && c.status != CallStatus.ACCEPTED && c.status != CallStatus.CONNECTED)
      throw new ResponseStatusException(HttpStatus.CONFLICT, "teller can end only an accepted call (use reject while ringing)");
    CallStatus status = (c.status == CallStatus.QUEUED || c.status == CallStatus.RINGING) ? CallStatus.ABANDONED : CallStatus.ENDED;
    String other = kiosk ? c.tellerIdentity() : c.kioskIdentity();
    if (other != null) out.send(other, SignalingMessage.withReason("CALL_ENDED", c.id, reason));
    finish(c, status, reason);
  }

  /** Validate, then forward an offer, answer or ICE candidate to the other party. Nothing else is trusted or forwarded. */
  public synchronized void relay(String from, SignalingMessage m) {
    CallSession c = sessions.require(m.callId());
    boolean kiosk = from.equals(c.kioskIdentity());
    if (!kiosk && !from.equals(c.tellerIdentity())) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    if (c.status != CallStatus.ACCEPTED && c.status != CallStatus.CONNECTED)
      throw new ResponseStatusException(HttpStatus.CONFLICT, "call is not accepted");
    if ("CALL_OFFER".equals(m.type()) && !kiosk) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "only the kiosk sends offers");
    if ("CALL_ANSWER".equals(m.type()) && kiosk) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "only the teller sends answers");
    String target = kiosk ? c.tellerIdentity() : c.kioskIdentity();
    if (!out.send(target, m.relayCopy())) throw new ResponseStatusException(HttpStatus.CONFLICT, "other party is offline");
  }

  public synchronized void mediaConnected(String callId, String from) {
    CallSession c = party(callId, from);
    if (c.status == CallStatus.ACCEPTED) {
      c.status = CallStatus.CONNECTED;
      c.connectedAt = LocalDateTime.now(ZoneOffset.UTC);
      sessions.update(c);
    }
  }

  public synchronized void mediaFailed(String callId, String from, String reason) {
    CallSession c = party(callId, from);
    String other = from.equals(c.kioskIdentity()) ? c.tellerIdentity() : c.kioskIdentity();
    if (other != null) out.send(other, SignalingMessage.withReason("CALL_FAILED", c.id, "MEDIA_FAILED"));
    finish(c, CallStatus.FAILED, "MEDIA_" + (reason == null ? "FAILED" : reason));
  }

  // ------------------------------------------------------------------ disconnects
  public synchronized void onDisconnect(String identity) {
    if (identity.startsWith("teller:")) {
      String tellerId = identity.substring("teller:".length());
      tellers.remove(tellerId);   // no new calls routed to a teller who is offline
      sessions.activeForTeller(tellerId).filter(c -> c.status == CallStatus.RINGING).ifPresent(this::requeue);
    }
    // A dropped signaling socket does not end a call: media is peer to peer and may be fine. Give it time to return.
    timers.schedule(() -> graceExpired(identity), graceSeconds, TimeUnit.SECONDS);
  }

  private synchronized void graceExpired(String identity) {
    if (out.isOnline(identity)) return;
    if (identity.startsWith("kiosk:")) {
      sessions.activeForTerminal(identity.substring("kiosk:".length())).ifPresent(c -> {
        CallStatus s = (c.status == CallStatus.QUEUED || c.status == CallStatus.RINGING) ? CallStatus.ABANDONED : CallStatus.ENDED;
        if (c.tellerIdentity() != null) out.send(c.tellerIdentity(), SignalingMessage.withReason("CALL_ENDED", c.id, "KIOSK_DISCONNECTED"));
        finish(c, s, "KIOSK_DISCONNECTED");
      });
    } else {
      sessions.activeForTeller(identity.substring("teller:".length()))
          .filter(c -> c.status == CallStatus.ACCEPTED || c.status == CallStatus.CONNECTED)
          .ifPresent(c -> {
            out.send(c.kioskIdentity(), SignalingMessage.withReason("CALL_FAILED", c.id, "TELLER_DISCONNECTED"));
            finish(c, CallStatus.FAILED, "TELLER_DISCONNECTED");
          });
    }
  }

  // ------------------------------------------------------------------ internals (callers hold the lock)
  private void dispatch() {
    for (CallSession c : new ArrayList<>(queue)) {
      String t = tellers.entrySet().stream()
          .filter(e -> e.getValue() && !c.triedTellers.contains(e.getKey()) && out.isOnline("teller:" + e.getKey()))
          .map(Map.Entry::getKey).findFirst().orElse(null);
      if (t == null) continue;
      queue.remove(c);
      tellers.put(t, false);
      c.tellerId = t;
      c.triedTellers.add(t);
      c.status = CallStatus.RINGING;
      sessions.update(c);
      log.info("call={} ringing teller={}", c.id, t);
      out.send("teller:" + t, SignalingMessage.incoming(c.id, c.terminalId, c.txId));
      out.send(c.kioskIdentity(), SignalingMessage.simple("CALL_RINGING", c.id));
      timers.schedule(() -> ringTimeout(c.id, t), ringSeconds, TimeUnit.SECONDS);
    }
  }

  private synchronized void ringTimeout(String callId, String tellerId) {
    CallSession c = sessions.find(callId).orElse(null);
    if (c == null || c.status != CallStatus.RINGING || !tellerId.equals(c.tellerId)) return;
    log.warn("ring timeout call={} teller={}", callId, tellerId);
    tellers.put(tellerId, false);   // unanswered: mark away so they are not rung again until they return
    out.send("teller:" + tellerId, SignalingMessage.withReason("CALL_ENDED", callId, "RING_TIMEOUT"));
    requeue(c);
  }

  /** Back to the front of the line, remembering who already had it. */
  private void requeue(CallSession c) {
    c.tellerId = null;
    c.status = CallStatus.QUEUED;
    queue.addFirst(c);
    sessions.update(c);
    out.send(c.kioskIdentity(), SignalingMessage.queued(c.id, positionOf(c)));
    dispatch();
  }

  void expireWaiting() {   // package-private for tests
    synchronized (this) {
      LocalDateTime cutoff = LocalDateTime.now(ZoneOffset.UTC).minusSeconds(maxWaitSeconds);
      for (CallSession c : new ArrayList<>(queue)) {
        if (c.requestedAt.isBefore(cutoff)) {
          out.send(c.kioskIdentity(), SignalingMessage.withReason("CALL_REJECTED", c.id, "NO_TELLER_AVAILABLE"));
          finish(c, CallStatus.ABANDONED, "NO_TELLER_AVAILABLE");
        }
      }
    }
  }

  private void finish(CallSession c, CallStatus status, String reason) {
    queue.remove(c);
    c.status = status;
    c.endReason = reason;
    c.endedAt = LocalDateTime.now(ZoneOffset.UTC);
    if (c.tellerId != null && tellers.containsKey(c.tellerId) && out.isOnline("teller:" + c.tellerId)) tellers.put(c.tellerId, true);
    sessions.update(c);
    events.publishEvent(new CallEndedEvent(c.id, c.txId, c.terminalId, c.tellerId, status, reason));
    dispatch();
  }

  private CallSession party(String callId, String from) {
    CallSession c = sessions.require(callId);
    if (!from.equals(c.kioskIdentity()) && !from.equals(c.tellerIdentity())) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    return c;
  }
}
