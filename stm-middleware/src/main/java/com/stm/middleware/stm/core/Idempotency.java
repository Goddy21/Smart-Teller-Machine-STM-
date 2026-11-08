package com.stm.middleware.stm.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.stm.middleware.common.Hashing;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

/**
 * Idempotency-Key handling for every call that moves money, issues a card or charges a fee.
 * The first call runs and its answer (success or business error) is stored; a retry with the same key and the same
 * body gets that answer back without running again; the same key with a different body is 409 IDEMPOTENCY_CONFLICT.
 * The action runs in its own transaction. Keys are scoped per kiosk and per operation, so W-4 "reverse" can reuse W-2's key as the spec asks.
 */
@Service
public class Idempotency {
    private final JdbcClient jdbc;
    private final Json json;
    private final TransactionTemplate tx;

    public Idempotency(JdbcClient jdbc, Json json, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.json = json;
        this.tx = tx;
    }

    private record Stored(String requestHash, Integer status, String body) {}

    public ResponseEntity<Object> run(Ctx ctx, String operation, Object request, HttpStatus okStatus, Supplier<Object> action) {
        String key = ctx.idempotencyKey();
        String hash = Hashing.sha256Hex(operation + "|" + json.write(request));
        try {
            jdbc.sql("""
                    INSERT INTO idempotency_records (terminal_id, idem_key, operation, request_hash)
                    VALUES (:t, :k, :o, :h)""")
                    .param("t", ctx.terminalId()).param("k", key).param("o", operation).param("h", hash).update();
        } catch (DuplicateKeyException dup) {
            Stored s = jdbc.sql("""
                    SELECT request_hash, status_code, response_body FROM idempotency_records
                    WHERE terminal_id = :t AND idem_key = :k AND operation = :o""")
                    .param("t", ctx.terminalId()).param("k", key).param("o", operation)
                    .query((rs, n) -> new Stored(rs.getString(1), (Integer) rs.getObject(2), rs.getString(3))).single();
            if (!s.requestHash().equals(hash)) throw Errors.idempotencyConflict();
            if (s.status() == null) throw Errors.inProgress();
            JsonNode body = json.tree(s.body());
            return ResponseEntity.status(s.status()).header("Idempotent-Replayed", "true").body(body);
        }
        try {
            // The operation runs in one database transaction: a failure leaves nothing half-posted.
            Object result = tx.execute(status -> action.get());
            store(ctx, key, operation, okStatus.value(), json.write(result));
            return ResponseEntity.status(okStatus).body(result);
        } catch (StmException e) {
            if (e.status().is4xxClientError() && !e.retryable()) {
                store(ctx, key, operation, e.status().value(), json.write(ErrorBodies.body(e, ctx.requestId())));
            } else {
                forget(ctx, key, operation);
            }
            throw e;
        } catch (RuntimeException e) {
            forget(ctx, key, operation);
            throw e;
        }
    }

    private void store(Ctx ctx, String key, String op, int status, String body) {
        jdbc.sql("""
                UPDATE idempotency_records SET status_code = :s, response_body = :b
                WHERE terminal_id = :t AND idem_key = :k AND operation = :o""")
                .param("s", status).param("b", body).param("t", ctx.terminalId()).param("k", key).param("o", op).update();
    }

    private void forget(Ctx ctx, String key, String op) {
        jdbc.sql("DELETE FROM idempotency_records WHERE terminal_id = :t AND idem_key = :k AND operation = :o")
                .param("t", ctx.terminalId()).param("k", key).param("o", op).update();
    }
}
