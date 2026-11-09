package com.stm.middleware.stm.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stm.middleware.common.Hashing;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Optional Idempotency-Key on every other /stm/v1 write (POST, PUT, PATCH, DELETE).
 * When the kiosk sends the header, the first answer (success, or an error that a retry would not change) is stored and
 * a retry with the same key and the same body gets it back, with Idempotent-Replayed: true, instead of running again.
 * The same key with another body is 409 IDEMPOTENCY_CONFLICT. Without the header nothing changes.
 * Calls that move money, issue a card or charge a fee keep their own, stricter handling (Idempotency, key required),
 * so they are skipped here. Keys are scoped per kiosk and per method + path.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 100)   // after Spring Security, so the kiosk is known
public class IdempotencyFilter extends OncePerRequestFilter {
    private static final Set<String> WRITES = Set.of("POST", "PUT", "PATCH", "DELETE");
    /** Handled by Idempotency.run in the controller (key required there). */
    private static final List<Pattern> OWN_HANDLING = List.of(
            Pattern.compile("POST /stm/v1/onboarding/applications/[^/]+/submit"),
            Pattern.compile("POST /stm/v1/cards/orders"),
            Pattern.compile("POST /stm/v1/statements"),
            Pattern.compile("POST /stm/v1/cash/deposits/[^/]+/commit"),
            Pattern.compile("POST /stm/v1/cash/withdrawals"),
            Pattern.compile("POST /stm/v1/cash/withdrawals/[^/]+/reverse"),
            Pattern.compile("POST /stm/v1/fx/exchanges/[^/]+/confirm"),
            Pattern.compile("POST /stm/v1/auth/device/token"));

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public IdempotencyFilter(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        String path = req.getRequestURI();
        if (!path.startsWith("/stm/v1/") || !WRITES.contains(req.getMethod())) return true;
        String key = req.getHeader("Idempotency-Key");
        if (key == null || key.isBlank()) return true;
        String op = req.getMethod() + " " + path;
        return OWN_HANDLING.stream().anyMatch(p -> p.matcher(op).matches());
    }

    private record Stored(String requestHash, Integer status, String body) {}

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof DevicePrincipal device)) {
            chain.doFilter(req, res);    // not signed in: the controller answers 401 as usual
            return;
        }
        String key = req.getHeader("Idempotency-Key").trim();
        if (key.length() > 100) {
            ErrorBodies.write(req, res, Errors.validation("Idempotency-Key", "Idempotency-Key is too long"), mapper);
            return;
        }
        byte[] body = req.getInputStream().readAllBytes();
        String op = req.getMethod() + " " + req.getRequestURI();
        if (op.length() > 200) op = op.substring(0, 200);
        String hash = Hashing.sha256Hex(op + "|" + new String(body, StandardCharsets.UTF_8));
        long terminal = device.terminalId();

        try {
            jdbc.sql("INSERT INTO idempotency_records (terminal_id, idem_key, operation, request_hash) VALUES (:t, :k, :o, :h)")
                    .param("t", terminal).param("k", key).param("o", op).param("h", hash).update();
        } catch (DuplicateKeyException dup) {
            Stored s = jdbc.sql("""
                    SELECT request_hash, status_code, response_body FROM idempotency_records
                    WHERE terminal_id = :t AND idem_key = :k AND operation = :o""")
                    .param("t", terminal).param("k", key).param("o", op)
                    .query((rs, n) -> new Stored(rs.getString(1), (Integer) rs.getObject(2), rs.getString(3))).single();
            if (!s.requestHash().equals(hash)) { ErrorBodies.write(req, res, Errors.idempotencyConflict(), mapper); return; }
            if (s.status() == null) { ErrorBodies.write(req, res, Errors.inProgress(), mapper); return; }
            res.setStatus(s.status());
            res.setHeader("Idempotent-Replayed", "true");
            res.setHeader("X-Request-Id", RequestIds.of(req));
            if (s.body() != null && !s.body().isEmpty()) {
                res.setContentType("application/json");
                res.setCharacterEncoding("UTF-8");
                res.getOutputStream().write(s.body().getBytes(StandardCharsets.UTF_8));
            }
            return;
        }

        ContentCachingResponseWrapper wrapped = new ContentCachingResponseWrapper(res);
        boolean done = false;
        try {
            chain.doFilter(new CachedBody(req, body), wrapped);
            int status = wrapped.getStatus();
            String out = new String(wrapped.getContentAsByteArray(), StandardCharsets.UTF_8);
            if (keep(status, out)) {
                jdbc.sql("""
                        UPDATE idempotency_records SET status_code = :s, response_body = :b
                        WHERE terminal_id = :t AND idem_key = :k AND operation = :o""")
                        .param("s", status).param("b", out).param("t", terminal).param("k", key).param("o", op).update();
                done = true;
            }
        } finally {
            if (!done) {
                jdbc.sql("DELETE FROM idempotency_records WHERE terminal_id = :t AND idem_key = :k AND operation = :o")
                        .param("t", terminal).param("k", key).param("o", op).update();
            }
            wrapped.copyBodyToResponse();
        }
    }

    /** Keep successes and errors a retry would not change; forget server errors and retryable ones so a retry runs. */
    private boolean keep(int status, String body) {
        if (status >= 200 && status < 300) return true;
        if (status < 400 || status >= 500) return false;
        try {
            JsonNode err = mapper.readTree(body).path("error");
            return !err.path("retryable").asBoolean(false);
        } catch (Exception e) {
            return false;
        }
    }

    /** The request with its body already read, so it can be hashed and still reach the controller. */
    private static final class CachedBody extends HttpServletRequestWrapper {
        private final byte[] body;

        CachedBody(HttpServletRequest req, byte[] body) {
            super(req);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public int read() { return in.read(); }
                @Override public int read(byte[] b, int off, int len) { return in.read(b, off, len); }
                @Override public boolean isFinished() { return in.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener l) { throw new UnsupportedOperationException(); }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }

        @Override
        public int getContentLength() { return body.length; }

        @Override
        public long getContentLengthLong() { return body.length; }
    }
}
