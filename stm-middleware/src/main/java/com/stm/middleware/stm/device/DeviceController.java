package com.stm.middleware.stm.device;

import com.stm.middleware.config.StmProperties;
import com.stm.middleware.stm.cash.Cassettes;
import com.stm.middleware.stm.core.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * Device and session APIs (D-1 .. D-7) and the cassette view (X-6).
 * The kiosk calls only the Gateway; hardware results from the device agent arrive in these calls.
 */
@RestController
@RequestMapping("/stm/v1")
public class DeviceController {
    private final DeviceTokenService tokens;
    private final SessionStore sessions;
    private final CustomerTokens customerTokens;
    private final DeviceHealthService health;
    private final Cassettes cassettes;
    private final StmAudit audit;
    private final StmProperties props;
    private final JdbcClient jdbc;
    private final int inactivityTimeoutSec;

    public DeviceController(DeviceTokenService tokens, SessionStore sessions, CustomerTokens customerTokens,
                            DeviceHealthService health, Cassettes cassettes, StmAudit audit, StmProperties props,
                            JdbcClient jdbc, @Value("${stm.session.inactivity-timeout-sec:60}") int inactivityTimeoutSec) {
        this.tokens = tokens;
        this.sessions = sessions;
        this.customerTokens = customerTokens;
        this.health = health;
        this.cassettes = cassettes;
        this.audit = audit;
        this.props = props;
        this.jdbc = jdbc;
        this.inactivityTimeoutSec = inactivityTimeoutSec;
    }

    // ------------------------------------------------------------------ D-1

    public record TokenRequest(@NotBlank @Size(max = 50) String deviceId, @Size(max = 40) String softwareVersion,
                               @Size(max = 100) String nonce) {}

    /**
     * Production: the mTLS client certificate CN must be the deviceId. Development (mTLS off): send the terminal's
     * provisioning token from POST /api/v1/terminals/register as "Authorization: Bearer ...".
     */
    @PostMapping("/auth/device/token")
    public Map<String, Object> token(@Valid @RequestBody TokenRequest req, HttpServletRequest http) {
        DevicePrincipal d;
        if (props.requireClientCert()) {
            d = DeviceAuthFilter.clientCertCn(http).filter(req.deviceId()::equals)
                    .flatMap(tokens::byDeviceId).orElseThrow(Errors::deviceUnauthorized);
        } else {
            String h = http.getHeader("Authorization");
            if (h == null || !h.startsWith("Bearer ")) throw Errors.deviceUnauthorized();
            d = tokens.byRegistrationToken(req.deviceId(), h.substring(7).trim()).orElseThrow(Errors::deviceUnauthorized);
        }
        if (req.softwareVersion() != null) {
            jdbc.sql("UPDATE terminals SET software_version = :v WHERE id = :id")
                    .param("v", req.softwareVersion()).param("id", d.terminalId()).update();
        }
        cassettes.ensureSeeded(d.terminalId(), d.deviceId());
        DeviceTokenService.Issued t = tokens.issue(d.terminalId());
        String branchName = jdbc.sql("SELECT name FROM branches WHERE code = :c").param("c", d.branchCode())
                .query(String.class).optional().orElse(d.branchCode());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("accessToken", t.accessToken());
        out.put("expiresIn", t.expiresIn());
        out.put("branchCode", d.branchCode());
        out.put("branchName", branchName);
        return out;
    }

    // ------------------------------------------------------------------ D-2 .. D-4

    public record StartRequest(@NotBlank @Pattern(regexp = "TOUCH|CARD") String trigger,
                               @Pattern(regexp = "en|sw") String language) {}

    @PostMapping("/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    @SessionOptional
    public Map<String, Object> start(Ctx ctx, @Valid @RequestBody StartRequest req) {
        health.requireInService(ctx.terminalId());
        // A kiosk runs one customer at a time: a session left open (page reload, crash) is closed first.
        List<String> stale = jdbc.sql("SELECT id FROM stm_sessions WHERE terminal_id = :t AND status = 'ACTIVE'")
                .param("t", ctx.terminalId()).query(String.class).list();
        for (String id : stale) {
            sessions.end(id, "SUPERSEDED", false);
            customerTokens.revokeAll(id);
        }
        StmSession s = sessions.start(ctx.terminalId(), req.trigger(), req.language() == null ? "en" : req.language());
        audit.entity(s.id(), ctx.terminalId(), "SESSION_STARTED", "trigger", req.trigger(), "language", s.language(),
                "device", ctx.deviceId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessionId", s.id());
        out.put("expiresAt", s.expiresAt());
        out.put("inactivityTimeoutSec", inactivityTimeoutSec);
        out.put("services", health.services(ctx.terminalId()));
        return out;
    }

    public record LanguageRequest(@NotBlank @Pattern(regexp = "en|sw") String language) {}

    @PatchMapping("/sessions/{sessionId}")
    public Map<String, Object> language(Ctx ctx, @PathVariable String sessionId, @Valid @RequestBody LanguageRequest req) {
        sameSession(ctx, sessionId);
        sessions.setLanguage(sessionId, req.language());
        return Map.of("sessionId", sessionId, "language", req.language());
    }

    public record EndRequest(@Size(max = 40) String reason, Boolean cardRetained) {}

    @PostMapping("/sessions/{sessionId}/end")
    @AllowEndedSession
    public Map<String, Object> end(Ctx ctx, @PathVariable String sessionId, @RequestBody(required = false) EndRequest req) {
        sameSession(ctx, sessionId);
        String reason = req == null || req.reason() == null ? "CUSTOMER_FINISHED" : req.reason();
        boolean retained = req != null && Boolean.TRUE.equals(req.cardRetained());
        List<Map<String, Object>> open = openItems(sessionId);
        boolean wasActive = ctx.session().active();
        sessions.end(sessionId, reason, retained);
        customerTokens.revokeAll(sessionId);
        jdbc.sql("UPDATE fx_quotes SET used_at = coalesce(used_at, now()) WHERE session_id = :s").param("s", sessionId).update();
        jdbc.sql("UPDATE fee_quotes SET used_at = coalesce(used_at, now()) WHERE session_id = :s").param("s", sessionId).update();
        if (wasActive) {
            audit.session(ctx, "SESSION_ENDED", "reason", reason, "cardRetained", retained, "openItems", open.size());
        }
        return Map.of("ended", true, "openItems", open);
    }

    private List<Map<String, Object>> openItems(String sessionId) {
        List<Map<String, Object>> items = new ArrayList<>();
        jdbc.sql("SELECT id, status FROM cash_deposits WHERE session_id = :s AND status IN ('AWAITING_CASH', 'COUNTED')")
                .param("s", sessionId).query(rs -> { items.add(item("CASH_DEPOSIT", rs.getString(1), rs.getString(2), "K-5 cancel")); });
        jdbc.sql("SELECT id, status FROM cash_withdrawals WHERE session_id = :s AND status = 'AUTHORISED'")
                .param("s", sessionId).query(rs -> { items.add(item("CASH_WITHDRAWAL", rs.getString(1), rs.getString(2), "W-3 confirm or W-4 reverse")); });
        jdbc.sql("SELECT id, status FROM fx_exchanges WHERE session_id = :s AND status IN ('AWAITING_CASH', 'COUNTED')")
                .param("s", sessionId).query(rs -> { items.add(item("FX_EXCHANGE", rs.getString(1), rs.getString(2), "X cancel")); });
        jdbc.sql("SELECT id, status FROM card_orders WHERE session_id = :s AND status IN ('PERSONALISING', 'READY')")
                .param("s", sessionId).query(rs -> { items.add(item("CARD_ORDER", rs.getString(1), rs.getString(2), "C-7 dispense")); });
        return items;
    }

    private static Map<String, Object> item(String type, String id, String status, String action) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("id", id);
        m.put("status", status);
        m.put("action", action);
        return m;
    }

    private static void sameSession(Ctx ctx, String sessionId) {
        if (!ctx.sessionId().equals(sessionId)) throw Errors.validation("sessionId", "Path session id must match X-Session-Id");
    }

    // ------------------------------------------------------------------ D-5

    @GetMapping("/config/tariffs")
    @SessionOptional
    public Map<String, Object> tariffs(Ctx ctx) {
        List<Map<String, Object>> list = jdbc.sql("SELECT code, label, fee, currency, includes_excise, version FROM tariffs ORDER BY code")
                .query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("code", rs.getString("code"));
                    m.put("label", rs.getString("label"));
                    m.put("fee", Money.of(rs.getBigDecimal("fee"), rs.getString("currency")));
                    if (rs.getBoolean("includes_excise")) m.put("includesExcise", true);
                    return m;
                }).list();
        String version = jdbc.sql("SELECT max(version) FROM tariffs").query(String.class).single();
        return Map.of("tariffs", list, "version", version);
    }

    // ------------------------------------------------------------------ D-6 / D-7 / X-6

    public record HealthReport(@NotNull Map<String, Object> modules, OffsetDateTime reportedAt) {}

    @PostMapping("/devices/{deviceId}/health")
    @SessionOptional
    public Map<String, Object> health(Ctx ctx, @PathVariable String deviceId, @Valid @RequestBody HealthReport req) {
        sameDevice(ctx, deviceId);
        List<String> disabled = health.report(ctx.terminalId(), ctx.deviceId(), req.modules(), req.reportedAt());
        return Map.of("accepted", true, "disabledServices", disabled);
    }

    @GetMapping("/devices/{deviceId}/status")
    @SessionOptional
    public DeviceHealthService.Status status(Ctx ctx, @PathVariable String deviceId) {
        sameDevice(ctx, deviceId);
        return health.status(ctx.terminalId());
    }

    @GetMapping("/devices/{deviceId}/cassettes")
    @SessionOptional
    public Map<String, Object> cassettes(Ctx ctx, @PathVariable String deviceId) {
        sameDevice(ctx, deviceId);
        cassettes.ensureSeeded(ctx.terminalId(), ctx.deviceId());
        return Map.of("cassettes", cassettes.view(ctx.terminalId()));
    }

    static void sameDevice(Ctx ctx, String deviceId) {
        if (!ctx.deviceId().equals(deviceId)) throw Errors.forbidden("A kiosk can only report or read its own device.");
    }
}
