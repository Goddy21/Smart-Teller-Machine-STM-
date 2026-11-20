package com.stm.middleware.backend.sim;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Keeps the simulator's dated test data current: the card-journeys test customer's "renew" card (crd_T002) always
 * expires next month, so the renewal journey can be tested on any day. Only that card, and only while it is ACTIVE.
 */
@Component
@Profile("!prod")
class SimTestData {
    private final JdbcClient jdbc;

    SimTestData(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @EventListener(ApplicationReadyEvent.class)
    void refresh() {
        try {
            jdbc.sql("""
                    UPDATE sim_cards SET expiry = to_char(now() + interval '1 month', 'YYYY-MM')
                    WHERE id = 'crd_T002' AND status = 'ACTIVE'
                      AND expiry <> to_char(now() + interval '1 month', 'YYYY-MM')""").update();
        } catch (RuntimeException ignored) {
            // no simulator tables (another profile): nothing to keep current
        }
    }
}
