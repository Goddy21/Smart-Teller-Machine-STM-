package com.stm.middleware.portal;

import com.stm.middleware.auth.PortalUser;
import com.stm.middleware.audit.AuditService;
import com.stm.middleware.common.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * Portal dashboards over the Gateway's data: overview figures, reference data (products, tariffs, FX rates, terms,
 * branches), the terminal registry, cash reconciliation, the device electronic journal, the SMS outbox and the
 * audit journal.
 */
@RestController
@RequestMapping("/api/v1/portal")
public class PortalInsightsController {
    private final Rows rows;
    private final JdbcClient jdbc;
    private final AuditService audit;

    public PortalInsightsController(Rows rows, JdbcClient jdbc, AuditService audit) {
        this.rows = rows;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ overview

    @GetMapping("/overview")
    public Map<String, Object> overview() {
        Map<String, Object> totals = rows.one("""
                SELECT
                  (SELECT count(*) FROM stm_sessions WHERE started_at >= date_trunc('day', now())) AS sessions_today,
                  (SELECT count(*) FROM stm_sessions WHERE status = 'ACTIVE') AS sessions_active,
                  (SELECT count(*) FROM applications WHERE status = 'ACCOUNT_OPENED') AS accounts_opened,
                  (SELECT count(*) FROM applications WHERE status = 'ACCOUNT_OPENED'
                     AND updated_at >= date_trunc('day', now())) AS accounts_opened_today,
                  (SELECT count(*) FROM applications WHERE referral_status = 'PENDING' AND status = 'REFERRED') AS referrals_waiting,
                  (SELECT count(*) FROM applications a WHERE a.status = 'IDENTITY_CAPTURED' AND a.biometric_attempts >= 3
                     AND a.expires_at > now()) AS identity_help_waiting,
                  (SELECT count(*) FROM card_orders WHERE status = 'DISPENSED' AND service <> 'NEW_CARD_SETUP') AS cards_issued,
                  (SELECT count(*) FROM card_orders WHERE status IN ('RETAINED', 'SPOILED')) AS cards_attention,
                  (SELECT count(*) FROM cash_deposits WHERE status = 'CREDITED') AS deposits,
                  (SELECT coalesce(sum(total - fee), 0) FROM cash_deposits WHERE status = 'CREDITED'
                     AND posted_at >= date_trunc('day', now())) AS deposits_today_kes,
                  (SELECT count(*) FROM cash_withdrawals WHERE status = 'COMPLETED') AS withdrawals,
                  (SELECT coalesce(sum(local_amount), 0) FROM cash_withdrawals WHERE status = 'COMPLETED'
                     AND created_at >= date_trunc('day', now())) AS withdrawals_today_kes,
                  (SELECT count(*) FROM fx_exchanges WHERE status IN ('CREDITED', 'COMPLETED'))
                    + (SELECT count(*) FROM cash_withdrawals WHERE source = 'FX_SELL' AND status = 'COMPLETED') AS fx_deals,
                  (SELECT count(*) FROM cash_withdrawals WHERE status = 'PENDING_RECONCILIATION') AS reversals_pending,
                  (SELECT count(*) FROM cash_withdrawals WHERE status = 'AUTHORISED' AND created_at < now() - interval '5 minutes')
                    + (SELECT count(*) FROM cash_deposits WHERE status = 'COUNTED' AND updated_at < now() - interval '10 minutes')
                    AS cash_stuck,
                  (SELECT count(*) FROM cash_positions WHERE NOT reconciled AND created_at > now() - interval '7 days') AS cash_variances,
                  (SELECT count(*) FROM terminals WHERE status = 'ACTIVE') AS terminals,
                  (SELECT count(*) FROM terminal_telemetry x JOIN terminals t ON t.id = x.terminal_id
                    WHERE t.status = 'ACTIVE' AND x.last_seen > now() - interval '180 seconds') AS terminals_online,
                  (SELECT count(*) FROM terminals WHERE status = 'ACTIVE' AND service_mode = 'LOCKED') AS terminals_locked,
                  (SELECT count(*) FROM teller_calls WHERE created_at >= date_trunc('day', now())) AS teller_calls_today,
                  (SELECT count(*) FROM sms_messages WHERE created_at >= date_trunc('day', now())) AS sms_today,
                  (SELECT count(*) FROM audit_journal) AS audit_entries""", Map.of()).orElseThrow();

        List<Map<String, Object>> days = rows.list("""
                SELECT to_char(d, 'YYYY-MM-DD') AS day,
                       count(s.id) FILTER (WHERE s.flow = 'ACCOUNT_OPENING') AS account_opening,
                       count(s.id) FILTER (WHERE s.flow = 'CARD_SERVICES') AS card_services,
                       count(s.id) FILTER (WHERE s.flow IN ('CASH_DEPOSIT', 'CASH_WITHDRAWAL')) AS cash,
                       count(s.id) FILTER (WHERE s.flow = 'FOREX') AS forex,
                       count(s.id) FILTER (WHERE s.flow = 'STATEMENT') AS statements,
                       count(s.id) FILTER (WHERE s.flow IS NULL) AS browse_only
                FROM generate_series(date_trunc('day', now()) - interval '13 days', date_trunc('day', now()), interval '1 day') AS d
                LEFT JOIN stm_sessions s ON s.started_at >= d AND s.started_at < d + interval '1 day'
                GROUP BY d ORDER BY d""");

        List<Map<String, Object>> byFlow = rows.list("""
                SELECT coalesce(flow, 'BROWSE_ONLY') AS key, count(*) AS count FROM stm_sessions GROUP BY 1 ORDER BY 2 DESC""");

        List<Map<String, Object>> funnel = rows.list("""
                SELECT step AS key, count FROM (
                  SELECT 'CONSENTED' AS step, 1 AS o, count(*) AS count FROM applications
                  UNION ALL SELECT 'IDENTITY_CAPTURED', 2, count(*) FROM applications WHERE verification_id IS NOT NULL
                  UNION ALL SELECT 'BIOMETRICS_VERIFIED', 3, count(*) FROM applications WHERE biometrics_passed
                  UNION ALL SELECT 'KYC_CAPTURED', 4, count(*) FROM applications WHERE kra_pin IS NOT NULL OR status = 'ACCOUNT_OPENED'
                  UNION ALL SELECT 'PHONE_VERIFIED', 5, count(*) FROM applications WHERE otp_verified
                  UNION ALL SELECT 'ACCOUNT_OPENED', 6, count(*) FROM applications WHERE status = 'ACCOUNT_OPENED'
                ) f ORDER BY o""");

        List<Map<String, Object>> outcomes = rows.list("""
                SELECT coalesce(end_reason, status) AS key, count(*) AS count FROM stm_sessions
                WHERE status <> 'ACTIVE' GROUP BY 1 ORDER BY 2 DESC""");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("totals", totals);
        out.put("last14Days", days);
        out.put("byFlow", byFlow);
        out.put("applicationFunnel", funnel);
        out.put("sessionOutcomes", outcomes);
        out.put("generatedAt", OffsetDateTime.now());
        return out;
    }

    // ------------------------------------------------------------------ reference data

    @GetMapping("/reference")
    public Map<String, Object> reference() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("products", rows.list("""
                SELECT p.code, p.category, p.name, p.name_sw, p.currency, p.account_type, p.min_opening_balance,
                       p.features AS features_json, p.card_products AS card_products_json, p.waive_card_fee, p.active,
                       (SELECT count(*) FROM applications a WHERE a.product_code = p.code AND a.status = 'ACCOUNT_OPENED') AS accounts_opened
                FROM products p ORDER BY p.sort_order"""));
        out.put("tariffs", rows.list("""
                SELECT t.code, t.label, t.fee, t.currency, t.includes_excise, t.version,
                       (SELECT json_agg(json_build_object('label', l.label, 'amount', l.amount) ORDER BY l.seq)::text
                        FROM tariff_lines l WHERE l.tariff_code = t.code) AS lines_json
                FROM tariffs t ORDER BY t.code"""));
        out.put("fxRates", rows.list("SELECT currency, symbol, buy, sell, updated_at FROM fx_rates ORDER BY currency"));
        out.put("terms", rows.list("""
                SELECT terms_id, language, version, product_code, active, audio_url,
                       json_array_length(sections::json) AS sections, consents AS consents_json
                FROM legal_terms ORDER BY terms_id, language"""));
        out.put("branches", rows.list("""
                SELECT b.code, b.name, (SELECT count(*) FROM terminals t WHERE t.branch_code = b.code) AS terminals
                FROM branches b ORDER BY b.code"""));
        return out;
    }

    public record RateUpdate(@NotNull @DecimalMin("0.0001") BigDecimal buy, @NotNull @DecimalMin("0.0001") BigDecimal sell) {}

    /** Manual rate entry until the treasury feed (spec open question 7) is connected. */
    @PutMapping("/fx-rates/{currency}")
    public Map<String, Object> setRate(@AuthenticationPrincipal PortalUser user, @PathVariable String currency,
                                       @Valid @RequestBody RateUpdate req) {
        if (req.buy().compareTo(req.sell()) > 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "RATE_INVALID", "The buy rate cannot be above the sell rate");
        }
        int n = jdbc.sql("UPDATE fx_rates SET buy = :b, sell = :s, updated_at = now() WHERE currency = :c")
                .param("b", req.buy()).param("s", req.sell()).param("c", currency.toUpperCase()).update();
        if (n == 0) throw new ApiException(HttpStatus.NOT_FOUND, "CURRENCY_NOT_FOUND", "Unknown currency " + currency);
        audit.record("FX_RATE_CHANGED", currency.toUpperCase(), null, Map.of("buy", req.buy().toPlainString(),
                "sell", req.sell().toPlainString(), "by", user == null ? "portal-key" : user.username()));
        return rows.one("SELECT currency, symbol, buy, sell, updated_at FROM fx_rates WHERE currency = :c",
                Map.of("c", currency.toUpperCase())).orElseThrow();
    }

    // ------------------------------------------------------------------ registry, reconciliation, journal, SMS

    @GetMapping("/terminals/registry")
    public List<Map<String, Object>> registry() {
        return rows.list("""
                SELECT t.terminal_code, t.branch_code, b.name AS branch_name, t.status, t.service_mode, t.software_version,
                       t.created_at, t.name, t.location, t.notes, t.token_rotated_at, t.retired_at,
                       (SELECT count(*) FROM stm_sessions s WHERE s.terminal_id = t.id AND s.started_at >= date_trunc('day', now())) AS sessions_today,
                       (SELECT count(*) FROM stm_sessions s WHERE s.terminal_id = t.id) AS sessions_total,
                       (SELECT max(d.issued_at) FROM device_tokens d WHERE d.terminal_id = t.id) AS last_sign_in
                FROM terminals t LEFT JOIN branches b ON b.code = t.branch_code ORDER BY t.terminal_code""");
    }

    @GetMapping("/reconciliation")
    public Map<String, Object> reconciliation() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("positions", rows.list("""
                SELECT p.id, t.terminal_code, p.event, p.cassettes AS cassettes_json, p.retract_bin, p.reject_bin,
                       p.counterfeit_bin, p.reconciled, p.variances AS variances_json, p.case_id, p.created_at
                FROM cash_positions p JOIN terminals t ON t.id = p.terminal_id ORDER BY p.created_at DESC LIMIT 200"""));
        // What the Gateway believes each kiosk holds now, per currency.
        out.put("cassettes", rows.list("""
                SELECT t.terminal_code, i.currency, sum(i.denomination * i.note_count) AS value, sum(i.note_count) AS notes,
                       json_agg(json_build_object('denomination', i.denomination, 'count', i.note_count)
                                ORDER BY i.denomination DESC)::text AS notes_by_denomination_json
                FROM cassette_inventory i JOIN terminals t ON t.id = i.terminal_id
                GROUP BY t.terminal_code, i.currency ORDER BY t.terminal_code, i.currency"""));
        out.put("pendingReversals", rows.list("""
                SELECT w.id, t.terminal_code, w.source, w.currency, w.amount, w.local_amount, w.transaction_ref,
                       w.reversal_ref, w.reverse_reason, w.credit_back_by, w.created_at
                FROM cash_withdrawals w JOIN terminals t ON t.id = w.terminal_id
                WHERE w.status = 'PENDING_RECONCILIATION' ORDER BY w.created_at DESC"""));
        return out;
    }

    /** Newest first; ref matches part of a reference. ?page= gives a page, as for /sessions. */
    @GetMapping("/device-journal")
    public Object deviceJournal(@RequestParam(required = false) String terminal,
                                @RequestParam(required = false) String ref,
                                @RequestParam(required = false) Integer page,
                                @RequestParam(defaultValue = "50") int size,
                                @RequestParam(defaultValue = "200") int limit) {
        StringBuilder from = new StringBuilder(" FROM device_journal j JOIN terminals t ON t.id = j.terminal_id WHERE 1 = 1");
        Map<String, Object> p = new HashMap<>();
        if (terminal != null && !terminal.isBlank()) { from.append(" AND t.terminal_code = :t"); p.put("t", terminal); }
        if (ref != null && !ref.isBlank()) { from.append(" AND j.ref ILIKE :r"); p.put("r", "%" + ref.trim() + "%"); }
        String select = "SELECT t.terminal_code, j.seq, j.at, j.event, j.ref, j.payload AS data_json, j.signature_valid, j.received_at"
                + from + " ORDER BY j.at DESC, j.terminal_id, j.seq DESC";
        if (page == null) {
            p.put("l", Math.max(1, Math.min(limit, 1000)));
            return rows.list(select + " LIMIT :l", p);
        }
        return rows.page("SELECT count(*) " + from, select, p, page, size);
    }

    /** Newest first. ?page= gives a page, as for /sessions. */
    @GetMapping("/sms")
    public Object sms(@RequestParam(required = false) Integer page,
                      @RequestParam(defaultValue = "50") int size,
                      @RequestParam(defaultValue = "100") int limit) {
        String select = """
                SELECT id, session_id, template, regexp_replace(mobile, '^(\\+2547)\\d{5}', '\\1•••••') AS mobile,
                       language, body, status, created_at
                FROM sms_messages ORDER BY created_at DESC, id DESC""";
        if (page == null) return rows.list(select + " LIMIT :l", Map.of("l", Math.max(1, Math.min(limit, 500))));
        return rows.page("SELECT count(*) FROM sms_messages", select, Map.of(), page, size);
    }

    // ------------------------------------------------------------------ audit journal

    /** Newest first. Filter by entity (a session id, terminal code, currency ...) or event type; page with ?beforeSeq=. */
    @GetMapping("/audit/journal")
    public List<Map<String, Object>> journal(@RequestParam(required = false) String entityId,
                                             @RequestParam(required = false) String eventType,
                                             @RequestParam(required = false) Long beforeSeq,
                                             @RequestParam(defaultValue = "100") int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT a.seq, a.event_type, a.entity_id, t.terminal_code, a.payload, a.prev_hash, a.hash, a.created_at
                FROM audit_journal a LEFT JOIN terminals t ON t.id = a.terminal_id WHERE 1 = 1""");
        Map<String, Object> p = new HashMap<>();
        if (entityId != null && !entityId.isBlank()) { sql.append(" AND a.entity_id = :e"); p.put("e", entityId.trim()); }
        if (eventType != null && !eventType.isBlank()) { sql.append(" AND a.event_type = :t"); p.put("t", eventType.trim()); }
        if (beforeSeq != null) { sql.append(" AND a.seq < :b"); p.put("b", beforeSeq); }
        sql.append(" ORDER BY a.seq DESC LIMIT :l");
        p.put("l", Math.max(1, Math.min(limit, 500)));
        return rows.list(sql.toString(), p);
    }

    @GetMapping("/audit/event-types")
    public List<String> eventTypes() {
        return jdbc.sql("SELECT DISTINCT event_type FROM audit_journal ORDER BY event_type").query(String.class).list();
    }
}
