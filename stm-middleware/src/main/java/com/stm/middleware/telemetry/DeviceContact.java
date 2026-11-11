package com.stm.middleware.telemetry;

import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Records that a kiosk was heard from: any authenticated gateway call or a device sign-in (D-1), not only the
 * D-6 health report. Written at most every 30 s per kiosk; when a kiosk comes back after being offline the fleet
 * page is told at once.
 */
@Component
public class DeviceContact {
    static final int WRITE_EVERY_MS = 30_000;

    private final JdbcClient jdbc;
    private final TelemetryService telemetry;
    private final Map<Long, Long> lastWrite = new ConcurrentHashMap<>();

    public DeviceContact(JdbcClient jdbc, @Lazy TelemetryService telemetry) {
        this.jdbc = jdbc;
        this.telemetry = telemetry;
    }

    public void seen(long terminalId, String terminalCode) {
        long now = System.currentTimeMillis();
        Long prev = lastWrite.get(terminalId);
        if (prev != null && now - prev < WRITE_EVERY_MS) return;
        lastWrite.put(terminalId, now);
        try {
            OffsetDateTime before = jdbc.sql("SELECT last_contact_at FROM terminals WHERE id = :id").param("id", terminalId)
                    .query(OffsetDateTime.class).optional().orElse(null);
            jdbc.sql("UPDATE terminals SET last_contact_at = now() WHERE id = :id").param("id", terminalId).update();
            if (before == null || before.isBefore(OffsetDateTime.now().minusSeconds(TelemetryService.OFFLINE_AFTER_SEC))) {
                telemetry.changed(terminalCode);
            }
        } catch (RuntimeException e) {
            lastWrite.remove(terminalId);   // best effort: never fail the kiosk's call because of this
        }
    }
}
