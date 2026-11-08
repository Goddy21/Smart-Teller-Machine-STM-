package com.stm.middleware.orchestrator;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record Tx(UUID id, long terminalId, TxState state, String customerId, String accountNo, String currency,
                 BigDecimal amount, String bankReference, String failureReason, String idempotencyKey,
                 int version, OffsetDateTime createdAt, OffsetDateTime updatedAt) {

    /** Clears the failure reason (used when a previously failing transaction finally succeeds). */
    public Tx withoutFailureReason() {
        return new Tx(id, terminalId, state, customerId, accountNo, currency, amount, bankReference,
                null, idempotencyKey, version, createdAt, updatedAt);
    }

    /** Returns the next immutable version of this transaction (version is bumped). Null args keep existing values. */
    public Tx next(TxState s, BigDecimal amt, String ref, String reason) {
        return new Tx(id, terminalId, s, customerId, accountNo, currency,
                amt != null ? amt : amount,
                ref != null ? ref : bankReference,
                reason != null ? reason : failureReason,
                idempotencyKey, version + 1, createdAt, OffsetDateTime.now());
    }
}
