package com.stm.middleware.stm.core;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.Optional;

/** Customer sessions (D-2 .. D-4). Every Gateway call inside a session touches it (sliding idle timeout). */
@Service
public class SessionStore {
    static final RowMapper<StmSession> ROW = (rs, n) -> new StmSession(rs.getString("id"), rs.getLong("terminal_id"),
            rs.getString("language"), rs.getString("status"), rs.getString("flow"), rs.getString("customer_id"),
            rs.getObject("started_at", OffsetDateTime.class), rs.getObject("expires_at", OffsetDateTime.class),
            rs.getObject("ended_at", OffsetDateTime.class));

    private final JdbcClient jdbc;
    private final int maxMinutes;
    private final int idleSeconds;

    public SessionStore(JdbcClient jdbc, @Value("${stm.session.max-minutes:30}") int maxMinutes,
                        @Value("${stm.session.idle-seconds:600}") int idleSeconds) {
        this.jdbc = jdbc;
        this.maxMinutes = maxMinutes;
        this.idleSeconds = idleSeconds;
    }

    public StmSession start(long terminalId, String trigger, String language) {
        String id = Ids.next("ses");
        jdbc.sql("""
                INSERT INTO stm_sessions (id, terminal_id, start_trigger, language, expires_at)
                VALUES (:id, :t, :tr, :l, now() + make_interval(mins => :m))""")
                .param("id", id).param("t", terminalId).param("tr", trigger).param("l", language).param("m", maxMinutes)
                .update();
        return find(id).orElseThrow();
    }

    public Optional<StmSession> find(String id) {
        return jdbc.sql("SELECT * FROM stm_sessions WHERE id = :id").param("id", id).query(ROW).optional();
    }

    /** Loads the session for this kiosk; marks it EXPIRED when past its absolute or idle limit. */
    public StmSession require(String id, long terminalId, boolean allowEnded) {
        StmSession s = find(id).filter(x -> x.terminalId() == terminalId).orElseThrow(Errors::sessionInvalid);
        if (s.active()) {
            int expired = jdbc.sql("""
                    UPDATE stm_sessions SET status = 'EXPIRED', ended_at = now(), end_reason = 'TIMEOUT'
                    WHERE id = :id AND status = 'ACTIVE'
                      AND (expires_at < now() OR last_activity_at < now() - make_interval(secs => :idle))""")
                    .param("id", id).param("idle", idleSeconds).update();
            if (expired == 0) {
                jdbc.sql("UPDATE stm_sessions SET last_activity_at = now() WHERE id = :id").param("id", id).update();
                return s;
            }
            s = find(id).orElseThrow();
        }
        // Ended sessions: only the money clean-up calls, and only for 15 minutes.
        if (allowEnded && s.endedAt() != null && s.endedAt().isAfter(OffsetDateTime.now().minusMinutes(15))) return s;
        throw Errors.sessionInvalid().detail("status", s.status());
    }

    public void setFlow(String id, String flow) {
        jdbc.sql("UPDATE stm_sessions SET flow = :f WHERE id = :id").param("f", flow).param("id", id).update();
    }

    public void setCustomer(String id, String customerId, String name) {
        jdbc.sql("UPDATE stm_sessions SET customer_id = :c, customer_name = :n WHERE id = :id")
                .param("c", customerId).param("n", name).param("id", id).update();
    }

    public void setLanguage(String id, String language) {
        jdbc.sql("UPDATE stm_sessions SET language = :l WHERE id = :id").param("l", language).param("id", id).update();
    }

    public void end(String id, String reason, boolean cardRetained) {
        jdbc.sql("""
                UPDATE stm_sessions SET status = 'ENDED', ended_at = now(), end_reason = :r, card_retained = :c
                WHERE id = :id AND status = 'ACTIVE'""")
                .param("r", reason).param("c", cardRetained).param("id", id).update();
    }
}
