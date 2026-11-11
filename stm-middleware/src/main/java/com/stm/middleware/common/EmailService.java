package com.stm.middleware.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Sends email through the SMTP server in stm.mail.* (SMTP_HOST etc. in the deploy .env).
 * With no SMTP host configured, mail is SIMULATED: it is written to email_outbox (with its body) and the log,
 * so a pilot still works and an administrator can read sign-in codes in the portal (Portal accounts, Emails).
 * Every message, sent or not, is recorded in email_outbox; bodies are kept only for simulated mail.
 */
@Service
public class EmailService {
    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    private final JdbcClient jdbc;
    private final SmtpClient.Config smtp;
    private final String from;

    public EmailService(JdbcClient jdbc,
                        @Value("${stm.mail.host:}") String host,
                        @Value("${stm.mail.port:587}") int port,
                        @Value("${stm.mail.security:STARTTLS}") String security,
                        @Value("${stm.mail.username:}") String username,
                        @Value("${stm.mail.password:}") String password,
                        @Value("${stm.mail.timeout-seconds:10}") int timeout,
                        @Value("${stm.mail.from:Smart Teller Portal <no-reply@stm.local>}") String from) {
        this.jdbc = jdbc;
        this.smtp = new SmtpClient.Config(host == null ? "" : host.trim(), port,
                SmtpClient.Security.valueOf(security.trim().toUpperCase()), username, password, timeout);
        this.from = from;
    }

    public record OutboxRow(long id, String to, String subject, String body, String purpose, String status, String error,
                            OffsetDateTime createdAt) {}

    /** True when a real SMTP server is configured. */
    public boolean live() {
        return !smtp.host().isBlank();
    }

    /**
     * Sends one plain-text email. Throws EMAIL_UNAVAILABLE (503) if the SMTP server refuses it; the failure is recorded.
     * @param secret true when the body holds a code or password: it is then never stored for real (SENT) mail
     */
    public void send(String to, String subject, String body, String purpose, boolean secret) {
        send(to, subject, body, null, purpose, secret);
    }

    /** As above, with a designed HTML version next to the plain text. Only the plain text is ever stored. */
    public void send(String to, String subject, String body, String html, String purpose, boolean secret) {
        if (!live()) {
            record(to, subject, body, purpose, "SIMULATED", null);
            log.info("SIMULATED email (no SMTP host configured) to {}: {}", to, subject);
            return;
        }
        try {
            new SmtpClient(smtp).send(from, to, subject, body, html);
            record(to, subject, secret ? null : body, purpose, "SENT", null);
        } catch (java.io.IOException | RuntimeException e) {
            String why = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            record(to, subject, null, purpose, "FAILED", why.length() > 500 ? why.substring(0, 500) : why);
            log.warn("Email to {} failed: {}", to, why);
            throw new ApiException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "EMAIL_UNAVAILABLE",
                    "The email could not be sent. Try again in a minute, or ask an administrator.");
        }
    }

    public record OutboxPage(List<OutboxRow> items, long total, int page, int size, int pages) {}

    /** Newest first, pages numbered from 1. */
    public OutboxPage page(int page, int size) {
        int sz = Math.max(1, Math.min(size, 200));
        long total = jdbc.sql("SELECT count(*) FROM email_outbox").query(Long.class).single();
        int pages = (int) Math.max(1, (total + sz - 1) / sz);
        int pg = Math.max(1, Math.min(page, pages));
        List<OutboxRow> items = jdbc.sql("""
                SELECT id, to_address, subject, body, purpose, status, error, created_at
                FROM email_outbox ORDER BY created_at DESC, id DESC LIMIT :n OFFSET :o""").param("n", sz).param("o", (long) (pg - 1) * sz)
                .query((rs, i) -> new OutboxRow(rs.getLong("id"), rs.getString("to_address"), rs.getString("subject"),
                        rs.getString("body"), rs.getString("purpose"), rs.getString("status"), rs.getString("error"),
                        rs.getObject("created_at", OffsetDateTime.class)))
                .list();
        return new OutboxPage(items, total, pg, sz, pages);
    }

    public List<OutboxRow> recent(int limit) {
        return jdbc.sql("""
                SELECT id, to_address, subject, body, purpose, status, error, created_at
                FROM email_outbox ORDER BY created_at DESC LIMIT :n""").param("n", Math.max(1, Math.min(limit, 500)))
                .query((rs, i) -> new OutboxRow(rs.getLong("id"), rs.getString("to_address"), rs.getString("subject"),
                        rs.getString("body"), rs.getString("purpose"), rs.getString("status"), rs.getString("error"),
                        rs.getObject("created_at", OffsetDateTime.class)))
                .list();
    }

    private void record(String to, String subject, String body, String purpose, String status, String error) {
        jdbc.sql("""
                INSERT INTO email_outbox (to_address, subject, body, purpose, status, error)
                VALUES (:t, :s, :b, :p, :st, :e)""")
                .param("t", to).param("s", subject).param("b", body).param("p", purpose).param("st", status).param("e", error)
                .update();
    }
}
