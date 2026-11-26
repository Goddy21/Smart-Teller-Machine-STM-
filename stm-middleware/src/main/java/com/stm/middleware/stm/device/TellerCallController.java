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
 * Poll GET /support/teller-calls/{callId}: QUEUED -> RINGING -> CONNECTED (with media to join) -> ENDED,
 * or NO_TELLER_AVAILABLE / CANCELLED. Every answer carries the line to show the customer (message / messageSw).
 * DELETE hangs up at any time: while waiting it cancels, once connected it ends the call for the teller too.
 */
@RestController
@RequestMapping("/stm/v1/support/teller-calls")
public class TellerCallController {
    private static final Set<String> FINAL = Set.of("ENDED", "CANCELLED", "NO_TELLER_AVAILABLE");

    /** What the kiosk shows the customer for each status (English, Kiswahili). */
    static String[] message(String status, String endedBy, String reason) {
        return switch (status) {
            case "QUEUED" -> new String[]{"Connecting you to a teller. Please wait…", "Tunakuunganisha na mhudumu. Tafadhali subiri…"};
            case "RINGING" -> new String[]{"Calling a teller…", "Tunampigia mhudumu…"};
            case "CONNECTED" -> new String[]{"You are talking to a teller.", "Unaongea na mhudumu."};
            case "NO_TELLER_AVAILABLE" -> new String[]{
                    "No teller is available right now. Please try again in a few minutes, or visit the banking hall.",
                    "Hakuna mhudumu anayepatikana sasa hivi. Tafadhali jaribu tena baada ya dakika chache, au tembelea ukumbi wa benki."};
            case "ENDED" -> "TELLER".equals(endedBy)
                    ? new String[]{"The teller has ended the call. Thank you.", "Mhudumu amemaliza simu. Asante."}
                    : "CUSTOMER".equals(endedBy)
                    ? new String[]{"You ended the call.", "Umemaliza simu."}
                    : new String[]{"The call was disconnected. You can call a teller again.",
                                   "Simu imekatika. Unaweza kumpigia mhudumu tena."};
            default -> new String[]{"The call was cancelled.", "Simu imesitishwa."};
        };
    }

    static Map<String, Object> answer(String callId, String status, String state, String endedBy, String reason) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("callId", callId);
        out.put("status", status);
        out.put("callState", state);
        if (endedBy != null) out.put("endedBy", endedBy);
        if (reason != null) out.put("reason", reason);
        String[] m = message(status, endedBy, reason);
        out.put("message", m[0]);
        out.put("messageSw", m[1]);
        return out;
    }

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
        Map<String, Object> out = answer(c.callId(), "QUEUED", c.state(), null, null);
        out.put("estimatedWaitSec", 45);
        out.put("media", null);
        return out;
    }

    @GetMapping("/{callId}")
    @AllowEndedSession
    public Map<String, Object> get(Ctx ctx, @PathVariable String callId) {
        mine(ctx, callId);
        JsonNode st = calls.status(ctx.deviceId(), callId);
        String state = st.path("state").asText();
        String endedBy = st.path("endedBy").isTextual() ? st.path("endedBy").asText() : null;
        String reason = st.path("endReason").isTextual() ? st.path("endReason").asText() : null;
        String status = switch (state) {
            case "CONNECTED", "ON_HOLD" -> "CONNECTED";
            case "RINGING" -> "RINGING";
            case "ENDED" -> "ENDED";
            case "ABANDONED" -> "NO_TELLER_AVAILABLE".equals(reason) ? "NO_TELLER_AVAILABLE" : "CANCELLED";
            default -> "QUEUED";
        };
        update(callId, status, endedBy, reason);
        Map<String, Object> out = answer(callId, status, state, endedBy, reason);
        if ("RINGING".equals(status) && st.path("ringSeconds").isNumber()) out.put("ringSeconds", st.path("ringSeconds").asInt());
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
        if (FINAL.contains(current)) return answer(callId, current, null, null, null);
        // Connected: the call ends for the teller too (their console closes it). Waiting or ringing: cancelled.
        boolean connected = "CONNECTED".equals(current);
        try {
            connected = !Set.of("QUEUED", "RINGING", "REQUESTED").contains(calls.state(ctx.deviceId(), callId));
            calls.cancel(ctx.deviceId(), callId);
        } catch (StmException e) {
            if (!"CALL_NOT_FOUND".equals(e.code())) throw e;
        }
        String status = connected ? "ENDED" : "CANCELLED";
        String reason = connected ? "CUSTOMER_HUNG_UP" : "CUSTOMER_CANCELLED";
        update(callId, status, "CUSTOMER", reason);
        audit.session(ctx, connected ? "TELLER_CALL_HUNG_UP" : "TELLER_CALL_CANCELLED", "callId", callId);
        return answer(callId, status, null, "CUSTOMER", reason);
    }

    private String mine(Ctx ctx, String callId) {
        return jdbc.sql("SELECT status FROM teller_calls WHERE id = :id AND session_id = :s")
                .param("id", callId).param("s", ctx.sessionId()).query(String.class).optional()
                .orElseThrow(() -> Errors.notFound("CALL_NOT_FOUND", "Call not found", "Simu haikupatikana"));
    }

    private void update(String callId, String status, String endedBy, String reason) {
        jdbc.sql("""
                UPDATE teller_calls SET status = :st, ended_by = coalesce(CAST(:by AS VARCHAR), ended_by),
                       end_reason = coalesce(CAST(:r AS VARCHAR), end_reason), updated_at = now()
                WHERE id = :id AND (status <> :st OR (ended_by IS NULL AND CAST(:by AS VARCHAR) IS NOT NULL))""")
                .param("st", status).param("by", endedBy).param("r", reason).param("id", callId).update();
    }
}
