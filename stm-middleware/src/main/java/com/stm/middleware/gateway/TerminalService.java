package com.stm.middleware.gateway;

import com.stm.middleware.common.ApiException;
import com.stm.middleware.common.Hashing;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;

@Service
public class TerminalService {
    private static final SecureRandom RNG = new SecureRandom();
    private final JdbcClient jdbc;

    public TerminalService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Registration(String terminalCode, String token) {}

    /** Registers a terminal and returns its opaque session token ONCE. Only the SHA-256 hash is stored. */
    public Registration register(String terminalCode, String branchCode) {
        byte[] raw = new byte[32];
        RNG.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        try {
            jdbc.sql("INSERT INTO terminals (terminal_code, branch_code, token_hash) VALUES (:c, :b, :h)")
                    .param("c", terminalCode).param("b", branchCode).param("h", Hashing.sha256Hex(token))
                    .update();
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "TERMINAL_EXISTS", "Terminal already registered: " + terminalCode);
        }
        return new Registration(terminalCode, token);
    }

    public Optional<TerminalPrincipal> authenticate(String token) {
        return jdbc.sql("SELECT id, terminal_code FROM terminals WHERE token_hash = :h AND status = 'ACTIVE'")
                .param("h", Hashing.sha256Hex(token))
                .query((rs, n) -> new TerminalPrincipal(rs.getLong("id"), rs.getString("terminal_code")))
                .optional();
    }

    public Optional<String> codeById(long terminalId) {
        return jdbc.sql("SELECT terminal_code FROM terminals WHERE id = :i").param("i", terminalId)
                .query(String.class).optional();
    }

    /** LOCKED terminals report "out of service" on GET /api/v1/kiosk/status. */
    public void setServiceMode(String terminalCode, String mode) {
        jdbc.sql("UPDATE terminals SET service_mode = :m WHERE terminal_code = :c")
                .param("m", mode).param("c", terminalCode).update();
    }

    public Optional<TerminalPrincipal> findByCode(String terminalCode) {
        return jdbc.sql("SELECT id, terminal_code FROM terminals WHERE terminal_code = :c AND status = 'ACTIVE'")
                .param("c", terminalCode)
                .query((rs, n) -> new TerminalPrincipal(rs.getLong("id"), rs.getString("terminal_code")))
                .optional();
    }
}
