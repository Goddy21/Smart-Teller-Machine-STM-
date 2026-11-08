package com.stm.middleware.backend.sim;

import com.stm.middleware.audit.AuditService;
import com.stm.middleware.auth.PortalUser;
import com.stm.middleware.portal.Rows;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Portal view of the SIMULATED back ends (demo profile only): the switches (core down, latency), the test identities
 * with their scenarios, and the simulated core's customers, accounts, cards and postings.
 */
@RestController
@Profile("!prod")
@RequestMapping("/api/v1/portal/simulator")
public class SimulatorController {
    private final SimControl control;
    private final Rows rows;
    private final AuditService audit;

    public SimulatorController(SimControl control, Rows rows, AuditService audit) {
        this.control = control;
        this.rows = rows;
        this.audit = audit;
    }

    @GetMapping
    public Map<String, Object> state() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("coreDown", control.coreDown());
        out.put("latencyMs", control.latencyMs());
        out.put("fixedOtp", control.fixedOtp());
        out.put("devPinBlockExample", Map.of("pin", "2580", "pinBlock", DevPinBlock.encode("2580")));
        out.put("identities", rows.list("""
                SELECT document_number, document_type, country, surname, given_names, date_of_birth, gender, kra_pin,
                       kra_status, scenario, notes FROM sim_registry ORDER BY scenario, document_number"""));
        out.put("paybills", List.of(Map.of("paybill", "522522", "name", "Blue River Utilities"),
                Map.of("paybill", "888880", "name", "Nairobi City Water")));
        return out;
    }

    public record Switches(Boolean coreDown, Integer latencyMs) {}

    @PutMapping
    public Map<String, Object> set(@AuthenticationPrincipal PortalUser user, @RequestBody Switches req) {
        if (req.coreDown() != null) control.setCoreDown(req.coreDown());
        if (req.latencyMs() != null) control.setLatencyMs(req.latencyMs());
        audit.record("SIMULATOR_CHANGED", "simulator", null, Map.of("coreDown", control.coreDown(),
                "latencyMs", control.latencyMs(), "by", user == null ? "portal-key" : user.username()));
        return state();
    }

    @GetMapping("/customers")
    public List<Map<String, Object>> customers(@RequestParam(required = false) String q) {
        Map<String, Object> p = new HashMap<>();
        p.put("q", q == null || q.isBlank() ? null : "%" + q.trim() + "%");
        return rows.list("""
                SELECT c.id, c.id_number, c.full_name, c.mobile, c.kra_pin, c.branch, c.created_at,
                       (SELECT json_agg(json_build_object('id', a.id, 'number', a.account_number, 'type', a.type,
                               'available', a.available, 'ledger', a.ledger, 'currency', a.currency, 'status', a.status)
                               ORDER BY a.created_at)::text FROM sim_accounts a WHERE a.customer_id = c.id) AS accounts_json,
                       (SELECT json_agg(json_build_object('id', k.id, 'maskedPan', k.masked_pan, 'product', k.product,
                               'status', k.status, 'expiry', k.expiry, 'accountId', k.account_id) ORDER BY k.created_at)::text
                          FROM sim_cards k WHERE k.customer_id = c.id) AS cards_json
                FROM sim_customers c
                WHERE CAST(:q AS VARCHAR) IS NULL OR c.full_name ILIKE :q OR c.id ILIKE :q OR c.id_number ILIKE :q
                ORDER BY c.created_at DESC""", p);
    }

    @GetMapping("/accounts/{accountId}/postings")
    public List<Map<String, Object>> postings(@PathVariable String accountId) {
        return rows.list("""
                SELECT id, core_ref, direction, amount, currency, narrative, balance_after, reversal_of, posted_at
                FROM sim_postings WHERE account_id = :a ORDER BY id DESC LIMIT 200""", Map.of("a", accountId));
    }

    /** Cash GLs per kiosk and currency (debit balance = cash the bank says is in the machine). */
    @GetMapping("/gl")
    public List<Map<String, Object>> gl() {
        return rows.list("""
                SELECT account_id AS gl, currency, sum(CASE WHEN direction = 'DR' THEN amount ELSE -amount END) AS balance,
                       count(*) AS postings
                FROM sim_postings WHERE account_id LIKE 'GL-%' GROUP BY account_id, currency ORDER BY account_id""");
    }
}
