package com.stm.middleware.auth;

import com.stm.middleware.audit.AuditService;
import com.stm.middleware.common.ApiException;
import com.stm.middleware.common.EmailService;
import com.stm.middleware.common.EmailTemplate;
import com.stm.middleware.common.Hashing;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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
 * - Second factor: after the password, a 6-digit code is emailed to the account's address (stm.portal-auth.email-otp).
 *   The session is only created once the code is entered. Too many wrong codes count as a failed sign-in.
 * - Every sign-in, failure, lockout, sign-out and account change goes to the hash-chained audit journal.
 */
@Service
public class PortalAuthService {
    private static final SecureRandom RNG = new SecureRandom();
    private static final Pattern USERNAME = Pattern.compile("[a-z0-9._-]{3,50}");
    private static final Pattern EMAIL = Pattern.compile("[^\\s@]{1,64}@[^\\s@]+\\.[^\\s@]{2,}");
    private static final int RESEND_AFTER_SEC = 60, MAX_RESENDS = 3;
    /** Spends the same BCrypt time for unknown usernames, so response time does not reveal which names exist. */
    private static final String DUMMY_HASH = new BCryptPasswordEncoder(12).encode("dummy-password-for-timing");

    private final JdbcClient jdbc;
    private final AuditService audit;
    private final PortalAuthProperties props;
    private final EmailService email;
    private final PasswordEncoder encoder = new BCryptPasswordEncoder(12);

    public PortalAuthService(JdbcClient jdbc, AuditService audit, PortalAuthProperties props, EmailService email) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.props = props;
        this.email = email;
    }

    /** Either a session (token, expiresAt, user) or, when a code was emailed, a challenge to finish with verifyOtp. */
    public record Login(String token, OffsetDateTime expiresAt, PortalUser user, Challenge otp) {}

    public record Challenge(String challenge, String maskedEmail, int expiresInSec, int resendAfterSec) {}

    public record UserRow(String username, String displayName, String email, String role, String branchCode, List<String> languages,
                          String status, boolean mustChangePassword, boolean locked, OffsetDateTime lastLoginAt,
                          OffsetDateTime createdAt, String createdBy, int activeSessions, OffsetDateTime archivedAt) {}

    /** temporaryPassword is null when it was emailed to the person (emailedTo says where). */
    public record Created(UserRow user, String temporaryPassword, String emailedTo, String emailError) {}

    /** What happened to a removed account. */
    public record Removed(String username, String outcome, String message) {}

    public record Activity(OffsetDateTime at, String event, String subject, Map<String, Object> details) {}

    private record Account(long id, String username, String passwordHash, String status, int failedAttempts,
                           OffsetDateTime lockedUntil, String email, String displayName) {}

    // ------------------------------------------------------------------ sign-in

    public Login login(String username, String password, String clientIp, String userAgent) {
        String u = normalise(username);
        Optional<Account> acc = jdbc.sql("""
                SELECT id, username, password_hash, status, failed_attempts, locked_until, email, display_name
                FROM portal_users WHERE username = :u""").param("u", u)
                .query((rs, n) -> new Account(rs.getLong("id"), rs.getString("username"), rs.getString("password_hash"),
                        rs.getString("status"), rs.getInt("failed_attempts"), rs.getObject("locked_until", OffsetDateTime.class),
                        rs.getString("email"), rs.getString("display_name")))
                .optional();

        if (acc.isEmpty() || "ARCHIVED".equals(acc.get().status())) {
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

        jdbc.sql("UPDATE portal_users SET failed_attempts = 0, locked_until = NULL WHERE id = :id").param("id", a.id()).update();
        if (props.otp() && a.email() != null && !a.email().isBlank()) {
            return new Login(null, null, null, startChallenge(a, clientIp));
        }
        return startSession(a.id(), u, clientIp, userAgent, "password");
    }

    /** Second step: the code from the email. Creates the session. */
    public Login verifyOtp(String challenge, String code, String clientIp, String userAgent) {
        record Pending(long userId, String username, String codeHash, int attempts) {}
        String h = Hashing.sha256Hex(challenge == null ? "" : challenge);
        Pending p = jdbc.sql("""
                SELECT c.user_id, u.username, c.code_hash, c.attempts
                FROM portal_login_challenges c JOIN portal_users u ON u.id = c.user_id
                WHERE c.token_hash = :h AND NOT c.consumed AND c.expires_at > now() AND u.status = 'ACTIVE'""").param("h", h)
                .query((rs, n) -> new Pending(rs.getLong("user_id"), rs.getString("username"), rs.getString("code_hash"),
                        rs.getInt("attempts")))
                .optional().orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "OTP_EXPIRED",
                        "This sign-in code has expired. Sign in again to get a new one."));
        String clean = code == null ? "" : code.replaceAll("\\s", "");
        if (!MessageDigest.isEqual(p.codeHash().getBytes(StandardCharsets.US_ASCII),
                Hashing.sha256Hex(h + ":" + clean).getBytes(StandardCharsets.US_ASCII))) {
            int tries = p.attempts() + 1;
            boolean dead = tries >= props.otpTries();
            jdbc.sql("UPDATE portal_login_challenges SET attempts = :a, consumed = :d WHERE token_hash = :h")
                    .param("a", tries).param("d", dead).param("h", h).update();
            if (dead) {
                // Counts as one failed sign-in towards the account lockout.
                jdbc.sql("""
                        UPDATE portal_users SET failed_attempts = failed_attempts + 1,
                               locked_until = CASE WHEN failed_attempts + 1 >= :max THEN now() + make_interval(mins => :m) ELSE locked_until END
                        WHERE id = :id""").param("max", props.maxFailures()).param("m", props.lock()).param("id", p.userId()).update();
                audit.record("PORTAL_LOGIN_FAILED", "user:" + p.username(), null, Map.of("reason", "too many wrong codes", "ip", nz(clientIp)));
                throw new ApiException(HttpStatus.UNAUTHORIZED, "OTP_TRIES_EXCEEDED", "Too many wrong codes. Sign in again.");
            }
            int left = props.otpTries() - tries;
            throw new ApiException(HttpStatus.UNAUTHORIZED, "OTP_INCORRECT",
                    "That code is not correct. " + left + (left == 1 ? " try" : " tries") + " left.");
        }
        jdbc.sql("UPDATE portal_login_challenges SET consumed = true WHERE token_hash = :h").param("h", h).update();
        return startSession(p.userId(), p.username(), clientIp, userAgent, "email code");
    }

    /** Sends a new code for the same sign-in (at most 3 times, once a minute). */
    public Challenge resendOtp(String challenge, String clientIp) {
        record Pending(long userId, String username, String email, String displayName, OffsetDateTime lastSent, int resends) {}
        String h = Hashing.sha256Hex(challenge == null ? "" : challenge);
        Pending p = jdbc.sql("""
                SELECT c.user_id, u.username, u.email, u.display_name, c.last_sent_at, c.resends
                FROM portal_login_challenges c JOIN portal_users u ON u.id = c.user_id
                WHERE c.token_hash = :h AND NOT c.consumed AND c.expires_at > now() AND u.status = 'ACTIVE'""").param("h", h)
                .query((rs, n) -> new Pending(rs.getLong("user_id"), rs.getString("username"), rs.getString("email"),
                        rs.getString("display_name"), rs.getObject("last_sent_at", OffsetDateTime.class), rs.getInt("resends")))
                .optional().orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "OTP_EXPIRED",
                        "This sign-in has expired. Sign in again to get a new code."));
        if (p.resends() >= MAX_RESENDS) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RESEND_LIMIT", "No more codes for this sign-in. Sign in again.");
        }
        long wait = RESEND_AFTER_SEC - java.time.Duration.between(p.lastSent(), OffsetDateTime.now()).toSeconds();
        if (wait > 0) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RESEND_TOO_SOON", "Wait " + wait + " seconds before asking for a new code.");
        }
        String code = otpCode();
        jdbc.sql("""
                UPDATE portal_login_challenges SET code_hash = :c, resends = resends + 1, last_sent_at = now(), attempts = 0,
                       expires_at = now() + make_interval(mins => :m)
                WHERE token_hash = :h""").param("c", Hashing.sha256Hex(h + ":" + code)).param("m", props.otpMins()).param("h", h).update();
        mailCode(p.email(), p.displayName(), code);
        audit.record("PORTAL_LOGIN_CODE_SENT", "user:" + p.username(), null, Map.of("resend", true, "ip", nz(clientIp)));
        return new Challenge(challenge, maskEmail(p.email()), props.otpMins() * 60, RESEND_AFTER_SEC);
    }

    private Challenge startChallenge(Account a, String clientIp) {
        String challenge = randomToken(), h = Hashing.sha256Hex(challenge), code = otpCode();
        // One live challenge per account: a new sign-in cancels the previous one.
        jdbc.sql("UPDATE portal_login_challenges SET consumed = true WHERE user_id = :u AND NOT consumed").param("u", a.id()).update();
        jdbc.sql("""
                INSERT INTO portal_login_challenges (token_hash, user_id, code_hash, expires_at, client_ip)
                VALUES (:h, :u, :c, now() + make_interval(mins => :m), :ip)""")
                .param("h", h).param("u", a.id()).param("c", Hashing.sha256Hex(h + ":" + code)).param("m", props.otpMins())
                .param("ip", truncate(clientIp, 45)).update();
        mailCode(a.email(), a.displayName(), code);
        audit.record("PORTAL_LOGIN_CODE_SENT", "user:" + a.username(), null, Map.of("ip", nz(clientIp)));
        return new Challenge(challenge, maskEmail(a.email()), props.otpMins() * 60, RESEND_AFTER_SEC);
    }

    private void mailCode(String to, String name, String code) {
        String warning = "If you did not just try to sign in, someone may know your password. Do not share this code; "
                + "change your password and tell your portal administrator.";
        String html = EmailTemplate.html("Your sign-in code", "Your Smart Teller portal sign-in code. It expires in "
                        + props.otpMins() + " minutes.", "Hello " + name + ",",
                List.of("Use this code to finish signing in to the Smart Teller (STM) portal."),
                List.of(new EmailTemplate.Fact("Sign-in code", code, true)),
                List.of("It expires in " + props.otpMins() + " minutes and works once."), warning);
        email.send(to, "Your Smart Teller portal sign-in code", """
                Hello %s,

                Your sign-in code for the Smart Teller (STM) portal is:

                    %s

                It expires in %d minutes and works once.

                If you did not just try to sign in, someone may know your password. Do not share this code;
                change your password and tell your portal administrator.

                Blue River Smart Teller
                """.formatted(name, code, props.otpMins()), html, "PORTAL_LOGIN_OTP", true);
    }

    private Login startSession(long userId, String username, String clientIp, String userAgent, String method) {
        jdbc.sql("UPDATE portal_users SET failed_attempts = 0, locked_until = NULL, last_login_at = now() WHERE id = :id")
                .param("id", userId).update();
        String token = randomToken();
        jdbc.sql("""
                INSERT INTO portal_sessions (token_hash, user_id, expires_at, client_ip, user_agent)
                VALUES (:h, :u, now() + make_interval(mins => :idle), :ip, :ua)""")
                .param("h", Hashing.sha256Hex(token)).param("u", userId).param("idle", props.idle())
                .param("ip", truncate(clientIp, 45)).param("ua", truncate(userAgent, 200)).update();
        audit.record("PORTAL_LOGIN", "user:" + username, null, Map.of("ip", nz(clientIp), "method", method));
        PortalUser user = authenticate(token).orElseThrow(PortalAuthService::invalid);
        return new Login(token, user.sessionExpiresAt(), user, null);
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

    private static final String ROW_SQL = """
            SELECT u.*, (SELECT count(*) FROM portal_sessions s
                         WHERE s.user_id = u.id AND NOT s.revoked AND s.expires_at > now()) AS active_sessions
            FROM portal_users u""";

    public List<UserRow> listUsers(boolean includeArchived) {
        return jdbc.sql(ROW_SQL + (includeArchived ? "" : " WHERE u.status <> 'ARCHIVED'") + " ORDER BY u.status = 'ARCHIVED', u.username")
                .query(PortalAuthService::toRow).list();
    }

    /**
     * Removes an account. Never signed in: deleted completely. Otherwise archived: cannot sign in, hidden from the list,
     * email cleared (it can be used again), name and username kept for the audit trail. Signs the person out everywhere.
     */
    public Removed removeUser(PortalUser admin, String username) {
        String u = normalise(username);
        if (u.equals(admin.username())) {
            throw new ApiException(HttpStatus.CONFLICT, "CANNOT_REMOVE_SELF", "You cannot remove your own account.");
        }
        record Target(long id, String role, String status, OffsetDateTime lastLogin, String displayName) {}
        Target t = jdbc.sql("SELECT id, role, status, last_login_at, display_name FROM portal_users WHERE username = :u").param("u", u)
                .query((rs, n) -> new Target(rs.getLong("id"), rs.getString("role"), rs.getString("status"),
                        rs.getObject("last_login_at", OffsetDateTime.class), rs.getString("display_name")))
                .optional().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "No such user"));
        if ("ARCHIVED".equals(t.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_REMOVED", "This account was already removed.");
        }
        if ("ADMIN".equals(t.role())) {
            long admins = jdbc.sql("SELECT count(*) FROM portal_users WHERE role = 'ADMIN' AND status = 'ACTIVE' AND id <> :id")
                    .param("id", t.id()).query(Long.class).single();
            if (admins == 0) {
                throw new ApiException(HttpStatus.CONFLICT, "LAST_ADMIN", "Keep at least one active administrator.");
            }
        }
        boolean used = t.lastLogin() != null || jdbc.sql("SELECT count(*) FROM portal_sessions WHERE user_id = :id")
                .param("id", t.id()).query(Long.class).single() > 0;
        if (!used) {
            jdbc.sql("DELETE FROM portal_login_challenges WHERE user_id = :id").param("id", t.id()).update();
            jdbc.sql("DELETE FROM portal_users WHERE id = :id").param("id", t.id()).update();
            audit.record("PORTAL_USER_DELETED", "user:" + u, null, Map.of("by", admin.username(), "name", t.displayName()));
            return new Removed(u, "DELETED", t.displayName() + " never signed in, so the account was deleted completely.");
        }
        revokeAll(u);
        jdbc.sql("""
                UPDATE portal_users SET status = 'ARCHIVED', email = NULL, archived_at = now(), archived_by = :by,
                       failed_attempts = 0, locked_until = NULL
                WHERE id = :id""").param("by", admin.username()).param("id", t.id()).update();
        jdbc.sql("UPDATE portal_login_challenges SET consumed = true WHERE user_id = :id").param("id", t.id()).update();
        audit.record("PORTAL_USER_ARCHIVED", "user:" + u, null, Map.of("by", admin.username(), "name", t.displayName()));
        return new Removed(u, "ARCHIVED", t.displayName() + " is removed. Their name stays in the audit trail and on past decisions, "
                + "so the username cannot be reused.");
    }

    /** Ends every open portal session of this person (lost device). The account stays usable. */
    public int signOutEverywhere(PortalUser admin, String username) {
        String u = normalise(username);
        find(u);
        int n = revokeAll(u);
        audit.record("PORTAL_SESSIONS_REVOKED", "user:" + u, null, Map.of("by", admin.username(), "sessions", n));
        return n;
    }

    /** The person's sign-ins, failures, codes, resets and account changes, plus what they did to other accounts. */
    public List<Activity> activity(String username, int limit) {
        String u = normalise(username);
        find(u);
        return jdbc.sql("""
                SELECT created_at, event_type, entity_id, payload FROM audit_journal
                WHERE entity_id = :e OR (event_type LIKE 'PORTAL_%' AND payload LIKE :by)
                ORDER BY seq DESC LIMIT :n""")
                .param("e", "user:" + u).param("by", "%\"by\":\"" + u.replace("%", "").replace("_", "\\_") + "\"%")
                .param("n", Math.max(1, Math.min(limit, 500)))
                .query((rs, i) -> new Activity(rs.getObject("created_at", OffsetDateTime.class), rs.getString("event_type"),
                        rs.getString("entity_id"), parse(rs.getString("payload"))))
                .list();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(String json) {
        try { return MAPPER.readValue(json, Map.class); } catch (Exception e) { return Map.of("raw", json); }
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();

    /** Emails a new temporary password. Returns the masked address, or throws EMAIL_UNAVAILABLE. */
    private String mailTemporaryPassword(String to, String name, String username, String temp, boolean reset) {
        String html = EmailTemplate.html(reset ? "Your password was reset" : "Your portal account is ready",
                reset ? "An administrator reset your Smart Teller portal password." : "Your Smart Teller portal account was created.",
                "Hello " + name + ",",
                List.of(reset ? "An administrator reset your password for the Smart Teller (STM) portal."
                        : "An administrator created your account for the Smart Teller (STM) portal."),
                List.of(new EmailTemplate.Fact("Username", username, false), new EmailTemplate.Fact("Temporary password", temp, false)),
                List.of("Sign in to the STM portal with these. You will be asked to choose your own password straight away, "
                        + "and a sign-in code will be sent to this email address each time you sign in."),
                "If you did not expect this email, tell your portal administrator.");
        email.send(to, reset ? "Your Smart Teller portal password was reset" : "Your Smart Teller portal account", """
                Hello %s,

                %s

                    Username:            %s
                    Temporary password:  %s

                Sign in to the STM portal with these. You will be asked to choose your own password straight away,
                and a sign-in code will be sent to this email address each time you sign in.

                If you did not expect this email, tell your portal administrator.

                Blue River Smart Teller
                """.formatted(name, reset ? "An administrator reset your password for the Smart Teller (STM) portal."
                        : "An administrator created your account for the Smart Teller (STM) portal.", username, temp), html,
                reset ? "PORTAL_PASSWORD_RESET" : "PORTAL_ACCOUNT_CREATED", true);
        return maskEmail(to);
    }

    public Created createUser(PortalUser admin, String username, String displayName, String emailAddress, PortalUser.Role role,
                              String branchCode, List<String> languages, boolean sendEmail) {
        String u = normalise(username);
        String mail = cleanEmail(emailAddress);
        if (!USERNAME.matcher(u).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_USERNAME",
                    "Use 3 to 50 lowercase letters, digits, dots, dashes or underscores.");
        }
        String temp = temporaryPassword();
        try {
            jdbc.sql("""
                    INSERT INTO portal_users (username, display_name, email, password_hash, role, branch_code, languages, created_by)
                    VALUES (:u, :n, :e, :p, :r, :b, :l, :by)""")
                    .param("u", u).param("n", displayName.trim()).param("e", mail).param("p", encoder.encode(temp)).param("r", role.name())
                    .param("b", blankToNull(branchCode)).param("l", langs(languages)).param("by", admin.username()).update();
        } catch (DuplicateKeyException e) {
            throw duplicate(e);
        }
        audit.record("PORTAL_USER_CREATED", "user:" + u, null, Map.of("by", admin.username(), "role", role.name()));
        return deliver(find(u), temp, sendEmail, false);
    }

    public UserRow updateUser(PortalUser admin, String username, String displayName, String emailAddress, PortalUser.Role role,
                              String branchCode, List<String> languages, String status) {
        String u = normalise(username);
        if (u.equals(admin.username()) && (role != null && role != PortalUser.Role.ADMIN || "DISABLED".equals(status))) {
            throw new ApiException(HttpStatus.CONFLICT, "CANNOT_DEMOTE_SELF", "You cannot remove your own administrator access.");
        }
        if (status != null && !List.of("ACTIVE", "DISABLED").contains(status)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_STATUS", "Status must be ACTIVE or DISABLED");
        }
        if (displayName != null && displayName.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_NAME", "The full name cannot be empty.");
        }
        String mail = emailAddress == null ? null : cleanEmail(emailAddress);
        int n;
        try {
            n = jdbc.sql("""
                UPDATE portal_users SET
                  display_name = COALESCE(:n, display_name),
                  email = CASE WHEN :setEmail THEN :e ELSE email END,
                  role = COALESCE(:r, role),
                  branch_code = CASE WHEN :setBranch THEN :b ELSE branch_code END,
                  languages = COALESCE(:l, languages),
                  status = COALESCE(:s, status),
                  locked_until = CASE WHEN :s = 'ACTIVE' THEN NULL ELSE locked_until END,
                  failed_attempts = CASE WHEN :s = 'ACTIVE' THEN 0 ELSE failed_attempts END,
                  archived_at = CASE WHEN :s = 'ACTIVE' THEN NULL ELSE archived_at END,
                  archived_by = CASE WHEN :s = 'ACTIVE' THEN NULL ELSE archived_by END
                WHERE username = :u""")
                .param("n", displayName == null ? null : displayName.trim()).param("r", role == null ? null : role.name())
                .param("setBranch", branchCode != null).param("b", blankToNull(branchCode))
                .param("l", languages == null ? null : langs(languages)).param("s", status).param("u", u)
                .param("setEmail", emailAddress != null).param("e", mail).update();
        } catch (DuplicateKeyException e) {
            throw duplicate(e);
        }
        if (n == 0) throw new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "No such user");
        if ("DISABLED".equals(status)) revokeAll(u);
        audit.record("PORTAL_USER_UPDATED", "user:" + u, null, Map.of("by", admin.username(),
                "role", role == null ? "" : role.name(), "status", status == null ? "" : status,
                "emailChanged", emailAddress != null, "nameChanged", displayName != null));
        return find(u);
    }

    /** Emails the temporary password when asked and possible; otherwise (or if the email fails) returns it to show once. */
    private Created deliver(UserRow row, String temp, boolean sendEmail, boolean reset) {
        if (!sendEmail || row.email() == null) return new Created(row, temp, null, null);
        try {
            return new Created(row, null, mailTemporaryPassword(row.email(), row.displayName(), row.username(), temp, reset), null);
        } catch (ApiException e) {
            return new Created(row, temp, null, e.getMessage());
        }
    }

    /** New temporary password, emailed or shown once; the user must change it at next sign-in. Signs the user out everywhere. */
    public Created resetPassword(PortalUser admin, String username, boolean sendEmail) {
        String temp = resetPasswordRaw(admin, username);
        return deliver(find(normalise(username)), temp, sendEmail, true);
    }

    private String resetPasswordRaw(PortalUser admin, String username) {
        String u = normalise(username);
        String temp = temporaryPassword();
        int n = jdbc.sql("""
                UPDATE portal_users SET password_hash = :p, must_change_password = true, failed_attempts = 0, locked_until = NULL
                WHERE username = :u AND status <> 'ARCHIVED'""").param("p", encoder.encode(temp)).param("u", u).update();
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

    /** Six random digits. */
    private static String otpCode() {
        return String.format("%06d", RNG.nextInt(1_000_000));
    }

    /** jane.doe@bank.co.ke -> j•••••e@bank.co.ke */
    static String maskEmail(String e) {
        int at = e.indexOf('@');
        if (at <= 0) return "•••";
        String local = e.substring(0, at);
        String shown = local.length() <= 2 ? local.charAt(0) + "•" : local.charAt(0) + "•".repeat(Math.min(local.length() - 2, 6)) + local.charAt(local.length() - 1);
        return shown + e.substring(at);
    }

    /** Blank = no email (null). Otherwise trimmed, lowercased and checked. */
    private static String cleanEmail(String e) {
        if (e == null || e.isBlank()) return null;
        String m = e.trim().toLowerCase();
        if (m.length() > 200 || !EMAIL.matcher(m).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_EMAIL", "Enter a valid email address, for example name@bank.co.ke.");
        }
        return m;
    }

    private static ApiException duplicate(DuplicateKeyException e) {
        String msg = String.valueOf(e.getMostSpecificCause().getMessage());
        return msg.contains("email")
                ? new ApiException(HttpStatus.CONFLICT, "EMAIL_EXISTS", "Another account already uses that email address.")
                : new ApiException(HttpStatus.CONFLICT, "USER_EXISTS", "That username is already taken.");
    }

    private static String randomToken() {
        byte[] raw = new byte[32];
        RNG.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    private int revokeAll(String username) {
        return jdbc.sql("""
                UPDATE portal_sessions SET revoked = true
                WHERE NOT revoked AND expires_at > now() AND user_id = (SELECT id FROM portal_users WHERE username = :u)""")
                .param("u", username).update();
    }

    private UserRow find(String username) {
        return jdbc.sql(ROW_SQL + " WHERE u.username = :u").param("u", username)
                .query(PortalAuthService::toRow).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "No such user"));
    }

    private static PortalUser toUser(ResultSet rs, int n) throws SQLException {
        return new PortalUser(rs.getLong("id"), rs.getString("username"), rs.getString("display_name"),
                PortalUser.Role.valueOf(rs.getString("role")), rs.getString("branch_code"),
                splitLangs(rs.getString("languages")), rs.getBoolean("must_change_password"),
                rs.getObject("expires_at", OffsetDateTime.class), false);
    }

    private static UserRow toRow(ResultSet rs, int n) throws SQLException {
        OffsetDateTime locked = rs.getObject("locked_until", OffsetDateTime.class);
        return new UserRow(rs.getString("username"), rs.getString("display_name"), rs.getString("email"), rs.getString("role"),
                rs.getString("branch_code"), splitLangs(rs.getString("languages")), rs.getString("status"),
                rs.getBoolean("must_change_password"), locked != null && locked.isAfter(OffsetDateTime.now()),
                rs.getObject("last_login_at", OffsetDateTime.class), rs.getObject("created_at", OffsetDateTime.class),
                rs.getString("created_by"), rs.getInt("active_sessions"), rs.getObject("archived_at", OffsetDateTime.class));
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
