package com.stm.middleware.orchestrator;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.UUID;

/** Append-only escrow ledger. Entry types: CASH_ACCEPTED, CREDIT_POSTED, CASH_RETURN_REQUIRED. */
@Repository
public class EscrowLedgerRepository {
    private final JdbcClient jdbc;

    public EscrowLedgerRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void append(UUID transactionId, String entryType, BigDecimal amount, String currency) {
        jdbc.sql("INSERT INTO escrow_ledger (transaction_id, entry_type, amount, currency) VALUES (:t, :e, :a, :c)")
                .param("t", transactionId).param("e", entryType)
                .param("a", amount).param("c", currency).update();
    }
}
