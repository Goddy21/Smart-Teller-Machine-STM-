package com.stm.middleware.journey;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stm.middleware.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Drives the kiosk "Temporarily out of service" screen and gates new sessions.
 * Inputs: remote LOCK/UNLOCK (terminals.service_mode), the kiosk's own heartbeat status, alarms and card stock.
 */
@Service
public class KioskStatusService {
    private static final Set<String> IDENTITY_BLOCKERS =
            Set.of("SCANNER_FAULT", "BIOMETRIC_FAULT", "CAMERA_FAULT", "SIGNATURE_PAD_FAULT");
    private static final Set<String> CARD_BLOCKERS = Set.of("CARD_DISPENSER_FAULT", "CARD_STOCK_EMPTY");
    private static final Set<String> DOWN_STATES = Set.of("OUT_OF_SERVICE", "MAINTENANCE");

    public record Status(boolean inService, boolean accountOpeningAvailable, boolean cardIssuanceAvailable,
                         String reason, String branchName, String helpline) {}

    public record BranchInfo(String code, String name) {}

    private record Row(String serviceMode, String branchCode, String branchName, String telemetryStatus,
                       String alarms, Integer cardStock) {}

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final String helpline;

    public KioskStatusService(JdbcClient jdbc, ObjectMapper mapper, @Value("${stm.helpline:}") String helpline) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.helpline = helpline;
    }

    public Status status(long terminalId) {
        Row r = jdbc.sql("""
                SELECT t.service_mode, t.branch_code, COALESCE(b.name, t.branch_code) AS branch_name,
                       x.status AS tstatus, x.alarms, x.card_stock
                FROM terminals t
                LEFT JOIN branches b ON b.code = t.branch_code
                LEFT JOIN terminal_telemetry x ON x.terminal_id = t.id
                WHERE t.id = :id""")
                .param("id", terminalId)
                .query((rs, n) -> new Row(rs.getString("service_mode"), rs.getString("branch_code"),
                        rs.getString("branch_name"), rs.getString("tstatus"), rs.getString("alarms"),
                        rs.getObject("card_stock", Integer.class)))
                .single();
        Set<String> alarms = parseAlarms(r.alarms());

        String reason = null;
        if ("LOCKED".equals(r.serviceMode())) reason = "LOCKED";
        else if (r.telemetryStatus() != null && DOWN_STATES.contains(r.telemetryStatus())) reason = r.telemetryStatus();
        else if (alarms.contains("OUT_OF_SERVICE")) reason = "OUT_OF_SERVICE";
        else if (alarms.stream().anyMatch(IDENTITY_BLOCKERS::contains)) reason = "IDENTITY_HARDWARE_FAULT";

        boolean identityOk = reason == null;
        boolean cardOk = identityOk && alarms.stream().noneMatch(CARD_BLOCKERS::contains)
                && (r.cardStock() == null || r.cardStock() > 0);
        return new Status(identityOk, identityOk, cardOk, reason, r.branchName(), helpline);
    }

    public BranchInfo branch(long terminalId) {
        return jdbc.sql("""
                SELECT t.branch_code, COALESCE(b.name, t.branch_code) AS name
                FROM terminals t LEFT JOIN branches b ON b.code = t.branch_code WHERE t.id = :id""")
                .param("id", terminalId)
                .query((rs, n) -> new BranchInfo(rs.getString("branch_code"), rs.getString("name")))
                .single();
    }

    public void assertAvailable(long terminalId, JourneyType journey) {
        Status s = status(terminalId);
        if (!s.inService()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "OUT_OF_SERVICE",
                    "This machine is temporarily out of service (" + s.reason() + ")");
        }
        if (journey == JourneyType.DEBIT_CARD && !s.cardIssuanceAvailable()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "CARD_ISSUANCE_UNAVAILABLE",
                    "Card issuance is not available on this machine right now");
        }
    }

    public void assertCardAvailable(long terminalId) {
        if (!status(terminalId).cardIssuanceAvailable()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "CARD_ISSUANCE_UNAVAILABLE",
                    "Card issuance is not available on this machine right now");
        }
    }

    private Set<String> parseAlarms(String json) {
        if (json == null) return Set.of();
        try {
            List<String> list = mapper.readValue(json, new TypeReference<List<String>>() {});
            return new HashSet<>(list);
        } catch (JsonProcessingException e) {
            return Set.of();
        }
    }
}
