package com.stm.callcontrol;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Real CallService driven by LiveKit-style webhook events, with in-memory doubles for Redis, the event hub and the audit log. */
class CallServiceWebhookTest {

  static class MemStore extends CallStore {
    final Map<String, Map<Object, Object>> calls = new HashMap<>();
    final Map<String, Set<String>> present = new HashMap<>();
    final Map<String, long[]> deadlines = new LinkedHashMap<>();   // "kind:id" -> due
    final Map<String, Boolean> tellers = new LinkedHashMap<>();
    final Map<String, Set<String>> langs = new HashMap<>();
    final TreeMap<String, Long> queue = new TreeMap<>();
    long clockSkew = 0;
    MemStore() { super(null); }
    @Override public void create(String id, String terminalId, String lang, String txId, long now) {
      Map<Object, Object> m = new HashMap<>(); m.put("id", id); m.put("terminalId", terminalId); m.put("lang", lang); m.put("txId", txId);
      m.put("state", "REQUESTED"); m.put("createdAt", Long.toString(now)); calls.put(id, m);
    }
    @Override public Call get(String id) { var m = calls.get(id); return m == null ? null : new Call(new HashMap<>(m)); }
    @Override public boolean cas(String id, CallState from, CallState to, String op, String tellerId) {
      var m = calls.get(id);
      if (m == null || !m.get("state").equals(from.name())) return false;
      m.put("state", to.name());
      if (op.equals("SET")) m.put("tellerId", tellerId); else if (op.equals("CLEAR")) m.remove("tellerId");
      return true;
    }
    @Override public String claim(Call c) {
      var m = calls.get(c.id);
      if (!"QUEUED".equals(m.get("state"))) return null;
      for (var e : tellers.entrySet())
        if (e.getValue() && langs.get(e.getKey()).contains(c.lang)) { e.setValue(false); m.put("state", "RINGING"); m.put("tellerId", e.getKey()); queue.remove(c.id); return e.getKey(); }
      return null;
    }
    @Override public void enqueue(String id, long score) { queue.put(id, score); }
    @Override public void dequeue(String id) { queue.remove(id); }
    @Override public Set<String> queued() { return new LinkedHashSet<>(queue.keySet()); }
    @Override public void setPresence(String id, boolean av, Set<String> l) { tellers.put(id, av); langs.put(id, l); }
    @Override public void setAvailable(String id, boolean av) { if (tellers.containsKey(id)) tellers.put(id, av); }
    @Override public void heartbeat(String id) { }
    final Map<String, String> terminalCall = new HashMap<>();
    @Override public void setTerminalCall(String t, String c) { terminalCall.put(t, c); }
    @Override public String getTerminalCall(String t) { return terminalCall.get(t); }
    @Override public void clearTerminalCall(String t, String c) { terminalCall.remove(t, c); }
    @Override public long addPresent(String callId, String identity) { var s = present.computeIfAbsent(callId, k -> new HashSet<>()); s.add(identity); return s.size(); }
    @Override public void removePresent(String callId, String identity) { present.computeIfAbsent(callId, k -> new HashSet<>()).remove(identity); }
    @Override public void schedule(String kind, String callId, long due) { deadlines.put(kind + ":" + callId, new long[]{due}); }
    @Override public List<String> takeDue(long now) {
      List<String> out = new ArrayList<>();
      for (var it = deadlines.entrySet().iterator(); it.hasNext();) { var e = it.next(); if (e.getValue()[0] <= now + clockSkew) { out.add(e.getKey()); it.remove(); } }
      return out;
    }
  }
  static class QuietHub extends StreamHub {
    QuietHub() { super(null, null); }
    @Override public void publish(String target, String key, String name, Object data) { }
    @Override public void localSend(String target, String key, String name, Object data) { }
  }
  static class QuietAudit extends AuditLog {
    final List<String> events = new ArrayList<>();
    QuietAudit() { super(null, null, null); }
    @Override public void callRequested(String callId, String txId, String terminalId, String lang) { }
    @Override public void onState(Call before, CallState to) { events.add(to.name()); }
    @Override public void event(String callId, String type, Map<String, Object> payload) { events.add(type); }
  }
  static class CountingOrchestrator extends OrchestratorClient {
    int interrupted, abandoned;
    @Override public void callInterrupted(String txId) { interrupted++; }
    @Override public void callAbandoned(String txId) { abandoned++; }
  }

  MemStore store; CountingOrchestrator orch; CallService svc; String id;

  /** A CONNECTED call between kiosk T1 and teller a, both present in the room. */
  void connectedCall() {
    store = new MemStore(); orch = new CountingOrchestrator();
    LiveKitTokens tokens = new LiveKitTokens();
    tokens.key = "devkey"; tokens.secret = "s".repeat(40); tokens.publicUrl = "ws://x";
    svc = new CallService(store, new QuietHub(), tokens, orch, new QuietAudit());
    svc.setPresence("a", true, Set.of("en"));
    id = svc.request("T1", "en", "tx1").id;
    svc.accept(id, "a");
    svc.onLiveKitEvent("participant_joined", "call-" + id, "kiosk:T1");
    svc.onLiveKitEvent("participant_joined", "call-" + id, "teller:a");
  }
  CallState state() { return store.get(id).state; }
  void ev(String event, String identity) { svc.onLiveKitEvent(event, "call-" + id, identity); }

  @Test void connectedCallStartsConnected() { connectedCall(); assertEquals(CallState.CONNECTED, state()); }

  @Test void supervisorLeavingNeverPutsTheCallOnHold() {
    connectedCall();
    ev("participant_joined", "sup:1");
    ev("participant_left", "sup:1");
    assertEquals(CallState.CONNECTED, state());
    assertEquals(0, orch.interrupted);
  }

  @Test void supervisorJoiningCannotReviveACallWhoseTellerIsGone() {
    connectedCall();
    ev("participant_left", "teller:a");
    assertEquals(CallState.ON_HOLD, state());
    ev("participant_joined", "sup:1");                    // kiosk + supervisor = 2 people, but not the two parties
    assertEquals(CallState.ON_HOLD, state());
    ev("participant_joined", "teller:a");                 // the real teller returns
    assertEquals(CallState.CONNECTED, state());
  }

  @Test void bothPartiesLeavingInterruptsOnlyOnce() {
    connectedCall();
    ev("participant_left", "teller:a"); ev("participant_left", "kiosk:T1");
    assertEquals(CallState.ON_HOLD, state());
    assertEquals(1, orch.interrupted);
  }

  @Test void reconnectWindowExpiryEndsTheCallAndRollsBack() {
    connectedCall();
    ev("participant_left", "teller:a");
    svc.sweep();                                          // window not over yet
    assertEquals(CallState.ON_HOLD, state());
    store.clockSkew = 61_000;
    svc.sweep();
    assertEquals(CallState.ENDED, state());
    assertEquals(1, orch.abandoned);
  }

  @Test void returningWithinTheWindowCancelsTheRollback() {
    connectedCall();
    ev("participant_left", "kiosk:T1");
    ev("participant_joined", "kiosk:T1");
    assertEquals(CallState.CONNECTED, state());
    store.clockSkew = 61_000;
    svc.sweep();                                          // the stale deadline fires but the call is healthy
    assertEquals(CallState.CONNECTED, state());
    assertEquals(0, orch.abandoned);
  }

  @Test void roomFinishedEndsAHeldCall() {
    connectedCall();
    ev("participant_left", "teller:a");
    svc.onLiveKitEvent("room_finished", "call-" + id, "");
    assertEquals(CallState.ENDED, state());
  }

  @Test void eventsForUnknownRoomsAreIgnored() {
    connectedCall();
    svc.onLiveKitEvent("participant_left", "call-nope", "kiosk:T1");
    svc.onLiveKitEvent("participant_left", "someone-elses-room", "kiosk:T1");
    assertEquals(CallState.CONNECTED, state());
  }
  @Test void tellerCanEndACallWhileTheCustomerIsGone() {
    connectedCall();
    ev("participant_left", "kiosk:T1");
    assertEquals(CallState.ON_HOLD, state());
    svc.end(id, "a");
    assertEquals(CallState.ENDED, state());
    assertEquals(1, orch.abandoned);                      // the held transaction is rolled back
    assertTrue(store.tellers.get("a"));                   // and the teller is available again
  }

  @Test void endingIsIdempotentAfterTheReconnectWindowEndedTheCall() {
    connectedCall();
    ev("participant_left", "kiosk:T1");
    store.clockSkew = 61_000;
    svc.sweep();
    assertEquals(CallState.ENDED, state());
    assertFalse(store.tellers.get("a"));
    svc.end(id, "a");                                     // the console's End button must not fail now
    assertTrue(store.tellers.get("a"));
    assertEquals(1, orch.abandoned);                      // not rolled back twice
  }

  @Test void anotherTellerCannotEndTheCall() {
    connectedCall();
    svc.setPresence("b", true, Set.of("en"));
    assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> svc.end(id, "b"));
    assertEquals(CallState.CONNECTED, state());
  }
  /** A hub that reports which kiosk pages still have an event stream. */
  static class StreamsHub extends QuietHub {
    final Set<String> kiosks = new HashSet<>();
    @Override public boolean hasStream(String target, String key) { return !"kiosk".equals(target) || kiosks.contains(key); }
  }

  @Test void aCustomerWhoLeftIsNeverRungToATeller() {
    store = new MemStore(); orch = new CountingOrchestrator();
    LiveKitTokens tokens = new LiveKitTokens(); tokens.key = "devkey"; tokens.secret = "s".repeat(40); tokens.publicUrl = "ws://x";
    StreamsHub hub = new StreamsHub();
    svc = new CallService(store, hub, tokens, orch, new QuietAudit());
    id = svc.request("T1", "en", "tx1").id;                       // no teller yet: waits in the queue
    store.calls.get(id).put("createdAt", Long.toString(System.currentTimeMillis() - 60_000));   // a minute ago; its page is gone
    svc.setPresence("a", true, Set.of("en"));
    assertEquals(CallState.ABANDONED, state());
    assertTrue(store.tellers.get("a"));                            // the teller was not rung
  }

  @Test void aWaitingCustomerWhosePageIsAliveIsRung() {
    store = new MemStore(); orch = new CountingOrchestrator();
    LiveKitTokens tokens = new LiveKitTokens(); tokens.key = "devkey"; tokens.secret = "s".repeat(40); tokens.publicUrl = "ws://x";
    StreamsHub hub = new StreamsHub();
    svc = new CallService(store, hub, tokens, orch, new QuietAudit());
    id = svc.request("T1", "en", "tx1").id;
    hub.kiosks.add(id);
    store.calls.get(id).put("createdAt", Long.toString(System.currentTimeMillis() - 60_000));
    svc.setPresence("a", true, Set.of("en"));
    assertEquals(CallState.RINGING, state());
  }
  @Test void theKioskDisplayKnowsTheCurrentCallUntilItEnds() {
    connectedCall();
    assertEquals(id, store.terminalCall.get("T1"));
    svc.end(id, "a");
    assertNull(store.terminalCall.get("T1"));
  }
}
