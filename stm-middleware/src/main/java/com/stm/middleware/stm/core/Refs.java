package com.stm.middleware.stm.core;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Customer-facing references: STM-DEP-10001, STM-WDL-10002, STM-FX-10003 ... (one running sequence). */
@Component
public class Refs {
    private final JdbcClient jdbc;

    public Refs(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public String next(String kind) {
        long n = jdbc.sql("SELECT nextval('stm_ref_seq')").query(Long.class).single();
        return "STM-" + kind + "-" + n;
    }
}
