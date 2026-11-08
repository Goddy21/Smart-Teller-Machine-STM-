package com.stm.middleware.stm.core;

import com.stm.middleware.audit.AuditService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Server-side safety net: sessions the kiosk never ended (power cut, crash) expire after the idle limit, their
 * customer tokens are voided and open quotes closed. Money items stay as they are for the kiosk (or back office)
 * to finish: an authorised withdrawal or a counted deposit is never decided here.
 */
@Component
public class SessionReaper {
    private final JdbcClient jdbc;
    private final AuditService audit;
    private final int idleSeconds;

    public SessionReaper(JdbcClient jdbc, AuditService audit, @Value("${stm.session.idle-seconds:600}") int idleSeconds) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.idleSeconds = idleSeconds;
    }

    private record Expired(String id, long terminalId) {}

    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    @Transactional
    public void expire() {
        List<Expired> expired = jdbc.sql("""
                UPDATE stm_sessions SET status = 'EXPIRED', ended_at = now(), end_reason = 'TIMEOUT'
                WHERE status = 'ACTIVE' AND (expires_at < now() OR last_activity_at < now() - make_interval(secs => :idle))
                RETURNING id, terminal_id""")
                .param("idle", idleSeconds).query((rs, n) -> new Expired(rs.getString(1), rs.getLong(2))).list();
        for (Expired e : expired) {
            jdbc.sql("UPDATE customer_tokens SET revoked = true WHERE session_id = :s").param("s", e.id()).update();
            jdbc.sql("UPDATE fx_quotes SET used_at = coalesce(used_at, now()) WHERE session_id = :s").param("s", e.id()).update();
            jdbc.sql("""
                    UPDATE applications SET status = 'EXPIRED', updated_at = now()
                    WHERE session_id = :s AND status NOT IN ('ACCOUNT_OPENED', 'CANCELLED', 'REJECTED', 'EXPIRED')""")
                    .param("s", e.id()).update();
            audit.record("SESSION_EXPIRED", e.id(), e.terminalId(), Map.of("reason", "TIMEOUT"));
        }
    }

    @Scheduled(cron = "0 17 3 * * *")
    public void purge() {
        jdbc.sql("DELETE FROM idempotency_records WHERE created_at < now() - interval '7 days'").update();
        jdbc.sql("DELETE FROM customer_tokens WHERE expires_at < now() - interval '1 day'").update();
    }
}
