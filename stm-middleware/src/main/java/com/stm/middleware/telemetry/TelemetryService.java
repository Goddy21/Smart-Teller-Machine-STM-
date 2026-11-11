package com.stm.middleware.telemetry;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stm.middleware.signaling.SignalingHandler;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Fleet view for the portal: the latest D-6 health report per kiosk (modules, disabled services, card stock),
 * the cassettes the Gateway believes each kiosk holds, and a live SSE stream of changes.
 */
@Service
public class TelemetryService {

    public record TerminalView(String terminalCode, String branchCode, String status, Map<String, Integer> cassetteCounts,
                               Integer cardStock, List<String> alarms, OffsetDateTime lastSeen, boolean online,
                               Map<String, Object> modules, List<String> disabledServices, String serviceMode,
                               String softwareVersion, OffsetDateTime healthReportedAt, OffsetDateTime lastContactAt,
                               boolean commandChannel) {}

    /** Heard from neither by a health report nor by any other call for this long = offline. */
    public static final int OFFLINE_AFTER_SEC = 180;

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final SignalingHandler signaling;
    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    public TelemetryService(JdbcClient jdbc, ObjectMapper mapper, @Lazy SignalingHandler signaling) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.signaling = signaling;
    }

    /**
     * Online = heard from in the last 3 minutes: a D-6 health report (every 60 s), any other gateway call or sign-in,
     * or an open remote-command WebSocket. lastSeen is the latest of those.
     */
    public List<TerminalView> list() {
        OffsetDateTime cutoff = OffsetDateTime.now().minusSeconds(OFFLINE_AFTER_SEC);
        return jdbc.sql("""
                SELECT t.id, t.terminal_code, t.branch_code, t.service_mode, t.software_version, t.last_contact_at,
                       x.status, x.card_stock, x.alarms, x.last_seen, x.reported_at, x.modules, x.disabled_services
                FROM terminals t LEFT JOIN terminal_telemetry x ON x.terminal_id = t.id
                WHERE t.status <> 'RETIRED'
                ORDER BY t.terminal_code""")
                .query((rs, n) -> {
                    OffsetDateTime health = rs.getObject("last_seen", OffsetDateTime.class);
                    OffsetDateTime contact = rs.getObject("last_contact_at", OffsetDateTime.class);
                    OffsetDateTime seen = health == null ? contact : contact == null || health.isAfter(contact) ? health : contact;
                    boolean ws = signaling.kioskConnected(rs.getString("terminal_code"));
                    return new TerminalView(rs.getString("terminal_code"), rs.getString("branch_code"),
                            rs.getString("status"), cassettes(rs.getLong("id")),
                            rs.getObject("card_stock", Integer.class),
                            parse(rs.getString("alarms"), new TypeReference<List<String>>() {}, List.of()),
                            seen, ws || (seen != null && seen.isAfter(cutoff)),
                            parse(rs.getString("modules"), new TypeReference<Map<String, Object>>() {}, Map.of()),
                            parse(rs.getString("disabled_services"), new TypeReference<List<String>>() {}, List.of()),
                            rs.getString("service_mode"), rs.getString("software_version"),
                            rs.getObject("reported_at", OffsetDateTime.class), contact, ws);
                }).list();
    }

    /** "KES 1000" -> 812 notes ... */
    private Map<String, Integer> cassettes(long terminalId) {
        Map<String, Integer> m = new java.util.LinkedHashMap<>();
        jdbc.sql("""
                SELECT currency, denomination, note_count FROM cassette_inventory
                WHERE terminal_id = :t ORDER BY currency, denomination DESC""")
                .param("t", terminalId)
                .query(rs -> {
                    m.put(rs.getString(1) + " " + rs.getBigDecimal(2).toPlainString(), rs.getInt(3));
                });
        return m;
    }

    /** Called after a health report, a lock/unlock or a cash movement on this kiosk. */
    public void changed(String terminalCode) {
        list().stream().filter(v -> v.terminalCode().equals(terminalCode)).findFirst().ifPresent(this::broadcast);
    }

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        return emitter;
    }

    private void broadcast(TerminalView view) {
        for (SseEmitter e : emitters) {
            try {
                e.send(SseEmitter.event().name("terminal-update").data(view));
            } catch (IOException | IllegalStateException ex) {
                emitters.remove(e);
            }
        }
    }

    private <T> T parse(String s, TypeReference<T> type, T fallback) {
        if (s == null) return fallback;
        try {
            return mapper.readValue(s, type);
        } catch (JsonProcessingException e) {
            return fallback;
        }
    }
}
