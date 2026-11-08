package com.stm.middleware.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stm.middleware.common.Hashing;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * Append-only, hash-chained journal (payload stored as TEXT, not jsonb, so the hashed bytes never change). Each row's hash covers the previous row's hash, so any edit or
 * deletion is detectable via {@link #verifyChain()}. DB triggers additionally block UPDATE/DELETE.
 * Joins the caller's transaction, so a business change and its audit record commit or roll back together.
 */
@Service
public class AuditService {
    private static final String GENESIS = "0".repeat(64);
    private static final long CHAIN_LOCK_KEY = 727_001L;

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public AuditService(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public record VerifyResult(boolean valid, long checked, Long brokenAtSeq) {}

    private record Row(long seq, String eventType, String entityId, Long terminalId, String payload,
                       String prevHash, String hash, Instant createdAt) {}

    @Transactional
    public void record(String eventType, String entityId, Long terminalId, Map<String, Object> payload) {
        String json;
        try {
            json = mapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise audit payload", e);
        }
        // Transaction-scoped advisory lock serialises writers so the chain has no forks (released at commit/rollback).
        jdbc.sql("SELECT pg_advisory_xact_lock(:k)").param("k", CHAIN_LOCK_KEY).query((rs, n) -> 1).list();
        String prev = jdbc.sql("SELECT hash FROM audit_journal ORDER BY seq DESC LIMIT 1")
                .query(String.class).optional().orElse(GENESIS);
        Instant at = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        String hash = Hashing.sha256Hex(canonical(prev, eventType, entityId, terminalId, json, at));
        jdbc.sql("""
                INSERT INTO audit_journal (event_type, entity_id, terminal_id, payload, prev_hash, hash, created_at)
                VALUES (:e, :i, :t, :p, :ph, :h, :c)""")
                .param("e", eventType)
                .param("i", entityId)
                .param("t", terminalId, Types.BIGINT)
                .param("p", json)
                .param("ph", prev)
                .param("h", hash)
                .param("c", OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
                .update();
    }

    public VerifyResult verifyChain() {
        String prev = GENESIS;
        long lastSeq = 0;
        long checked = 0;
        while (true) {
            List<Row> rows = jdbc.sql("""
                    SELECT seq, event_type, entity_id, terminal_id, payload, prev_hash, hash, created_at
                    FROM audit_journal WHERE seq > :s ORDER BY seq LIMIT 1000""")
                    .param("s", lastSeq)
                    .query((rs, n) -> new Row(rs.getLong("seq"), rs.getString("event_type"), rs.getString("entity_id"),
                            rs.getObject("terminal_id", Long.class), rs.getString("payload"),
                            rs.getString("prev_hash"), rs.getString("hash"),
                            rs.getObject("created_at", OffsetDateTime.class).toInstant()))
                    .list();
            if (rows.isEmpty()) break;
            for (Row r : rows) {
                String expected = Hashing.sha256Hex(
                        canonical(prev, r.eventType(), r.entityId(), r.terminalId(), r.payload(), r.createdAt()));
                if (!r.prevHash().equals(prev) || !expected.equals(r.hash())) {
                    return new VerifyResult(false, checked, r.seq());
                }
                prev = r.hash();
                lastSeq = r.seq();
                checked++;
            }
        }
        return new VerifyResult(true, checked, null);
    }

    private static String canonical(String prev, String eventType, String entityId, Long terminalId,
                                    String payload, Instant at) {
        return String.join("|", prev, eventType, entityId, terminalId == null ? "" : terminalId.toString(), payload, at.toString());
    }
}
