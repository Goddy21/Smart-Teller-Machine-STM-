package com.stm.callcontrol;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Stateless: every fact lives in Redis (CallStore), every transition is a compare-and-set, every
 *  cross-node signal goes through StreamHub. Any node can serve any request; no synchronized blocks. */
@Service
public class CallService {
  private static final Logger log = LoggerFactory.getLogger(CallService.class);
  static final long RING_MS = 20_000, RECONNECT_MS = 60_000;
  /** A queued call whose kiosk page has had no event stream for this long is a ghost (page closed, reloaded, network lost). */
  static final long KIOSK_GRACE_MS = 10_000;

  private final CallStore store;
  private final StreamHub hub;
  private final LiveKitTokens tokens;
  private final OrchestratorClient orchestrator;

  private final AuditLog audit;

  public CallService(CallStore store, StreamHub hub, LiveKitTokens tokens, OrchestratorClient orchestrator, AuditLog audit) {
    this.store = store; this.hub = hub; this.tokens = tokens; this.orchestrator = orchestrator; this.audit = audit;
  }

  public Call get(String id) { return store.get(id); }

  public Call request(String terminalId, String lang, String txId) {
    String id = UUID.randomUUID().toString();
    long now = System.currentTimeMillis();
    store.create(id, terminalId, lang, txId, now);
    audit.callRequested(id, txId, terminalId, lang);
    transition(store.get(id), CallState.QUEUED);
    store.enqueue(id, now);
    dispatch();
    return store.get(id);
  }

  public void setPresence(String tellerId, boolean available, Set<String> langs) {
    store.setPresence(tellerId, available, langs);
    dispatch();
  }
  /** Keeps presence alive and tells the console the truth: is this teller still available (a missed ring sets
   *  them away) and does the service still hold a live event stream for them (if not, the console reconnects). */
  public Map<String, Object> heartbeat(String tellerId) {
    store.heartbeat(tellerId);
    Boolean av = store.isAvailable(tellerId);
    return Map.of("available", Boolean.TRUE.equals(av), "present", av != null, "stream", hub.hasStream("teller", tellerId));
  }

  /** FIFO by request time. The claim is one Lua script, so two nodes can never ring the same teller. */
  void dispatch() {
    for (String id : store.queued()) {
      Call c = store.get(id);
      if (c == null || c.state != CallState.QUEUED) { store.dequeue(id); continue; }
      if (kioskGone(c)) { abandonGhost(c); continue; }   // never ring a teller for a customer who has left
      String t = store.claim(c);
      if (t == null) continue;
      log.info("call={} QUEUED -> RINGING teller={}", id, t);
      audit.event(id, "STATE_RINGING", Map.of("from", "QUEUED", "to", "RINGING", "teller", t));
      hub.publish("kiosk", id, "state", Map.of("state", CallState.RINGING));
      hub.publish("teller", t, "ring", Map.of("callId", id, "terminalId", c.terminalId, "lang", c.lang, "txId", c.txId));
      store.schedule("ring", id, System.currentTimeMillis() + RING_MS);
    }
  }

  public void decline(String callId, String tellerId) { releaseRing(mine(callId, tellerId, CallState.RINGING), "declined"); }

  /** Unanswered or declined: the teller is marked away (not available) so they are not re-rung forever. */
  private void releaseRing(Call c, String reason) {
    String old = c.tellerId;
    if (!move(c, CallState.QUEUED, "CLEAR", null)) return;
    if (old != null) {
      store.setAvailable(old, false);
      hub.publish("teller", old, "cancelled", Map.of("callId", c.id, "reason", reason));
    }
    store.enqueue(c.id, c.createdAt);   // original timestamp: keeps its place in line
    dispatch();
  }

  public Map<String, Object> accept(String callId, String tellerId) {
    Call c = mine(callId, tellerId, CallState.RINGING);
    if (kioskGone(c)) {
      abandonGhost(c);
      store.setAvailable(tellerId, true);
      dispatch();
      throw new ResponseStatusException(HttpStatus.CONFLICT, "the customer has left the kiosk; you are available for the next call");
    }
    transition(c, CallState.CONNECTED);
    hub.publish("kiosk", c.id, "connected", connectedPayload(c));
    store.setTerminalCall(c.terminalId, c.id);
    hub.publish("display", c.terminalId, "connected", displayPayload(c));
    String tok = tokens.join(c.room, "teller:" + tellerId, "Teller " + tellerId, true, false, Duration.ofMinutes(2));
    return Map.of("url", tokens.publicUrl, "token", tok, "room", c.room, "txId", c.txId, "terminalId", c.terminalId);
  }

  /** Teller ends the call. Works while CONNECTED or ON_HOLD (customer gone), and is idempotent once ENDED so the
   *  teller can always get back to available, e.g. after the reconnect window already ended the call. */
  public void end(String callId, String tellerId) {
    Call c = store.get(callId);
    if (c == null || !tellerId.equals(c.tellerId)) throw new ResponseStatusException(HttpStatus.CONFLICT, "not this teller's call");
    if (c.state == CallState.CONNECTED || c.state == CallState.ON_HOLD) {
      boolean held = c.state == CallState.ON_HOLD;
      if (move(c, CallState.ENDED, "NONE", null) && held) orchestrator.callAbandoned(c.txId);   // customer never came back
    } else if (c.state != CallState.ENDED) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "call not active for this teller");
    }
    store.setAvailable(tellerId, true);
    dispatch();
  }

  /** Customer cancels from the kiosk while waiting or ringing. */
  public void abandon(String callId, String terminalId) {
    Call c = store.get(callId);
    if (c == null || !c.terminalId.equals(terminalId)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    if (c.state != CallState.QUEUED && c.state != CallState.RINGING)
      throw new ResponseStatusException(HttpStatus.CONFLICT, "call already connected");
    String t = c.tellerId;
    transition(c, CallState.ABANDONED);
    store.dequeue(c.id);
    if (t != null) { store.setAvailable(t, true); hub.publish("teller", t, "cancelled", Map.of("callId", c.id, "reason", "customer cancelled")); }
    dispatch();
  }

  /** LiveKit webhooks (any node). A call is CONNECTED only while both participants are in the room. */
  public void onLiveKitEvent(String event, String room, String identity) {
    if (!room.startsWith("call-")) return;
    Call c = store.get(room.substring(5));
    if (c == null) return;
    // Only this call's own kiosk and teller count. A hidden supervisor (or anyone else) joining or leaving must never
    // move the call to or from ON_HOLD. Found by running a real LiveKit server: supervisors DO produce these webhooks.
    if (event.startsWith("participant_") && !isParty(c, identity)) return;
    switch (event) {
      case "participant_joined" -> {
        long n = store.addPresent(c.id, identity);
        if (c.state == CallState.ON_HOLD && n >= 2) move(c, CallState.CONNECTED, "NONE", null);
      }
      case "participant_left" -> {
        store.removePresent(c.id, identity);
        if (c.state == CallState.CONNECTED && move(c, CallState.ON_HOLD, "NONE", null)) {
          orchestrator.callInterrupted(c.txId);          // hold cash in escrow, pause transaction timers
          store.schedule("reconnect", c.id, System.currentTimeMillis() + RECONNECT_MS);
        }
      }
      case "room_finished" -> {
        if ((c.state == CallState.CONNECTED || c.state == CallState.ON_HOLD) && move(c, CallState.ENDED, "NONE", null) && c.tellerId != null)
          hub.publish("teller", c.tellerId, "ended", Map.of("callId", c.id, "reason", "room closed"));
      }
      default -> { }
    }
  }

  private boolean kioskGone(Call c) {
    return System.currentTimeMillis() - c.createdAt > KIOSK_GRACE_MS && !hub.hasStream("kiosk", c.id);
  }

  private void abandonGhost(Call c) {
    log.warn("call={} kiosk {} has no event stream: abandoning (page closed or network lost)", c.id, c.terminalId);
    store.dequeue(c.id);
    if (move(c, CallState.ABANDONED, "NONE", null)) audit.event(c.id, "KIOSK_GONE", Map.of("terminal", c.terminalId, "state", c.state.name()));
  }

  private static boolean isParty(Call c, String identity) {
    return identity.equals("kiosk:" + c.terminalId) || (c.tellerId != null && identity.equals("teller:" + c.tellerId));
  }

  // ---- timers: every node polls; Redis hands each due item to exactly one of them ----
  @Scheduled(fixedDelay = 1000)
  void sweep() {
    for (String d : store.takeDue(System.currentTimeMillis())) {
      int i = d.indexOf(':');
      String kind = d.substring(0, i), id = d.substring(i + 1);
      try {
        Call c = store.get(id);
        if (c == null) continue;
        if (kind.equals("ring") && c.state == CallState.RINGING) { log.warn("ring timeout call={} teller={} (now set away)", id, c.tellerId); releaseRing(c, "timeout"); }
        else if (kind.equals("reconnect") && c.state == CallState.ON_HOLD && move(c, CallState.ENDED, "NONE", null)) {
          orchestrator.callAbandoned(c.txId);            // cancel and return, compensating rollback
          if (c.tellerId != null) hub.publish("teller", c.tellerId, "ended", Map.of("callId", c.id, "reason", "reconnect window expired"));
        }
      } catch (Exception e) { log.error("deadline {} failed", d, e); }
    }
  }

  @Scheduled(fixedDelay = 2000)
  void tick() { dispatch(); }   // picks up queued calls when a teller frees up or a node missed a trigger

  // ---- streams and tokens ----
  public Map<String, Object> refreshKioskToken(String callId, String terminalId) {
    Call c = store.get(callId);
    if (c == null || !c.terminalId.equals(terminalId) || (c.state != CallState.CONNECTED && c.state != CallState.ON_HOLD))
      throw new ResponseStatusException(HttpStatus.CONFLICT);
    return connectedPayload(c);
  }

  public SseEmitter kioskStream(String callId, String terminalId) {
    Call c = store.get(callId);
    if (c == null || !c.terminalId.equals(terminalId)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    SseEmitter e = hub.open("kiosk", callId);
    hub.localSend("kiosk", callId, "state", Map.of("state", c.state));
    if (c.state == CallState.CONNECTED) hub.localSend("kiosk", callId, "connected", connectedPayload(c));
    return e;
  }

  public SseEmitter tellerStream(String tellerId) { return hub.open("teller", tellerId); }

  /** The kiosk's second, customer-facing screen. It shows the teller's video while this terminal is in a call. */
  public SseEmitter displayStream(String terminalId) {
    SseEmitter e = hub.open("display", terminalId);
    String id = store.getTerminalCall(terminalId);
    Call c = id == null ? null : store.get(id);
    if (c != null && (c.state == CallState.CONNECTED || c.state == CallState.ON_HOLD))
      hub.localSend("display", terminalId, "connected", displayPayload(c));
    return e;
  }

  /** Subscribe-only and hidden: the teller never sees it as a participant, and it cannot publish anything. */
  private Map<String, Object> displayPayload(Call c) {
    String tok = tokens.join(c.room, "display:" + c.terminalId, "Display " + c.terminalId, false, true, Duration.ofMinutes(2));
    return Map.of("url", tokens.publicUrl, "token", tok, "room", c.room, "callId", c.id);
  }

  // ---- helpers ----
  private Map<String, Object> connectedPayload(Call c) {
    String tok = tokens.join(c.room, "kiosk:" + c.terminalId, "Kiosk " + c.terminalId, true, false, Duration.ofMinutes(2));
    return Map.of("url", tokens.publicUrl, "token", tok, "room", c.room);
  }

  private Call mine(String callId, String tellerId, CallState expected) {
    Call c = store.get(callId);
    if (c == null || !tellerId.equals(c.tellerId) || c.state != expected)
      throw new ResponseStatusException(HttpStatus.CONFLICT, "call not " + expected + " for this teller");
    return c;
  }

  private void transition(Call c, CallState to) {
    if (!move(c, to, "NONE", null)) throw new ResponseStatusException(HttpStatus.CONFLICT, "call state changed");
  }

  /** Validates against the state machine, then compare-and-sets. False means another node got there first. */
  private boolean move(Call c, CallState to, String tellerOp, String tellerId) {
    if (!c.state.canGoTo(to)) throw new IllegalStateException(c.state + " -> " + to + " not allowed");
    boolean ok = store.cas(c.id, c.state, to, tellerOp, tellerId);
    if (ok) {
      log.info("call={} {} -> {}", c.id, c.state, to); hub.publish("kiosk", c.id, "state", Map.of("state", to)); audit.onState(c, to);
      if (to == CallState.ENDED || to == CallState.ABANDONED) {
        hub.publish("display", c.terminalId, "ended", Map.of("callId", c.id));
        try { store.clearTerminalCall(c.terminalId, c.id); } catch (Exception e) { log.warn("terminal-call clear failed", e); }
      }
    }
    return ok;
  }
}
