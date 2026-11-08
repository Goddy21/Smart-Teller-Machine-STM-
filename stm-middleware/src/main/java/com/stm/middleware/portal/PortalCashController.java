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

    /** kind = DEPOSIT | WITHDRAWAL | FX (default: all), newest first. One row shape for every kind. */
    @GetMapping("/transactions")
    public List<Map<String, Object>> transactions(@RequestParam(required = false) String kind,
                                                  @RequestParam(required = false) String status,
                                                  @RequestParam(defaultValue = "300") int limit) {
        String sql = """
                SELECT * FROM (
                  SELECT d.id, 'DEPOSIT' AS kind, t.terminal_code, d.session_id, d.status, d.currency,
                         coalesce(d.total, 0) AS amount, coalesce(d.total, 0) - d.fee AS local_amount, d.target_label AS party,
                         d.transaction_ref, d.core_ref, NULL AS reversal_ref, d.rejected_count AS notes_returned,
                         d.suspected_counterfeit, d.created_at, d.updated_at
                  FROM cash_deposits d JOIN terminals t ON t.id = d.terminal_id
                  UNION ALL
                  SELECT w.id, CASE WHEN w.source = 'ACCOUNT' THEN 'WITHDRAWAL' ELSE 'FX' END, t.terminal_code, w.session_id,
                         w.status, w.currency, w.amount, w.local_amount,
                         CASE w.source WHEN 'FX_SELL' THEN 'Bank sells ' || w.currency WHEN 'FX_KES_OUT' THEN 'KES paid out for forex'
                                       ELSE 'Account ' || coalesce(w.account_id, '') END,
                         w.transaction_ref, w.core_ref, w.reversal_ref, NULL, NULL, w.created_at, w.updated_at
                  FROM cash_withdrawals w JOIN terminals t ON t.id = w.terminal_id
                  UNION ALL
                  SELECT x.id, 'FX', t.terminal_code, x.session_id, x.status, x.currency, coalesce(x.foreign_total, 0),
                         coalesce(x.local_amount, 0),
                         'Bank buys ' || x.currency || CASE x.destination_type WHEN 'CASH' THEN ' for cash' ELSE ' to account' END,
                         x.transaction_ref, x.core_ref, NULL, x.rejected_count, NULL, x.created_at, x.updated_at
                  FROM fx_exchanges x JOIN terminals t ON t.id = x.terminal_id
                ) all_tx WHERE (CAST(:k AS VARCHAR) IS NULL OR kind = :k) AND (CAST(:s AS VARCHAR) IS NULL OR status = :s)
                ORDER BY created_at DESC LIMIT :l""";
        Map<String, Object> p = new HashMap<>();
        p.put("k", kind == null || kind.isBlank() ? null : kind);
        p.put("s", status == null || status.isBlank() ? null : status);
        p.put("l", Math.max(1, Math.min(limit, 1000)));
        return rows.list(sql, p);
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

    @GetMapping("/card-orders")
    public List<Map<String, Object>> cardOrders(@RequestParam(required = false) String status,
                                                @RequestParam(defaultValue = "300") int limit) {
        Map<String, Object> p = new HashMap<>();
        p.put("s", status == null || status.isBlank() ? null : status);
        p.put("l", Math.max(1, Math.min(limit, 1000)));
        return rows.list("""
                SELECT o.id, t.terminal_code, o.session_id, o.customer_id, s.customer_name, o.service, o.account_id,
                       o.card_product, o.name_on_card, o.masked_pan, o.card_id, o.fee_amount, o.fee_posted, o.fee_ref,
                       o.status, o.outcome, o.replaces_card_id, o.reference, o.application_id, o.created_at, o.updated_at
                FROM card_orders o JOIN terminals t ON t.id = o.terminal_id JOIN stm_sessions s ON s.id = o.session_id
                WHERE (CAST(:s AS VARCHAR) IS NULL OR o.status = :s)
                ORDER BY o.created_at DESC LIMIT :l""", p);
    }
}
