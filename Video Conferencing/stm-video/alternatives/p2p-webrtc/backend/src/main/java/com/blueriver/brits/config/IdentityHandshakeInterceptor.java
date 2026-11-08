package com.blueriver.brits.config;

import com.blueriver.brits.webrtc.dto.CallIdentity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.util.MultiValueMap;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;

/** Reads the identity set by the gateway (X-Terminal-Id / X-Teller-Id). In dev mode only, the socket URL may carry
 *  ?terminal= or ?teller= because browsers cannot set headers on a WebSocket. Replace with BRITS authentication. */
public class IdentityHandshakeInterceptor implements HandshakeInterceptor {
  private final boolean dev;

  public IdentityHandshakeInterceptor(boolean dev) { this.dev = dev; }

  @Override
  public boolean beforeHandshake(ServerHttpRequest req, ServerHttpResponse res, WebSocketHandler h, Map<String, Object> attrs) {
    HttpHeaders headers = req.getHeaders();
    String terminal = headers.getFirst("X-Terminal-Id"), teller = headers.getFirst("X-Teller-Id");
    if (dev && terminal == null && teller == null) {
      MultiValueMap<String, String> q = UriComponentsBuilder.fromUri(req.getURI()).build().getQueryParams();
      terminal = q.getFirst("terminal");
      teller = q.getFirst("teller");
    }
    try {
      attrs.put("identity", CallIdentity.resolve(terminal, teller).key());
      return true;
    } catch (ResponseStatusException e) {
      res.setStatusCode(HttpStatus.UNAUTHORIZED);
      return false;
    }
  }

  @Override
  public void afterHandshake(ServerHttpRequest req, ServerHttpResponse res, WebSocketHandler h, Exception ex) { }
}
