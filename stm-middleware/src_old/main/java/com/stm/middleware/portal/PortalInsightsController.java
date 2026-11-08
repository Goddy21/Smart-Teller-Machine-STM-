package com.stm.middleware.portal;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read-only views for the STM web portal: dashboard figures, the audit journal, reference data,
 * the terminal registry and the escrow ledger. Protected by X-Portal-Key like the rest of /api/v1/portal.
 */
@RestController
@RequestMapping("/api/v1/portal")
public class PortalInsightsController {
    private final JdbcClient jdbc;

    public PortalInsightsController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ overview

    public record Totals(long sessionsToday, long sessionsActive, long escalatedWaiting, long reviewsWaiting,
                         long accountsOpened, long cardsIssued, long cardsToCollect, long terminals,
                         long terminalsOnline, long terminalsLocked, long deposits, BigDecimal depositsCommitted,
                         long depositsNeedingAttention, long auditEntries) {}

    public record DayCount(String day, long openAccount, long debitCard, long completed) {}

    public record Count(String key, long count) {}

    public record Overview(Totals totals, List<DayCount> last14Days, List<Count> byStatus, List<Count> openAccountSteps,
                           OffsetDateTime generatedAt) {}

    @GetMapping("/overview")
    public Overview overview() {
        Totals totals = jdbc.sql("""
                SELECT
                  (SELECT count(*) FROM kiosk_sessions WHERE created_at >= date_trunc('day', now())) AS sessions_today,
                  (SELECT count(*) FROM kiosk_sessions WHERE status = 'IN_PROGRESS') AS sessions_active,
                  (SELECT count(*) FROM kiosk_sessions WHERE status = 'IN_PROGRESS' AND escalated) AS escalated_waiting,
                  (SELECT count(*) FROM kiosk_sessions WHERE status = 'IN_PROGRESS' AND review_required
                                                         AND review_status <> 'APPROVED') AS reviews_waiting,
                  (SELECT count(*) FROM kiosk_sessions WHERE account_no IS NOT NULL AND journey = 'OPEN_ACCOUNT') AS accounts_opened,
                  (SELECT count(*) FROM kiosk_sessions WHERE card_status = 'ISSUED') AS cards_issued,
                  (SELECT count(*) FROM card_fulfilments WHERE status = 'PENDING_COLLECTION') AS cards_to_collect,
                  (SELECT count(*) FROM terminals WHERE status = 'ACTIVE') AS terminals,
                  (SELECT count(*) FROM terminal_telemetry x JOIN terminals t ON t.id = x.terminal_id
                    WHERE t.status = 'ACTIVE' AND x.last_seen > now() - interval '90 seconds') AS terminals_online,
                  (SELECT count(*) FROM terminals WHERE status = 'ACTIVE' AND service_mode = 'LOCKED') AS terminals_locked,
                  (SELECT count(*) FROM transactions) AS deposits,
                  (SELECT coalesce(sum(amount), 0) FROM transactions WHERE state = 'COMMITTED') AS deposits_committed,
                  (SELECT count(*) FROM transactions WHERE state IN ('UNKNOWN_OUTCOME', 'COMPENSATING', 'ROLLED_BACK'))
                    AS deposits_attention,
                  (SELECT count(*) FROM audit_journal) AS audit_entries""")
                .query((rs, n) -> new Totals(rs.getLong("sessions_today"), rs.getLong("sessions_active"),
                        rs.getLong("escalated_waiting"), rs.getLong("reviews_waiting"), rs.getLong("accounts_opened"),
                        rs.getLong("cards_issued"), rs.getLong("cards_to_collect"), rs.getLong("terminals"),
                        rs.getLong("terminals_online"), rs.getLong("terminals_locked"), rs.getLong("deposits"),
                        rs.getBigDecimal("deposits_committed"), rs.getLong("deposits_attention"),
                        rs.getLong("audit_entries")))
                .single();

        List<DayCount> days = jdbc.sql("""
                SELECT to_char(d, 'YYYY-MM-DD') AS day,
                       count(s.id) FILTER (WHERE s.journey = 'OPEN_ACCOUNT') AS open_account,
                       count(s.id) FILTER (WHERE s.journey = 'DEBIT_CARD')   AS debit_card,
                       count(s.id) FILTER (WHERE s.status = 'COMPLETED')     AS completed
                FROM generate_series(date_trunc('day', now()) - interval '13 days', date_trunc('day', now()),
                                     interval '1 day') AS d
                LEFT JOIN kiosk_sessions s ON s.created_at >= d AND s.created_at < d + interval '1 day'
                GROUP BY d ORDER BY d""")
                .query((rs, n) -> new DayCount(rs.getString("day"), rs.getLong("open_account"),
                        rs.getLong("debit_card"), rs.getLong("completed")))
                .list();

        List<Count> byStatus = jdbc.sql("SELECT status AS k, count(*) AS c FROM kiosk_sessions GROUP BY status ORDER BY c DESC")
                .query((rs, n) -> new Count(rs.getString("k"), rs.getLong("c"))).list();

        List<Count> steps = jdbc.sql("""
                SELECT step AS k, count(*) AS c FROM kiosk_sessions
                WHERE journey = 'OPEN_ACCOUNT' GROUP BY step""")
                .query((rs, n) -> new Count(rs.getString("k"), rs.getLong("c"))).list();

        return new Overview(totals, days, byStatus, steps, OffsetDateTime.now());
    }

    // ------------------------------------------------------------------ audit journal

    public record JournalEntry(long seq, String eventType, String entityId, String terminalCode, String payload,
                               String prevHash, String hash, OffsetDateTime createdAt) {}

    /** Newest first. Filter by entity (a session or transaction id) or event type; page with ?beforeSeq=. */
    @GetMapping("/audit/journal")
    public List<JournalEntry> journal(@RequestParam(required = false) String entityId,
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
        return jdbc.sql(sql.toString()).params(p)
                .query((rs, n) -> new JournalEntry(rs.getLong("seq"), rs.getString("event_type"),
                        rs.getString("entity_id"), rs.getString("terminal_code"), rs.getString("payload"),
                        rs.getString("prev_hash"), rs.getString("hash"),
                        rs.getObject("created_at", OffsetDateTime.class)))
                .list();
    }

    /** One session's journal entries, oldest first. Open to every portal role (the full journal is for audit roles). */
    @GetMapping("/sessions/{id}/timeline")
    public List<JournalEntry> timeline(@PathVariable UUID id) {
        return jdbc.sql("""
                SELECT a.seq, a.event_type, a.entity_id, t.terminal_code, a.payload, a.prev_hash, a.hash, a.created_at
                FROM audit_journal a LEFT JOIN terminals t ON t.id = a.terminal_id
                WHERE a.entity_id = :e ORDER BY a.seq LIMIT 200""")
                .param("e", id.toString())
                .query((rs, n) -> new JournalEntry(rs.getLong("seq"), rs.getString("event_type"),
                        rs.getString("entity_id"), rs.getString("terminal_code"), rs.getString("payload"),
                        rs.getString("prev_hash"), rs.getString("hash"),
                        rs.getObject("created_at", OffsetDateTime.class)))
                .list();
    }

    @GetMapping("/audit/event-types")
    public List<String> eventTypes() {
        return jdbc.sql("SELECT DISTINCT event_type FROM audit_journal ORDER BY event_type").query(String.class).list();
    }

    // ------------------------------------------------------------------ reference data and registry

    public record ProductRow(String code, String name, String currency, BigDecimal interestRate,
                             BigDecimal minOpeningBalance, BigDecimal monthlyFee, boolean overdraft, boolean active,
                             long accountsOpened) {}

    public record BranchRow(String code, String name, long terminals) {}

    public record Reference(List<ProductRow> products, List<BranchRow> branches) {}

    @GetMapping("/reference")
    public Reference reference() {
        List<ProductRow> products = jdbc.sql("""
                SELECT p.*, (SELECT count(*) FROM kiosk_sessions s
                             WHERE s.product_code = p.code AND s.account_no IS NOT NULL) AS opened
                FROM account_products p ORDER BY p.code""")
                .query((rs, n) -> new ProductRow(rs.getString("code"), rs.getString("name"), rs.getString("currency"),
                        rs.getBigDecimal("interest_rate"), rs.getBigDecimal("min_opening_balance"),
                        rs.getBigDecimal("monthly_fee"), rs.getBoolean("overdraft"), rs.getBoolean("active"),
                        rs.getLong("opened")))
                .list();
        List<BranchRow> branches = jdbc.sql("""
                SELECT b.code, b.name, (SELECT count(*) FROM terminals t WHERE t.branch_code = b.code) AS terminals
                FROM branches b ORDER BY b.code""")
                .query((rs, n) -> new BranchRow(rs.getString("code"), rs.getString("name"), rs.getLong("terminals")))
                .list();
        return new Reference(products, branches);
    }

    public record TerminalRow(String terminalCode, String branchCode, String branchName, String status,
                              String serviceMode, OffsetDateTime createdAt, long sessionsToday, long sessionsTotal) {}

    /** Every registered kiosk, including ones that never sent telemetry. */
    @GetMapping("/terminals/registry")
    public List<TerminalRow> registry() {
        return jdbc.sql("""
                SELECT t.terminal_code, t.branch_code, b.name AS branch_name, t.status, t.service_mode, t.created_at,
                       (SELECT count(*) FROM kiosk_sessions s WHERE s.terminal_id = t.id
                          AND s.created_at >= date_trunc('day', now())) AS today,
                       (SELECT count(*) FROM kiosk_sessions s WHERE s.terminal_id = t.id) AS total
                FROM terminals t LEFT JOIN branches b ON b.code = t.branch_code
                ORDER BY t.terminal_code""")
                .query((rs, n) -> new TerminalRow(rs.getString("terminal_code"), rs.getString("branch_code"),
                        rs.getString("branch_name"), rs.getString("status"), rs.getString("service_mode"),
                        rs.getObject("created_at", OffsetDateTime.class), rs.getLong("today"), rs.getLong("total")))
                .list();
    }

    // ------------------------------------------------------------------ deposits

    public record LedgerRow(long seq, String entryType, BigDecimal amount, String currency, OffsetDateTime createdAt) {}

    @GetMapping("/transactions/{id}/ledger")
    public List<LedgerRow> ledger(@PathVariable UUID id) {
        return jdbc.sql("""
                SELECT seq, entry_type, amount, currency, created_at FROM escrow_ledger
                WHERE transaction_id = :id ORDER BY seq""")
                .param("id", id)
                .query((rs, n) -> new LedgerRow(rs.getLong("seq"), rs.getString("entry_type"),
                        rs.getBigDecimal("amount"), rs.getString("currency"),
                        rs.getObject("created_at", OffsetDateTime.class)))
                .list();
    }
}
