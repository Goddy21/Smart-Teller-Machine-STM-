package com.blueriver.brits.webrtc.websocket;

import com.blueriver.brits.webrtc.dto.SignalingMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** The only place that knows which identity is on which socket. Sends are serialised per socket. */
@Component
public class SignalingPublisher {
  private static final Logger log = LoggerFactory.getLogger(SignalingPublisher.class);
  private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
  private final ObjectMapper om;

  public SignalingPublisher(ObjectMapper om) { this.om = om; }

  /** A newer connection replaces an older one for the same identity. */
  public void register(String identity, WebSocketSession raw) {
    WebSocketSession wrapped = new ConcurrentWebSocketSessionDecorator(raw, 5_000, 256 * 1024);
    WebSocketSession old = sessions.put(identity, wrapped);
    if (old != null) {
      try { old.close(CloseStatus.POLICY_VIOLATION.withReason("replaced by a newer connection")); } catch (Exception ignored) { }
    }
  }

  /** True only if this socket was the current one, so a replaced socket closing does not look like a disconnect. */
  public boolean unregister(String identity, WebSocketSession raw) {
    WebSocketSession cur = sessions.get(identity);
    return cur != null && cur.getId().equals(raw.getId()) && sessions.remove(identity, cur);
  }

  public boolean isOnline(String identity) {
    WebSocketSession s = sessions.get(identity);
    return s != null && s.isOpen();
  }

  public boolean send(String identity, SignalingMessage m) {
    WebSocketSession s = sessions.get(identity);
    if (s == null || !s.isOpen()) return false;
    try { s.sendMessage(new TextMessage(om.writeValueAsString(m))); return true; }
    catch (Exception e) { log.warn("send to {} failed: {}", identity, e.toString()); return false; }
  }
}
