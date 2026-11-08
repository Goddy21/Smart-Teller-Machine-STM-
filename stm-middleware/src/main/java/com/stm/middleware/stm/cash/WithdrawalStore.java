package com.stm.middleware.stm.cash;

import com.stm.middleware.stm.core.Ctx;
import com.stm.middleware.stm.core.Json;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/** Inserts withdrawal rows (W-2, and the KES pay-out of a forex exchange). */
@Component
public class WithdrawalStore {
    private final JdbcClient jdbc;
    private final Json json;

    public WithdrawalStore(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void insert(String id, Ctx ctx, String customerId, String accountId, String source, String currency,
                       BigDecimal amount, BigDecimal local, String quoteId, String exchangeId, List<Cassettes.Note> mix,
                       String txRef, String coreRef, BigDecimal balanceAfter) {
        jdbc.sql("""
                INSERT INTO cash_withdrawals (id, session_id, terminal_id, customer_id, account_id, source, currency, amount,
                    local_amount, fx_quote_id, exchange_id, mix, status, transaction_ref, core_ref, balance_after)
                VALUES (:id, :s, :t, :c, :a, :src, :cur, :amt, :loc, :q, :x, :mix, 'AUTHORISED', :tr, :cr, :b)""")
                .param("id", id).param("s", ctx.sessionId()).param("t", ctx.terminalId()).param("c", customerId)
                .param("a", accountId).param("src", source).param("cur", currency).param("amt", amount).param("loc", local)
                .param("q", quoteId).param("x", exchangeId).param("mix", json.write(mix)).param("tr", txRef)
                .param("cr", coreRef).param("b", balanceAfter).update();
    }
}
