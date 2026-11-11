package com.stm.middleware.gateway;

import com.stm.middleware.audit.AuditService;
import com.stm.middleware.auth.PortalUser;
import com.stm.middleware.common.ApiException;
import com.stm.middleware.common.Hashing;
import com.stm.middleware.telemetry.TelemetryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Kiosk administration from the portal (ADMIN only, see SecurityConfig): create a kiosk, edit its details, replace its
 * token, disable or enable it, and remove it. The terminal token is shown once, when created or replaced; only its
 * SHA-256 hash is stored. Every action is written to the audit journal under the signed-in administrator.
 */
@RestController
@RequestMapping("/api/v1/portal/terminals")
public class PortalTerminalsController {
    private static final SecureRandom RNG = new SecureRandom();

    private final JdbcClient jdbc;
    private final AuditService audit;
    private final TelemetryService telemetry;
    private final TransactionTemplate ownTx;

    public PortalTerminalsController(JdbcClient jdbc, AuditService audit, TelemetryService telemetry, PlatformTransactionManager tm) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.telemetry = telemetry;
        this.ownTx = new TransactionTemplate(tm);
        this.ownTx.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    public record CreateRequest(@NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9-]{2,49}",
                                        message = "Use 3 to 50 letters, digits and hyphens, for example STM-NBO-MOI-04") String terminalCode,
                                @NotBlank @Size(max = 20) String branchCode,
                                @Size(max = 100) String branchName,
                                @Size(max = 80) String name, @Size(max = 160) String location, @Size(max = 500) String notes) {}

    public record Created(String terminalCode, String branchCode, String token) {}

    /** Creates the kiosk and returns its terminal token ONCE. A branch that does not exist yet is created with branchName. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Created create(@AuthenticationPrincipal PortalUser admin, @Valid @RequestBody CreateRequest req) {
        String code = req.terminalCode().trim().toUpperCase();
        String branch = branch(req.branchCode(), req.branchName());
        boolean exists = jdbc.sql("SELECT count(*) FROM terminals WHERE terminal_code = :c").param("c", code).query(Long.class).single() > 0;
        if (exists) {
            throw new ApiException(HttpStatus.CONFLICT, "TERMINAL_EXISTS",
                    "A kiosk with the ID " + code + " already exists (it may have been removed: look under Removed kiosks).");
        }
        String token = newToken();
        jdbc.sql("""
                INSERT INTO terminals (terminal_code, branch_code, token_hash, name, location, notes, updated_at)
                VALUES (:c, :b, :h, :n, :l, :o, now())""")
                .param("c", code).param("b", branch).param("h", Hashing.sha256Hex(token))
                .param("n", blank(req.name())).param("l", blank(req.location())).param("o", blank(req.notes())).update();
        audit.record("TERMINAL_CREATED", code, null, Map.of("branchCode", branch, "by", by(admin)));
        telemetry.changed(code);
        return new Created(code, branch, token);
    }

    public record UpdateRequest(@NotBlank @Size(max = 20) String branchCode, @Size(max = 100) String branchName,
                                @Size(max = 80) String name, @Size(max = 160) String location, @Size(max = 500) String notes) {}

    /** Branch, name, location and notes. The kiosk ID cannot change: sessions, cash and the audit journal refer to it. */
    @PutMapping("/{terminalCode}")
    @Transactional
    public Map<String, Object> update(@AuthenticationPrincipal PortalUser admin, @PathVariable String terminalCode,
                                      @Valid @RequestBody UpdateRequest req) {
        Row t = find(terminalCode);
        String branch = branch(req.branchCode(), req.branchName());
        jdbc.sql("""
                UPDATE terminals SET branch_code = :b, name = :n, location = :l, notes = :o, updated_at = now()
                WHERE id = :id""")
                .param("b", branch).param("n", blank(req.name())).param("l", blank(req.location())).param("o", blank(req.notes()))
                .param("id", t.id()).update();
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("by", by(admin));
        if (!branch.equals(t.branchCode())) { detail.put("branchFrom", t.branchCode()); detail.put("branchTo", branch); }
        audit.record("TERMINAL_UPDATED", t.code(), t.id(), detail);
        telemetry.changed(t.code());
        return Map.of("terminalCode", t.code(), "branchCode", branch, "branchChanged", !branch.equals(t.branchCode()));
    }

    /**
     * Replaces the terminal token: the old one stops working at once and the kiosk is signed out. Returns the new token
     * ONCE. Use it when a token was shared in the wrong place, when a machine is re-imaged, or before go-live.
     */
    @PostMapping("/{terminalCode}/rotate-token")
    @Transactional
    public Created rotate(@AuthenticationPrincipal PortalUser admin, @PathVariable String terminalCode) {
        Row t = find(terminalCode);
        if ("RETIRED".equals(t.status())) throw new ApiException(HttpStatus.CONFLICT, "TERMINAL_RETIRED", "Restore this kiosk first.");
        String token = newToken();
        jdbc.sql("UPDATE terminals SET token_hash = :h, token_rotated_at = now(), updated_at = now() WHERE id = :id")
                .param("h", Hashing.sha256Hex(token)).param("id", t.id()).update();
        int signedOut = jdbc.sql("DELETE FROM device_tokens WHERE terminal_id = :id").param("id", t.id()).update();
        audit.record("TERMINAL_TOKEN_ROTATED", t.code(), t.id(), Map.of("by", by(admin), "deviceTokensRevoked", signedOut));
        return new Created(t.code(), t.branchCode(), token);
    }

    public record StatusRequest(@NotBlank @Pattern(regexp = "ACTIVE|DISABLED") String status) {}

    /**
     * DISABLED: the kiosk cannot sign in or call the gateway until it is enabled again (stored as REVOKED). Different
     * from Lock, which keeps the kiosk connected and shows "Temporarily out of service". ACTIVE also restores a removed kiosk.
     */
    @PutMapping("/{terminalCode}/status")
    @Transactional
    public Map<String, Object> status(@AuthenticationPrincipal PortalUser admin, @PathVariable String terminalCode,
                                      @Valid @RequestBody StatusRequest req) {
        Row t = find(terminalCode);
        boolean enable = "ACTIVE".equals(req.status());
        jdbc.sql("UPDATE terminals SET status = :s, retired_at = NULL, updated_at = now() WHERE id = :id")
                .param("s", enable ? "ACTIVE" : "REVOKED").param("id", t.id()).update();
        if (!enable) jdbc.sql("DELETE FROM device_tokens WHERE terminal_id = :id").param("id", t.id()).update();
        audit.record(enable ? ("RETIRED".equals(t.status()) ? "TERMINAL_RESTORED" : "TERMINAL_ENABLED") : "TERMINAL_DISABLED",
                t.code(), t.id(), Map.of("by", by(admin)));
        telemetry.changed(t.code());
        return Map.of("terminalCode", t.code(), "status", enable ? "ACTIVE" : "DISABLED");
    }

    /**
     * Removes a kiosk. One that never served a customer is deleted outright (its ID becomes free again). One with
     * history is retired instead: it can no longer sign in and leaves the fleet page, but its sessions, cash records
     * and audit entries stay, and it can be restored.
     */
    @DeleteMapping("/{terminalCode}")
    public Map<String, Object> remove(@AuthenticationPrincipal PortalUser admin, @PathVariable String terminalCode) {
        Row t = find(terminalCode);
        boolean deleted;
        try {
            deleted = Boolean.TRUE.equals(ownTx.execute(tx -> {
                for (String table : new String[]{"device_tokens", "terminal_telemetry", "cassette_inventory"}) {
                    jdbc.sql("DELETE FROM " + table + " WHERE terminal_id = :id").param("id", t.id()).update();
                }
                return jdbc.sql("DELETE FROM terminals WHERE id = :id").param("id", t.id()).update() > 0;
            }));
        } catch (DataIntegrityViolationException hasHistory) {
            deleted = false;
        }
        if (!deleted) {
            ownTx.executeWithoutResult(tx -> {
                jdbc.sql("UPDATE terminals SET status = 'RETIRED', retired_at = now(), updated_at = now() WHERE id = :id")
                        .param("id", t.id()).update();
                jdbc.sql("DELETE FROM device_tokens WHERE terminal_id = :id").param("id", t.id()).update();
            });
        }
        audit.record(deleted ? "TERMINAL_DELETED" : "TERMINAL_RETIRED", t.code(), deleted ? null : t.id(), Map.of("by", by(admin)));
        return Map.of("terminalCode", t.code(), "outcome", deleted ? "DELETED" : "RETIRED");
    }

    // ------------------------------------------------------------------ helpers

    private record Row(long id, String code, String branchCode, String status) {}

    private Row find(String terminalCode) {
        return jdbc.sql("SELECT id, terminal_code, branch_code, status FROM terminals WHERE terminal_code = :c")
                .param("c", terminalCode)
                .query((rs, n) -> new Row(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4))).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TERMINAL_NOT_FOUND", "Unknown kiosk: " + terminalCode));
    }

    /** The branch code to store; a new branch needs its name. */
    private String branch(String branchCode, String branchName) {
        String code = branchCode.trim().toUpperCase();
        if (!code.matches("[A-Z0-9][A-Z0-9-]{0,19}")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Branch code: letters, digits and hyphens only.");
        }
        boolean exists = jdbc.sql("SELECT count(*) FROM branches WHERE code = :c").param("c", code).query(Long.class).single() > 0;
        if (!exists) {
            if (branchName == null || branchName.isBlank()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "BRANCH_NOT_FOUND", "Branch " + code + " does not exist. Give its name to add it.");
            }
            jdbc.sql("INSERT INTO branches (code, name) VALUES (:c, :n)").param("c", code).param("n", branchName.trim()).update();
            audit.record("BRANCH_CREATED", "branch:" + code, null, Map.of("name", branchName.trim()));
        }
        return code;
    }

    private static String newToken() {
        byte[] raw = new byte[32];
        RNG.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String by(PortalUser admin) {
        return admin == null ? "portal-key" : admin.username();
    }
}
