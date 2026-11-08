package com.stm.middleware.stm.device;

import com.stm.middleware.backend.CoreBanking;
import com.stm.middleware.stm.cash.Cassettes;
import com.stm.middleware.stm.core.*;
import com.stm.middleware.telemetry.TelemetryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * Reconciliation and audit from the device agent: the electronic journal (A-1) and cassette counts (A-2).
 * Back office uses both to settle disputes and to balance the kiosk's cash GL in core.
 */
@RestController
@RequestMapping("/stm/v1/devices/{deviceId}")
public class JournalController {
    private final JdbcClient jdbc;
    private final Json json;
    private final Cassettes cassettes;
    private final CoreBanking core;
    private final StmAudit audit;
    private final TelemetryService telemetry;

    public JournalController(JdbcClient jdbc, Json json, Cassettes cassettes, CoreBanking core, StmAudit audit,
                             TelemetryService telemetry) {
        this.jdbc = jdbc;
        this.json = json;
        this.cassettes = cassettes;
        this.core = core;
        this.audit = audit;
        this.telemetry = telemetry;
    }

    // ------------------------------------------------------------------ A-1

    public record Entry(@NotNull Long seq, @NotNull OffsetDateTime at, @NotBlank @Size(max = 40) String event,
                        @Size(max = 60) String ref, Map<String, Object> data) {}

    public record JournalUpload(@NotNull Long fromSeq, @NotNull Long toSeq, @NotEmpty List<@Valid Entry> entries,
                                String signature) {}

    /**
     * Idempotent: an entry already received is kept as it was. The signature is HMAC-SHA256 over the entries'
     * "seq|at|event|ref" lines joined by \n, keyed with the hex SHA-256 of the kiosk's provisioning token.
     */
    @PostMapping("/journal")
    @SessionOptional
    @Transactional
    public Map<String, Object> journal(Ctx ctx, @PathVariable String deviceId, @Valid @RequestBody JournalUpload req) {
        DeviceController.sameDevice(ctx, deviceId);
        boolean valid = signatureValid(ctx.terminalId(), req);
        long accepted = req.fromSeq() - 1;
        List<Entry> sorted = new ArrayList<>(req.entries());
        sorted.sort(Comparator.comparing(Entry::seq));
        for (Entry e : sorted) {
            jdbc.sql("""
                    INSERT INTO device_journal (terminal_id, seq, at, event, ref, payload, signature_valid)
                    VALUES (:t, :s, :a, :e, :r, :p, :v) ON CONFLICT (terminal_id, seq) DO NOTHING""")
                    .param("t", ctx.terminalId()).param("s", e.seq()).param("a", e.at()).param("e", e.event())
                    .param("r", e.ref()).param("p", e.data() == null ? null : json.write(e.data())).param("v", valid).update();
            if (e.seq() == accepted + 1) accepted = e.seq();
        }
        if (!valid) audit.device(ctx.device(), "JOURNAL_SIGNATURE_UNVERIFIED", "fromSeq", req.fromSeq(), "toSeq", req.toSeq());
        return Map.of("acceptedToSeq", accepted, "signatureValid", valid);
    }

    private boolean signatureValid(long terminalId, JournalUpload req) {
        if (req.signature() == null || req.signature().isBlank()) return false;
        String key = jdbc.sql("SELECT token_hash FROM terminals WHERE id = :t").param("t", terminalId)
                .query(String.class).single();
        StringBuilder data = new StringBuilder();
        for (Entry e : req.entries()) {
            if (!data.isEmpty()) data.append('\n');
            data.append(e.seq()).append('|').append(e.at().toInstant()).append('|').append(e.event()).append('|')
                    .append(e.ref() == null ? "" : e.ref());
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String expected = HexFormat.of().formatHex(mac.doFinal(data.toString().getBytes(StandardCharsets.UTF_8)));
            return expected.equalsIgnoreCase(req.signature().trim());
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ A-2

    public record Cassette(@Size(max = 10) String id, @NotBlank String currency, @NotBlank String denomination,
                           @NotNull Integer count) {}

    public record Positions(@NotBlank @Size(max = 30) String event, @NotEmpty List<@Valid Cassette> cassettes,
                            Integer retractBin, Integer rejectBin, Integer counterfeitBin) {}

    /**
     * Compares what the cassettes hold with the kiosk's cash GL in core, per currency. A difference opens a case.
     * The device's count then becomes the Gateway's view of the cassettes.
     */
    @PostMapping("/cash-positions")
    @SessionOptional
    @Transactional
    public Map<String, Object> positions(Ctx ctx, @PathVariable String deviceId, @Valid @RequestBody Positions req) {
        DeviceController.sameDevice(ctx, deviceId);
        cassettes.ensureSeeded(ctx.terminalId(), ctx.deviceId());
        Map<String, BigDecimal> counted = new TreeMap<>();
        Map<String, Map<Integer, Integer>> byCurrency = new TreeMap<>();
        for (Cassette c : req.cassettes()) {
            int denom = new BigDecimal(c.denomination()).intValueExact();
            counted.merge(c.currency(), BigDecimal.valueOf((long) denom * c.count()), BigDecimal::add);
            byCurrency.computeIfAbsent(c.currency(), k -> new TreeMap<>()).merge(denom, c.count(), Integer::sum);
        }
        List<Map<String, Object>> variances = new ArrayList<>();
        boolean reconciled = true;
        for (var e : counted.entrySet()) {
            BigDecimal expected = core.glBalance(Cassettes.cashGl(e.getKey(), ctx.deviceId()), e.getKey());
            BigDecimal diff = e.getValue().subtract(expected);
            if (diff.signum() != 0) reconciled = false;
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("currency", e.getKey());
            v.put("expected", expected.setScale(2).toPlainString());
            v.put("counted", e.getValue().setScale(2).toPlainString());
            v.put("difference", diff.setScale(2).toPlainString());
            variances.add(v);
        }
        String caseId = null;
        if (!reconciled) {
            caseId = "REC-" + jdbc.sql("SELECT nextval('stm_ref_seq')").query(Long.class).single();
            audit.device(ctx.device(), "CASH_VARIANCE", "caseId", caseId, "event", req.event(), "variances", variances);
        }
        // The physical count wins: the Gateway's cassette view follows the device from now on.
        byCurrency.forEach((cur, notes) -> {
            jdbc.sql("UPDATE cassette_inventory SET note_count = 0 WHERE terminal_id = :t AND currency = :c")
                    .param("t", ctx.terminalId()).param("c", cur).update();
            notes.forEach((d, n) -> cassettes.set(ctx.terminalId(), cur, d, n));
        });
        jdbc.sql("""
                INSERT INTO cash_positions (terminal_id, event, cassettes, retract_bin, reject_bin, counterfeit_bin,
                                            reconciled, variances, case_id)
                VALUES (:t, :e, :c, :rb, :jb, :cb, :r, :v, :case)""")
                .param("t", ctx.terminalId()).param("e", req.event()).param("c", json.write(req.cassettes()))
                .param("rb", Optional.ofNullable(req.retractBin()).orElse(0))
                .param("jb", Optional.ofNullable(req.rejectBin()).orElse(0))
                .param("cb", Optional.ofNullable(req.counterfeitBin()).orElse(0))
                .param("r", reconciled).param("v", json.write(variances)).param("case", caseId).update();
        telemetry.changed(ctx.deviceId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reconciled", reconciled);
        out.put("variances", variances);
        out.put("caseId", caseId);
        return out;
    }
}
