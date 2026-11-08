package com.stm.middleware.stm.accounts;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/** Cash limits at the STM (spec open question 5: confirm with the bank). */
@Component
public class Limits {
    public static final BigDecimal PER_WITHDRAWAL = new BigDecimal("40000.00");
    public static final BigDecimal DAILY_WITHDRAWAL = new BigDecimal("40000.00");
    public static final BigDecimal MULTIPLE_OF = new BigDecimal("100");
    public static final int DEPOSIT_MAX_NOTES = 100;
    public static final BigDecimal DEPOSIT_MAX_AMOUNT = new BigDecimal("500000.00");
    public static final int FX_MAX_NOTES = 50;
    public static final BigDecimal FX_MULTIPLE_OF = new BigDecimal("50");

    private final JdbcClient jdbc;

    public Limits(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** KES withdrawn today by this customer at any STM (authorised or completed). */
    public BigDecimal withdrawnToday(String customerId) {
        return jdbc.sql("""
                SELECT coalesce(sum(local_amount), 0) FROM cash_withdrawals
                WHERE customer_id = :c AND source = 'ACCOUNT' AND status IN ('AUTHORISED', 'COMPLETED')
                  AND created_at >= date_trunc('day', now())""")
                .param("c", customerId).query(BigDecimal.class).single();
    }

    public BigDecimal dailyRemaining(String customerId) {
        return DAILY_WITHDRAWAL.subtract(withdrawnToday(customerId)).max(BigDecimal.ZERO);
    }

    /** Foreign notes sold today to this customer, in that currency. */
    public BigDecimal foreignBoughtToday(String customerId, String currency) {
        return jdbc.sql("""
                SELECT coalesce(sum(amount), 0) FROM cash_withdrawals
                WHERE customer_id = :c AND source = 'FX_SELL' AND currency = :cur AND status IN ('AUTHORISED', 'COMPLETED')
                  AND created_at >= date_trunc('day', now())""")
                .param("c", customerId).param("cur", currency).query(BigDecimal.class).single();
    }
}
