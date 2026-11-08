package com.blueriver.brits.webrtc.controller;

import com.blueriver.brits.webrtc.dto.CallIdentity;
import com.blueriver.brits.webrtc.dto.CallRequest;
import com.blueriver.brits.webrtc.dto.CallResponse;
import com.blueriver.brits.webrtc.entity.CallSession;
import com.blueriver.brits.webrtc.entity.CallStatus;
import com.blueriver.brits.webrtc.service.CallRoutingService;
import com.blueriver.brits.webrtc.service.CallSessionService;
import com.blueriver.brits.webrtc.service.TurnCredentialService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/stm/calls")
public class CallController {
  private final CallRoutingService routing;
  private final CallSessionService sessions;
  private final TurnCredentialService turn;

  public CallController(CallRoutingService routing, CallSessionService sessions, TurnCredentialService turn) {
    this.routing = routing; this.sessions = sessions; this.turn = turn;
  }

  private static CallIdentity who(String terminal, String teller) { return CallIdentity.resolve(terminal, teller); }
  private static void requireKiosk(CallIdentity w) { if (!w.isKiosk()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "kiosk only"); }
  private static void requireTeller(CallIdentity w) { if (w.isKiosk()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "teller only"); }

  @PostMapping("/request")
  public CallResponse request(@RequestHeader(value = "X-Terminal-Id", required = false) String terminal,
                              @RequestHeader(value = "X-Teller-Id", required = false) String teller,
                              @RequestBody(required = false) CallRequest body) {
    CallIdentity w = who(terminal, teller);
    requireKiosk(w);
    CallSession c = routing.request(w.id(), body == null ? null : body.txId());
    return new CallResponse(c.id, c.status.name(), routing.positionOf(c));
  }

  @GetMapping("/queue")
  public List<Map<String, Object>> queue(@RequestHeader(value = "X-Terminal-Id", required = false) String terminal,
                                         @RequestHeader(value = "X-Teller-Id", required = false) String teller) {
    requireTeller(who(terminal, teller));
    return routing.snapshotQueue();
  }

  @PostMapping("/{id}/accept")
  public CallResponse accept(@PathVariable String id,
                             @RequestHeader(value = "X-Terminal-Id", required = false) String terminal,
                             @RequestHeader(value = "X-Teller-Id", required = false) String teller) {
    CallIdentity w = who(terminal, teller);
    requireTeller(w);
    routing.accept(id, w.id());
    return new CallResponse(id, CallStatus.ACCEPTED.name(), 0);
  }

  @PostMapping("/{id}/reject")
  public void reject(@PathVariable String id,
                     @RequestHeader(value = "X-Terminal-Id", required = false) String terminal,
                     @RequestHeader(value = "X-Teller-Id", required = false) String teller) {
    CallIdentity w = who(terminal, teller);
    requireTeller(w);
    routing.reject(id, w.id());
  }

  @PostMapping("/{id}/end")
  public void end(@PathVariable String id,
                  @RequestHeader(value = "X-Terminal-Id", required = false) String terminal,
                  @RequestHeader(value = "X-Teller-Id", required = false) String teller) {
    routing.end(id, who(terminal, teller).key(), "USER_ENDED");
  }

  /** Short-lived TURN credentials, only for the two parties of an accepted call. */
  @GetMapping("/{id}/turn-credentials")
  public TurnCredentialService.IceConfig turnCredentials(@PathVariable String id,
                                                         @RequestHeader(value = "X-Terminal-Id", required = false) String terminal,
                                                         @RequestHeader(value = "X-Teller-Id", required = false) String teller) {
    CallIdentity w = who(terminal, teller);
    CallSession c = sessions.require(id);
    if (!w.key().equals(c.kioskIdentity()) && !w.key().equals(c.tellerIdentity())) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    if (c.status != CallStatus.ACCEPTED && c.status != CallStatus.CONNECTED)
      throw new ResponseStatusException(HttpStatus.CONFLICT, "call is not accepted");
    return turn.issue(w.key());
  }
}
