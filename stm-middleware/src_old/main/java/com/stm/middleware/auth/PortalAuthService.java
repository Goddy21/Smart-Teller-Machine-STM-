package com.stm.middleware.auth;

import com.stm.middleware.audit.AuditService;
import com.stm.middleware.common.ApiException;
import com.stm.middleware.common.Hashing;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Portal accounts and sessions.
 * - Passwords: BCrypt (cost 12). Session tokens: 32 random bytes, only their SHA-256 is stored (like terminal tokens).
 * - Sessions slide: each request extends them by the idle timeout, never past the absolute limit.
 * - Lockout after N wrong passwords in a row. Failures never reveal whether the username exists.
 * - Every sign-in, failure, lockout, sign-out and account change goes to the hash-chained audit journal.
 */
@Service
public class PortalAuthService {
    private static final SecureRandom RNG = new SecureRandom();
    private static final Pattern USERNAME = Pattern.compile("[a-z0-9._-]{3,50}");
    /** Spends the same BCrypt time for unknown usernames, so response time does not reveal which names exist. */
    private static final String DUMMY_HASH = new BCryptPasswordEncoder(12).encode("dummy-password-for-timing");

    private final JdbcClient jdbc;
    private final AuditService audit;
    private final PortalAuthProperties props;
    private final PasswordEncoder encoder = new BCryptPasswordEncoder(12);

    public PortalAuthService(JdbcClient jdbc, AuditService audit, PortalAuthProperties props) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.props = props;
    }

    public record Login(String token, OffsetDateTime expiresAt, PortalUser user) {}

    public record UserRow(String username, String displayName, String role, String branchCode, List<String> languages,
                          String status, boolean mustChangePassword, boolean locked, OffsetDateTime lastLoginAt,
                          OffsetDateTime createdAt, String createdBy) {}

    public record Created(UserRow user, String temporaryPassword) {}

    private record Account(long id, String username, String passwordHash, String status, int failedAttempts,
                           OffsetDateTime lockedUntil) {}

    // ------------------------------------------------------------------ sign-in

    public Login login(String username, String password, String clientIp, String userAgent) {
        String u = normalise(username);
        Optional<Account> acc = jdbc.sql("""
                SELECT id, username, password_hash, status, failed_attempts, locked_until
                FROM portal_users WHERE username = :u""").param("u", u)
                .query((rs, n) -> new Account(rs.getLong("id"), rs.getString("username"), rs.getString("password_hash"),
                        rs.getString("status"), rs.getInt("failed_attempts"), rs.getObject("locked_until", OffsetDateTime.class)))
                .optional();

        if (acc.isEmpty()) {
            encoder.matches(password == null ? "" : password, DUMMY_HASH);
            audit.record("PORTAL_LOGIN_FAILED", "user:" + u, null, Map.of("reason", "unknown user", "ip", nz(clientIp)));
            throw invalid();
        }
        Account a = acc.get();
        if (a.lockedUntil() != null && a.lockedUntil().isAfter(OffsetDateTime.now())) {
            audit.record("PORTAL_LOGIN_FAILED", "user:" + u, null, Map.of("reason", "locked", "ip", nz(clientIp)));
            throw new ApiException(HttpStatus.LOCKED, "ACCOUNT_LOCKED",
                    "Too many wrong passwords. Try again after " + a.lockedUntil().toLocalTime().withNano(0) + ", or ask an administrator.");
        }
        if (!encoder.matches(password == null ? "" : password, a.passwordHash())) {
            int fails = a.failedAttempts() + 1;
            boolean lock = fails >= props.maxFailures();
            jdbc.sql("""
                    UPDATE portal_users SET failed_attempts = :f,
                           locked_until = CASE WHEN :lock THEN now() + make_interval(mins => :m) ELSE locked_until END
                    WHERE id = :id""")
                    .param("f", lock ? 0 : fails).param("lock", lock).param("m", props.lock()).param("id", a.id()).update();
            audit.record(lock ? "PORTAL_ACCOUNT_LOCKED" : "PORTAL_LOGIN_FAILED", "user:" + u, null,
                    Map.of("reason", "wrong password", "attempt", fails, "ip", nz(clientIp)));
            throw invalid();
        }
        if (!"ACTIVE".equals(a.status())) {
            audit.record("PORTAL_LOGIN_FAILED", "user:" + u, null, Map.of("reason", "disabled", "ip", nz(clientIp)));
            throw new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_DISABLED", "This account is disabled. Ask an administrator.");
        }

        jdbc.sql("UPDATE portal_users SET failed_attempts = 0, locked_until = NULL, last_login_at = now() WHERE id = :id")
                .param("id", a.id()).update();
        String token = randomToken();
        jdbc.sql("""
                INSERT INTO portal_sessions (token_hash, user_id, expires_at, client_ip, user_agent)
                VALUES (:h, :u, now() + make_interval(mins => :idle), :ip, :ua)""")
                .param("h", Hashing.sha256Hex(token)).param("u", a.id()).param("idle", props.idle())
                .param("ip", truncate(clientIp, 45)).param("ua", truncate(userAgent, 200)).update();
        audit.record("PORTAL_LOGIN", "user:" + u, null, Map.of("ip", nz(clientIp)));
        PortalUser user = authenticate(token).orElseThrow(PortalAuthService::invalid);
        return new Login(token, user.sessionExpiresAt(), user);
    }

    /** Validates a session token and slides its expiry. Empty = unknown, expired, revoked, or the account is disabled. */
    public Optional<PortalUser> authenticate(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        return jdbc.sql("""
                UPDATE portal_sessions s
                SET last_seen_at = now(),
                    expires_at = LEAST(s.created_at + make_interval(hours => :abs), now() + make_interval(mins => :idle))
                FROM portal_users u
                WHERE s.token_hash = :h AND NOT s.revoked AND s.expires_at > now()
                  AND u.id = s.user_id AND u.status = 'ACTIVE'
                RETURNING u.id, u.username, u.display_name, u.role, u.branch_code, u.languages,
                          u.must_change_password, s.expires_at""")
                .param("h", Hashing.sha256Hex(token)).param("abs", props.absolute()).param("idle", props.idle())
                .query(PortalAuthService::toUser).optional();
    }

    public void logout(String token, PortalUser user) {
        if (token != null) {
            jdbc.sql("UPDATE portal_sessions SET revoked = true WHERE token_hash = :h").param("h", Hashing.sha256Hex(token)).update();
        }
        if (user != null && !user.service()) audit.record("PORTAL_LOGOUT", "user:" + user.username(), null, Map.of());
    }

    public PortalUser changePassword(PortalUser user, String token, String current, String next) {
        if (user.service()) throw new ApiException(HttpStatus.BAD_REQUEST, "NOT_A_USER", "The shared key has no password");
        String hash = jdbc.sql("SELECT password_hash FROM portal_users WHERE id = :id").param("id", user.id())
                .query(String.class).single();
        if (!encoder.matches(current == null ? "" : current, hash)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "WRONG_PASSWORD", "Your current password is not correct.");
        }
        checkPolicy(next, user.username());
        if (encoder.matches(next, hash)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "PASSWORD_REUSED", "Choose a password different from the current one.");
        }
        jdbc.sql("""
                UPDATE portal_users SET password_hash = :p, must_change_password = false, password_changed_at = now()
                WHERE id = :id""").param("p", encoder.encode(next)).param("id", user.id()).update();
        // Sign out every other device of this user.
        jdbc.sql("UPDATE portal_sessions SET revoked = true WHERE user_id = :u AND token_hash <> :h")
                .param("u", user.id()).param("h", Hashing.sha256Hex(token)).update();
        audit.record("PORTAL_PASSWORD_CHANGED", "user:" + user.username(), null, Map.of());
        return authenticate(token).orElseThrow(PortalAuthService::invalid);
    }

    // ------------------------------------------------------------------ administration

    public List<UserRow> listUsers() {
        return jdbc.sql("SELECT * FROM portal_users ORDER BY username").query(PortalAuthService::toRow).list();
    }

    public Created createUser(PortalUser admin, String username, String displayName, PortalUser.Role role,
                              String branchCode, List<String> languages) {
        String u = normalise(username);
        if (!USERNAME.matcher(u).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_USERNAME",
                    "Use 3 to 50 lowercase letters, digits, dots, dashes or underscores.");
        }
        String temp = temporaryPassword();
        try {
            jdbc.sql("""
                    INSERT INTO portal_users (username, display_name, password_hash, role, branch_code, languages, created_by)
                    VALUES (:u, :n, :p, :r, :b, :l, :by)""")
                    .param("u", u).param("n", displayName.trim()).param("p", encoder.encode(temp)).param("r", role.name())
                    .param("b", blankToNull(branchCode)).param("l", langs(languages)).param("by", admin.username()).update();
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "USER_EXISTS", "That username is already taken.");
        }
        audit.record("PORTAL_USER_CREATED", "user:" + u, null, Map.of("by", admin.username(), "role", role.name()));
        return new Created(find(u), temp);
    }

    public UserRow updateUser(PortalUser admin, String username, String displayName, PortalUser.Role role,
                              String branchCode, List<String> languages, String status) {
        String u = normalise(username);
        if (u.equals(admin.username()) && (role != null && role != PortalUser.Role.ADMIN || "DISABLED".equals(status))) {
            throw new ApiException(HttpStatus.CONFLICT, "CANNOT_DEMOTE_SELF", "You cannot remove your own administrator access.");
        }
        if (status != null && !List.of("ACTIVE", "DISABLED").contains(status)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_STATUS", "Status must be ACTIVE or DISABLED");
        }
        int n = jdbc.sql("""
                UPDATE portal_users SET
                  display_name = COALESCE(:n, display_name),
                  role = COALESCE(:r, role),
                  branch_code = CASE WHEN :setBranch THEN :b ELSE branch_code END,
                  languages = COALESCE(:l, languages),
                  status = COALESCE(:s, status),
                  locked_until = CASE WHEN :s = 'ACTIVE' THEN NULL ELSE locked_until END,
                  failed_attempts = CASE WHEN :s = 'ACTIVE' THEN 0 ELSE failed_attempts END
                WHERE username = :u""")
                .param("n", displayName == null ? null : displayName.trim()).param("r", role == null ? null : role.name())
                .param("setBranch", branchCode != null).param("b", blankToNull(branchCode))
                .param("l", languages == null ? null : langs(languages)).param("s", status).param("u", u).update();
        if (n == 0) throw new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "No such user");
        if ("DISABLED".equals(status)) revokeAll(u);
        audit.record("PORTAL_USER_UPDATED", "user:" + u, null, Map.of("by", admin.username(),
                "role", role == null ? "" : role.name(), "status", status == null ? "" : status));
        return find(u);
    }

    /** New temporary password; the user must change it at next sign-in. Signs the user out everywhere. */
    public String resetPassword(PortalUser admin, String username) {
        String u = normalise(username);
        String temp = temporaryPassword();
        int n = jdbc.sql("""
                UPDATE portal_users SET password_hash = :p, must_change_password = true, failed_attempts = 0, locked_until = NULL
                WHERE username = :u""").param("p", encoder.encode(temp)).param("u", u).update();
        if (n == 0) throw new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "No such user");
        revokeAll(u);
        audit.record("PORTAL_PASSWORD_RESET", "user:" + u, null, Map.of("by", admin.username()));
        return temp;
    }

    /** Creates the first administrator when there are no users at all. Returns the password used, or null if not needed. */
    public String bootstrapAdmin(String password) {
        long count = jdbc.sql("SELECT count(*) FROM portal_users").query(Long.class).single();
        if (count > 0) return null;
        String pw = password == null || password.isBlank() ? temporaryPassword() : password;
        jdbc.sql("""
                INSERT INTO portal_users (username, display_name, password_hash, role, created_by)
                VALUES ('admin', 'Administrator', :p, 'ADMIN', 'bootstrap')""").param("p", encoder.encode(pw)).update();
        audit.record("PORTAL_USER_CREATED", "user:admin", null, Map.of("by", "bootstrap", "role", "ADMIN"));
        return pw;
    }

    // ------------------------------------------------------------------ helpers

    /** At least 10 characters, with a letter and a digit, and not containing the username. */
    static void checkPolicy(String pw, String username) {
        if (pw == null || pw.length() < 10 || pw.length() > 128 || !pw.matches(".*[A-Za-z].*") || !pw.matches(".*\\d.*")) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "WEAK_PASSWORD",
                    "Use at least 10 characters, with at least one letter and one number.");
        }
        if (username != null && pw.toLowerCase().contains(username.toLowerCase())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "WEAK_PASSWORD", "The password must not contain your username.");
        }
    }

    /** 14 characters from an alphabet without look-alikes (no 0/O, 1/l/I), always with letters and digits. */
    static String temporaryPassword() {
        String letters = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ", digits = "23456789";
        char[] c = new char[14];
        for (int i = 0; i < c.length; i++) {
            String set = i % 4 == 3 ? digits : letters;
            c[i] = set.charAt(RNG.nextInt(set.length()));
        }
        return new String(c);
    }

    private static String randomToken() {
        byte[] raw = new byte[32];
        RNG.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    private void revokeAll(String username) {
        jdbc.sql("UPDATE portal_sessions SET revoked = true WHERE user_id = (SELECT id FROM portal_users WHERE username = :u)")
                .param("u", username).update();
    }

    private UserRow find(String username) {
        return jdbc.sql("SELECT * FROM portal_users WHERE username = :u").param("u", username)
                .query(PortalAuthService::toRow).single();
    }

    private static PortalUser toUser(ResultSet rs, int n) throws SQLException {
        return new PortalUser(rs.getLong("id"), rs.getString("username"), rs.getString("display_name"),
                PortalUser.Role.valueOf(rs.getString("role")), rs.getString("branch_code"),
                splitLangs(rs.getString("languages")), rs.getBoolean("must_change_password"),
                rs.getObject("expires_at", OffsetDateTime.class), false);
    }

    private static UserRow toRow(ResultSet rs, int n) throws SQLException {
        OffsetDateTime locked = rs.getObject("locked_until", OffsetDateTime.class);
        return new UserRow(rs.getString("username"), rs.getString("display_name"), rs.getString("role"),
                rs.getString("branch_code"), splitLangs(rs.getString("languages")), rs.getString("status"),
                rs.getBoolean("must_change_password"), locked != null && locked.isAfter(OffsetDateTime.now()),
                rs.getObject("last_login_at", OffsetDateTime.class), rs.getObject("created_at", OffsetDateTime.class),
                rs.getString("created_by"));
    }

    private static List<String> splitLangs(String s) {
        return s == null ? List.of() : Arrays.stream(s.split(",")).map(String::trim).filter(x -> !x.isEmpty()).toList();
    }

    private static String langs(List<String> l) {
        List<String> clean = l == null ? List.of() : l.stream().map(x -> x.trim().toLowerCase()).filter(x -> x.matches("[a-z]{2}")).distinct().toList();
        return clean.isEmpty() ? "en" : String.join(",", clean);
    }

    private static String normalise(String username) { return username == null ? "" : username.trim().toLowerCase(); }
    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }
    private static String nz(String s) { return s == null ? "" : s; }
    private static String truncate(String s, int max) { return s == null ? null : s.length() <= max ? s : s.substring(0, max); }

    private static ApiException invalid() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Wrong username or password.");
    }
}
