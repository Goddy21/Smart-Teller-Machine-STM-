package com.stm.middleware.stm.core;

import com.stm.middleware.audit.AuditService;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** Writes Gateway events to the hash-chained audit journal. Session events use the session id as the entity. */
@Component
public class StmAudit {
    private final AuditService audit;

    public StmAudit(AuditService audit) {
        this.audit = audit;
    }

    public void session(Ctx ctx, String eventType, Object... keyValues) {
        audit.record(eventType, ctx.sessionId(), ctx.terminalId(), map(keyValues));
    }

    public void device(DevicePrincipal d, String eventType, Object... keyValues) {
        audit.record(eventType, d.deviceId(), d.terminalId(), map(keyValues));
    }

    public void entity(String entityId, Long terminalId, String eventType, Object... keyValues) {
        audit.record(eventType, entityId, terminalId, map(keyValues));
    }

    /** key, value, key, value ... (null values are left out). */
    public static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (kv[i + 1] != null) m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }
}
