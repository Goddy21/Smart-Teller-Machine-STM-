package com.stm.middleware.backend.sim;

import com.stm.middleware.audit.AuditService;
import com.stm.middleware.auth.PortalUser;
import com.stm.middleware.backend.CoreBanking;
import com.stm.middleware.common.ApiException;
import com.stm.middleware.stm.core.Json;
import com.stm.middleware.stm.receipts.SmsService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.function.Function;

/**
 * Administrators' test-data tools in the portal: edit and delete test customers, accounts, cards and sessions.
 * Only with the simulated bank (never in the prod profile, so they disappear at go-live), only for ADMIN
 * (SecurityConfig), and switchable off with ADMIN_TEST_TOOLS=false. Every delete can run as a dry run first
 * (?dryRun=true: everything is done, counted and rolled back), which is what the portal's confirmation shows.
 * The built-in test customers can be edited but not deleted. Every change is written to the audit journal.
 */
@RestController
@Profile("!prod")
@RequestMapping("/api/v1/portal/test-data")
public class TestDataController {
    static final Set<String> BUILT_IN = Set.of("CIF00204417", "CIF00391144", "CIF00482913", "CIF00900001");
    static final String FUNDING_GL = "GL-TEST-FUNDING";

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final CoreBanking core;
    private final AuditService audit;
    private final Json json;
    /** The server allows the tools at all (ADMIN_TEST_TOOLS, default true; never in the prod profile). */
    private final boolean allowed;
    private final int dormantMonths;

    public TestDataController(JdbcClient jdbc, TransactionTemplate tx, CoreBanking core, AuditService audit, Json json,
                              @Value("${stm.admin.test-data-tools:true}") boolean enabled,
                              @Value("${stm.accounts.dormant-after-months:12}") int dormantMonths) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.core = core;
        this.audit = audit;
        this.json = json;
        this.allowed = enabled;
        this.dormantMonths = dormantMonths;
    }

    /** enabled = allowed on this server AND switched on by an administrator (the Simulator page switch). */
    @GetMapping("/status")
    public Map<String, Object> status() {
        boolean on = switchedOn();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", allowed && on);
        out.put("allowed", allowed);
        out.put("switchedOn", on);
        out.put("builtInCustomers", BUILT_IN);
        return out;
    }

    public record Toggle(@NotNull Boolean enabled) {}

    /** The administrators' switch: shows or hides every edit and delete option in the portal. Kept across restarts. */
    @PutMapping("/status")
    public Map<String, Object> toggle(@AuthenticationPrincipal PortalUser user, @Valid @RequestBody Toggle req) {
        if (!allowed) throw new ApiException(HttpStatus.FORBIDDEN, "TOOLS_DISABLED", "The test-data tools are not available on this server.");
        String by = user == null ? "portal-key" : user.username();
        jdbc.sql("""
                INSERT INTO portal_settings (key, value, updated_at, updated_by) VALUES ('admin.test-data-tools', :v, now(), :b)
                ON CONFLICT (key) DO UPDATE SET value = excluded.value, updated_at = now(), updated_by = excluded.updated_by""")
                .param("v", Boolean.toString(req.enabled())).param("b", by).update();
        audit.record(req.enabled() ? "TEST_DATA_TOOLS_ON" : "TEST_DATA_TOOLS_OFF", "test-data-tools", null, Map.of("by", by));
        return status();
    }

    private boolean switchedOn() {
        return jdbc.sql("SELECT value FROM portal_settings WHERE key = 'admin.test-data-tools'").query(String.class)
                .optional().map("true"::equals).orElse(true);
    }

    // ================================================================== deletes (dry run first)

    @DeleteMapping("/customers/{customerId}")
    public Map<String, Object> deleteCustomer(@AuthenticationPrincipal PortalUser user, @PathVariable String customerId,
                                              @RequestParam(defaultValue = "false") boolean dryRun) {
        on();
        if (BUILT_IN.contains(customerId)) throw bad("BUILT_IN", "The built-in test customers cannot be deleted.");
        customerName(customerId);
        return run(user, "TEST_CUSTOMER_DELETED", customerId, dryRun, c -> {
            List<String> accounts = ids("SELECT id FROM sim_accounts WHERE customer_id = :x", customerId);
            Set<String> sessions = new LinkedHashSet<>(ids("SELECT id FROM stm_sessions WHERE customer_id = :x", customerId));
            sessions.addAll(ids("SELECT session_id FROM applications WHERE customer_id = :x", customerId));
            c.put("accounts", accounts.size());
            deleteSessions(c, sessions);
            deleteAccounts(c, accounts);
            deleteCustomerRecords(c, customerId);
        });
    }

    @DeleteMapping("/accounts/{accountId}")
    public Map<String, Object> deleteAccount(@AuthenticationPrincipal PortalUser user, @PathVariable String accountId,
                                             @RequestParam(defaultValue = "false") boolean dryRun) {
        on();
        String customerId = owner(accountId);
        if (BUILT_IN.contains(customerId)) throw bad("BUILT_IN", "The built-in test customers' accounts cannot be deleted.");
        return run(user, "TEST_ACCOUNT_DELETED", accountId, dryRun, c -> {
            String number = jdbc.sql("SELECT account_number FROM sim_accounts WHERE id = :a").param("a", accountId)
                    .query(String.class).single();
            Set<String> sessions = new LinkedHashSet<>(ids(
                    "SELECT session_id FROM applications WHERE account_number = :x", number));
            sessions.addAll(ids("SELECT session_id FROM card_orders WHERE account_id = :x", accountId));
            sessions.addAll(ids("SELECT session_id FROM fee_quotes WHERE debit_account_id = :x", accountId));
            c.put("accounts", 1);
            deleteSessions(c, sessions);
            deleteAccounts(c, List.of(accountId));
            long left = jdbc.sql("SELECT count(*) FROM sim_accounts WHERE customer_id = :c").param("c", customerId)
                    .query(Long.class).single();
            if (left == 0) {   // the last account: the customer goes too
                c.put("customerDeleted", customerId);
                deleteCustomerRecords(c, customerId);
            } else {
                c.put("customerKept", customerId);
                c.put("otherAccounts", left);
            }
        });
    }

    /** One card, or every card of a customer (cardId "all" with ?customerId=). The account and the customer stay. */
    @DeleteMapping("/cards/{cardId}")
    public Map<String, Object> deleteCards(@AuthenticationPrincipal PortalUser user, @PathVariable String cardId,
                                           @RequestParam(required = false) String customerId,
                                           @RequestParam(defaultValue = "false") boolean dryRun) {
        on();
        List<String> cards = "all".equals(cardId)
                ? ids("SELECT id FROM sim_cards WHERE customer_id = :x", required(customerId, "customerId"))
                : ids("SELECT id FROM sim_cards WHERE id = :x", cardId);
        if (cards.isEmpty()) throw notFound("No card found.");
        return run(user, "TEST_CARDS_REMOVED", "all".equals(cardId) ? customerId : cardId, dryRun, c -> {
            c.put("cards", cards.size());
            count(c, "card sign-ins", jdbc.sql("DELETE FROM card_auths WHERE card_id IN (:k)").param("k", cards).update());
            count(c, "card orders cancelled", jdbc.sql("""
                    UPDATE card_orders SET status = 'CANCELLED', outcome = 'REMOVED' WHERE card_id IN (:k)""")
                    .param("k", cards).update());
            List<String> accounts = jdbc.sql("SELECT DISTINCT account_id FROM sim_cards WHERE id IN (:k)").param("k", cards)
                    .query(String.class).list();
            jdbc.sql("DELETE FROM sim_cards WHERE id IN (:k)").param("k", cards).update();
            // The application no longer shows a card when its account has none left.
            jdbc.sql("""
                    UPDATE applications SET card_product = NULL, name_on_card = NULL
                    WHERE account_id IN (:a) AND NOT EXISTS (SELECT 1 FROM sim_cards k WHERE k.account_id = applications.account_id)""")
                    .param("a", accounts).update();
        });
    }

    @DeleteMapping("/sessions/{sessionId}")
    public Map<String, Object> deleteSession(@AuthenticationPrincipal PortalUser user, @PathVariable String sessionId,
                                             @RequestParam(defaultValue = "false") boolean dryRun) {
        on();
        record S(String status, String customer) {}
        S s = jdbc.sql("SELECT status, customer_name FROM stm_sessions WHERE id = :id").param("id", sessionId)
                .query((rs, n) -> new S(rs.getString(1), rs.getString(2))).optional()
                .orElseThrow(() -> notFound("Session not found."));
        return run(user, "TEST_SESSION_DELETED", sessionId, dryRun, c -> {
            if ("ACTIVE".equals(s.status())) c.put("warning", "The session is still open: the customer at the kiosk will be sent back to the start.");
            long opened = jdbc.sql("SELECT count(*) FROM applications WHERE session_id = :s AND status = 'ACCOUNT_OPENED'")
                    .param("s", sessionId).query(Long.class).single();
            if (opened > 0) c.put("note", "An account was opened in this session. The account itself stays; delete it separately if needed.");
            deleteSessions(c, List.of(sessionId));
        });
    }

    // ================================================================== edits

    public record Adjust(@NotBlank @Pattern(regexp = "CREDIT|DEBIT") String direction,
                         @NotNull @DecimalMin("0.01") @DecimalMax("10000000") BigDecimal amount,
                         @Size(max = 80) String narrative) {}

    /** Puts test money in (or takes it out), posted against the test-funding GL like any other posting. */
    @PostMapping("/accounts/{accountId}/adjust")
    public Map<String, Object> adjust(@AuthenticationPrincipal PortalUser user, @PathVariable String accountId,
                                      @Valid @RequestBody Adjust req) {
        on();
        owner(accountId);
        CoreBanking.Account a = core.account(accountId).orElseThrow(() -> notFound("Account not found."));
        BigDecimal amount = req.amount().setScale(2, RoundingMode.HALF_UP);
        String narrative = req.narrative() == null || req.narrative().isBlank() ? "Test funding" : req.narrative().trim();
        CoreBanking.Posting p = tx.execute(s -> "CREDIT".equals(req.direction())
                ? core.credit(accountId, amount, a.currency(), narrative, FUNDING_GL)
                : core.debit(accountId, amount, a.currency(), narrative, FUNDING_GL));
        audit.record("TEST_BALANCE_ADJUSTED", accountId, null, by(user, Map.of("direction", req.direction(),
                "amount", amount.toPlainString(), "coreRef", p.coreRef(), "balanceAfter", p.balanceAfter().toPlainString())));
        return Map.of("accountId", accountId, "coreRef", p.coreRef(), "balanceAfter", p.balanceAfter(), "currency", a.currency());
    }

    public record AccountStatus(@NotBlank @Pattern(regexp = "ACTIVE|DORMANT|FROZEN|CLOSED") String status,
                                @Size(max = 40) String reason) {}

    /** DORMANT is set by moving the last activity back past the dormancy period, as it happens for real. */
    @PutMapping("/accounts/{accountId}/status")
    public Map<String, Object> accountStatus(@AuthenticationPrincipal PortalUser user, @PathVariable String accountId,
                                             @Valid @RequestBody AccountStatus req) {
        on();
        owner(accountId);
        String reason = req.reason() == null || req.reason().isBlank() ? null : req.reason().trim().toUpperCase().replace(' ', '_');
        switch (req.status()) {
            case "ACTIVE" -> jdbc.sql("UPDATE sim_accounts SET status = 'ACTIVE', status_reason = NULL, last_activity_at = now() WHERE id = :a")
                    .param("a", accountId).update();
            case "DORMANT" -> jdbc.sql("""
                    UPDATE sim_accounts SET status = 'ACTIVE', status_reason = NULL,
                           last_activity_at = now() - make_interval(months => :m) - interval '1 day' WHERE id = :a""")
                    .param("m", dormantMonths).param("a", accountId).update();
            default -> jdbc.sql("UPDATE sim_accounts SET status = :s, status_reason = :r WHERE id = :a")
                    .param("s", req.status()).param("r", reason == null ? ("FROZEN".equals(req.status()) ? "TEST" : "CLOSED_BY_BANK") : reason)
                    .param("a", accountId).update();
        }
        audit.record("TEST_ACCOUNT_STATUS_SET", accountId, null, by(user, Map.of("status", req.status(), "reason", String.valueOf(reason))));
        return Map.of("accountId", accountId, "status", req.status());
    }

    public record CardStatus(@NotBlank @Pattern(regexp = "ACTIVE|INACTIVE_PIN_REQUIRED|TEMP_BLOCKED|BLOCKED|PIN_LOCKED|EXPIRED") String status,
                             @Size(max = 20) String reason) {}

    @PutMapping("/cards/{cardId}/status")
    public Map<String, Object> cardStatus(@AuthenticationPrincipal PortalUser user, @PathVariable String cardId,
                                          @Valid @RequestBody CardStatus req) {
        on();
        long n = jdbc.sql("SELECT count(*) FROM sim_cards WHERE id = :k").param("k", cardId).query(Long.class).single();
        if (n == 0) throw notFound("Card not found.");
        String reason = req.reason() == null || req.reason().isBlank() ? null : req.reason().trim().toUpperCase().replace(' ', '_');
        String fresh = java.time.YearMonth.now().plusYears(4).toString();
        String sql = switch (req.status()) {
            case "ACTIVE" -> """
                    UPDATE sim_cards SET status = 'ACTIVE', pin_tries_left = 3, blocked_reason = NULL, blocked_at = NULL,
                           temp_blocked_reason = NULL, temp_blocked_at = NULL,
                           expiry = CASE WHEN to_date(expiry, 'YYYY-MM') < date_trunc('month', now()) THEN :fresh ELSE expiry END
                    WHERE id = :k""";
            case "INACTIVE_PIN_REQUIRED" -> """
                    UPDATE sim_cards SET status = 'INACTIVE_PIN_REQUIRED', pin_hash = NULL, pin_tries_left = 3, blocked_reason = NULL,
                           blocked_at = NULL, temp_blocked_reason = NULL, temp_blocked_at = NULL WHERE id = :k""";
            case "TEMP_BLOCKED" -> """
                    UPDATE sim_cards SET status = 'TEMP_BLOCKED', temp_blocked_reason = coalesce(CAST(:r AS VARCHAR), 'MISPLACED'),
                           temp_blocked_at = now() WHERE id = :k""";
            case "BLOCKED" -> """
                    UPDATE sim_cards SET status = 'BLOCKED', blocked_reason = coalesce(CAST(:r AS VARCHAR), 'LOST'), blocked_at = now()
                    WHERE id = :k""";
            case "PIN_LOCKED" -> "UPDATE sim_cards SET status = 'PIN_LOCKED', pin_tries_left = 0 WHERE id = :k";
            default -> "UPDATE sim_cards SET expiry = to_char(now() - interval '1 month', 'YYYY-MM') WHERE id = :k";   // EXPIRED
        };
        var q = jdbc.sql(sql).param("k", cardId);
        if (sql.contains(":r")) q = q.param("r", reason);
        if (sql.contains(":fresh")) q = q.param("fresh", fresh);
        q.update();
        audit.record("TEST_CARD_STATUS_SET", cardId, null, by(user, Map.of("status", req.status(), "reason", String.valueOf(reason))));
        return Map.of("cardId", cardId, "status", req.status());
    }

    @GetMapping("/customers/{customerId}")
    public Map<String, Object> customer(@PathVariable String customerId) {
        on();
        return jdbc.sql("SELECT id, full_name, mobile, email, kra_pin, address, profile FROM sim_customers WHERE id = :c")
                .param("c", customerId).query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("customerId", rs.getString(1));
                    m.put("fullName", rs.getString(2));
                    m.put("mobile", rs.getString(3));
                    m.put("email", rs.getString(4));
                    m.put("kraPin", rs.getString(5));
                    m.put("address", readMap(rs.getString(6)));
                    Map<String, Object> p = readMap(rs.getString(7));
                    m.put("occupation", p.get("occupation"));
                    m.put("employer", p.get("employer"));
                    m.put("sourceOfFunds", p.get("sourceOfFunds"));
                    m.put("monthlyIncome", p.get("monthlyIncome"));
                    m.put("nextOfKin", p.get("nextOfKin"));
                    return m;
                }).optional().orElseThrow(() -> notFound("Customer not found."));
    }

    public record NextOfKin(@Size(max = 80) String fullName, @Size(max = 30) String relationship, @Size(max = 20) String mobile) {}

    public record Address(@Size(max = 120) String line1, @Size(max = 60) String town, @Size(max = 60) String county,
                          @Size(max = 60) String subCounty, @Size(max = 60) String ward, @Size(max = 30) String poBox) {}

    public record CustomerEdit(@Size(min = 3, max = 120) String fullName, @Size(max = 20) String mobile,
                               @Email @Size(max = 120) String email, @Size(max = 20) String kraPin, @Valid Address address,
                               @Size(max = 60) String occupation, @Size(max = 80) String employer,
                               @Size(max = 80) String sourceOfFunds, @Size(max = 60) String monthlyIncome,
                               @Valid NextOfKin nextOfKin) {}

    /** The bank's customer record, and the details shown on the customer's applications in the portal. */
    @PatchMapping("/customers/{customerId}")
    public Map<String, Object> editCustomer(@AuthenticationPrincipal PortalUser user, @PathVariable String customerId,
                                            @Valid @RequestBody CustomerEdit req) {
        on();
        Map<String, Object> current = customer(customerId);
        String mobile = req.mobile() == null || req.mobile().isBlank() ? (String) current.get("mobile")
                : SmsService.normalise("mobile", req.mobile());
        String kra = req.kraPin() == null ? (String) current.get("kraPin") : req.kraPin().isBlank() ? null : req.kraPin().trim().toUpperCase();
        if (kra != null && !kra.matches("[AP]\\d{9}[A-Z]")) throw bad("VALIDATION_ERROR", "A KRA PIN looks like A123456789B.");
        String fullName = req.fullName() == null ? (String) current.get("fullName") : req.fullName().trim().replaceAll("\\s+", " ");
        String email = req.email() == null ? (String) current.get("email") : req.email().isBlank() ? null : req.email().trim();
        Map<String, Object> address = new LinkedHashMap<>(castMap(current.get("address")));
        if (req.address() != null) {
            putIf(address, "line1", req.address().line1());
            putIf(address, "town", req.address().town());
            putIf(address, "county", req.address().county());
            putIf(address, "subCounty", req.address().subCounty());
            putIf(address, "ward", req.address().ward());
            putIf(address, "poBox", req.address().poBox());
        }
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("occupation", pick(req.occupation(), current.get("occupation")));
        profile.put("employer", pick(req.employer(), current.get("employer")));
        profile.put("sourceOfFunds", req.sourceOfFunds() == null || req.sourceOfFunds().isBlank() ? pick(req.sourceOfFunds(), current.get("sourceOfFunds"))
                : com.stm.middleware.stm.onboarding.KycOptions.sourceOfFunds(req.sourceOfFunds()));
        profile.put("monthlyIncome", req.monthlyIncome() == null || req.monthlyIncome().isBlank() ? pick(req.monthlyIncome(), current.get("monthlyIncome"))
                : com.stm.middleware.stm.onboarding.KycOptions.monthlyIncome(req.monthlyIncome()));
        Object kin = current.get("nextOfKin");
        if (req.nextOfKin() != null) {
            Map<String, Object> k = new LinkedHashMap<>(castMap(kin));
            putIf(k, "fullName", req.nextOfKin().fullName());
            putIf(k, "relationship", req.nextOfKin().relationship());
            if (req.nextOfKin().mobile() != null && !req.nextOfKin().mobile().isBlank()) {
                k.put("mobile", SmsService.normalise("nextOfKin.mobile", req.nextOfKin().mobile()));
            }
            kin = k;
        }
        profile.put("nextOfKin", kin);
        String first = fullName.split(" ")[0];
        Map<String, Object> app = new LinkedHashMap<>(profile);
        tx.executeWithoutResult(s -> {
            jdbc.sql("""
                    UPDATE sim_customers SET full_name = :f, first_name = :fn, mobile = :m, email = :e, kra_pin = :k,
                           address = :a, profile = :p WHERE id = :c""")
                    .param("f", fullName).param("fn", first).param("m", mobile).param("e", email).param("k", kra)
                    .param("a", json.write(address)).param("p", json.write(profile)).param("c", customerId).update();
            jdbc.sql("""
                    UPDATE applications SET full_name = :f, mobile = :m, email = :e, kra_pin = :k, address = :a,
                           occupation = :o, employer = :emp, source_of_funds = :sf, monthly_income = :mi, next_of_kin = :nk
                    WHERE customer_id = :c""")
                    .param("f", fullName).param("m", mobile).param("e", email).param("k", kra).param("a", json.write(address))
                    .param("o", app.get("occupation")).param("emp", app.get("employer")).param("sf", app.get("sourceOfFunds"))
                    .param("mi", app.get("monthlyIncome")).param("nk", app.get("nextOfKin") == null ? null : json.write(app.get("nextOfKin")))
                    .param("c", customerId).update();
            jdbc.sql("UPDATE stm_sessions SET customer_name = :f WHERE customer_id = :c").param("f", fullName).param("c", customerId).update();
        });
        audit.record("TEST_CUSTOMER_EDITED", customerId, null, by(user, Map.of("fields", changed(req))));
        return customer(customerId);
    }

    // ================================================================== helpers

    private interface Work { void accept(Map<String, Object> counts); }

    /** Runs the change in one transaction; a dry run counts everything and rolls it back. */
    private Map<String, Object> run(PortalUser user, String event, String entity, boolean dryRun, Work work) {
        Map<String, Object> counts = new LinkedHashMap<>();
        tx.executeWithoutResult(s -> {
            work.accept(counts);
            if (dryRun) s.setRollbackOnly();
        });
        if (!dryRun) audit.record(event, entity, null, by(user, new LinkedHashMap<>(counts)));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dryRun", dryRun);
        out.put("deleted", !dryRun);
        out.put("summary", counts);
        return out;
    }

    private void deleteSessions(Map<String, Object> c, Collection<String> sessionIds) {
        List<String> s = new ArrayList<>(new LinkedHashSet<>(sessionIds));
        s.removeIf(Objects::isNull);
        c.put("sessions", s.size());
        if (s.isEmpty()) return;
        count(c, "images", jdbc.sql("""
                DELETE FROM captures WHERE owner_id IN (SELECT id FROM applications WHERE session_id IN (:s))
                   OR owner_id IN (SELECT id FROM identity_verifications WHERE session_id IN (:s))
                   OR owner_id IN (SELECT id FROM biometric_checks WHERE session_id IN (:s))
                   OR owner_id IN (SELECT ref FROM customer_lookups WHERE session_id IN (:s))
                   OR owner_id IN (SELECT id FROM card_orders WHERE session_id IN (:s))""").param("s", s).update());
        count(c, "text messages", jdbc.sql("DELETE FROM sms_messages WHERE session_id IN (:s)").param("s", s).update());
        Function<String, Integer> del = t -> jdbc.sql("DELETE FROM " + t + " WHERE session_id IN (:s)").param("s", s).update();
        count(c, "applications", del.apply("applications"));
        count(c, "card orders", del.apply("card_orders"));
        count(c, "teller calls", del.apply("teller_calls"));
        count(c, "cash deposits", del.apply("cash_deposits"));
        count(c, "cash withdrawals", del.apply("cash_withdrawals"));
        count(c, "forex exchanges", del.apply("fx_exchanges"));
        count(c, "statements", del.apply("statements"));
        for (String t : List.of("customer_tokens", "otps", "identity_verifications", "biometric_checks", "customer_lookups",
                "fee_quotes", "card_auths", "deposit_targets", "fx_quotes", "receipts", "service_events", "mobile_changes")) {
            del.apply(t);
        }
        jdbc.sql("DELETE FROM stm_sessions WHERE id IN (:s)").param("s", s).update();
    }

    private void deleteAccounts(Map<String, Object> c, List<String> accounts) {
        if (accounts.isEmpty()) return;
        List<String> cards = jdbc.sql("SELECT id FROM sim_cards WHERE account_id IN (:a)").param("a", accounts).query(String.class).list();
        if (!cards.isEmpty()) jdbc.sql("DELETE FROM card_auths WHERE card_id IN (:k)").param("k", cards).update();
        count(c, "cards", cards.size());
        jdbc.sql("DELETE FROM sim_postings WHERE account_id IN (:a)").param("a", accounts).update();
        jdbc.sql("DELETE FROM sim_cards WHERE account_id IN (:a)").param("a", accounts).update();
        jdbc.sql("DELETE FROM sim_accounts WHERE id IN (:a)").param("a", accounts).update();
    }

    private void deleteCustomerRecords(Map<String, Object> c, String customerId) {
        for (String t : List.of("sim_biometrics", "sim_face_references", "sim_signatures", "sim_mobile_changes", "sim_cards")) {
            jdbc.sql("DELETE FROM " + t + " WHERE customer_id = :c").param("c", customerId).update();
        }
        jdbc.sql("DELETE FROM applications WHERE customer_id = :c").param("c", customerId).update();
        c.put("customer", jdbc.sql("DELETE FROM sim_customers WHERE id = :c").param("c", customerId).update());
    }

    private static void count(Map<String, Object> c, String what, int n) {
        if (n > 0) c.merge(what, n, (a, b) -> (Integer) a + (Integer) b);
    }

    private List<String> ids(String sql, String value) {
        return jdbc.sql(sql).param("x", value).query(String.class).list().stream().filter(Objects::nonNull).toList();
    }

    private String owner(String accountId) {
        return jdbc.sql("SELECT customer_id FROM sim_accounts WHERE id = :a").param("a", accountId).query(String.class)
                .optional().orElseThrow(() -> notFound("Account not found."));
    }

    private String customerName(String customerId) {
        return jdbc.sql("SELECT full_name FROM sim_customers WHERE id = :c").param("c", customerId).query(String.class)
                .optional().orElseThrow(() -> notFound("Customer not found."));
    }

    private void on() {
        if (!allowed) throw new ApiException(HttpStatus.FORBIDDEN, "TOOLS_DISABLED", "The test-data tools are not available on this server.");
        if (!switchedOn()) throw new ApiException(HttpStatus.FORBIDDEN, "TOOLS_SWITCHED_OFF",
                "The test-data tools are switched off. An administrator can switch them on on the Simulator page.");
    }

    private static String required(String v, String name) {
        if (v == null || v.isBlank()) throw bad("VALIDATION_ERROR", name + " is required");
        return v;
    }

    private static ApiException bad(String code, String message) { return new ApiException(HttpStatus.BAD_REQUEST, code, message); }

    private static ApiException notFound(String message) { return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", message); }

    private static Map<String, Object> by(PortalUser user, Map<String, Object> m) {
        Map<String, Object> out = new LinkedHashMap<>(m);
        out.put("by", user == null ? "portal-key" : user.username());
        return out;
    }

    private Map<String, Object> readMap(String s) {
        if (s == null || s.isBlank()) return new LinkedHashMap<>();
        try { return json.read(s, Json.MAP); } catch (RuntimeException e) { return new LinkedHashMap<>(Map.of("line1", s)); }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object o) { return o instanceof Map<?, ?> m ? (Map<String, Object>) m : new LinkedHashMap<>(); }

    private static void putIf(Map<String, Object> m, String k, String v) {
        if (v == null) return;
        if (v.isBlank()) m.remove(k); else m.put(k, v.trim());
    }

    private static Object pick(String v, Object current) { return v == null ? current : v.isBlank() ? null : v.trim(); }

    private static List<String> changed(CustomerEdit r) {
        List<String> f = new ArrayList<>();
        if (r.fullName() != null) f.add("fullName");
        if (r.mobile() != null) f.add("mobile");
        if (r.email() != null) f.add("email");
        if (r.kraPin() != null) f.add("kraPin");
        if (r.address() != null) f.add("address");
        if (r.occupation() != null) f.add("occupation");
        if (r.employer() != null) f.add("employer");
        if (r.sourceOfFunds() != null) f.add("sourceOfFunds");
        if (r.monthlyIncome() != null) f.add("monthlyIncome");
        if (r.nextOfKin() != null) f.add("nextOfKin");
        return f;
    }
}
