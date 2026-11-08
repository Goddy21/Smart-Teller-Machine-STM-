package com.stm.middleware.stm.cash;

import com.stm.middleware.backend.Collections;
import com.stm.middleware.backend.CoreBanking;
import com.stm.middleware.stm.accounts.Limits;
import com.stm.middleware.stm.cards.Owned;
import com.stm.middleware.stm.core.*;
import com.stm.middleware.stm.device.DeviceHealthService;
import com.stm.middleware.stm.receipts.SmsService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * Cash deposit (K-2 .. K-5). The core is posted only after the recycler has counted the notes and the customer has
 * confirmed; until then the notes sit in escrow and Cancel returns them. Only after K-4 answers 200 may the device
 * agent move the notes from escrow to the cassettes.
 */
@RestController
@RequestMapping("/stm/v1/cash/deposits")
public class DepositController {
    static final Set<Integer> KES_NOTES = Set.of(50, 100, 200, 500, 1000);

    private final CoreBanking core;
    private final Collections collections;
    private final Owned owned;
    private final Cassettes cassettes;
    private final DeviceHealthService health;
    private final SessionStore sessions;
    private final Idempotency idempotency;
    private final Refs refs;
    private final JdbcClient jdbc;
    private final Json json;
    private final StmAudit audit;

    public DepositController(CoreBanking core, Collections collections, Owned owned, Cassettes cassettes,
                             DeviceHealthService health, SessionStore sessions, Idempotency idempotency, Refs refs,
                             JdbcClient jdbc, Json json, StmAudit audit) {
        this.core = core;
        this.collections = collections;
        this.owned = owned;
        this.cassettes = cassettes;
        this.health = health;
        this.sessions = sessions;
        this.idempotency = idempotency;
        this.refs = refs;
        this.jdbc = jdbc;
        this.json = json;
        this.audit = audit;
    }

    private record Deposit(String id, String targetRef, String status, String currency, String accepted, BigDecimal total,
                           BigDecimal fee, int rejected, String transactionRef, String coreRef, BigDecimal previousBalance,
                           BigDecimal newBalance, OffsetDateTime postedAt, String depositorMobile) {}

    private Deposit load(Ctx ctx, String id, boolean lock) {
        return jdbc.sql("""
                SELECT id, target_ref, status, currency, accepted, total, fee, rejected_count, transaction_ref, core_ref,
                       previous_balance, new_balance, posted_at, depositor_mobile
                FROM cash_deposits WHERE id = :id AND session_id = :s""" + (lock ? " FOR UPDATE" : ""))
                .param("id", id).param("s", ctx.sessionId())
                .query((rs, n) -> new Deposit(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getBigDecimal(6), rs.getBigDecimal(7), rs.getInt(8), rs.getString(9),
                        rs.getString(10), rs.getBigDecimal(11), rs.getBigDecimal(12), rs.getObject(13, OffsetDateTime.class),
                        rs.getString(14)))
                .optional().orElseThrow(() -> Errors.notFound("DEPOSIT_NOT_FOUND", "Deposit not found", "Amana haikupatikana"));
    }

    // ------------------------------------------------------------------ K-2

    public record OpenRequest(@Size(max = 40) String targetRef, @Size(max = 30) String accountId,
                              @Pattern(regexp = "KES") String currency, @Size(max = 20) String depositorMobile) {}

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Map<String, Object> open(Ctx ctx, @Valid @RequestBody OpenRequest req) {
        health.requireService(ctx.terminalId(), "cashDeposit");
        cassettes.ensureSeeded(ctx.terminalId(), ctx.deviceId());
        String targetRef = req.targetRef();
        String label;
        if (targetRef != null) {
            label = jdbc.sql("SELECT account_name || ' · ' || masked FROM deposit_targets WHERE ref = :r AND session_id = :s")
                    .param("r", targetRef).param("s", ctx.sessionId()).query(String.class).optional()
                    .orElseThrow(() -> Errors.notFound("TARGET_NOT_FOUND", "Check the account first (K-1)", "Hakiki akaunti kwanza"));
        } else if (req.accountId() != null) {
            // Card flow (E2 "Insert my card"): the account comes from S-2.
            CoreBanking.Account a = owned.account(ctx, req.accountId());
            String name = core.customer(a.customerId()).orElseThrow().fullName();
            targetRef = Ids.next("tg");
            label = name + " · " + Owned.accountLabel(a);
            jdbc.sql("""
                    INSERT INTO deposit_targets (ref, session_id, type, account_id, account_name, masked)
                    VALUES (:r, :s, 'ACCOUNT', :a, :n, :m)""")
                    .param("r", targetRef).param("s", ctx.sessionId()).param("a", a.id()).param("n", name)
                    .param("m", Owned.accountLabel(a)).update();
        } else {
            throw Errors.validation("targetRef", "targetRef (K-1) or accountId (card + PIN) is required");
        }
        String mobile = req.depositorMobile() == null || req.depositorMobile().isBlank() ? null
                : SmsService.normalise("depositorMobile", req.depositorMobile());
        String id = Ids.next("dp");
        OffsetDateTime expires = OffsetDateTime.now().plusMinutes(5);
        jdbc.sql("""
                INSERT INTO cash_deposits (id, session_id, terminal_id, target_ref, target_label, currency, depositor_mobile,
                                           status, expires_at)
                VALUES (:id, :s, :t, :tr, :l, 'KES', :m, 'AWAITING_CASH', :e)""")
                .param("id", id).param("s", ctx.sessionId()).param("t", ctx.terminalId()).param("tr", targetRef)
                .param("l", label).param("m", mobile).param("e", expires).update();
        sessions.setFlow(ctx.sessionId(), "CASH_DEPOSIT");
        audit.session(ctx, "DEPOSIT_OPENED", "depositId", id, "targetRef", targetRef);
        return Map.of("depositId", id, "status", "AWAITING_CASH", "expiresAt", expires);
    }

    // ------------------------------------------------------------------ K-3

    public record Notes(@NotBlank String denomination, @NotNull @Min(0) Integer count) {}

    public record Rejected(@Min(0) Integer count, List<String> reasons) {}

    public record CountRequest(@NotNull List<@Valid Notes> accepted, @Valid Rejected rejected,
                               @Min(0) Integer suspectedCounterfeit, Boolean escrowHeld) {}

    @PutMapping("/{depositId}/count")
    @Transactional
    public Map<String, Object> count(Ctx ctx, @PathVariable String depositId, @Valid @RequestBody CountRequest req) {
        Deposit d = load(ctx, depositId, true);
        if (!Set.of("AWAITING_CASH", "COUNTED").contains(d.status())) {
            throw Errors.wrongState("This deposit is " + d.status().toLowerCase() + ".").detail("status", d.status());
        }
        int notes = 0;
        BigDecimal total = BigDecimal.ZERO;
        List<Map<String, Object>> breakdown = new ArrayList<>();
        List<Cassettes.Note> accepted = new ArrayList<>();
        for (Notes n : req.accepted()) {
            int denom;
            try {
                denom = Integer.parseInt(n.denomination());
            } catch (NumberFormatException e) {
                throw Errors.validation("accepted.denomination", "Unknown denomination " + n.denomination());
            }
            if (!KES_NOTES.contains(denom)) throw Errors.validation("accepted.denomination", "Not a KES note: " + denom);
            if (n.count() == 0) continue;
            notes += n.count();
            BigDecimal amount = BigDecimal.valueOf((long) denom * n.count());
            total = total.add(amount);
            breakdown.add(Map.of("denomination", String.valueOf(denom), "count", n.count(), "amount", amount.setScale(2).toPlainString()));
            accepted.add(new Cassettes.Note(String.valueOf(denom), n.count()));
        }
        if (notes > Limits.DEPOSIT_MAX_NOTES || total.compareTo(Limits.DEPOSIT_MAX_AMOUNT) > 0) {
            throw Errors.depositLimitExceeded(Limits.DEPOSIT_MAX_NOTES, Money.kes(Limits.DEPOSIT_MAX_AMOUNT));
        }
        BigDecimal fee = jdbc.sql("SELECT fee FROM tariffs WHERE code = 'CASH_DEP_STM'").query(BigDecimal.class)
                .optional().orElse(BigDecimal.ZERO);
        int rejected = req.rejected() == null || req.rejected().count() == null ? 0 : req.rejected().count();
        int counterfeit = req.suspectedCounterfeit() == null ? 0 : req.suspectedCounterfeit();
        jdbc.sql("""
                UPDATE cash_deposits SET status = 'COUNTED', accepted = :a, total = :t, fee = :f, rejected_count = :r,
                       rejected_reasons = :rr, suspected_counterfeit = :c, updated_at = now() WHERE id = :id""")
                .param("a", json.write(accepted)).param("t", total).param("f", fee).param("r", rejected)
                .param("rr", json.write(req.rejected() == null ? List.of() : req.rejected().reasons()))
                .param("c", counterfeit).param("id", depositId).update();
        audit.session(ctx, "DEPOSIT_COUNTED", "depositId", depositId, "total", total.toPlainString(), "notes", notes,
                "returned", rejected, "suspectedCounterfeit", counterfeit);
        if (counterfeit > 0) {
            // Kept by the recycler; the bank files the CBK counterfeit report from this event.
            audit.session(ctx, "COUNTERFEIT_SUSPECTED", "depositId", depositId, "notes", counterfeit, "currency", "KES");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("depositId", depositId);
        out.put("status", "COUNTED");
        out.put("total", Money.kes(total));
        out.put("breakdown", breakdown);
        out.put("fee", Money.kes(fee));
        out.put("returnedNotes", rejected);
        out.put("suspectedCounterfeit", counterfeit);
        return out;
    }

    // ------------------------------------------------------------------ K-4

    public record CommitRequest(@NotNull @Valid Money confirmedTotal) {}

    @PostMapping("/{depositId}/commit")
    public ResponseEntity<Object> commit(Ctx ctx, @PathVariable String depositId, @Valid @RequestBody CommitRequest req) {
        return idempotency.run(ctx, "K-4:" + depositId, req, HttpStatus.OK, () -> doCommit(ctx, depositId, req));
    }

    private Map<String, Object> doCommit(Ctx ctx, String depositId, CommitRequest req) {
        Deposit d = load(ctx, depositId, true);
        if ("CREDITED".equals(d.status())) return credited(d);
        if (!"COUNTED".equals(d.status())) throw Errors.wrongState("Count the notes first (K-3).").detail("status", d.status());
        if (d.total().signum() <= 0) throw Errors.validation("confirmedTotal", "No notes were accepted");
        if (req.confirmedTotal().value().compareTo(d.total()) != 0 || !"KES".equals(req.confirmedTotal().currency())) {
            throw Errors.wrongState("The confirmed total does not match the counted total.").detail("total", Money.kes(d.total()));
        }
        record Target(String type, String accountId, String paybill, String reference) {}
        Target t = jdbc.sql("SELECT type, account_id, paybill, account_reference FROM deposit_targets WHERE ref = :r")
                .param("r", d.targetRef())
                .query((rs, n) -> new Target(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4))).single();
        String txRef = refs.next("DEP");
        String gl = Cassettes.cashGl("KES", ctx.deviceId());
        BigDecimal net = d.total().subtract(d.fee());
        String coreRef;
        BigDecimal prev = null, after = null;
        if ("ACCOUNT".equals(t.type())) {
            CoreBanking.Posting p = core.credit(t.accountId(), net, "KES", "Cash deposit " + txRef + " " + ctx.deviceId(), gl);
            coreRef = p.coreRef();
            prev = p.previousBalance();
            after = p.balanceAfter();
        } else {
            Collections.Biller b = collections.paybill(t.paybill(), t.reference()).orElseThrow();
            coreRef = core.transferGl(gl, b.settlementGl(), net, "KES", "Paybill " + t.paybill() + " " + t.reference() + " " + txRef);
        }
        cassettes.add(ctx.terminalId(), "KES", json.read(d.accepted(), new com.fasterxml.jackson.core.type.TypeReference<List<Cassettes.Note>>() {}));
        jdbc.sql("""
                UPDATE cash_deposits SET status = 'CREDITED', transaction_ref = :tr, core_ref = :cr, previous_balance = :p,
                       new_balance = :n, posted_at = now(), updated_at = now() WHERE id = :id""")
                .param("tr", txRef).param("cr", coreRef).param("p", prev).param("n", after).param("id", depositId).update();
        audit.session(ctx, "DEPOSIT_CREDITED", "depositId", depositId, "transactionRef", txRef, "coreRef", coreRef,
                "amount", net.toPlainString(), "target", t.type());
        return credited(load(ctx, depositId, false));
    }

    private Map<String, Object> credited(Deposit d) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("depositId", d.id());
        out.put("status", "CREDITED");
        out.put("transactionRef", d.transactionRef());
        out.put("coreRef", d.coreRef());
        out.put("amount", Money.kes(d.total().subtract(d.fee())));
        if (d.previousBalance() != null) out.put("previousBalance", Money.kes(d.previousBalance()));
        if (d.newBalance() != null) out.put("newBalance", Money.kes(d.newBalance()));
        out.put("postedAt", d.postedAt());
        return out;
    }

    // ------------------------------------------------------------------ K-5

    public record CancelRequest(@Size(max = 40) String reason) {}

    @PostMapping("/{depositId}/cancel")
    @AllowEndedSession
    @Transactional
    public Map<String, Object> cancel(Ctx ctx, @PathVariable String depositId, @RequestBody(required = false) CancelRequest req) {
        Deposit d = load(ctx, depositId, true);
        if ("CREDITED".equals(d.status())) {
            throw Errors.of(HttpStatus.CONFLICT, "DEPOSIT_ALREADY_CREDITED",
                    "This deposit is already credited; the notes cannot be returned.",
                    "Amana hii tayari imewekwa; noti haziwezi kurudishwa.", false).detail("transactionRef", d.transactionRef());
        }
        if ("CANCELLED".equals(d.status())) return Map.of("depositId", depositId, "status", "CANCELLED", "returnCash", false);
        String reason = req == null || req.reason() == null ? "CUSTOMER_CANCELLED" : req.reason();
        // Counted notes are in escrow; uncounted ones may still be in the shutter. Either way the device gives them back.
        boolean notesInEscrow = true;
        jdbc.sql("UPDATE cash_deposits SET status = 'CANCELLED', cancel_reason = :r, updated_at = now() WHERE id = :id")
                .param("r", reason).param("id", depositId).update();
        audit.session(ctx, "DEPOSIT_CANCELLED", "depositId", depositId, "reason", reason, "returnCash", notesInEscrow);
        return Map.of("depositId", depositId, "status", "CANCELLED", "returnCash", notesInEscrow);
    }
}
