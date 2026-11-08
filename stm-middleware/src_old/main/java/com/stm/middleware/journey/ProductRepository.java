package com.stm.middleware.journey;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Repository
public class ProductRepository {
    public record Product(String code, String name, String currency, BigDecimal interestRate,
                          BigDecimal minOpeningBalance, BigDecimal monthlyFee, boolean overdraft) {}

    private static final RowMapper<Product> MAPPER = (rs, n) -> new Product(rs.getString("code"), rs.getString("name"),
            rs.getString("currency"), rs.getBigDecimal("interest_rate"), rs.getBigDecimal("min_opening_balance"),
            rs.getBigDecimal("monthly_fee"), rs.getBoolean("overdraft"));

    private final JdbcClient jdbc;

    public ProductRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Product> listActive() {
        return jdbc.sql("""
                SELECT code, name, currency, interest_rate, min_opening_balance, monthly_fee, overdraft
                FROM account_products WHERE active = true ORDER BY code""").query(MAPPER).list();
    }

    public Optional<Product> findActive(String code) {
        return jdbc.sql("""
                SELECT code, name, currency, interest_rate, min_opening_balance, monthly_fee, overdraft
                FROM account_products WHERE code = :c AND active = true""").param("c", code).query(MAPPER).optional();
    }
}
