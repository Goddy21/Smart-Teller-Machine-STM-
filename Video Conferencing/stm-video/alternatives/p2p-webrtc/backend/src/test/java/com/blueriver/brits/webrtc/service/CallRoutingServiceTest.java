package com.blueriver.brits.webrtc.service;

import com.blueriver.brits.webrtc.dto.SignalingMessage;
import com.blueriver.brits.webrtc.entity.CallSession;
import com.blueriver.brits.webrtc.entity.CallSessionRepository;
import com.blueriver.brits.webrtc.entity.CallStatus;
import com.blueriver.brits.webrtc.websocket.SignalingPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Drives the real routing logic with a fake socket layer and an in-memory repository. Timers are shortened to 1 s. */
class CallRoutingServiceTest {

  static class FakePublisher extends SignalingPublisher {
    final Set<String> online = new HashSet<>();
    final List<String> sent = new ArrayList<>();   // "to|TYPE|reason"
    FakePublisher() { super(null); }
    @Override public boolean isOnline(String id) { return online.contains(id); }
    @Override public boolean send(String id, SignalingMessage m) {
      sent.add(id + "|" + m.type() + "|" + m.reason());
      return online.contains(id);
    }
    boolean got(String to, String type) { return sent.stream().anyMatch(s -> s.startsWith(to + "|" + type)); }
    boolean got(String to, String type, String reason) { return sent.contains(to + "|" + type + "|" + reason); }
  }

  static class Fx {
    final FakePublisher out = new FakePublisher();
    final List<Object> events = new ArrayList<>();
    final CallSessionService sessions;
    final CallRoutingService routing;
    Fx() {
      CallSessionRepository repo = (CallSessionRepository) Proxy.newProxyInstance(getClass().getClassLoader(),
          new Class[]{CallSessionRepository.class}, (p, m, a) -> m.getName().equals("save") ? a[0] : null);
      sessions = new CallSessionService(repo);
      routing = new CallRoutingService(sessions, out, events::add);
      routing.ringSeconds = 1; routing.graceSeconds = 1; routing.maxWaitSeconds = 1;
    }
    void kiosk(String terminal) { out.online.add("kiosk:" + terminal); }
    void teller(String id) { out.online.add("teller:" + id); routing.setPresence(id, true); }
    CallEndedEvent lastEnd() { return (CallEndedEvent) events.get(events.size() - 1); }
    void stop() { routing.stop(); }
  }

  static SignalingMessage msg(String type, String callId) { return SignalingMessage.simple(type, callId); }
  static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { throw new RuntimeException(e); } }
  static HttpStatus statusOf(ResponseStatusException e) { return HttpStatus.valueOf(e.getStatusCode().value()); }

  @Test void happyPathFromRequestToEnd() {
    Fx fx = new Fx(); fx.kiosk("T1"); fx.teller("a");
    CallSession c = fx.routing.request("T1", "tx1");
    assertEquals(CallStatus.RINGING, c.status);
    assertEquals("a", c.tellerId);
    assertTrue(fx.out.got("teller:a", "CALL_INCOMING"));
    assertTrue(fx.out.got("kiosk:T1", "CALL_RINGING"));

    fx.routing.accept(c.id, "a");
    assertEquals(CallStatus.ACCEPTED, c.status);
    assertTrue(fx.out.got("kiosk:T1", "CALL_ACCEPTED"));

    fx.routing.relay("kiosk:T1", msg("CALL_OFFER", c.id));
    assertTrue(fx.out.got("teller:a", "CALL_OFFER"));
    fx.routing.relay("teller:a", msg("CALL_ANSWER", c.id));
    assertTrue(fx.out.got("kiosk:T1", "CALL_ANSWER"));
    fx.routing.relay("kiosk:T1", msg("ICE_CANDIDATE", c.id));
    fx.routing.relay("teller:a", msg("ICE_CANDIDATE", c.id));

    fx.routing.mediaConnected(c.id, "kiosk:T1");
    assertEquals(CallStatus.CONNECTED, c.status);

    fx.routing.end(c.id, "teller:a", "USER_ENDED");
    assertEquals(CallStatus.ENDED, c.status);
    assertTrue(fx.out.got("kiosk:T1", "CALL_ENDED", "USER_ENDED"));
    assertEquals(CallStatus.ENDED, fx.lastEnd().status());
    assertTrue(fx.sessions.find(c.id).isEmpty());
    // the teller is free again: the next call reaches them
    fx.kiosk("T2");
    assertEquals("a", fx.routing.request("T2", "tx2").tellerId);
    fx.stop();
  }

  @Test void noMediaSignalingBeforeTellerAccepts() {
    Fx fx = new Fx(); fx.kiosk("T1"); fx.teller("a");
    CallSession c = fx.routing.request("T1", "tx");
    ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> fx.routing.relay("kiosk:T1", msg("CALL_OFFER", c.id)));
    assertEquals(HttpStatus.CONFLICT, statusOf(e));
    fx.stop();
  }

  @Test void onlyKioskOffersAndOnlyTellerAnswers() {
    Fx fx = new Fx(); fx.kiosk("T1"); fx.teller("a");
    CallSession c = fx.routing.request("T1", "tx"); fx.routing.accept(c.id, "a");
    assertEquals(HttpStatus.FORBIDDEN, statusOf(assertThrows(ResponseStatusException.class, () -> fx.routing.relay("teller:a", msg("CALL_OFFER", c.id)))));
    assertEquals(HttpStatus.FORBIDDEN, statusOf(assertThrows(ResponseStatusException.class, () -> fx.routing.relay("kiosk:T1", msg("CALL_ANSWER", c.id)))));
    fx.stop();
  }

  @Test void outsidersCannotRelayOrEnd() {
    Fx fx = new Fx(); fx.kiosk("T1"); fx.teller("a"); fx.teller("b");
    CallSession c = fx.routing.request("T1", "tx");
    String other = c.tellerId.equals("a") ? "b" : "a";
    fx.routing.accept(c.id, c.tellerId);
    assertEquals(HttpStatus.FORBIDDEN, statusOf(assertThrows(ResponseStatusException.class, () -> fx.routing.relay("teller:" + other, msg("ICE_CANDIDATE", c.id)))));
    assertEquals(HttpStatus.FORBIDDEN, statusOf(assertThrows(ResponseStatusException.class, () -> fx.routing.end(c.id, "teller:" + other, "X"))));
    assertEquals(HttpStatus.FORBIDDEN, statusOf(assertThrows(ResponseStatusException.class, () -> fx.routing.relay("kiosk:T9", msg("ICE_CANDIDATE", c.id)))));
    fx.stop();
  }

  @Test void onlyTheRingingTellerCanAccept() {
    Fx fx = new Fx(); fx.kiosk("T1"); fx.teller("a"); fx.teller("b");
    CallSession c = fx.routing.request("T1", "tx");
    String other = c.tellerId.equals("a") ? "b" : "a";
    assertThrows(ResponseStatusException.class, () -> fx.routing.accept(c.id, other));
    assertEquals(CallStatus.RINGING, c.status);
    fx.stop();
  }

  @Test void rejectedCallGoesToNextTellerNeverBackToTheSameOne() {
    Fx fx = new Fx(); fx.kiosk("T1"); fx.teller("a"); fx.teller("b");
    CallSession c = fx.routing.request("T1", "tx");
    String first = c.tellerId;
    fx.routing.reject(c.id, first);
    assertNotEquals(first, c.tellerId);
    assertEquals(CallStatus.RINGING, c.status);
    String second = c.tellerId;
    fx.routing.reject(c.id, second);
    assertEquals(CallStatus.QUEUED, c.status);      // both declined: waits, is not re-offered
    assertEquals(null, c.tellerId);
    assertEquals(1, fx.routing.positionOf(c));
    fx.stop();
  }

  @Test void unansweredCallMovesOnAndTheMissedTellerIsMarkedAway() {
    Fx fx = new Fx(); fx.kiosk("T1"); fx.teller("a"); fx.teller("b");
    CallSession c = fx.routing.request("T1", "tx");
    String first = c.tellerId, second = first.equals("a") ? "b" : "a";
    sleep(1400);
    assertEquals(second, c.tellerId);
    assertEquals(CallStatus.RINGING, c.status);
    assertTrue(fx.out.got("teller:" + first, "CALL_ENDED", "RING_TIMEOUT"));
    fx.kiosk("T2");
    CallSession c2 = fx.routing.request("T2", "tx2");     // the missed teller is away, so a new call must not reach them
    assertEquals(CallStatus.QUEUED, c2.status);
    fx.stop();
  }

  @Test void singleTellerWhoMissesACallIsNotRungAgain() {
    Fx fx = new Fx(); fx.kiosk("T1"); fx.teller("a");
    CallSession c = fx.routing.request("T1", "tx");
    sleep(1400);
    assertEquals(CallStatus.QUEUED, c.status);
    assertEquals(null, c.tellerId);
    fx.stop();
  }

  @Test void customerCancelsWhileQueued() {
    Fx fx = new Fx(); fx.kiosk("T1");
    CallSession c = fx.routing.request("T1", "tx");
    assertEquals(CallStatus.QUEUED, c.status);
    fx.routing.end(c.id, "kiosk:T1", "USER_ENDED");
    assertEquals(CallStatus.ABANDONED, c.status);
    assertEquals(0, fx.routing.positionOf(c));
    assertEquals(CallStatus.ABANDONED, fx.lastEnd().status());
    fx.stop();
  }

  @Test void customerCancelsWhileRingingAndTellerIsFreed() {
    Fx fx = new Fx(); fx.kiosk("T1"); fx.kiosk("T2"); fx.teller("a");
    CallSession c = fx.routing.request("T1", "tx");
    fx.routing.end(c.id, "kiosk:T1", "USER_ENDED");
    assertEquals(CallStatus.ABANDONED, c.status);
    assertTrue(fx.out.got("teller:a", "CALL_ENDED", "USER_ENDED"));
    assertEquals("a", fx.routing.request("T2", "tx2").tellerId);
    fx.stop();
  }

  @Test void tellerWhoDisconnectsWhileRingingHandsTheCallOn() {
    Fx fx = new Fx(); fx.kiosk("T1"); fx.teller("a"); fx.teller("b");
    CallSession c = fx.routing.request("T1", "tx");
    String first = c.tellerId, second = first.equals("a") ? "b" : "a";
    fx.out.online.remove("teller:" + first);
    fx.routing.onDisconnect("teller:" + first);
    assertEquals(second, c.tellerId);
    assertEquals(CallStatus.RINGING, c.status);
    fx.stop();
  }

  @Test void kioskDisconnectEndsTheCallOnlyAfterTheGracePeriod() {
    Fx fx = new Fx(); fx.kiosk("T1"); fx.teller("a");
    CallSession c = fx.routing.request("T1", "tx"); fx.routing.accept(c.id, "a");
    fx.out.online.remove("kiosk:T1"); fx.routing.onDisconnect("kiosk:T1");
    assertEquals(CallStatus.ACCEPTED, c.status);          // still alive during grace
    sleep(1400);
    assertEquals(CallStatus.ENDED, c.status);
    assertTrue(fx.out.got("teller:a", "CALL_ENDED", "KIOSK_DISCONNECTED"));
    assertEquals(CallStatus.ENDED, fx.lastEnd().status());
    fx.stop();
  }

  @Test void socketThatComesBackWithinGraceKeepsTheCall() {
    Fx fx = new Fx(); fx.kiosk("T1"); fx.teller("a");
    CallSession c = fx.routing.request("T1", "tx"); fx.routing.accept(c.id, "a");
    fx.routing.mediaConnected(c.id, "kiosk:T1");
    fx.out.online.remove("kiosk:T1"); fx.routing.onDisconnect("kiosk:T1");
    sleep(300); fx.out.online.add("kiosk:T1");           // reconnected
    sleep(1200);
    assertEquals(CallStatus.CONNECTED, c.status);
    fx.stop();
  }

  @Test void tellerDroppingMidCallFailsItAndTellsTheKiosk() {
    Fx fx = new Fx(); fx.kiosk("T1"); fx.teller("a");
    CallSession c = fx.routing.request("T1", "tx"); fx.routing.accept(c.id, "a");
    fx.out.online.remove("teller:a"); fx.routing.onDisconnect("teller:a");
    sleep(1400);
    assertEquals(CallStatus.FAILED, c.status);
    assertTrue(fx.out.got("kiosk:T1", "CALL_FAILED", "TELLER_DISCONNECTED"));
    assertEquals("TELLER_DISCONNECTED", fx.lastEnd().reason());
    fx.stop();
  }

  @Test void customerIsToldWhenNoTellerEverAnswers() {
    Fx fx = new Fx(); fx.kiosk("T1");
    CallSession c = fx.routing.request("T1", "tx");
    sleep(1200);
    fx.routing.expireWaiting();
    assertEquals(CallStatus.ABANDONED, c.status);
    assertTrue(fx.out.got("kiosk:T1", "CALL_REJECTED", "NO_TELLER_AVAILABLE"));
    fx.stop();
  }

  @Test void aTerminalCanHaveOnlyOneActiveCall() {
    Fx fx = new Fx(); fx.kiosk("T1");
    fx.routing.request("T1", "tx");
    assertEquals(HttpStatus.CONFLICT, statusOf(assertThrows(ResponseStatusException.class, () -> fx.routing.request("T1", "tx2"))));
    fx.stop();
  }

  @Test void aTellerInACallStaysBusyEvenIfTheyToggleAvailable() {
    Fx fx = new Fx(); fx.kiosk("T1"); fx.kiosk("T2"); fx.teller("a");
    CallSession c = fx.routing.request("T1", "tx"); fx.routing.accept(c.id, "a");
    fx.routing.setPresence("a", true);
    assertEquals(CallStatus.QUEUED, fx.routing.request("T2", "tx2").status);
    fx.stop();
  }

  @Test void mediaFailureEndsTheCallAndNotifiesThePeer() {
    Fx fx = new Fx(); fx.kiosk("T1"); fx.teller("a");
    CallSession c = fx.routing.request("T1", "tx"); fx.routing.accept(c.id, "a");
    fx.routing.mediaFailed(c.id, "kiosk:T1", "ICE_FAILED");
    assertEquals(CallStatus.FAILED, c.status);
    assertTrue(fx.out.got("teller:a", "CALL_FAILED"));
    fx.stop();
  }
}
