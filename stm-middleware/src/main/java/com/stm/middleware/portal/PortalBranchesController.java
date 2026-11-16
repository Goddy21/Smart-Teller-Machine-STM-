package com.stm.middleware.portal;

import com.stm.middleware.audit.AuditService;
import com.stm.middleware.auth.PortalUser;
import com.stm.middleware.common.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/** Branches: list for every portal user; add, edit, close and remove for ADMIN (SecurityConfig). */
@RestController
@RequestMapping("/api/v1/portal/branches")
public class PortalBranchesController {
    private final JdbcClient jdbc;
    private final Rows rows;
    private final AuditService audit;

    public PortalBranchesController(JdbcClient jdbc, Rows rows, AuditService audit) {
        this.jdbc = jdbc;
        this.rows = rows;
        this.audit = audit;
    }

    private static final String SELECT = """
            SELECT b.code, b.name, b.location, b.region, b.status, b.created_at, b.updated_at,
                   (SELECT count(*) FROM terminals t WHERE t.branch_code = b.code AND t.status <> 'RETIRED') AS terminals,
                   (SELECT count(*) FROM terminals t WHERE t.branch_code = b.code AND t.status = 'ACTIVE') AS terminals_active,
                   (SELECT count(*) FROM portal_users u WHERE u.branch_code = b.code AND u.status <> 'ARCHIVED') AS users,
                   (SELECT count(*) FROM terminal_operators o WHERE o.branch_code = b.code) AS terminal_users,
                   (SELECT count(*) FROM stm_sessions s JOIN terminals t ON t.id = s.terminal_id
                     WHERE t.branch_code = b.code AND s.started_at >= date_trunc('day', now())) AS sessions_today
            FROM branches b""";

    @GetMapping
    public List<Map<String, Object>> list() {
        return rows.list(SELECT + " ORDER BY b.code");
    }

    public record CreateRequest(@NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9-]{0,19}",
                                        message = "Use up to 20 letters, digits and hyphens, for example NBO-MOI") String code,
                                @NotBlank @Size(max = 100) String name,
                                @Size(max = 160) String location, @Size(max = 60) String region) {}

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@AuthenticationPrincipal PortalUser admin, @Valid @RequestBody CreateRequest req) {
        String code = req.code().trim().toUpperCase();
        if (exists(code)) throw new ApiException(HttpStatus.CONFLICT, "BRANCH_EXISTS", "Branch " + code + " already exists.");
        jdbc.sql("INSERT INTO branches (code, name, location, region) VALUES (:c, :n, :l, :r)")
                .param("c", code).param("n", req.name().trim()).param("l", blank(req.location())).param("r", blank(req.region()))
                .update();
        audit.record("BRANCH_CREATED", "branch:" + code, null, Map.of("name", req.name().trim(), "by", by(admin)));
        return one(code);
    }

    public record UpdateRequest(@NotBlank @Size(max = 100) String name, @Size(max = 160) String location,
                                @Size(max = 60) String region) {}

    @PutMapping("/{code}")
    public Map<String, Object> update(@AuthenticationPrincipal PortalUser admin, @PathVariable String code,
                                      @Valid @RequestBody UpdateRequest req) {
        String c = known(code);
        jdbc.sql("UPDATE branches SET name = :n, location = :l, region = :r, updated_at = now() WHERE code = :c")
                .param("c", c).param("n", req.name().trim()).param("l", blank(req.location())).param("r", blank(req.region()))
                .update();
        audit.record("BRANCH_UPDATED", "branch:" + c, null, Map.of("name", req.name().trim(), "by", by(admin)));
        return one(c);
    }

    public record StatusRequest(@NotBlank @Pattern(regexp = "ACTIVE|CLOSED") String status) {}

    /** A closed branch keeps its history; no new kiosks can be added to it. Its kiosks must be moved or disabled first. */
    @PutMapping("/{code}/status")
    public Map<String, Object> status(@AuthenticationPrincipal PortalUser admin, @PathVariable String code,
                                      @Valid @RequestBody StatusRequest req) {
        String c = known(code);
        if ("CLOSED".equals(req.status())) {
            long active = jdbc.sql("SELECT count(*) FROM terminals WHERE branch_code = :c AND status = 'ACTIVE'")
                    .param("c", c).query(Long.class).single();
            if (active > 0) {
                throw new ApiException(HttpStatus.CONFLICT, "BRANCH_HAS_KIOSKS",
                        "Branch " + c + " still has " + active + " active kiosk(s). Move them to another branch or disable them first.");
            }
        }
        jdbc.sql("UPDATE branches SET status = :s, updated_at = now() WHERE code = :c").param("s", req.status()).param("c", c).update();
        audit.record("BRANCH_" + req.status(), "branch:" + c, null, Map.of("by", by(admin)));
        return one(c);
    }

    /** Only a branch nothing points to can be deleted; otherwise close it. */
    @DeleteMapping("/{code}")
    public Map<String, Object> remove(@AuthenticationPrincipal PortalUser admin, @PathVariable String code) {
        String c = known(code);
        long used = jdbc.sql("""
                SELECT (SELECT count(*) FROM terminals WHERE branch_code = :c)
                     + (SELECT count(*) FROM portal_users WHERE branch_code = :c)
                     + (SELECT count(*) FROM terminal_operators WHERE branch_code = :c)""").param("c", c).query(Long.class).single();
        if (used > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "BRANCH_IN_USE",
                    "Branch " + c + " has kiosks or users (now or in the past). Close it instead of deleting it.");
        }
        jdbc.sql("DELETE FROM branches WHERE code = :c").param("c", c).update();
        audit.record("BRANCH_DELETED", "branch:" + c, null, Map.of("by", by(admin)));
        return Map.of("code", c, "deleted", true);
    }

    private Map<String, Object> one(String code) {
        return rows.one(SELECT + " WHERE b.code = :c", Map.of("c", code)).orElseThrow();
    }

    private boolean exists(String code) {
        return jdbc.sql("SELECT count(*) FROM branches WHERE code = :c").param("c", code).query(Long.class).single() > 0;
    }

    private String known(String code) {
        String c = code.trim().toUpperCase();
        if (!exists(c)) throw new ApiException(HttpStatus.NOT_FOUND, "BRANCH_NOT_FOUND", "No branch " + c);
        return c;
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String by(PortalUser u) {
        return u == null ? "portal-key" : u.username();
    }
}
