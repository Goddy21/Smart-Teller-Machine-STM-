package com.blueriver.brits.webrtc.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/** Coturn "TURN REST API" credentials: username = expiry:identity, password = base64(HMAC-SHA1(secret, username)).
 *  They expire by themselves; the shared secret stays on the server. */
@Service
public class TurnCredentialService {
  public record IceConfig(List<Map<String, Object>> iceServers, long expiresAt) {}

  @Value("${turn.host}") String host;
  @Value("${turn.port:3478}") int port;
  @Value("${turn.tls-port:5349}") int tlsPort;
  @Value("${turn.secret}") String secret;
  @Value("${turn.ttl-seconds:600}") long ttl;

  public IceConfig issue(String identity) {
    long expires = Instant.now().getEpochSecond() + ttl;
    String user = expires + ":" + identity.replace(':', '-');
    String credential = Base64.getEncoder().encodeToString(hmacSha1(user));
    List<Map<String, Object>> servers = List.of(
        Map.of("urls", List.of("stun:" + host + ":" + port)),
        Map.of("urls", List.of(
                "turn:" + host + ":" + port + "?transport=udp",
                "turn:" + host + ":" + port + "?transport=tcp",
                "turns:" + host + ":" + tlsPort + "?transport=tcp"),
            "username", user, "credential", credential));
    return new IceConfig(servers, expires);
  }

  private byte[] hmacSha1(String data) {
    try {
      Mac mac = Mac.getInstance("HmacSHA1");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
      return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    } catch (Exception e) { throw new IllegalStateException(e); }
  }
}
