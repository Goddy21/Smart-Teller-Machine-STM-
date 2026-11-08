package com.stm.middleware.stm.device;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stm.middleware.stm.core.Errors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Talks to stm-video's call-control on behalf of a kiosk (D-8, D-9). The Gateway has already authenticated the kiosk,
 * so it passes the identity in X-Terminal-Id, exactly the role call-control expects its gateway to play.
 */
@Component
public class CallControlClient {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final ObjectMapper mapper;
    private final String base;

    public CallControlClient(ObjectMapper mapper, @Value("${stm.call-control.url:http://localhost:8090}") String base) {
        this.mapper = mapper;
        this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    public record Created(String callId, String state) {}

    public Created create(String terminal, String lang, String txId) {
        JsonNode n = send("POST", "/api/calls", terminal, Map.of("lang", lang, "txId", txId));
        return new Created(n.path("callId").asText(), n.path("state").asText("QUEUED"));
    }

    /** The call's state (call-control GET /api/calls/{id}, stm-video v11+). */
    public String state(String terminal, String callId) {
        return send("GET", "/api/calls/" + callId, terminal, null).path("state").asText();
    }

    /** {url, token, room}: a short-lived LiveKit join token for the kiosk, once the call is CONNECTED. */
    public JsonNode media(String terminal, String callId) {
        return send("POST", "/api/calls/" + callId + "/token", terminal, null);
    }

    public void cancel(String terminal, String callId) {
        send("DELETE", "/api/calls/" + callId, terminal, null);
    }

    private JsonNode send(String method, String path, String terminal, Object body) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(5))
                    .header("X-Terminal-Id", terminal).header("Accept", "application/json");
            if (body != null) {
                b.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            } else {
                b.method(method, HttpRequest.BodyPublishers.noBody());
            }
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() == 404) throw Errors.notFound("CALL_NOT_FOUND", "Call not found", "Simu haikupatikana");
            if (r.statusCode() == 409) throw Errors.wrongState("The call is not connected yet.");
            if (r.statusCode() >= 300) throw Errors.videoUnavailable().detail("status", r.statusCode());
            return r.body() == null || r.body().isBlank() ? mapper.createObjectNode() : mapper.readTree(r.body());
        } catch (IOException e) {
            throw Errors.videoUnavailable();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw Errors.videoUnavailable();
        }
    }
}
