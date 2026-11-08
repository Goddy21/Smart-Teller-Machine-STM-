package com.stm.middleware.stm.device;

import com.fasterxml.jackson.databind.JsonNode;
import com.stm.middleware.stm.core.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * "Talk to a teller" (D-8, D-9). The call is placed with call-control; the customer session stays open.
 * The session id travels as the call's transaction id, so the teller's console shows this session next to the video.
 * Poll GET /support/teller-calls/{callId}: QUEUED -> CONNECTED (with media to join) -> ENDED.
 */
@RestController
@RequestMapping("/stm/v1/support/teller-calls")
public class TellerCallController {
    private static final Set<String> FINAL = Set.of("ENDED", "CANCELLED");

    private final CallControlClient calls;
    private final JdbcClient jdbc;
    private final StmAudit audit;

    public TellerCallController(CallControlClient calls, JdbcClient jdbc, StmAudit audit) {
        this.calls = calls;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public record CallRequest(@Size(max = 10) String screen, @Size(max = 30) String flow,
                              @Size(max = 40) String applicationId, @Pattern(regexp = "en|sw") String language) {}

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(Ctx ctx, @Valid @RequestBody(required = false) CallRequest req) {
        CallRequest r = req == null ? new CallRequest(null, null, null, null) : req;
        String lang = r.language() != null ? r.language() : ctx.lang();
        CallControlClient.Created c = calls.create(ctx.deviceId(), lang, ctx.sessionId());
        jdbc.sql("""
                INSERT INTO teller_calls (id, session_id, terminal_id, screen, flow, application_id, language, status)
                VALUES (:id, :s, :t, :sc, :f, :a, :l, 'QUEUED')""")
                .param("id", c.callId()).param("s", ctx.sessionId()).param("t", ctx.terminalId()).param("sc", r.screen())
                .param("f", r.flow()).param("a", r.applicationId()).param("l", lang).update();
        audit.session(ctx, "TELLER_CALL_REQUESTED", "callId", c.callId(), "screen", r.screen(), "flow", r.flow(),
                "applicationId", r.applicationId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("callId", c.callId());
        out.put("status", "QUEUED");
        out.put("estimatedWaitSec", 45);
        out.put("media", null);
        return out;
    }

    @GetMapping("/{callId}")
    @AllowEndedSession
    public Map<String, Object> get(Ctx ctx, @PathVariable String callId) {
        mine(ctx, callId);
        String state = calls.state(ctx.deviceId(), callId);
        String status = switch (state) {
            case "CONNECTED", "ON_HOLD" -> "CONNECTED";
            case "ENDED" -> "ENDED";
            case "ABANDONED" -> "CANCELLED";
            default -> "QUEUED";
        };
        update(callId, status);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("callId", callId);
        out.put("status", status);
        out.put("callState", state);
        if ("CONNECTED".equals(status)) {
            JsonNode m = calls.media(ctx.deviceId(), callId);
            Map<String, Object> media = new LinkedHashMap<>();
            media.put("provider", "webrtc");
            media.put("roomUrl", m.path("url").asText());
            media.put("room", m.path("room").asText());
            media.put("token", m.path("token").asText());
            out.put("media", media);
        } else {
            out.put("media", null);
        }
        return out;
    }

    @DeleteMapping("/{callId}")
    @AllowEndedSession
    public Map<String, Object> cancel(Ctx ctx, @PathVariable String callId) {
        String current = mine(ctx, callId);
        if (!FINAL.contains(current)) {
            try {
                calls.cancel(ctx.deviceId(), callId);
            } catch (StmException e) {
                if (!"CALL_NOT_FOUND".equals(e.code())) throw e;
            }
            update(callId, "CANCELLED");
            audit.session(ctx, "TELLER_CALL_CANCELLED", "callId", callId);
        }
        return Map.of("callId", callId, "status", "CANCELLED");
    }

    private String mine(Ctx ctx, String callId) {
        return jdbc.sql("SELECT status FROM teller_calls WHERE id = :id AND session_id = :s")
                .param("id", callId).param("s", ctx.sessionId()).query(String.class).optional()
                .orElseThrow(() -> Errors.notFound("CALL_NOT_FOUND", "Call not found", "Simu haikupatikana"));
    }

    private void update(String callId, String status) {
        jdbc.sql("UPDATE teller_calls SET status = :st, updated_at = now() WHERE id = :id AND status <> :st")
                .param("st", status).param("id", callId).update();
    }
}
