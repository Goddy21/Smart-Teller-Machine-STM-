package com.blueriver.brits.webrtc.websocket;

import com.blueriver.brits.webrtc.dto.SignalingMessage;
import com.blueriver.brits.webrtc.service.CallRoutingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/** Signaling endpoint. Identity comes from the handshake, never from message contents. */
@Component
public class WebRTCSignalingHandler extends TextWebSocketHandler {
  private static final Logger log = LoggerFactory.getLogger(WebRTCSignalingHandler.class);
  private final ObjectMapper om;
  private final CallRoutingService routing;
  private final SignalingPublisher out;

  public WebRTCSignalingHandler(ObjectMapper om, CallRoutingService routing, SignalingPublisher out) {
    this.om = om; this.routing = routing; this.out = out;
  }

  private static String identity(WebSocketSession s) { return (String) s.getAttributes().get("identity"); }

  @Override
  public void afterConnectionEstablished(WebSocketSession session) {
    out.register(identity(session), session);
    log.info("signaling connected {}", identity(session));
  }

  @Override
  protected void handleTextMessage(WebSocketSession session, TextMessage text) {
    String who = identity(session);
    SignalingMessage m = null;
    try {
      m = om.readValue(text.getPayload(), SignalingMessage.class);
      switch (m.type() == null ? "" : m.type()) {
        case "PING" -> { }   // keeps the idle timeout from firing
        case "PRESENCE" -> {
          if (!who.startsWith("teller:")) throw new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN);
          routing.setPresence(who.substring("teller:".length()), Boolean.TRUE.equals(m.available()));
        }
        case "CALL_OFFER", "CALL_ANSWER", "ICE_CANDIDATE" -> routing.relay(who, m);
        case "MEDIA_CONNECTED" -> routing.mediaConnected(m.callId(), who);
        case "MEDIA_FAILED" -> routing.mediaFailed(m.callId(), who, m.reason());
        case "CALL_ENDED" -> routing.end(m.callId(), who, "USER_ENDED");
        default -> throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "unknown message type");
      }
    } catch (ResponseStatusException e) {
      out.send(who, SignalingMessage.withReason("ERROR", m == null ? null : m.callId(), e.getReason()));
    } catch (Exception e) {
      log.warn("bad signaling message from {}: {}", who, e.toString());
      out.send(who, SignalingMessage.withReason("ERROR", null, "malformed message"));
    }
  }

  @Override
  public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
    String who = identity(session);
    if (out.unregister(who, session)) {
      log.info("signaling disconnected {} ({})", who, status);
      routing.onDisconnect(who);
    }
  }
}
