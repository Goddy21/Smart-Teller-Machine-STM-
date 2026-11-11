package com.stm.middleware.stm.services;

import com.stm.middleware.stm.core.Ctx;
import com.stm.middleware.stm.core.Ids;
import com.stm.middleware.stm.core.Json;
import com.stm.middleware.stm.core.StmAudit;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * What a customer changed at the kiosk outside account opening and cash: PIN change or reset, card freeze,
 * account reactivation, phone number, biometric enrolment, signature. One row per change (service_events),
 * shown on the session in the portal, plus the audit journal entry.
 */
@Component
public class ServiceEvents {
    private final JdbcClient jdbc;
    private final Json json;
    private final StmAudit audit;

    public ServiceEvents(JdbcClient jdbc, Json json, StmAudit audit) {
        this.jdbc = jdbc;
        this.json = json;
        this.audit = audit;
    }

    /** kind: PIN_CHANGED, PIN_RESET, CARD_TEMP_BLOCKED, CARD_UNBLOCKED, ACCOUNT_REACTIVATED, MOBILE_CHANGED ... */
    public String record(Ctx ctx, String customerId, String kind, String subject, String reference, Object... detail) {
        String id = Ids.next("svc");
        Map<String, Object> d = StmAudit.map(detail);
        jdbc.sql("""
                INSERT INTO service_events (id, session_id, terminal_id, customer_id, kind, subject, detail, reference)
                VALUES (:id, :s, :t, :c, :k, :sub, :d, :r)""")
                .param("id", id).param("s", ctx.sessionId()).param("t", ctx.terminalId()).param("c", customerId)
                .param("k", kind).param("sub", subject).param("d", json.write(d)).param("r", reference).update();
        Map<String, Object> a = new java.util.LinkedHashMap<>(d);
        a.put("customerId", customerId);
        if (subject != null) a.put("subject", subject);
        if (reference != null) a.put("reference", reference);
        java.util.List<Object> kv = new java.util.ArrayList<>();
        a.forEach((k, v) -> { kv.add(k); kv.add(v); });
        audit.session(ctx, kind, kv.toArray());
        return id;
    }
}
