package com.stm.middleware.telemetry;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stm.middleware.config.StmProperties;
import com.stm.middleware.gateway.TerminalPrincipal;
import jakarta.validation.constraints.NotBlank;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class TelemetryService {
    public record Heartbeat(@NotBlank String status, Map<String, Integer> cassetteCounts,
                            Integer cardStock, List<String> alarms) {}

    public record TerminalView(String terminalCode, String branchCode, String status,
                               Map<String, Integer> cassetteCounts, Integer cardStock, List<String> alarms,
                               OffsetDateTime lastSeen, boolean online) {}

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final StmProperties props;
    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    public TelemetryService(JdbcClient jdbc, ObjectMapper mapper, StmProperties props) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.props = props;
    }

    public void record(TerminalPrincipal p, Heartbeat hb) {
        List<String> alarms = new ArrayList<>(hb.alarms() == null ? List.of() : hb.alarms());
        if (hb.cardStock() != null && hb.cardStock() < props.lowCardStock() && !alarms.contains("LOW_CARD_STOCK")) {
            alarms.add("LOW_CARD_STOCK");
        }
        String cassettes = json(hb.cassetteCounts() == null ? Map.of() : hb.cassetteCounts());
        String alarmJson = json(alarms);

        if (!update(p.terminalId(), hb.status(), cassettes, hb.cardStock(), alarmJson)) {
            try {
                jdbc.sql("""
                        INSERT INTO terminal_telemetry (terminal_id, status, cassettes, card_stock, alarms)
                        VALUES (:t, :s, :c, :cs, :a)""")
                        .param("t", p.terminalId()).param("s", hb.status()).param("c", cassettes)
                        .param("cs", hb.cardStock(), Types.INTEGER).param("a", alarmJson).update();
            } catch (DuplicateKeyException race) {
                update(p.terminalId(), hb.status(), cassettes, hb.cardStock(), alarmJson);
            }
        }
        list().stream().filter(v -> v.terminalCode().equals(p.terminalCode())).findFirst().ifPresent(this::broadcast);
    }

    private boolean update(long terminalId, String status, String cassettes, Integer cardStock, String alarms) {
        return jdbc.sql("""
                UPDATE terminal_telemetry
                SET status = :s, cassettes = :c, card_stock = :cs, alarms = :a, last_seen = now()
                WHERE terminal_id = :t""")
                .param("s", status).param("c", cassettes).param("cs", cardStock, Types.INTEGER)
                .param("a", alarms).param("t", terminalId).update() == 1;
    }

    public List<TerminalView> list() {
        OffsetDateTime cutoff = OffsetDateTime.now().minusSeconds(90);
        return jdbc.sql("""
                SELECT t.terminal_code, t.branch_code, x.status, x.cassettes, x.card_stock, x.alarms, x.last_seen
                FROM terminals t LEFT JOIN terminal_telemetry x ON x.terminal_id = t.id
                ORDER BY t.terminal_code""")
                .query((rs, n) -> {
                    OffsetDateTime seen = rs.getObject("last_seen", OffsetDateTime.class);
                    return new TerminalView(rs.getString("terminal_code"), rs.getString("branch_code"),
                            rs.getString("status"),
                            parse(rs.getString("cassettes"), new TypeReference<Map<String, Integer>>() {}, Map.of()),
                            rs.getObject("card_stock", Integer.class),
                            parse(rs.getString("alarms"), new TypeReference<List<String>>() {}, List.of()),
                            seen, seen != null && seen.isAfter(cutoff));
                }).list();
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

    private String json(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
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
