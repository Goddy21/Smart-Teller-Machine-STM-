package com.stm.middleware.portal;

import org.springframework.web.bind.annotation.*;

import java.util.*;

/** Money at the kiosks for the portal: deposits, withdrawals, forex deals and card orders, with their core postings. */
@RestController
@RequestMapping("/api/v1/portal")
public class PortalCashController {
    private final Rows rows;

    public PortalCashController(Rows rows) {
        this.rows = rows;
    }

    /** Account number when the core knows the account, else the id as stored. */
    private static String acct(String col) {
        return "(SELECT coalesce(a.account_number, " + col + ") FROM (SELECT 1) one LEFT JOIN sim_accounts a ON a.id = " + col + ")";
    }

    /**
     * Every customer transaction at the kiosks in one row shape (like a CDM/ATM journal): cash deposits, withdrawals,
     * forex, card orders, statements, account openings and customer services. cash_flow is IN, OUT or NONE.
     */
    private static final String ALL_TX = """
                (
                  SELECT d.id, 'DEPOSIT' AS kind, t.terminal_code, d.session_id, d.status, d.currency,
                         coalesce(d.total, 0) AS amount, coalesce(d.total, 0) - d.fee AS local_amount, d.fee,
                         d.target_label AS party, 'CASH' AS from_account,
                         coalesce(%1$s, dt.paybill || coalesce(' / ' || dt.account_reference, ''), d.target_ref) AS to_account,
                         s.customer_id, coalesce(s.customer_name, dt.account_name) AS customer_name,
                         'Cash deposit to ' || d.target_label AS narration, 'IN' AS cash_flow, d.new_balance AS balance_after,
                         d.transaction_ref, d.core_ref, NULL AS reversal_ref, d.rejected_count AS notes_returned,
                         d.suspected_counterfeit, d.created_at, d.updated_at
                  FROM cash_deposits d JOIN terminals t ON t.id = d.terminal_id JOIN stm_sessions s ON s.id = d.session_id
                       LEFT JOIN deposit_targets dt ON dt.ref = d.target_ref
                  UNION ALL
                  SELECT w.id, CASE WHEN w.source = 'ACCOUNT' THEN 'WITHDRAWAL' ELSE 'FX' END, t.terminal_code, w.session_id,
                         w.status, w.currency, w.amount, w.local_amount, 0,
                         CASE w.source WHEN 'FX_SELL' THEN 'Bank sells ' || w.currency WHEN 'FX_KES_OUT' THEN 'KES paid out for forex'
                                       ELSE 'Account ' || coalesce(w.account_id, '') END,
                         CASE WHEN w.source = 'ACCOUNT' THEN %2$s ELSE 'FX' END, 'CASH',
                         coalesce(w.customer_id, s.customer_id), s.customer_name,
                         CASE w.source WHEN 'ACCOUNT' THEN 'Cash withdrawal' WHEN 'FX_SELL' THEN 'Forex sold to customer' ELSE 'KES paid out for forex' END,
                         'OUT', w.balance_after,
                         w.transaction_ref, w.core_ref, w.reversal_ref, NULL, NULL, w.created_at, w.updated_at
                  FROM cash_withdrawals w JOIN terminals t ON t.id = w.terminal_id JOIN stm_sessions s ON s.id = w.session_id
                  UNION ALL
                  SELECT x.id, 'FX', t.terminal_code, x.session_id, x.status, x.currency, coalesce(x.foreign_total, 0),
                         coalesce(x.local_amount, 0), 0,
                         'Bank buys ' || x.currency || CASE x.destination_type WHEN 'CASH' THEN ' for cash' ELSE ' to account' END,
                         'CASH ' || x.currency, CASE x.destination_type WHEN 'CASH' THEN 'CASH KES' ELSE %3$s END,
                         s.customer_id, s.customer_name,
                         'Forex: ' || x.currency || ' bought from customer', 'IN', x.new_balance,
                         x.transaction_ref, x.core_ref, NULL, x.rejected_count, NULL, x.created_at, x.updated_at
                  FROM fx_exchanges x JOIN terminals t ON t.id = x.terminal_id JOIN stm_sessions s ON s.id = x.session_id
                  UNION ALL
                  SELECT o.id, 'CARD', t.terminal_code, o.session_id, o.status, 'KES', o.fee_amount, o.fee_amount, o.fee_amount,
                         initcap(replace(o.service, '_', ' ')) || ' · ' || o.card_product, %4$s, coalesce(o.masked_pan, 'card'),
                         o.customer_id, coalesce(s.customer_name, o.name_on_card),
                         'Card ' || lower(replace(o.service, '_', ' ')) || coalesce(' ' || o.masked_pan, ''), 'NONE', NULL,
                         coalesce(o.reference, o.id), o.fee_ref, NULL, NULL, NULL, o.created_at, o.updated_at
                  FROM card_orders o JOIN terminals t ON t.id = o.terminal_id JOIN stm_sessions s ON s.id = o.session_id
                  UNION ALL
                  SELECT st.id, 'STATEMENT', t.terminal_code, st.session_id, 'PRINTED', st.currency, st.fee, st.fee, st.fee,
                         CASE st.type WHEN 'MINI' THEN 'Mini statement' ELSE 'Full statement' END, %5$s, NULL,
                         st.customer_id, s.customer_name,
                         CASE st.type WHEN 'MINI' THEN 'Mini statement' ELSE 'Full statement, ' || coalesce(st.period_months, 0) || ' months' END,
                         'NONE', st.balance_after, st.reference, st.fee_ref, NULL, NULL, NULL, st.created_at, st.created_at
                  FROM statements st JOIN stm_sessions s ON s.id = st.session_id JOIN terminals t ON t.id = s.terminal_id
                  UNION ALL
                  SELECT ap.id, 'ACCOUNT_OPENING', t.terminal_code, ap.session_id, ap.status, 'KES', 0, 0, 0,
                         'Account opening · ' || ap.product_code, NULL, ap.account_number,
                         ap.customer_id, ap.full_name, 'New account ' || ap.product_code, 'NONE', NULL,
                         coalesce(ap.reference, ap.id), NULL, NULL, NULL, NULL, ap.created_at, ap.updated_at
                  FROM applications ap JOIN terminals t ON t.id = ap.terminal_id
                  UNION ALL
                  SELECT e.id, 'SERVICE', t.terminal_code, e.session_id, 'COMPLETED', 'KES', 0, 0, 0,
                         initcap(replace(e.kind, '_', ' ')), e.subject, NULL,
                         e.customer_id, s.customer_name, initcap(replace(e.kind, '_', ' ')) || coalesce(' · ' || e.subject, ''),
                         'NONE', NULL, coalesce(e.reference, e.id), NULL, NULL, NULL, NULL, e.created_at, e.created_at
                  FROM service_events e JOIN terminals t ON t.id = e.terminal_id JOIN stm_sessions s ON s.id = e.session_id
                ) all_tx""".formatted(acct("dt.account_id"), acct("w.account_id"), acct("x.account_id"), acct("o.account_id"),
            acct("st.account_id"));

    public static final List<String> KINDS = List.of("DEPOSIT", "WITHDRAWAL", "FX", "CARD", "STATEMENT", "ACCOUNT_OPENING", "SERVICE");

    private record Filter(String where, Map<String, Object> params) {}

    private static Filter filter(String kind, String status, String terminal, java.time.LocalDate from, java.time.LocalDate to,
                                 String q) {
        StringBuilder w = new StringBuilder("""
                 WHERE (CAST(:k AS VARCHAR) IS NULL OR kind = :k) AND (CAST(:s AS VARCHAR) IS NULL OR status = :s)
                   AND (CAST(:tm AS VARCHAR) IS NULL OR terminal_code = :tm)
                   AND (CAST(:q AS VARCHAR) IS NULL OR party ILIKE :q OR transaction_ref ILIKE :q OR core_ref ILIKE :q
                        OR terminal_code ILIKE :q OR id ILIKE :q OR customer_name ILIKE :q OR customer_id ILIKE :q
                        OR from_account ILIKE :q OR to_account ILIKE :q OR session_id ILIKE :q)""");
        Map<String, Object> p = new HashMap<>();
        p.put("k", blank(kind));
        p.put("s", blank(status));
        p.put("tm", blank(terminal));
        p.put("q", blank(q) == null ? null : "%" + q.trim() + "%");
        if (from != null) { w.append(" AND created_at >= (CAST(:f AS date) + time '00:00') AT TIME ZONE 'Africa/Nairobi'"); p.put("f", from); }
        if (to != null) { w.append(" AND created_at < (CAST(:u AS date) + 1 + time '00:00') AT TIME ZONE 'Africa/Nairobi'"); p.put("u", to); }
        return new Filter(w.toString(), p);
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /**
     * kind = DEPOSIT | WITHDRAWAL | FX | CARD | STATEMENT | ACCOUNT_OPENING | SERVICE (default: all), newest first.
     * terminal = kiosk code; from / to = dates (Nairobi). q searches customer, accounts, references and kiosk.
     * ?page= gives a page, as for /sessions.
     */
    @GetMapping("/transactions")
    public Object transactions(@RequestParam(required = false) String kind,
                               @RequestParam(required = false) String status,
                               @RequestParam(required = false) String terminal,
                               @RequestParam(required = false) java.time.LocalDate from,
                               @RequestParam(required = false) java.time.LocalDate to,
                               @RequestParam(required = false) String q,
                               @RequestParam(required = false) Integer page,
                               @RequestParam(defaultValue = "50") int size,
                               @RequestParam(defaultValue = "300") int limit) {
        Filter f = filter(kind, status, terminal, from, to, q);
        Map<String, Object> p = f.params();
        String select = "SELECT * FROM " + ALL_TX + " " + f.where() + " ORDER BY created_at DESC, id DESC";
        if (page == null) {
            p.put("l", Math.max(1, Math.min(limit, 1000)));
            return rows.list(select + " LIMIT :l", p);
        }
        Rows.Page pg = rows.page("SELECT count(*) FROM " + ALL_TX + " " + f.where(), select, p, page, size);
        // Counts per kind for the filter tabs, over the same filters except kind.
        Map<String, Object> pk = new HashMap<>(p);
        pk.put("k", null);
        Map<String, Long> counts = new LinkedHashMap<>();
        rows.list("SELECT kind, count(*) AS n FROM " + ALL_TX + " " + f.where() + " GROUP BY kind", pk)
                .forEach(r -> counts.put((String) r.get("kind"), ((Number) r.get("n")).longValue()));
        return pg.withCounts(counts);
    }

    /** The same rows as /transactions (same filters, up to 20 000), as a CSV file that opens in Excel. */
    @GetMapping(value = "/transactions/export.csv", produces = "text/csv")
    public org.springframework.http.ResponseEntity<byte[]> export(@RequestParam(required = false) String kind,
                                                                  @RequestParam(required = false) String status,
                                                                  @RequestParam(required = false) String terminal,
                                                                  @RequestParam(required = false) java.time.LocalDate from,
                                                                  @RequestParam(required = false) java.time.LocalDate to,
                                                                  @RequestParam(required = false) String q) {
        Filter f = filter(kind, status, terminal, from, to, q);
        Map<String, Object> p = f.params();
        p.put("l", 20_000);
        List<Map<String, Object>> list = rows.list("SELECT * FROM " + ALL_TX + " " + f.where()
                + " ORDER BY created_at DESC, id DESC LIMIT :l", p);
        String[][] cols = {
                {"createdAt", "Date (UTC)"}, {"transactionRef", "Reference"}, {"coreRef", "Core reference"}, {"id", "Transaction id"},
                {"terminalCode", "Terminal"}, {"kind", "Type"}, {"status", "Status"}, {"fromAccount", "From account"},
                {"toAccount", "To account"}, {"customerName", "Customer name"}, {"customerId", "Customer id"},
                {"currency", "Currency"}, {"amount", "Amount"}, {"localAmount", "KES value"}, {"fee", "Fee"},
                {"cashFlow", "Cash in/out"}, {"balanceAfter", "Balance after"}, {"narration", "Narration"},
                {"reversalRef", "Reversal"}, {"sessionId", "Session"}};
        StringBuilder b = new StringBuilder("\uFEFF");
        b.append(String.join(",", Arrays.stream(cols).map(c -> csv(c[1])).toList())).append("\r\n");
        for (Map<String, Object> r : list) {
            b.append(String.join(",", Arrays.stream(cols).map(c -> csv(r.get(c[0]))).toList())).append("\r\n");
        }
        String name = "stm-transactions-" + java.time.LocalDate.now(java.time.ZoneId.of("Africa/Nairobi")) + ".csv";
        return org.springframework.http.ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"" + name + "\"")
                .contentType(org.springframework.http.MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(b.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String csv(Object v) {
        if (v == null) return "";
        String s = v.toString();
        // Excel would run a cell that starts with = + - @ as a formula.
        if (!s.isEmpty() && "=+-@".indexOf(s.charAt(0)) >= 0 && !(v instanceof Number)) s = "'" + s;
        return s.matches(".*[\",\r\n].*") ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }

    /** Today's totals (KES value) per kind and the open exceptions, over every transaction (not just one page). */
    @GetMapping("/transactions/summary")
    public Map<String, Object> summary() {
        return rows.one("""
                SELECT coalesce(sum(local_amount) FILTER (WHERE kind = 'DEPOSIT' AND %1$s), 0) AS deposited_today,
                       coalesce(sum(local_amount) FILTER (WHERE kind = 'WITHDRAWAL' AND %1$s), 0) AS withdrawn_today,
                       coalesce(sum(local_amount) FILTER (WHERE kind = 'FX' AND %1$s), 0) AS fx_today,
                       coalesce(sum(fee) FILTER (WHERE %1$s), 0) AS fees_today,
                       count(*) FILTER (WHERE %1$s) AS count_today,
                       count(*) FILTER (WHERE status IN ('REVERSED', 'PENDING_RECONCILIATION', 'UNKNOWN')
                                        OR coalesce(suspected_counterfeit, 0) > 0) AS exceptions
                FROM """.formatted("""
                created_at >= date_trunc('day', now() AT TIME ZONE 'Africa/Nairobi') AT TIME ZONE 'Africa/Nairobi'
                AND status NOT IN ('CANCELLED', 'RETURNED', 'REVERSED')""") + ALL_TX, Map.of()).orElseThrow();
    }

    /** Core postings behind one or more references (comma-separated core refs, as stored on a transaction). */
    @GetMapping("/postings")
    public List<Map<String, Object>> postings(@RequestParam String refs) {
        List<String> list = Arrays.stream(refs.split(",")).map(String::trim).filter(s -> !s.isEmpty()).limit(10).toList();
        if (list.isEmpty()) return List.of();
        return rows.list("""
                SELECT id, core_ref, account_id, direction, amount, currency, narrative, balance_after, reversal_of, posted_at
                FROM sim_postings WHERE core_ref IN (:r) OR reversal_of IN (:r) ORDER BY id""", Map.of("r", list));
    }

    /** Newest first. q searches customer, name on card, card number, reference and kiosk. ?page= gives a page. */
    @GetMapping("/card-orders")
    public Object cardOrders(@RequestParam(required = false) String status,
                             @RequestParam(required = false) String q,
                             @RequestParam(required = false) Integer page,
                             @RequestParam(defaultValue = "50") int size,
                             @RequestParam(defaultValue = "300") int limit) {
        Map<String, Object> p = new HashMap<>();
        p.put("s", status == null || status.isBlank() ? null : status);
        p.put("q", q == null || q.isBlank() ? null : "%" + q.trim() + "%");
        String from = """
                 FROM card_orders o JOIN terminals t ON t.id = o.terminal_id JOIN stm_sessions s ON s.id = o.session_id
                WHERE (CAST(:s AS VARCHAR) IS NULL OR o.status = :s)
                  AND (CAST(:q AS VARCHAR) IS NULL OR s.customer_name ILIKE :q OR o.name_on_card ILIKE :q OR o.masked_pan ILIKE :q
                       OR o.reference ILIKE :q OR o.customer_id ILIKE :q OR t.terminal_code ILIKE :q OR o.id ILIKE :q)""";
        String select = """
                SELECT o.id, t.terminal_code, o.session_id, o.customer_id, s.customer_name, o.service, o.account_id,
                       o.card_product, o.name_on_card, o.masked_pan, o.card_id, o.fee_amount, o.fee_posted, o.fee_ref,
                       o.status, o.outcome, o.replaces_card_id, o.reference, o.application_id, o.created_at, o.updated_at,
                       o.steps AS steps_json """ + from + " ORDER BY o.created_at DESC, o.id DESC";
        if (page == null) {
            p.put("l", Math.max(1, Math.min(limit, 1000)));
            return rows.list(select + " LIMIT :l", p);
        }
        return rows.page("SELECT count(*) " + from, select, p, page, size);
    }
}
