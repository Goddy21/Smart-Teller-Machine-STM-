package com.stm.middleware.stm.operators;

import com.stm.middleware.operators.OperatorRoles;
import com.stm.middleware.operators.PortalOperatorsController;

import com.stm.middleware.stm.core.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * O-1 / O-2: a terminal user (custodian, cash-in-transit, engineer, supervisor) signs in on the kiosk's maintenance
 * screen with username and PIN. The answer says which maintenance functions the kiosk app may offer them.
 */
@RestController
@RequestMapping("/stm/v1/operators")
public class OperatorController {
    static final int MAX_ATTEMPTS = 5;
    static final int LOCK_MINUTES = 15;
    static final int SIGN_IN_SEC = 1800;

    private final JdbcClient jdbc;
    private final StmAudit audit;

    public OperatorController(JdbcClient jdbc, StmAudit audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public record LoginRequest(@NotBlank @Size(max = 40) String username, @NotBlank @Pattern(regexp = "\\d{4,8}") String pin) {}

    private record Op(long id, String username, String fullName, String role, String status, String branchCode,
                      boolean allTerminals, String pinHash, int failed, OffsetDateTime lockedUntil) {}

    @PostMapping("/login")
    @SessionOptional
    public Map<String, Object> login(Ctx ctx, @Valid @RequestBody LoginRequest req) {
        String username = req.username().trim().toLowerCase();
        Op op = jdbc.sql("""
                SELECT id, username, full_name, role, status, branch_code, all_terminals, pin_hash, failed_attempts, locked_until
                FROM terminal_operators WHERE username = :u""").param("u", username)
                .query((rs, n) -> new Op(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getString(6), rs.getBoolean(7), rs.getString(8), rs.getInt(9),
                        rs.getObject(10, OffsetDateTime.class))).optional().orElse(null);
        if (op == null) {
            PortalOperatorsController.PINS.matches(req.pin(), DUMMY);   // same time as a real check
            audit.entity("operator:" + username, ctx.terminalId(), "OPERATOR_LOGIN_FAILED", "reason", "UNKNOWN_USER");
            throw invalid(MAX_ATTEMPTS);
        }
        if (op.lockedUntil() != null && op.lockedUntil().isAfter(OffsetDateTime.now())) {
            throw Errors.of(HttpStatus.LOCKED, "OPERATOR_LOCKED", "Too many wrong PINs. Try again later or ask an administrator to reset your PIN.",
                    "Umekosea PIN mara nyingi. Jaribu baadaye au mwombe msimamizi akubadilishie PIN.", false)
                    .detail("lockedUntil", op.lockedUntil().toString());
        }
        if (!PortalOperatorsController.PINS.matches(req.pin(), op.pinHash())) {
            int failed = op.failed() + 1;
            boolean lock = failed >= MAX_ATTEMPTS;
            jdbc.sql("""
                    UPDATE terminal_operators SET failed_attempts = :f,
                           locked_until = CASE WHEN :l THEN now() + make_interval(mins => :m) ELSE locked_until END
                    WHERE id = :id""").param("f", lock ? 0 : failed).param("l", lock).param("m", LOCK_MINUTES)
                    .param("id", op.id()).update();
            audit.entity("operator:" + username, ctx.terminalId(), "OPERATOR_LOGIN_FAILED", "reason", "WRONG_PIN", "locked", lock);
            throw invalid(lock ? 0 : MAX_ATTEMPTS - failed);
        }
        if (!"ACTIVE".equals(op.status())) {
            throw Errors.forbidden("This terminal user is disabled.");
        }
        if (!allowedHere(op, ctx.terminalId())) {
            audit.entity("operator:" + username, ctx.terminalId(), "OPERATOR_LOGIN_FAILED", "reason", "NOT_ASSIGNED");
            throw Errors.forbidden("This terminal user is not assigned to this kiosk.");
        }
        jdbc.sql("""
                UPDATE terminal_operators SET failed_attempts = 0, locked_until = NULL, last_login_at = now(), last_terminal = :t
                WHERE id = :id""").param("t", ctx.deviceId()).param("id", op.id()).update();
        audit.entity("operator:" + username, ctx.terminalId(), "OPERATOR_SIGNED_IN", "role", op.role(), "device", ctx.deviceId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("operatorRef", "OPR-" + op.id());
        out.put("username", op.username());
        out.put("fullName", op.fullName());
        out.put("role", op.role());
        out.put("functions", OperatorRoles.FUNCTIONS.getOrDefault(op.role(), List.of()));
        out.put("expiresInSec", SIGN_IN_SEC);
        return out;
    }

    public record LogoutRequest(@NotBlank @Size(max = 30) String operatorRef, @Size(max = 200) String summary) {}

    @PostMapping("/logout")
    @SessionOptional
    public Map<String, Object> logout(Ctx ctx, @Valid @RequestBody LogoutRequest req) {
        String username = jdbc.sql("SELECT username FROM terminal_operators WHERE 'OPR-' || id = :r").param("r", req.operatorRef())
                .query(String.class).optional().orElse(req.operatorRef());
        audit.entity("operator:" + username, ctx.terminalId(), "OPERATOR_SIGNED_OUT", "summary",
                req.summary() == null ? "" : req.summary());
        return Map.of("signedOut", true);
    }

    private boolean allowedHere(Op op, long terminalId) {
        if (op.allTerminals()) {
            if (op.branchCode() == null) return true;
            return jdbc.sql("SELECT count(*) FROM terminals WHERE id = :t AND branch_code = :b")
                    .param("t", terminalId).param("b", op.branchCode()).query(Long.class).single() > 0;
        }
        return jdbc.sql("SELECT count(*) FROM terminal_operator_kiosks WHERE operator_id = :o AND terminal_id = :t")
                .param("o", op.id()).param("t", terminalId).query(Long.class).single() > 0;
    }

    private static StmException invalid(int attemptsLeft) {
        return Errors.of(HttpStatus.UNAUTHORIZED, "OPERATOR_INVALID", "Wrong username or PIN.", "Jina au PIN si sahihi.", false)
                .detail("attemptsLeft", attemptsLeft);
    }

    private static final String DUMMY = PortalOperatorsController.PINS.encode("000000-dummy");
}
