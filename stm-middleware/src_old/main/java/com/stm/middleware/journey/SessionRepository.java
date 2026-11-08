package com.stm.middleware.journey;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;

@Repository
public class SessionRepository {
    /** Sentinel: set the column to now(). */
    public static final Object NOW = new Object();

    private static final Set<String> COLUMNS = Set.of(
            "step", "status", "surname", "given_names", "date_of_birth", "nationality", "sex", "document_type",
            "document_number", "document_expiry", "mobile", "email", "consent_given", "consent_at", "customer_id",
            "review_required", "review_status", "identity_status", "face_status", "face_attempts", "finger_status",
            "finger_attempts", "tier", "product_code", "agreement_version", "signature_method", "signed_at",
            "account_no", "account_branch", "account_currency", "card_ref", "card_masked_pan", "card_status",
            "embossed_name", "error_ref", "lookup_attempts", "escalated", "escalation_reason", "completed_at");

    public record Fulfilment(UUID id, UUID sessionId, String cardRef, String accountNo, String branchCode,
                             String errorRef, String status, OffsetDateTime createdAt) {}

    private static final RowMapper<KioskSession> MAPPER = (rs, n) -> new KioskSession(
            UUID.fromString(rs.getString("id")), rs.getLong("terminal_id"),
            JourneyType.valueOf(rs.getString("journey")), rs.getString("language"),
            Step.valueOf(rs.getString("step")), rs.getString("status"),
            rs.getString("surname"), rs.getString("given_names"), rs.getObject("date_of_birth", LocalDate.class),
            rs.getString("nationality"), rs.getString("sex"),
            rs.getString("document_type"), rs.getString("document_number"),
            rs.getObject("document_expiry", LocalDate.class),
            rs.getString("mobile"), rs.getString("email"), rs.getBoolean("consent_given"),
            rs.getString("customer_id"), rs.getBoolean("review_required"), rs.getString("review_status"),
            rs.getString("identity_status"), rs.getString("face_status"), rs.getInt("face_attempts"),
            rs.getString("finger_status"), rs.getInt("finger_attempts"),
            rs.getString("tier"), rs.getString("product_code"), rs.getString("signature_method"),
            rs.getString("account_no"), rs.getString("account_branch"), rs.getString("account_currency"),
            rs.getString("card_ref"), rs.getString("card_masked_pan"), rs.getString("card_status"),
            rs.getString("embossed_name"), rs.getString("error_ref"),
            rs.getInt("lookup_attempts"), rs.getBoolean("escalated"), rs.getString("escalation_reason"),
            rs.getObject("created_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class));

    private final JdbcClient jdbc;

    public SessionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(UUID id, long terminalId, String idempotencyKey, JourneyType journey, String language, Step step) {
        jdbc.sql("""
                INSERT INTO kiosk_sessions (id, terminal_id, idempotency_key, journey, language, step)
                VALUES (:id, :t, :k, :j, :l, :s)""")
                .param("id", id).param("t", terminalId).param("k", idempotencyKey)
                .param("j", journey.name()).param("l", language).param("s", step.name())
                .update();
    }

    public Optional<KioskSession> find(UUID id) {
        return jdbc.sql("SELECT * FROM kiosk_sessions WHERE id = :id").param("id", id).query(MAPPER).optional();
    }

    public Optional<KioskSession> findByIdempotencyKey(long terminalId, String key) {
        return jdbc.sql("SELECT * FROM kiosk_sessions WHERE terminal_id = :t AND idempotency_key = :k")
                .param("t", terminalId).param("k", key).query(MAPPER).optional();
    }

    public List<KioskSession> findInProgressByTerminal(long terminalId) {
        return jdbc.sql("SELECT * FROM kiosk_sessions WHERE terminal_id = :t AND status = 'IN_PROGRESS'")
                .param("t", terminalId).query(MAPPER).list();
    }

    public List<KioskSession> findIdleInProgress(int idleSeconds) {
        return jdbc.sql("""
                SELECT * FROM kiosk_sessions
                WHERE status = 'IN_PROGRESS' AND updated_at <= now() - (:s * interval '1 second')
                ORDER BY updated_at LIMIT 100""")
                .param("s", idleSeconds).query(MAPPER).list();
    }

    /** Abandoned sessions that never opened an account: candidates for PII removal (DECLINED is kept for AML). */
    public List<KioskSession> findScrubCandidates(int olderThanHours) {
        return jdbc.sql("""
                SELECT * FROM kiosk_sessions
                WHERE status IN ('CANCELLED', 'TIMED_OUT', 'ABANDONED', 'REJECTED')
                  AND account_no IS NULL AND pii_scrubbed = false
                  AND updated_at <= now() - (:h * interval '1 hour')
                ORDER BY updated_at LIMIT 100""")
                .param("h", olderThanHours).query(MAPPER).list();
    }

    public List<KioskSession> list(String status, Boolean escalated) {
        StringBuilder sql = new StringBuilder("SELECT * FROM kiosk_sessions WHERE 1 = 1");
        Map<String, Object> params = new HashMap<>();
        if (status != null) { sql.append(" AND status = :status"); params.put("status", status); }
        if (escalated != null) { sql.append(" AND escalated = :esc"); params.put("esc", escalated); }
        sql.append(" ORDER BY updated_at DESC LIMIT 200");
        return jdbc.sql(sql.toString()).params(params).query(MAPPER).list();
    }

    /**
     * Compare-and-set update: applies only if the session is still at {@code expectedStep} (null = any step).
     * Column names are whitelisted; values are always bound parameters. Returns false if nothing was updated.
     */
    public boolean update(UUID id, Step expectedStep, Map<String, Object> cols) {
        StringBuilder sql = new StringBuilder("UPDATE kiosk_sessions SET updated_at = now()");
        Map<String, Object> params = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : cols.entrySet()) {
            if (!COLUMNS.contains(e.getKey())) throw new IllegalArgumentException("Unknown column " + e.getKey());
            if (e.getValue() == NOW) {
                sql.append(", ").append(e.getKey()).append(" = now()");
            } else {
                sql.append(", ").append(e.getKey()).append(" = :c_").append(e.getKey());
                Object v = e.getValue();
                params.put("c_" + e.getKey(), v instanceof Enum<?> en ? en.name() : v);
            }
        }
        sql.append(" WHERE id = :w_id");
        params.put("w_id", id);
        if (expectedStep != null) {
            sql.append(" AND step = :w_step");
            params.put("w_step", expectedStep.name());
        }
        return jdbc.sql(sql.toString()).params(params).update() == 1;
    }

    public void touch(UUID id) {
        jdbc.sql("UPDATE kiosk_sessions SET updated_at = now() WHERE id = :id").param("id", id).update();
    }

    public void scrubPii(UUID id) {
        jdbc.sql("""
                UPDATE kiosk_sessions
                SET surname = NULL, given_names = NULL, date_of_birth = NULL, document_number = NULL,
                    mobile = NULL, email = NULL, pii_scrubbed = true, updated_at = now()
                WHERE id = :id""").param("id", id).update();
    }

    public void insertFulfilment(UUID sessionId, String cardRef, String accountNo, String branchCode, String errorRef) {
        jdbc.sql("""
                INSERT INTO card_fulfilments (id, session_id, card_ref, account_no, branch_code, error_ref)
                VALUES (:id, :s, :c, :a, :b, :e)""")
                .param("id", UUID.randomUUID()).param("s", sessionId).param("c", cardRef)
                .param("a", accountNo).param("b", branchCode).param("e", errorRef).update();
    }

    public List<Fulfilment> listFulfilments(String status) {
        return jdbc.sql("""
                SELECT id, session_id, card_ref, account_no, branch_code, error_ref, status, created_at
                FROM card_fulfilments WHERE status = :s ORDER BY created_at DESC LIMIT 200""")
                .param("s", status)
                .query((rs, n) -> new Fulfilment(UUID.fromString(rs.getString("id")),
                        UUID.fromString(rs.getString("session_id")), rs.getString("card_ref"),
                        rs.getString("account_no"), rs.getString("branch_code"), rs.getString("error_ref"),
                        rs.getString("status"), rs.getObject("created_at", OffsetDateTime.class)))
                .list();
    }
}
