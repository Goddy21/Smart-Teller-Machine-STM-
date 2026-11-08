package com.stm.middleware.stm.cards;

import com.stm.middleware.backend.CardSystem;
import com.stm.middleware.backend.CoreBanking;
import com.stm.middleware.stm.core.*;
import com.stm.middleware.stm.device.DeviceHealthService;
import com.stm.middleware.stm.receipts.SmsService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * Fee quote (C-4) and the card itself: order + fee (C-5), printing progress (C-6), hand-over (C-7), PIN (C-8),
 * activation (C-9) and blocking (C-10). Account opening reuses C-5 .. C-9 with the account from B-12.
 */
@RestController
@RequestMapping("/stm/v1")
public class CardController {
    static final List<String> STEPS = List.of("PICK_BLANK", "PRINT_NAME", "PROGRAM_EMV_CHIP", "QUALITY_CHECK");
    static final int STEP_SECONDS = 3;   // simulated printer pace when the device agent does not report steps

    private final Owned owned;
    private final CoreBanking core;
    private final CardSystem cms;
    private final DeviceHealthService health;
    private final SessionStore sessions;
    private final Idempotency idempotency;
    private final Refs refs;
    private final SmsService sms;
    private final JdbcClient jdbc;
    private final Json json;
    private final StmAudit audit;

    public CardController(Owned owned, CoreBanking core, CardSystem cms, DeviceHealthService health, SessionStore sessions,
                          Idempotency idempotency, Refs refs, SmsService sms, JdbcClient jdbc, Json json, StmAudit audit) {
        this.owned = owned;
        this.core = core;
        this.cms = cms;
        this.health = health;
        this.sessions = sessions;
        this.idempotency = idempotency;
        this.refs = refs;
        this.sms = sms;
        this.jdbc = jdbc;
        this.json = json;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ C-4

    public record QuoteRequest(@NotBlank @Pattern(regexp = "CARD_ISSUE|CARD_REPLACEMENT") String service,
                               @NotBlank @Pattern(regexp = "CLASSIC|GOLD") String cardProduct,
                               @NotBlank String debitAccountId) {}

    @PostMapping("/fees/quote")
    @Transactional
    public Map<String, Object> quote(Ctx ctx, @Valid @RequestBody QuoteRequest req) {
        CoreBanking.Account a = owned.account(ctx, req.debitAccountId());
        String tariff = "CARD_ISSUE_" + req.cardProduct();
        List<Map<String, Object>> lines = jdbc.sql("SELECT label, amount FROM tariff_lines WHERE tariff_code = :t ORDER BY seq")
                .param("t", tariff)
                .query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("label", rs.getString(1));
                    m.put("amount", rs.getBigDecimal(2).setScale(2).toPlainString());
                    return m;
                }).list();
        record T(BigDecimal fee, String currency, boolean excise) {}
        T t = jdbc.sql("SELECT fee, currency, includes_excise FROM tariffs WHERE code = :c").param("c", tariff)
                .query((rs, n) -> new T(rs.getBigDecimal(1), rs.getString(2), rs.getBoolean(3))).single();
        boolean sufficient = a.available().compareTo(t.fee()) >= 0;
        String id = Ids.next("fq");
        OffsetDateTime expires = OffsetDateTime.now().plusMinutes(10);
        jdbc.sql("""
                INSERT INTO fee_quotes (id, session_id, customer_id, service, card_product, debit_account_id, lines, total,
                                        currency, includes_excise, sufficient_funds, expires_at)
                VALUES (:id, :s, :c, :sv, :p, :a, :l, :t, :cur, :e, :suf, :exp)""")
                .param("id", id).param("s", ctx.sessionId()).param("c", ctx.customerId()).param("sv", req.service())
                .param("p", req.cardProduct()).param("a", a.id()).param("l", json.write(lines)).param("t", t.fee())
                .param("cur", t.currency()).param("e", t.excise()).param("suf", sufficient).param("exp", expires).update();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("quoteId", id);
        out.put("lines", lines);
        out.put("total", Money.of(t.fee(), t.currency()));
        out.put("includesExcise", t.excise());
        out.put("sufficientFunds", sufficient);
        if (!sufficient) out.put("available", Money.of(a.available(), a.currency()));
        out.put("expiresAt", expires);
        return out;
    }

    // ------------------------------------------------------------------ C-5

    public record OrderRequest(@NotBlank @Pattern(regexp = "LINK_AND_ISSUE|REPLACEMENT|NEW_CARD_SETUP") String service,
                               String accountId, @Pattern(regexp = "CLASSIC|GOLD") String cardProduct,
                               @Size(max = 26) @Pattern(regexp = "[A-Z .'-]*") String nameOnCard,
                               String feeQuoteId, Boolean customerAuthorised, String replacesCardId,
                               String cardId, String applicationId) {}

    @PostMapping("/cards/orders")
    public ResponseEntity<Object> order(Ctx ctx, @Valid @RequestBody OrderRequest req) {
        return idempotency.run(ctx, "C-5", req, HttpStatus.CREATED, () -> doOrder(ctx, req));
    }

    private Map<String, Object> doOrder(Ctx ctx, OrderRequest req) {
        String customerId = ctx.customerId();
        if ("NEW_CARD_SETUP".equals(req.service())) return newCardSetup(ctx, req);
        health.requireService(ctx.terminalId(), "cardServices");
        if (req.accountId() == null) throw Errors.validation("accountId", "accountId is required");
        if (req.cardProduct() == null) throw Errors.validation("cardProduct", "cardProduct is required");
        CoreBanking.Account account = owned.account(ctx, req.accountId());
        CoreBanking.Customer customer = core.customer(customerId).orElseThrow();

        // Account opening: the first card for the new account is free when the product says so.
        record App(String id, String accountId, String nameOnCard, boolean waive) {}
        Optional<App> app = jdbc.sql("""
                SELECT a.id, a.account_id, a.name_on_card, p.waive_card_fee FROM applications a
                JOIN products p ON p.code = a.product_code
                WHERE a.session_id = :s AND a.customer_id = :c AND a.status = 'ACCOUNT_OPENED'
                  AND (CAST(:app AS VARCHAR) IS NULL OR a.id = :app)
                ORDER BY a.updated_at DESC LIMIT 1""")
                .param("s", ctx.sessionId()).param("c", customerId).param("app", req.applicationId())
                .query((rs, n) -> new App(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBoolean(4))).optional();
        boolean waived = app.isPresent() && app.get().accountId().equals(account.id()) && app.get().waive();

        if ("REPLACEMENT".equals(req.service())) {
            if (req.replacesCardId() == null) throw Errors.validation("replacesCardId", "replacesCardId is required");
            CardSystem.Card old = owned.card(ctx, req.replacesCardId());
            if (!"BLOCKED".equals(old.status()) && !"EXPIRED".equals(old.status()) && !"PIN_LOCKED".equals(old.status())) {
                throw Errors.wrongState("Block the old card first (C-10).").detail("cardStatus", old.status());
            }
        } else {
            boolean hasCard = cms.cards(customerId).stream().anyMatch(c -> c.accountId().equals(account.id())
                    && Set.of("ACTIVE", "INACTIVE_PIN_REQUIRED").contains(c.status()));
            if (!account.cardEligible()) {
                throw Errors.of(HttpStatus.UNPROCESSABLE_ENTITY, "ACCOUNT_NOT_ELIGIBLE", "Cards aren't available for this account type.",
                        "Kadi hazipatikani kwa aina hii ya akaunti.", false);
            }
            if (hasCard) throw Errors.wrongState("This account already has a card. Choose Card Replacement instead.");
        }

        BigDecimal fee = BigDecimal.ZERO;
        String feeRef = null;
        if (!waived) {
            if (req.feeQuoteId() == null) throw Errors.validation("feeQuoteId", "feeQuoteId from C-4 is required");
            if (!Boolean.TRUE.equals(req.customerAuthorised())) {
                throw Errors.validation("customerAuthorised", "The customer must authorise the fee");
            }
            record Q(String customer, String account, String product, BigDecimal total, OffsetDateTime expires, OffsetDateTime used) {}
            Q q = jdbc.sql("""
                    SELECT customer_id, debit_account_id, card_product, total, expires_at, used_at FROM fee_quotes
                    WHERE id = :id AND session_id = :s FOR UPDATE""")
                    .param("id", req.feeQuoteId()).param("s", ctx.sessionId())
                    .query((rs, n) -> new Q(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBigDecimal(4),
                            rs.getObject(5, OffsetDateTime.class), rs.getObject(6, OffsetDateTime.class)))
                    .optional().orElseThrow(() -> Errors.notFound("QUOTE_NOT_FOUND", "Quote not found", "Nukuu haikupatikana"));
            if (!q.customer().equals(customerId) || !q.account().equals(account.id()) || !q.product().equals(req.cardProduct())) {
                throw Errors.validation("feeQuoteId", "The quote was made for a different account or card");
            }
            if (q.used() != null || q.expires().isBefore(OffsetDateTime.now())) throw Errors.quoteExpired();
            jdbc.sql("UPDATE fee_quotes SET used_at = now() WHERE id = :id").param("id", req.feeQuoteId()).update();
            fee = q.total();
            feeRef = core.debit(account.id(), fee, account.currency(),
                    "Card issuance fee " + req.cardProduct() + " " + ctx.deviceId(), "GL-FEES-CARD").coreRef();
        }

        String nameOnCard = req.nameOnCard() != null && !req.nameOnCard().isBlank() ? req.nameOnCard()
                : app.map(App::nameOnCard).orElse(com.stm.middleware.stm.support.CardNames.options(
                        customer.fullName().substring(0, customer.fullName().lastIndexOf(' ')),
                        customer.fullName().substring(customer.fullName().lastIndexOf(' ') + 1)).get(0));
        CardSystem.Card card = cms.create(customerId, account.id(), req.cardProduct(), nameOnCard);
        String payload = cms.personalisationPayload(card, nameOnCard);
        String orderId = Ids.next("co");
        String jobId = Ids.next("pj");
        String ref = refs.next("CRD");
        List<Map<String, String>> steps = new ArrayList<>();
        STEPS.forEach(s -> steps.add(new LinkedHashMap<>(Map.of("code", s, "status", "PENDING"))));
        jdbc.sql("""
                INSERT INTO card_orders (id, session_id, terminal_id, customer_id, application_id, service, account_id,
                    card_product, name_on_card, fee_quote_id, fee_amount, fee_posted, fee_ref, replaces_card_id, card_id,
                    masked_pan, job_id, status, steps, reference)
                VALUES (:id, :s, :t, :c, :app, :sv, :a, :p, :n, :q, :fee, :posted, :ref, :rep, :card, :pan, :job,
                    'PERSONALISING', :steps, :r)""")
                .param("id", orderId).param("s", ctx.sessionId()).param("t", ctx.terminalId()).param("c", customerId)
                .param("app", app.map(App::id).orElse(null)).param("sv", req.service()).param("a", account.id())
                .param("p", req.cardProduct()).param("n", nameOnCard).param("q", req.feeQuoteId()).param("fee", fee)
                .param("posted", feeRef != null).param("ref", feeRef).param("rep", req.replacesCardId())
                .param("card", card.id()).param("pan", card.maskedPan()).param("job", jobId)
                .param("steps", json.write(steps)).param("r", ref).update();
        jdbc.sql("UPDATE terminal_telemetry SET card_stock = greatest(card_stock - 1, 0) WHERE terminal_id = :t AND card_stock IS NOT NULL")
                .param("t", ctx.terminalId()).update();
        sessions.setFlow(ctx.sessionId(), app.isPresent() ? "ACCOUNT_OPENING" : "CARD_SERVICES");
        audit.session(ctx, "CARD_ORDERED", "orderId", orderId, "service", req.service(), "cardId", card.id(),
                "accountId", account.id(), "product", req.cardProduct(), "fee", fee.toPlainString(), "feeRef", feeRef,
                "waived", waived, "replacesCardId", req.replacesCardId(), "reference", ref);
        Map<String, Object> feeOut = new LinkedHashMap<>();
        feeOut.put("posted", feeRef != null);
        feeOut.put("transactionRef", feeRef);
        feeOut.put("amount", fee.setScale(2).toPlainString());
        feeOut.put("currency", account.currency());
        if (waived) feeOut.put("waived", true);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("orderId", orderId);
        out.put("cardId", card.id());
        out.put("maskedPan", card.maskedPan());
        out.put("fee", feeOut);
        out.put("personalisation", Map.of("jobId", jobId, "payloadForPrinter", payload));
        out.put("reference", ref);
        return out;
    }

    /** A card printed elsewhere: no printing, no fee; the kiosk goes straight to C-8 and C-9. */
    private Map<String, Object> newCardSetup(Ctx ctx, OrderRequest req) {
        if (req.cardId() == null) throw Errors.validation("cardId", "cardId (from C-2) is required for NEW_CARD_SETUP");
        CardSystem.Card card = owned.card(ctx, req.cardId());
        if (!"INACTIVE_PIN_REQUIRED".equals(card.status())) throw Errors.wrongState("This card is already set up.");
        String orderId = Ids.next("co");
        String ref = refs.next("CRD");
        jdbc.sql("""
                INSERT INTO card_orders (id, session_id, terminal_id, customer_id, service, account_id, card_product,
                    name_on_card, card_id, masked_pan, status, steps, outcome, reference)
                VALUES (:id, :s, :t, :c, 'NEW_CARD_SETUP', :a, :p, '-', :card, :pan, 'DISPENSED', '[]', 'TAKEN', :r)""")
                .param("id", orderId).param("s", ctx.sessionId()).param("t", ctx.terminalId()).param("c", card.customerId())
                .param("a", card.accountId()).param("p", card.product()).param("card", card.id())
                .param("pan", card.maskedPan()).param("r", ref).update();
        sessions.setFlow(ctx.sessionId(), "CARD_SERVICES");
        audit.session(ctx, "CARD_SETUP_STARTED", "orderId", orderId, "cardId", card.id());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("orderId", orderId);
        out.put("cardId", card.id());
        out.put("maskedPan", card.maskedPan());
        out.put("fee", Map.of("posted", false, "amount", "0.00", "currency", "KES"));
        out.put("personalisation", null);
        out.put("reference", ref);
        return out;
    }

    // ------------------------------------------------------------------ C-6 (+ device agent step reports)

    private record Order(String id, String sessionId, String status, String steps, OffsetDateTime createdAt,
                         String cardId, String feeRef, String reference, String accountId) {}

    private Order order(Ctx ctx, String orderId) {
        return jdbc.sql("""
                SELECT id, session_id, status, steps, created_at, card_id, fee_ref, reference, account_id
                FROM card_orders WHERE id = :id AND session_id = :s""")
                .param("id", orderId).param("s", ctx.sessionId())
                .query((rs, n) -> new Order(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getObject(5, OffsetDateTime.class), rs.getString(6), rs.getString(7), rs.getString(8),
                        rs.getString(9)))
                .optional().orElseThrow(() -> Errors.notFound("ORDER_NOT_FOUND", "Card order not found", "Agizo la kadi halikupatikana"));
    }

    @GetMapping("/cards/orders/{orderId}")
    @AllowEndedSession
    public Map<String, Object> progress(Ctx ctx, @PathVariable String orderId) {
        Order o = order(ctx, orderId);
        List<Map<String, Object>> steps = json.read(o.steps(), Json.LIST_OF_MAPS);
        String status = o.status();
        boolean reported = steps.stream().anyMatch(s -> !"PENDING".equals(s.get("status")));
        if ("PERSONALISING".equals(status) && !reported) {
            // No device agent reports: simulate the printer's pace.
            long elapsed = Duration.between(o.createdAt(), OffsetDateTime.now()).toSeconds();
            int done = (int) Math.min(STEPS.size(), elapsed / STEP_SECONDS);
            for (int i = 0; i < steps.size(); i++) {
                steps.get(i).put("status", i < done ? "DONE" : i == done ? "IN_PROGRESS" : "PENDING");
            }
            if (done >= STEPS.size()) status = "READY";
        } else if ("PERSONALISING".equals(status) && steps.stream().allMatch(s -> "DONE".equals(s.get("status")))) {
            status = "READY";
        }
        if (!status.equals(o.status())) {
            jdbc.sql("UPDATE card_orders SET status = :st, steps = :steps, updated_at = now() WHERE id = :id")
                    .param("st", status).param("steps", json.write(steps)).param("id", orderId).update();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("orderId", orderId);
        out.put("status", status);
        out.put("steps", steps);
        return out;
    }

    public record StepReport(@NotBlank @Pattern(regexp = "PICK_BLANK|PRINT_NAME|PROGRAM_EMV_CHIP|QUALITY_CHECK") String code,
                             @NotBlank @Pattern(regexp = "IN_PROGRESS|DONE|FAILED") String status) {}

    /** Device agent: one printer step finished (or failed). Not in the spec's 58; optional for real printers. */
    @PutMapping("/cards/orders/{orderId}/steps")
    @AllowEndedSession
    @Transactional
    public Map<String, Object> step(Ctx ctx, @PathVariable String orderId, @Valid @RequestBody StepReport req) {
        Order o = order(ctx, orderId);
        List<Map<String, Object>> steps = json.read(o.steps(), Json.LIST_OF_MAPS);
        steps.stream().filter(s -> req.code().equals(s.get("code"))).forEach(s -> s.put("status", req.status()));
        jdbc.sql("UPDATE card_orders SET steps = :steps, updated_at = now() WHERE id = :id")
                .param("steps", json.write(steps)).param("id", orderId).update();
        return progress(ctx, orderId);
    }

    // ------------------------------------------------------------------ C-7

    public record DispenseRequest(@NotBlank @Pattern(regexp = "TAKEN|RETAINED|SPOILED") String outcome,
                                  @Pattern(regexp = "PASS|FAIL") String qualityCheck, @Size(max = 30) String cardSerial) {}

    @PostMapping("/cards/orders/{orderId}/dispense")
    @AllowEndedSession
    @Transactional
    public Map<String, Object> dispense(Ctx ctx, @PathVariable String orderId, @Valid @RequestBody DispenseRequest req) {
        Order o = order(ctx, orderId);
        if (Set.of("DISPENSED", "RETAINED", "SPOILED").contains(o.status())) {
            return Map.of("orderId", orderId, "status", o.status(),
                    "cardStatus", cms.card(o.cardId()).map(CardSystem.Card::status).orElse("UNKNOWN"));
        }
        String status = switch (req.outcome()) {
            case "TAKEN" -> "DISPENSED";
            case "RETAINED" -> "RETAINED";
            default -> "SPOILED";
        };
        String refundRef = null;
        if (!"DISPENSED".equals(status)) {
            cms.destroy(o.cardId(), req.outcome());
            if ("SPOILED".equals(status) && o.feeRef() != null) {
                refundRef = core.reverse(o.feeRef(), "Refund: card spoiled at " + ctx.deviceId());
            }
        }
        jdbc.sql("""
                UPDATE card_orders SET status = :st, outcome = :o, quality_check = :q, card_serial = :cs, updated_at = now()
                WHERE id = :id""")
                .param("st", status).param("o", req.outcome()).param("q", req.qualityCheck()).param("cs", req.cardSerial())
                .param("id", orderId).update();
        audit.session(ctx, "CARD_" + status, "orderId", orderId, "cardId", o.cardId(), "cardSerial", req.cardSerial(),
                "qualityCheck", req.qualityCheck(), "refundRef", refundRef);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("orderId", orderId);
        out.put("status", status);
        out.put("cardStatus", cms.card(o.cardId()).map(CardSystem.Card::status).orElse("UNKNOWN"));
        if (refundRef != null) out.put("refundRef", refundRef);
        return out;
    }

    // ------------------------------------------------------------------ C-8 .. C-10

    public record PinRequest(@NotBlank @Pattern(regexp = "[0-9A-Fa-f]{16,32}") String pinBlock,
                             @Size(max = 40) String format, @Size(max = 40) String keySerialNumber,
                             Boolean confirmed, Map<String, Boolean> policyChecks) {}

    @PostMapping("/cards/{cardId}/pin")
    @Transactional
    public Map<String, Object> pin(Ctx ctx, @PathVariable String cardId, @Valid @RequestBody PinRequest req) {
        CardSystem.Card card = owned.card(ctx, cardId);
        if (!Boolean.TRUE.equals(req.confirmed())) {
            throw Errors.of(HttpStatus.UNPROCESSABLE_ENTITY, "PIN_NOT_CONFIRMED", "The two PIN entries did not match.",
                    "PIN mbili hazikulingana.", false);
        }
        if (!Set.of("INACTIVE_PIN_REQUIRED", "ACTIVE").contains(card.status())) {
            throw Errors.wrongState("A PIN cannot be set on this card.").detail("cardStatus", card.status());
        }
        boolean dispensed = jdbc.sql("""
                SELECT count(*) FROM card_orders WHERE card_id = :c AND (status = 'DISPENSED' OR service = 'NEW_CARD_SETUP')""")
                .param("c", cardId).query(Long.class).single() > 0 || "ACTIVE".equals(card.status());
        if (!dispensed) throw Errors.wrongState("Take the card first (C-7).");
        String rule = cms.pinPolicyViolation(req.pinBlock(), req.format(), req.keySerialNumber());
        if (rule != null) throw Errors.pinPolicy(rule);
        cms.setPin(cardId, req.pinBlock().toUpperCase(), req.format(), req.keySerialNumber());
        audit.session(ctx, "CARD_PIN_SET", "cardId", cardId);
        return Map.of("cardId", cardId, "pinSet", true);
    }

    public record ActivateRequest(@Pattern(regexp = "STM") String channel) {}

    @PostMapping("/cards/{cardId}/activate")
    @Transactional
    public Map<String, Object> activate(Ctx ctx, @PathVariable String cardId, @RequestBody(required = false) ActivateRequest req) {
        owned.card(ctx, cardId);
        CardSystem.Card card = cms.activate(cardId);
        CoreBanking.Account a = core.account(card.accountId()).orElseThrow();
        String ref = jdbc.sql("SELECT reference FROM card_orders WHERE card_id = :c ORDER BY created_at DESC LIMIT 1")
                .param("c", cardId).query(String.class).optional().orElseGet(() -> refs.next("CRD"));
        String mobile = core.customer(card.customerId()).map(CoreBanking.Customer::mobile).orElse(null);
        if (mobile != null) {
            sms.send(ctx.sessionId(), "CARD_ACTIVATED", mobile, ctx.lang(),
                    Map.of("card", card.maskedPan(), "account", Owned.accountLabel(a), "ref", ref));
        }
        audit.session(ctx, "CARD_ACTIVATED", "cardId", cardId, "reference", ref);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cardId", cardId);
        out.put("status", card.status());
        out.put("maskedPan", card.maskedPan());
        out.put("linkedAccount", Owned.accountLabel(a));
        out.put("limits", Map.of("atmDaily", Money.kes(40_000), "posDaily", Money.kes(150_000)));
        out.put("reference", ref);
        return out;
    }

    public record BlockRequest(@NotBlank @Pattern(regexp = "LOST|STOLEN|DAMAGED|EXPIRED") String reason) {}

    @PostMapping("/cards/{cardId}/block")
    @Transactional
    public Map<String, Object> block(Ctx ctx, @PathVariable String cardId, @Valid @RequestBody BlockRequest req) {
        owned.card(ctx, cardId);
        CardSystem.Card card = cms.block(cardId, req.reason());
        String mobile = core.customer(card.customerId()).map(CoreBanking.Customer::mobile).orElse(null);
        if (mobile != null) {
            sms.send(ctx.sessionId(), "CARD_BLOCKED", mobile, ctx.lang(), Map.of("card", card.maskedPan(), "reason", req.reason()));
        }
        audit.session(ctx, "CARD_BLOCKED", "cardId", cardId, "reason", req.reason());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cardId", cardId);
        out.put("status", card.status());
        out.put("blockedAt", OffsetDateTime.now());
        out.put("reason", req.reason());
        return out;
    }
}
