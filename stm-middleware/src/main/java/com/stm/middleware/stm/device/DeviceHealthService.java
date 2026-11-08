package com.stm.middleware.stm.device;

import com.stm.middleware.stm.core.Errors;
import com.stm.middleware.stm.core.Json;
import com.stm.middleware.telemetry.TelemetryService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * D-6 health reports and the service switches they drive (D-2 "services", D-7 status chips).
 * A module is healthy when its status is OK (or a warning such as PAPER_LOW / LOW); anything else switches off
 * the services that need it. A remote LOCK from the portal switches everything off.
 */
@Service
public class DeviceHealthService {
    public static final List<String> SERVICES =
            List.of("accountOpening", "cardServices", "cashDeposit", "cashWithdrawal", "forex", "statements");

    private static final Set<String> WARNINGS = Set.of("OK", "PAPER_LOW", "LOW", "WARNING", "READY");

    /** Which services each module is needed by. */
    private static final Map<String, List<String>> NEEDS = Map.of(
            "cardReader", List.of("cardServices", "statements", "cashWithdrawal"),
            "cardPrinter", List.of("cardServices", "accountOpening"),
            "cashRecycler", List.of("cashDeposit", "cashWithdrawal", "forex"),
            "idScanner", List.of("accountOpening", "forex"),
            "fingerprint", List.of("accountOpening", "cardServices"),
            "camera", List.of("accountOpening"),
            "pinPad", List.of("accountOpening", "cardServices", "statements", "cashWithdrawal"));

    public record Status(boolean online, String cashRecycler, boolean allServicesAvailable, String message) {}

    private record Row(String serviceMode, String modules, String disabledServices, OffsetDateTime reportedAt) {}

    private final JdbcClient jdbc;
    private final Json json;
    private final TelemetryService telemetry;

    public DeviceHealthService(JdbcClient jdbc, Json json, TelemetryService telemetry) {
        this.jdbc = jdbc;
        this.json = json;
        this.telemetry = telemetry;
    }

    /** Stores the report; returns the services now switched off. */
    public List<String> report(long terminalId, String deviceId, Map<String, Object> modules, OffsetDateTime reportedAt) {
        Set<String> disabled = new TreeSet<>();
        List<String> alarms = new ArrayList<>();
        Integer cardStock = null;
        for (var e : modules.entrySet()) {
            String status = statusOf(e.getValue());
            if (e.getKey().equals("cardPrinter") && e.getValue() instanceof Map<?, ?> m && m.get("blankStock") instanceof Number n) {
                cardStock = n.intValue();
                if (cardStock == 0) {
                    disabled.addAll(NEEDS.get("cardPrinter"));
                    alarms.add("CARD_STOCK_EMPTY");
                }
            }
            if (!WARNINGS.contains(status)) {
                disabled.addAll(NEEDS.getOrDefault(e.getKey(), List.of()));
                alarms.add(e.getKey() + ":" + status);
            } else if (!status.equals("OK") && !status.equals("READY")) {
                alarms.add(e.getKey() + ":" + status);
            }
        }
        String overall = disabled.isEmpty() ? (alarms.isEmpty() ? "OK" : "WARNING") : "DEGRADED";
        String mods = json.write(modules);
        String dis = json.write(disabled);
        String al = json.write(alarms);
        OffsetDateTime at = reportedAt != null ? reportedAt : OffsetDateTime.now();
        int n = jdbc.sql("""
                UPDATE terminal_telemetry SET status = :s, modules = :m, disabled_services = :d, alarms = :a,
                       card_stock = coalesce(:cs, card_stock), reported_at = :r, last_seen = now()
                WHERE terminal_id = :t""")
                .param("s", overall).param("m", mods).param("d", dis).param("a", al).param("cs", cardStock, java.sql.Types.INTEGER)
                .param("r", at).param("t", terminalId).update();
        if (n == 0) {
            try {
                jdbc.sql("""
                        INSERT INTO terminal_telemetry (terminal_id, status, modules, disabled_services, alarms, card_stock, reported_at)
                        VALUES (:t, :s, :m, :d, :a, :cs, :r)""")
                        .param("t", terminalId).param("s", overall).param("m", mods).param("d", dis).param("a", al)
                        .param("cs", cardStock, java.sql.Types.INTEGER).param("r", at).update();
            } catch (DuplicateKeyException race) {
                return report(terminalId, deviceId, modules, reportedAt);
            }
        }
        telemetry.changed(deviceId);
        return new ArrayList<>(disabled);
    }

    /** The six service switches for D-2 / the A1 tiles. */
    public Map<String, Boolean> services(long terminalId) {
        Row r = row(terminalId);
        Map<String, Boolean> out = new LinkedHashMap<>();
        boolean locked = "LOCKED".equals(r.serviceMode());
        Set<String> disabled = disabled(r);
        for (String s : SERVICES) out.put(s, !locked && !disabled.contains(s));
        return out;
    }

    public void requireService(long terminalId, String service) {
        Row r = row(terminalId);
        if ("LOCKED".equals(r.serviceMode())) throw outOfService();
        if (disabled(r).contains(service)) throw Errors.serviceUnavailable(service);
    }

    public void requireInService(long terminalId) {
        if ("LOCKED".equals(row(terminalId).serviceMode())) throw outOfService();
    }

    public Status status(long terminalId) {
        Row r = row(terminalId);
        boolean locked = "LOCKED".equals(r.serviceMode());
        Map<String, Object> mods = r.modules() == null ? Map.of() : json.read(r.modules(), Json.MAP);
        Object cash = mods.get("cashRecycler");
        String recycler = cash == null ? "UNKNOWN" : WARNINGS.contains(statusOf(cash)) ? "READY" : "FAULT";
        Set<String> disabled = disabled(r);
        boolean all = !locked && disabled.isEmpty();
        String message = locked ? "This machine is temporarily out of service."
                : disabled.isEmpty() ? null
                : "Some services are unavailable on this machine: " + String.join(", ", disabled) + ".";
        return new Status(!locked, recycler, all, message);
    }

    private Set<String> disabled(Row r) {
        // A report older than 10 minutes says nothing reliable about the hardware: do not switch services off on it.
        if (r.disabledServices() == null || r.reportedAt() == null
                || r.reportedAt().isBefore(OffsetDateTime.now().minusMinutes(10))) return Set.of();
        return new TreeSet<>(json.read(r.disabledServices(), Json.STRINGS));
    }

    private Row row(long terminalId) {
        return jdbc.sql("""
                SELECT t.service_mode, x.modules, x.disabled_services, x.reported_at
                FROM terminals t LEFT JOIN terminal_telemetry x ON x.terminal_id = t.id WHERE t.id = :t""")
                .param("t", terminalId)
                .query((rs, n) -> new Row(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getObject(4, OffsetDateTime.class)))
                .single();
    }

    private static String statusOf(Object v) {
        if (v instanceof Map<?, ?> m) return String.valueOf(m.get("status")).toUpperCase();
        return String.valueOf(v).toUpperCase();
    }

    private static com.stm.middleware.stm.core.StmException outOfService() {
        return Errors.of(HttpStatus.SERVICE_UNAVAILABLE, "OUT_OF_SERVICE", "This machine is temporarily out of service.",
                "Mashine hii haifanyi kazi kwa muda.", true);
    }
}
