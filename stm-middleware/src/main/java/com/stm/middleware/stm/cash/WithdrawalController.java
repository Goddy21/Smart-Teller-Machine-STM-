package com.stm.middleware.stm.cash;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.stm.middleware.backend.CoreBanking;
import com.stm.middleware.stm.accounts.Limits;
import com.stm.middleware.stm.cards.Owned;
import com.stm.middleware.stm.core.*;
import com.stm.middleware.stm.device.DeviceHealthService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * Cash withdrawal (W-2 .. W-4), also used for foreign notes sold to the customer (E13: W-2 with fxQuoteId)
 * and for the KES paid out by a forex exchange (X-5 with destination CASH).
 * Cash leaves the machine only after core has approved; if the notes are not taken, W-4 reverses the posting.
 */
@RestController
@RequestMapping("/stm/v1/cash/withdrawals")
public class WithdrawalController {
    static final int PRESENT_TIMEOUT_SEC = 30;
    private static final TypeReference<List<Cassettes.Note>> NOTES = new TypeReference<>() {};

    private final CoreBanking core;
    private final Owned owned;
    private final Cassettes cassettes;
    private final Limits limits;
    private final DeviceHealthService health;
    private final SessionStore sessions;
    private final Idempotency idempotency;
    private final Refs refs;
    private final JdbcClient jdbc;
    private final Json json;
    private final StmAudit audit;
    private final WithdrawalStore store;
    private final boolean creditImmediately;

    public WithdrawalController(CoreBanking core, Owned owned, Cassettes cassettes, Limits limits,
                                DeviceHealthService health, SessionStore sessions, Idempotency idempotency, Refs refs,
                                JdbcClient jdbc, Json json, StmAudit audit, WithdrawalStore store,
                                @Value("${stm.cash.retract-credit:IMMEDIATE}") String retractCredit) {
        this.core = core;
        this.owned = owned;
        this.cassettes = cassettes;
        this.limits = limits;
        this.health = health;
        this.sessions = sessions;
        this.idempotency = idempotency;
        this.refs = refs;
        this.jdbc = jdbc;
        this.json = json;
        this.audit = audit;
        this.store = store;
        this.creditImmediately = !"AFTER_COUNT".equalsIgnoreCase(retractCredit);
    }

    // ------------------------------------------------------------------ W-2

    public record WithdrawRequest(@NotBlank String accountId, @NotNull @Valid Money amount, String fxQuoteId,
                                  @Valid List<Cassettes.Note> proposedMix) {}

    @PostMapping
    public ResponseEntity<Object> withdraw(Ctx ctx, @Valid @RequestBody WithdrawRequest req) {
        return idempotency.run(ctx, "W-2", req, HttpStatus.CREATED, () -> doWithdraw(ctx, req));
    }

    private Map<String, Object> doWithdraw(Ctx ctx, WithdrawRequest req) {
        CoreBanking.Account a = owned.account(ctx, req.accountId());
        BigDecimal amount = req.amount().value();
        if (amount.signum() <= 0) throw Errors.validation("amount", "Enter an amount");
        cassettes.ensureSeeded(ctx.terminalId(), ctx.deviceId());
        String id = Ids.next("wd");
        String txRef = refs.next("WDL");
        List<Cassettes.Note> mix;
        List<String> coreRefs = new ArrayList<>();
        BigDecimal local;
        String source;
        String currency = req.amount().currency();
        BigDecimal balanceAfter;
        if (req.fxQuoteId() == null) {
            health.requireService(ctx.terminalId(), "cashWithdrawal");
            sessions.setFlow(ctx.sessionId(), "CASH_WITHDRAWAL");
            if (!"KES".equals(currency)) throw Errors.validation("amount.currency", "Use a forex quote (X-2) for foreign currency");
            if (amount.remainder(Limits.MULTIPLE_OF).signum() != 0) {
                throw Errors.validation("amount", "Enter a multiple of KES " + Limits.MULTIPLE_OF);
            }
            if (amount.compareTo(Limits.PER_WITHDRAWAL) > 0) throw Errors.limitExceeded(Money.kes(Limits.PER_WITHDRAWAL));
            BigDecimal daily = limits.dailyRemaining(ctx.customerId());
            if (amount.compareTo(daily) > 0) throw Errors.limitExceeded(Money.kes(daily));
            mix = cassettes.mix(ctx.terminalId(), "KES", amount, req.proposedMix());
            CoreBanking.Posting p = core.debit(a.id(), amount, "KES", "STM cash withdrawal " + txRef + " " + ctx.deviceId(),
                    Cassettes.cashGl("KES", ctx.deviceId()));
            coreRefs.add(p.coreRef());
            local = amount;
            source = "ACCOUNT";
            balanceAfter = p.balanceAfter();
        } else {
            // E13: the bank sells foreign notes, debited in KES at the locked rate.
            health.requireService(ctx.terminalId(), "forex");
            sessions.setFlow(ctx.sessionId(), "FOREX");
            record Q(String direction, String currency, BigDecimal rate, BigDecimal foreign, BigDecimal local,
                     String account, OffsetDateTime expires, OffsetDateTime used) {}
            Q q = jdbc.sql("""
                    SELECT direction, currency, rate, foreign_amount, local_amount, debit_account_id, expires_at, used_at
                    FROM fx_quotes WHERE id = :id AND session_id = :s FOR UPDATE""")
                    .param("id", req.fxQuoteId()).param("s", ctx.sessionId())
                    .query((rs, n) -> new Q(rs.getString(1), rs.getString(2), rs.getBigDecimal(3), rs.getBigDecimal(4),
                            rs.getBigDecimal(5), rs.getString(6), rs.getObject(7, OffsetDateTime.class),
                            rs.getObject(8, OffsetDateTime.class)))
                    .optional().orElseThrow(() -> Errors.notFound("QUOTE_NOT_FOUND", "Quote not found", "Nukuu haikupatikana"));
            if (!"BANK_SELLS".equals(q.direction()) || !q.currency().equals(currency) || q.foreign().compareTo(amount) != 0
                    || !a.id().equals(q.account())) {
                throw Errors.validation("fxQuoteId", "The quote does not match this withdrawal");
            }
            if (q.used() != null || q.expires().isBefore(OffsetDateTime.now())) throw Errors.quoteExpired();
            jdbc.sql("UPDATE fx_quotes SET used_at = now() WHERE id = :id").param("id", req.fxQuoteId()).update();
            mix = cassettes.mix(ctx.terminalId(), currency, amount, req.proposedMix());
            CoreBanking.Posting p = core.debit(a.id(), q.local(), "KES",
                    "FX sell " + currency + " " + amount.toPlainString() + " @" + q.rate().toPlainString() + " " + txRef,
                    "GL-FX-POSITION-KES");
            coreRefs.add(p.coreRef());
            coreRefs.add(core.transferGl("GL-FX-POSITION-" + currency, Cassettes.cashGl(currency, ctx.deviceId()), amount,
                    currency, "FX sell notes " + txRef));
            local = q.local();
            source = "FX_SELL";
            balanceAfter = p.balanceAfter();
        }
        store.insert(id, ctx, a.customerId(), a.id(), source, currency, amount, local, req.fxQuoteId(), null, mix, txRef,
                String.join(",", coreRefs), balanceAfter);
        audit.session(ctx, "WITHDRAWAL_AUTHORISED", "withdrawalId", id, "transactionRef", txRef, "coreRef", coreRefs,
                "currency", currency, "amount", amount.toPlainString(), "source", source);
        return authorised(id, txRef, coreRefs.get(0), currency, mix, balanceAfter == null ? null : Money.kes(balanceAfter));
    }

    static Map<String, Object> authorised(String id, String txRef, String coreRef, String currency, List<Cassettes.Note> mix,
                                          Money balanceAfter) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("withdrawalId", id);
        out.put("status", "AUTHORISED");
        out.put("transactionRef", txRef);
        out.put("coreRef", coreRef);
        out.put("dispense", Map.of("currency", currency, "mix", mix));
        if (balanceAfter != null) out.put("balanceAfter", balanceAfter);
        out.put("presentTimeoutSec", PRESENT_TIMEOUT_SEC);
        return out;
    }

    // ------------------------------------------------------------------ W-3 / W-4

    private record W(String id, String status, String source, String currency, BigDecimal amount, BigDecimal local,
                     String accountId, String mix, String txRef, String coreRef, String reversalRef,
                     OffsetDateTime creditBackBy, String exchangeId) {}

    private W load(Ctx ctx, String id) {
        return jdbc.sql("""
                SELECT id, status, source, currency, amount, local_amount, account_id, mix, transaction_ref, core_ref,
                       reversal_ref, credit_back_by, exchange_id
                FROM cash_withdrawals WHERE id = :id AND session_id = :s FOR UPDATE""")
                .param("id", id).param("s", ctx.sessionId())
                .query((rs, n) -> new W(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getBigDecimal(5), rs.getBigDecimal(6), rs.getString(7), rs.getString(8), rs.getString(9),
                        rs.getString(10), rs.getString(11), rs.getObject(12, OffsetDateTime.class), rs.getString(13)))
                .optional().orElseThrow(() -> Errors.notFound("WITHDRAWAL_NOT_FOUND", "Withdrawal not found", "Utoaji haukupatikana"));
    }

    public record ConfirmRequest(@Valid List<Cassettes.Note> presented, @NotNull Boolean taken) {}

    @PostMapping("/{withdrawalId}/confirm")
    @AllowEndedSession
    @Transactional
    public Map<String, Object> confirm(Ctx ctx, @PathVariable String withdrawalId, @Valid @RequestBody ConfirmRequest req) {
        W w = load(ctx, withdrawalId);
        if ("COMPLETED".equals(w.status())) return Map.of("withdrawalId", withdrawalId, "status", "COMPLETED");
        if (!"AUTHORISED".equals(w.status())) throw Errors.wrongState("This withdrawal is " + w.status().toLowerCase() + ".");
        if (!req.taken()) throw Errors.wrongState("The notes were not taken: call W-4 reverse instead.");
        List<Cassettes.Note> presented = req.presented() == null || req.presented().isEmpty()
                ? json.read(w.mix(), NOTES) : req.presented();
        cassettes.take(ctx.terminalId(), w.currency(), presented);
        jdbc.sql("UPDATE cash_withdrawals SET status = 'COMPLETED', presented = :p, updated_at = now() WHERE id = :id")
                .param("p", json.write(presented)).param("id", withdrawalId).update();
        if (w.exchangeId() != null) {
            jdbc.sql("UPDATE fx_exchanges SET status = 'COMPLETED', updated_at = now() WHERE id = :x").param("x", w.exchangeId()).update();
        }
        audit.session(ctx, "WITHDRAWAL_COMPLETED", "withdrawalId", withdrawalId, "transactionRef", w.txRef());
        return Map.of("withdrawalId", withdrawalId, "status", "COMPLETED");
    }

    public record ReverseRequest(@NotBlank @Pattern(regexp = "CASH_RETRACTED|DISPENSE_FAILED|TIMEOUT|CUSTOMER_CANCELLED") String reason,
                                 @Valid List<Cassettes.Note> presented, @Min(0) Integer retractedCount) {}

    /** Same Idempotency-Key as the W-2 it reverses (scoped separately, so it never replays W-2's answer). */
    @PostMapping("/{withdrawalId}/reverse")
    @AllowEndedSession
    public ResponseEntity<Object> reverse(Ctx ctx, @PathVariable String withdrawalId, @Valid @RequestBody ReverseRequest req) {
        return idempotency.run(ctx, "W-4", Map.of("id", withdrawalId, "req", req), HttpStatus.OK,
                () -> doReverse(ctx, withdrawalId, req));
    }

    /**
     * W-2 timed out, so the kiosk has no withdrawalId: send the W-2 Idempotency-Key and the Gateway reverses whatever
     * reached core (or answers NOTHING_TO_REVERSE).
     */
    @PostMapping("/by-idempotency-key/reverse")
    @AllowEndedSession
    public ResponseEntity<Object> reverseByKey(Ctx ctx, @Valid @RequestBody ReverseRequest req) {
        String key = ctx.idempotencyKey();
        Optional<String> body = jdbc.sql("""
                SELECT response_body FROM idempotency_records
                WHERE terminal_id = :t AND idem_key = :k AND operation = 'W-2' AND status_code = 201""")
                .param("t", ctx.terminalId()).param("k", key).query(String.class).optional();
        if (body.isEmpty()) {
            return ResponseEntity.ok(Map.of("status", "NOTHING_TO_REVERSE",
                    "message", "No withdrawal with this Idempotency-Key reached core."));
        }
        JsonNode n = json.tree(body.get());
        return reverse(ctx, n.path("withdrawalId").asText(), req);
    }

    private Map<String, Object> doReverse(Ctx ctx, String withdrawalId, ReverseRequest req) {
        W w = load(ctx, withdrawalId);
        if (Set.of("REVERSED", "PENDING_RECONCILIATION").contains(w.status())) return reversed(w);
        if (!"AUTHORISED".equals(w.status())) throw Errors.wrongState("This withdrawal is " + w.status().toLowerCase() + ".");
        List<Cassettes.Note> presented = req.presented() == null ? List.of() : req.presented();
        BigDecimal retractedValue = Cassettes.value(presented);
        if (retractedValue.signum() > 0) {
            // Presented notes went to the retract bin: they are no longer in the cassettes, so move them off the cash GL.
            cassettes.take(ctx.terminalId(), w.currency(), presented);
            core.transferGl(Cassettes.retractGl(w.currency(), ctx.deviceId()), Cassettes.cashGl(w.currency(), ctx.deviceId()),
                    retractedValue, w.currency(), "Retracted " + w.txRef());
        }
        String reversalRef = refs.next("RTR");
        String status;
        OffsetDateTime creditBy;
        if ("FX_KES_OUT".equals(w.source()) || !creditImmediately) {
            // The bank credits after counting the retract bin (or the foreign notes were already bought): back office settles.
            status = "PENDING_RECONCILIATION";
            creditBy = OffsetDateTime.now().plusHours(24);
        } else {
            for (String ref : w.coreRef().split(",")) core.reverse(ref, "Reversal " + reversalRef + " (" + req.reason() + ")");
            status = "REVERSED";
            creditBy = OffsetDateTime.now();
        }
        jdbc.sql("""
                UPDATE cash_withdrawals SET status = :st, reversal_ref = :r, reverse_reason = :rr, presented = :p,
                       retracted_count = :rc, credit_back_by = :cb, updated_at = now() WHERE id = :id""")
                .param("st", status).param("r", reversalRef).param("rr", req.reason()).param("p", json.write(presented))
                .param("rc", req.retractedCount()).param("cb", creditBy).param("id", withdrawalId).update();
        audit.session(ctx, "WITHDRAWAL_" + status, "withdrawalId", withdrawalId, "reversalRef", reversalRef,
                "reason", req.reason(), "retractedValue", retractedValue.toPlainString());
        return reversed(load(ctx, withdrawalId));
    }

    private static Map<String, Object> reversed(W w) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("withdrawalId", w.id());
        out.put("status", w.status());
        out.put("reversalRef", w.reversalRef());
        out.put("amount", Money.of(w.source().equals("ACCOUNT") ? w.amount() : w.local(), "KES"));
        out.put("creditBackBy", w.creditBackBy());
        return out;
    }

    static BigDecimal round2(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }
}
