package com.stm.middleware.backend.sim;

import com.stm.middleware.backend.Telco;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * SIMULATED SIM-swap lookups (sim_sim_swaps). Swaps are stored as "hours ago", so a demo customer's recent swap stays
 * recent however long the system has been running. Not used in the prod profile.
 */
@Service
@Profile("!prod")
public class SimulatedTelco implements Telco {
    private final JdbcClient jdbc;
    private final SimControl control;

    public SimulatedTelco(JdbcClient jdbc, SimControl control) {
        this.jdbc = jdbc;
        this.control = control;
    }

    @Override
    public Optional<OffsetDateTime> lastSimSwap(String msisdn) {
        control.core();
        return jdbc.sql("SELECT now() - make_interval(hours => hours_ago) FROM sim_sim_swaps WHERE msisdn = :m")
                .param("m", msisdn).query(OffsetDateTime.class).optional();
    }
}
