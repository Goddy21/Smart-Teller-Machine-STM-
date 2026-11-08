package com.stm.callcontrol;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** Drives the REAL LiveKitTokens class: mints tokens and receives/verifies real LiveKit webhooks. */
public class LkTool {
  public static void main(String[] a) throws Exception {
    LiveKitTokens t = new LiveKitTokens();
    t.key = "devkey"; t.secret = "change-me-to-a-32+char-secret-value-0000"; t.publicUrl = "ws://127.0.0.1:7880";
    switch (a[0]) {
      case "join" -> System.out.println(t.join(a[1], a[2], a[3], Boolean.parseBoolean(a[4]), Boolean.parseBoolean(a[5]), Duration.ofSeconds(Long.parseLong(a[6]))));
      case "badsecret" -> { t.secret = "x".repeat(40); System.out.println(t.join(a[1], a[2], a[3], true, false, Duration.ofSeconds(120))); }
      case "record" -> System.out.println(t.recordToken(a[1]));
      case "webhooks" -> {
        ObjectMapper om = new ObjectMapper();
        Path out = Path.of(a[2]);
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", Integer.parseInt(a[1])), 0);
        s.createContext("/webhook", ex -> {
          String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          String auth = ex.getRequestHeaders().getFirst("Authorization");
          Map<String, Object> rec = new LinkedHashMap<>();
          rec.put("verified", t.verifyWebhook(auth, body));
          rec.put("tamperedBody", t.verifyWebhook(auth, body + " "));
          rec.put("tamperedSig", auth != null && t.verifyWebhook(auth.substring(0, auth.length() - 3) + "AAA", body));
          rec.put("noAuth", t.verifyWebhook(null, body));
          rec.put("contentType", ex.getRequestHeaders().getFirst("Content-Type"));
          rec.put("body", body);
          Files.writeString(out, om.writeValueAsString(rec) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
          ex.sendResponseHeaders(200, -1); ex.close();
        });
        s.start();
        System.out.println("webhook receiver listening"); Thread.sleep(Long.MAX_VALUE);
      }
      default -> throw new IllegalArgumentException(a[0]);
    }
  }
}
