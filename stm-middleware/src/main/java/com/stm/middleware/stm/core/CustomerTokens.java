package com.stm.middleware.stm.core;

import com.stm.middleware.common.Hashing;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * X-Customer-Token: issued after card + PIN (S-2, source CARD_PIN), identity + OTP + thumbprint (B-5 for card
 * services, VERIFIED_IDENTITY), face + thumbprint when the phone is lost (BIOMETRIC_ONLY: only a change of phone number)
 * or account opening (B-12, ONBOARDING). Bound to one session and one customer; voided when the session ends.
 */
@Service
public class CustomerTokens {
    private final JdbcClient jdbc;

    public CustomerTokens(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public String issue(String sessionId, String customerId, String source) {
        String token = Ids.secret("cst_");
        jdbc.sql("""
                INSERT INTO customer_tokens (token_hash, session_id, customer_id, source, expires_at)
                VALUES (:h, :s, :c, :src, now() + interval '30 minutes')""")
                .param("h", Hashing.sha256Hex(token)).param("s", sessionId).param("c", customerId).param("src", source)
                .update();
        return token;
    }

    /** The customer the token belongs to, if it is valid for this session. */
    public String validate(String token, String sessionId) {
        if (token == null || token.isBlank()) throw Errors.customerTokenRequired();
        return jdbc.sql("""
                SELECT customer_id FROM customer_tokens
                WHERE token_hash = :h AND session_id = :s AND NOT revoked AND expires_at > now()""")
                .param("h", Hashing.sha256Hex(token.trim())).param("s", sessionId)
                .query(String.class).optional().orElseThrow(Errors::customerTokenInvalid);
    }

    /** How the customer behind this token was verified: CARD_PIN, VERIFIED_IDENTITY, BIOMETRIC_ONLY or ONBOARDING. */
    public String source(String token, String sessionId) {
        if (token == null || token.isBlank()) throw Errors.customerTokenRequired();
        return jdbc.sql("""
                SELECT source FROM customer_tokens
                WHERE token_hash = :h AND session_id = :s AND NOT revoked AND expires_at > now()""")
                .param("h", Hashing.sha256Hex(token.trim())).param("s", sessionId)
                .query(String.class).optional().orElseThrow(Errors::customerTokenInvalid);
    }

    public void revokeAll(String sessionId) {
        jdbc.sql("UPDATE customer_tokens SET revoked = true WHERE session_id = :s").param("s", sessionId).update();
    }
}
