package com.stm.callcontrol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** Mints room-scoped LiveKit join tokens (HS256 JWT) and verifies LiveKit webhooks. No SDK dependency. */
@Component
public class LiveKitTokens {
  private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
  private static final ObjectMapper OM = new ObjectMapper();
  @Value("${livekit.api-key}") String key;
  @Value("${livekit.api-secret}") String secret;
  @Value("${livekit.public-url}") public String publicUrl;

  public String join(String room, String identity, String name, boolean canPublish, boolean hidden, Duration ttl) {
    long now = Instant.now().getEpochSecond();
    Map<String, Object> video = new LinkedHashMap<>();
    video.put("room", room);
    video.put("roomJoin", true);
    video.put("canPublish", canPublish);
    video.put("canSubscribe", true);
    video.put("canPublishData", canPublish);
    if (hidden) video.put("hidden", true);   // supervisor silent monitoring
    Map<String, Object> claims = new LinkedHashMap<>();
    claims.put("iss", key); claims.put("sub", identity); claims.put("name", name);
    claims.put("nbf", now); claims.put("exp", now + ttl.toSeconds()); claims.put("video", video);
    try {
      String head = B64.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
      String body = B64.encodeToString(OM.writeValueAsBytes(claims));
      return head + "." + body + "." + B64.encodeToString(hmac(head + "." + body));
    } catch (Exception e) { throw new IllegalStateException(e); }
  }

  /** Server-side token for the Egress API (starts and stops recordings in one room). */
  public String recordToken(String room) {
    long now = Instant.now().getEpochSecond();
    Map<String, Object> claims = new LinkedHashMap<>();
    claims.put("iss", key); claims.put("sub", "call-control"); claims.put("nbf", now); claims.put("exp", now + 60);
    claims.put("video", Map.of("room", room, "roomRecord", true));
    try {
      String head = B64.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
      String body = B64.encodeToString(OM.writeValueAsBytes(claims));
      return head + "." + body + "." + B64.encodeToString(hmac(head + "." + body));
    } catch (Exception e) { throw new IllegalStateException(e); }
  }

  /** LiveKit signs webhooks with a JWT whose sha256 claim is the hash of the raw body. */
  public boolean verifyWebhook(String auth, String rawBody) {
    try {
      if (auth == null) return false;
      String[] p = auth.replaceFirst("^Bearer ", "").split("\\.");
      if (p.length != 3) return false;
      if (!MessageDigest.isEqual(hmac(p[0] + "." + p[1]), Base64.getUrlDecoder().decode(p[2]))) return false;
      JsonNode c = OM.readTree(Base64.getUrlDecoder().decode(p[1]));
      if (!key.equals(c.path("iss").asText())) return false;
      if (c.path("exp").asLong(0) < Instant.now().getEpochSecond()) return false;
      byte[] hash = MessageDigest.getInstance("SHA-256").digest(rawBody.getBytes(StandardCharsets.UTF_8));
      String got = c.path("sha256").asText().replace('-', '+').replace('_', '/').replace("=", "");
      String want = Base64.getEncoder().withoutPadding().encodeToString(hash);
      return MessageDigest.isEqual(got.getBytes(StandardCharsets.UTF_8), want.getBytes(StandardCharsets.UTF_8));
    } catch (Exception e) { return false; }
  }

  private byte[] hmac(String s) throws Exception {
    Mac m = Mac.getInstance("HmacSHA256");
    m.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    return m.doFinal(s.getBytes(StandardCharsets.UTF_8));
  }
}
