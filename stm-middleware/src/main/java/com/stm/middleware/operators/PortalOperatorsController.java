package com.stm.middleware.operators;

import com.stm.middleware.audit.AuditService;
import com.stm.middleware.auth.PortalUser;
import com.stm.middleware.common.ApiException;
import com.stm.middleware.portal.Rows;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.security.SecureRandom;
import java.util.*;

/**
 * Terminal users: people who sign in on the kiosk itself (custodians, cash-in-transit, engineers, branch supervisors)
 * with a username and a 6-digit PIN. ADMIN manages them (SecurityConfig); the PIN is shown once.
 */
@RestController
@RequestMapping("/api/v1/portal/terminal-users")
public class PortalOperatorsController {
    public static final BCryptPasswordEncoder PINS = new BCryptPasswordEncoder(10);
    private static final SecureRandom RNG = new SecureRandom();

    private final JdbcClient jdbc;
    private final Rows rows;
    private final AuditService audit;

    public PortalOperatorsController(JdbcClient jdbc, Rows rows, AuditService audit) {
        this.jdbc = jdbc;
        this.rows = rows;
        this.audit = audit;
    }

    private static final String SELECT = """
            SELECT o.id, o.username, o.full_name, o.role, o.phone, o.branch_code, b.name AS branch_name, o.all_terminals,
                   o.status, o.failed_attempts, o.locked_until, o.last_login_at, o.last_terminal, o.pin_changed_at,
                   o.created_by, o.created_at, o.updated_at,
                   (SELECT coalesce(json_agg(t.terminal_code ORDER BY t.terminal_code), '[]')::text
                      FROM terminal_operator_kiosks k JOIN terminals t ON t.id = k.terminal_id
                     WHERE k.operator_id = o.id) AS terminals_json
            FROM terminal_operators o LEFT JOIN branches b ON b.code = o.branch_code""";

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(required = false) String branch,
                                          @RequestParam(required = false) String terminal) {
        Map<String, Object> p = new HashMap<>();
        p.put("b", branch == null || branch.isBlank() ? null : branch);
        p.put("t", terminal == null || terminal.isBlank() ? null : terminal);
        return rows.list(SELECT + " " + """
                 WHERE (CAST(:b AS VARCHAR) IS NULL OR o.branch_code = :b)
                   AND (CAST(:t AS VARCHAR) IS NULL OR o.all_terminals OR EXISTS (
                        SELECT 1 FROM terminal_operator_kiosks k JOIN terminals t ON t.id = k.terminal_id
                         WHERE k.operator_id = o.id AND t.terminal_code = :t))
                 ORDER BY o.username""", p);
    }

    @GetMapping("/roles")
    public Map<String, List<String>> roles() {
        return OperatorRoles.FUNCTIONS;
    }

    /** allTerminals = may sign in on every kiosk (of the branch, when branchCode is set); otherwise only on terminals. */
    public record OperatorRequest(@Pattern(regexp = "[a-zA-Z0-9._-]{3,40}", message = "Use 3 to 40 letters, digits, dots, hyphens or underscores")
                                  String username,
                                  @NotBlank @Size(max = 100) String fullName,
                                  @NotBlank @Pattern(regexp = "CUSTODIAN|CIT|ENGINEER|SUPERVISOR") String role,
                                  @Size(max = 20) String phone, @Size(max = 20) String branchCode,
                                  Boolean allTerminals, List<String> terminals) {}

    public record Created(Map<String, Object> user, String pin) {}

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Created create(@AuthenticationPrincipal PortalUser admin, @Valid @RequestBody OperatorRequest req) {
        if (req.username() == null) throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Username is required");
        String username = req.username().trim().toLowerCase();
        if (jdbc.sql("SELECT count(*) FROM terminal_operators WHERE username = :u").param("u", username).query(Long.class).single() > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "USERNAME_TAKEN", "Terminal user " + username + " already exists.");
        }
        String branch = branch(req.branchCode());
        String pin = newPin();
        long id = jdbc.sql("""
                INSERT INTO terminal_operators (username, full_name, role, phone, branch_code, all_terminals, pin_hash, created_by,
                                                pin_changed_at)
                VALUES (:u, :n, :r, :p, :b, :a, :h, :by, now()) RETURNING id""")
                .param("u", username).param("n", req.fullName().trim()).param("r", req.role()).param("p", blank(req.phone()))
                .param("b", branch).param("a", Boolean.TRUE.equals(req.allTerminals())).param("h", PINS.encode(pin))
                .param("by", by(admin)).query(Long.class).single();
        assign(id, req);
        audit.record("TERMINAL_USER_CREATED", "operator:" + username, null, Map.of("role", req.role(), "by", by(admin)));
        return new Created(one(id), pin);
    }

    @PutMapping("/{id}")
    @Transactional
    public Map<String, Object> update(@AuthenticationPrincipal PortalUser admin, @PathVariable long id,
                                      @Valid @RequestBody OperatorRequest req) {
        String username = known(id);
        jdbc.sql("""
                UPDATE terminal_operators SET full_name = :n, role = :r, phone = :p, branch_code = :b, all_terminals = :a,
                       updated_at = now() WHERE id = :id""")
                .param("n", req.fullName().trim()).param("r", req.role()).param("p", blank(req.phone()))
                .param("b", branch(req.branchCode())).param("a", Boolean.TRUE.equals(req.allTerminals())).param("id", id).update();
        jdbc.sql("DELETE FROM terminal_operator_kiosks WHERE operator_id = :id").param("id", id).update();
        assign(id, req);
        audit.record("TERMINAL_USER_UPDATED", "operator:" + username, null, Map.of("role", req.role(), "by", by(admin)));
        return one(id);
    }

    /** New PIN, shown once; also clears a lockout. */
    @PostMapping("/{id}/reset-pin")
    public Created resetPin(@AuthenticationPrincipal PortalUser admin, @PathVariable long id) {
        String username = known(id);
        String pin = newPin();
        jdbc.sql("""
                UPDATE terminal_operators SET pin_hash = :h, failed_attempts = 0, locked_until = NULL, pin_changed_at = now(),
                       updated_at = now() WHERE id = :id""").param("h", PINS.encode(pin)).param("id", id).update();
        audit.record("TERMINAL_USER_PIN_RESET", "operator:" + username, null, Map.of("by", by(admin)));
        return new Created(one(id), pin);
    }

    public record StatusRequest(@NotBlank @Pattern(regexp = "ACTIVE|DISABLED") String status) {}

    @PutMapping("/{id}/status")
    public Map<String, Object> status(@AuthenticationPrincipal PortalUser admin, @PathVariable long id,
                                      @Valid @RequestBody StatusRequest req) {
        String username = known(id);
        jdbc.sql("""
                UPDATE terminal_operators SET status = :s, failed_attempts = 0, locked_until = NULL, updated_at = now()
                WHERE id = :id""").param("s", req.status()).param("id", id).update();
        audit.record("TERMINAL_USER_" + req.status(), "operator:" + username, null, Map.of("by", by(admin)));
        return one(id);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> remove(@AuthenticationPrincipal PortalUser admin, @PathVariable long id) {
        String username = known(id);
        jdbc.sql("DELETE FROM terminal_operators WHERE id = :id").param("id", id).update();
        audit.record("TERMINAL_USER_DELETED", "operator:" + username, null, Map.of("by", by(admin)));
        return Map.of("id", id, "deleted", true);
    }

    private void assign(long id, OperatorRequest req) {
        if (Boolean.TRUE.equals(req.allTerminals()) || req.terminals() == null) return;
        for (String code : new LinkedHashSet<>(req.terminals())) {
            int n = jdbc.sql("""
                    INSERT INTO terminal_operator_kiosks (operator_id, terminal_id)
                    SELECT :o, id FROM terminals WHERE terminal_code = :c AND status <> 'RETIRED'""")
                    .param("o", id).param("c", code).update();
            if (n == 0) throw new ApiException(HttpStatus.BAD_REQUEST, "TERMINAL_NOT_FOUND", "No kiosk " + code);
        }
    }

    private String branch(String code) {
        if (code == null || code.isBlank()) return null;
        String c = code.trim().toUpperCase();
        if (jdbc.sql("SELECT count(*) FROM branches WHERE code = :c").param("c", c).query(Long.class).single() == 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "BRANCH_NOT_FOUND", "No branch " + c);
        }
        return c;
    }

    private Map<String, Object> one(long id) {
        return rows.one(SELECT + " WHERE o.id = :id", Map.of("id", id)).orElseThrow();
    }

    private String known(long id) {
        return jdbc.sql("SELECT username FROM terminal_operators WHERE id = :id").param("id", id).query(String.class).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TERMINAL_USER_NOT_FOUND", "No terminal user " + id));
    }

    private static String newPin() {
        // Avoid trivially guessable PINs: all-same digits and straight runs.
        while (true) {
            String pin = String.format("%06d", RNG.nextInt(1_000_000));
            if (pin.chars().distinct().count() == 1 || "0123456789".contains(pin) || "9876543210".contains(pin)) continue;
            return pin;
        }
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String by(PortalUser u) {
        return u == null ? "portal-key" : u.username();
    }
}
