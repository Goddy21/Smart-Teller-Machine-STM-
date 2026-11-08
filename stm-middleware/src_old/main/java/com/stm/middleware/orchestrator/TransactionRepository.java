package com.stm.middleware.orchestrator;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class TransactionRepository {
    private static final RowMapper<Tx> MAPPER = (rs, n) -> new Tx(
            UUID.fromString(rs.getString("id")),
            rs.getLong("terminal_id"),
            TxState.valueOf(rs.getString("state")),
            rs.getString("customer_id"),
            rs.getString("account_no"),
            rs.getString("currency"),
            rs.getBigDecimal("amount"),
            rs.getString("bank_reference"),
            rs.getString("failure_reason"),
            rs.getString("idempotency_key"),
            rs.getInt("version"),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("updated_at", OffsetDateTime.class));

    private final JdbcClient jdbc;

    public TransactionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(UUID id, long terminalId, String customerId, String accountNo, String currency, String key) {
        jdbc.sql("""
                INSERT INTO transactions (id, terminal_id, state, customer_id, account_no, currency, idempotency_key)
                VALUES (:id, :t, 'INITIATED', :c, :a, :cur, :k)""")
                .param("id", id).param("t", terminalId).param("c", customerId)
                .param("a", accountNo).param("cur", currency).param("k", key)
                .update();
    }

    public Optional<Tx> findById(UUID id) {
        return jdbc.sql("SELECT * FROM transactions WHERE id = :id").param("id", id).query(MAPPER).optional();
    }

    public Optional<Tx> findByIdempotencyKey(long terminalId, String key) {
        return jdbc.sql("SELECT * FROM transactions WHERE terminal_id = :t AND idempotency_key = :k")
                .param("t", terminalId).param("k", key).query(MAPPER).optional();
    }

    /** Newest first, for the portal. */
    public List<Tx> findRecent(int limit) {
        return jdbc.sql("SELECT * FROM transactions ORDER BY seq DESC LIMIT :l").param("l", limit).query(MAPPER).list();
    }

    public List<Tx> findByState(TxState state, int olderThanSeconds) {
        return jdbc.sql("""
                SELECT * FROM transactions
                WHERE state = :s AND updated_at <= now() - (:sec * interval '1 second')
                ORDER BY seq LIMIT 200""")
                .param("s", state.name()).param("sec", olderThanSeconds).query(MAPPER).list();
    }

    /** Optimistic-locked update. Returns affected rows (0 = someone else changed it first). */
    public int update(Tx next, int expectedVersion) {
        return jdbc.sql("""
                UPDATE transactions
                SET state = :s, amount = :amt, bank_reference = :ref, failure_reason = :reason,
                    version = version + 1, updated_at = now()
                WHERE id = :id AND version = :v""")
                .param("s", next.state().name())
                .param("amt", next.amount(), Types.DECIMAL)
                .param("ref", next.bankReference(), Types.VARCHAR)
                .param("reason", next.failureReason(), Types.VARCHAR)
                .param("id", next.id())
                .param("v", expectedVersion)
                .update();
    }
}
