package com.stm.middleware.stm.cash;

import com.fasterxml.jackson.core.type.TypeReference;
import com.stm.middleware.backend.CoreBanking;
import com.stm.middleware.stm.accounts.Limits;
import com.stm.middleware.stm.cards.Owned;
import com.stm.middleware.stm.core.*;
import com.stm.middleware.stm.device.DeviceHealthService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
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
 * Forex (X-1 .. X-5). BANK_BUYS: the customer inserts USD/EUR/GBP notes and gets KES (credited or as cash).
 * BANK_SELLS: the customer buys foreign notes, debited in KES (then W-2 with the quote, W-3/W-4).
 * Every exchange needs an ID or passport scan first (B-4, purpose FOREX). Quotes hold the rate for 5 minutes.
 */
@RestController
@RequestMapping("/stm/v1/fx")
public class FxController {
    static final int QUOTE_MINUTES = 5;
    static final Map<String, Set<Integer>> NOTES = Map.of(
            "USD", Set.of(1, 2, 5, 10, 20, 50, 100),
            "EUR", Set.of(5, 10, 20, 50, 100, 200, 500),
            "GBP", Set.of(5, 10, 20, 50));
    private static final TypeReference<List<Cassettes.Note>> NOTE_LIST = new TypeReference<>() {};

    private final CoreBanking core;
    private final Owned owned;
    private final Cassettes cassettes;
    private final Limits limits;
    private final DeviceHealthService health;
    private final SessionStore sessions;
    private final Idempotency idempotency;
    private final Refs refs;
    private final WithdrawalStore withdrawals;
    private final JdbcClient jdbc;
    private final Json json;
    private final StmAudit audit;

    public FxController(CoreBanking core, Owned owned, Cassettes cassettes, Limits limits, DeviceHealthService health,
                        SessionStore sessions, Idempotency idempotency, Refs refs, WithdrawalStore withdrawals,
                        JdbcClient jdbc, Json json, StmAudit audit) {
        this.core = core;
        this.owned = owned;
        this.cassettes = cassettes;
        this.limits = limits;
        this.health = health;
        this.sessions = sessions;
        this.idempotency = idempotency;
        this.refs = refs;
        this.withdrawals = withdrawals;
        this.jdbc = jdbc;
        this.json = json;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ X-1

    @GetMapping("/rates")
    @SessionOptional
    public Map<String, Object> rates(Ctx ctx, @RequestParam(defaultValue = "STM") String channel) {
        List<Map<String, Object>> rates = new ArrayList<>();
        OffsetDateTime[] asOf = {null};
        jdbc.sql("SELECT currency, symbol, buy, sell, updated_at FROM fx_rates ORDER BY currency").query(rs -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("currency", rs.getString(1));
            m.put("symbol", rs.getString(2));
            m.put("buy", rs.getBigDecimal(3).setScale(2, RoundingMode.HALF_UP).toPlainString());
            m.put("sell", rs.getBigDecimal(4).setScale(2, RoundingMode.HALF_UP).toPlainString());
            rates.add(m);
            OffsetDateTime u = rs.getObject(5, OffsetDateTime.class);
            if (asOf[0] == null || u.isAfter(asOf[0])) asOf[0] = u;
        });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("asOf", asOf[0]);
        out.put("base", "KES");
        out.put("commissionPct", "0");
        out.put("rates", rates);
        return out;
    }

    // ------------------------------------------------------------------ X-2

    public record QuoteRequest(@NotBlank @Pattern(regexp = "BANK_BUYS|BANK_SELLS") String direction,
                               @NotBlank @Pattern(regexp = "USD|EUR|GBP") String currency,
                               @NotBlank String identityVerificationId, String foreignAmount, String debitAccountId) {}

    @PostMapping("/quotes")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Map<String, Object> quote(Ctx ctx, @Valid @RequestBody QuoteRequest req) {
        health.requireService(ctx.terminalId(), "forex");
        sessions.setFlow(ctx.sessionId(), "FOREX");
        requireVerified(ctx, req.identityVerificationId());
        BigDecimal rate = jdbc.sql("SELECT " + ("BANK_BUYS".equals(req.direction()) ? "buy" : "sell") + " FROM fx_rates WHERE currency = :c")
                .param("c", req.currency()).query(BigDecimal.class).single();
        BigDecimal foreign = null, local = null;
        String account = null;
        if ("BANK_SELLS".equals(req.direction())) {
            if (req.debitAccountId() == null) throw Errors.validation("debitAccountId", "debitAccountId is required (card + PIN first)");
            CoreBanking.Account a = owned.account(ctx, req.debitAccountId());
            account = a.id();
            foreign = amount("foreignAmount", req.foreignAmount());
            if (foreign.remainder(Limits.FX_MULTIPLE_OF).signum() != 0) {
                throw Errors.validation("foreignAmount", "Enter a multiple of " + Limits.FX_MULTIPLE_OF);
            }
            BigDecimal left = Cassettes.FOREIGN_DAILY_MAX.subtract(limits.foreignBoughtToday(ctx.customerId(), req.currency()));
            if (foreign.compareTo(left) > 0) throw Errors.limitExceeded(Money.of(left.max(BigDecimal.ZERO), req.currency()));
            cassettes.ensureSeeded(ctx.terminalId(), ctx.deviceId());
            cassettes.mix(ctx.terminalId(), req.currency(), foreign, null);   // CASSETTE_UNAVAILABLE if it cannot be paid
            local = foreign.multiply(rate).setScale(2, RoundingMode.HALF_UP);
            if (a.available().compareTo(local) < 0) throw Errors.insufficientFunds(Money.of(a.available(), a.currency()));
        }
        String id = Ids.next("fxq");
        OffsetDateTime expires = OffsetDateTime.now().plusMinutes(QUOTE_MINUTES);
        jdbc.sql("""
                INSERT INTO fx_quotes (id, session_id, direction, currency, rate, foreign_amount, local_amount, debit_account_id,
                                       verification_id, expires_at)
                VALUES (:id, :s, :d, :c, :r, :f, :l, :a, :v, :e)""")
                .param("id", id).param("s", ctx.sessionId()).param("d", req.direction()).param("c", req.currency())
                .param("r", rate).param("f", foreign).param("l", local).param("a", account)
                .param("v", req.identityVerificationId()).param("e", expires).update();
        audit.session(ctx, "FX_QUOTED", "quoteId", id, "direction", req.direction(), "currency", req.currency(),
                "rate", rate.toPlainString(), "foreignAmount", foreign == null ? null : foreign.toPlainString());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("quoteId", id);
        out.put("direction", req.direction());
        out.put("currency", req.currency());
        out.put("rate", rate.setScale(2, RoundingMode.HALF_UP).toPlainString());
        out.put("commission", Money.kes(BigDecimal.ZERO));
        if (foreign != null) out.put("foreignAmount", Money.of(foreign, req.currency()));
        if (local != null) out.put("localAmount", Money.kes(local));
        out.put("expiresAt", expires);
        return out;
    }

    private void requireVerified(Ctx ctx, String verificationId) {
        String result = jdbc.sql("SELECT result FROM identity_verifications WHERE id = :id AND session_id = :s")
                .param("id", verificationId).param("s", ctx.sessionId()).query(String.class).optional()
                .orElseThrow(() -> Errors.notFound("VERIFICATION_NOT_FOUND", "Scan your ID or passport first",
                        "Changanua kitambulisho au pasipoti kwanza"));
        if (!"MATCH".equals(result)) throw Errors.identityMismatch(result, 0);
    }

    private static BigDecimal amount(String field, String s) {
        try {
            BigDecimal v = new BigDecimal(s == null ? "" : s);
            if (v.signum() <= 0) throw new NumberFormatException();
            return v;
        } catch (NumberFormatException e) {
            throw Errors.validation(field, field + " must be a positive decimal string");
        }
    }

    // ------------------------------------------------------------------ X-3

    public record Destination(@NotBlank @Pattern(regexp = "ACCOUNT|CASH") String type, String accountId) {}

    public record ExchangeRequest(@NotBlank String quoteId, @NotNull @Valid Destination destination) {}

    @PostMapping("/exchanges")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Map<String, Object> exchange(Ctx ctx, @Valid @RequestBody ExchangeRequest req) {
        health.requireService(ctx.terminalId(), "forex");
        record Q(String direction, String currency, BigDecimal rate, String verification, OffsetDateTime expires, OffsetDateTime used) {}
        Q q = jdbc.sql("""
                SELECT direction, currency, rate, verification_id, expires_at, used_at FROM fx_quotes
                WHERE id = :id AND session_id = :s FOR UPDATE""")
                .param("id", req.quoteId()).param("s", ctx.sessionId())
                .query((rs, n) -> new Q(rs.getString(1), rs.getString(2), rs.getBigDecimal(3), rs.getString(4),
                        rs.getObject(5, OffsetDateTime.class), rs.getObject(6, OffsetDateTime.class)))
                .optional().orElseThrow(() -> Errors.notFound("QUOTE_NOT_FOUND", "Quote not found", "Nukuu haikupatikana"));
        if (!"BANK_BUYS".equals(q.direction())) throw Errors.validation("quoteId", "Use W-2 with this quote to buy foreign notes");
        if (q.used() != null || q.expires().isBefore(OffsetDateTime.now())) throw Errors.quoteExpired();
        String accountId = null;
        if ("ACCOUNT".equals(req.destination().type())) {
            if (req.destination().accountId() == null) throw Errors.validation("destination.accountId", "accountId is required");
            CoreBanking.Account a = core.account(req.destination().accountId())
                    .orElseThrow(() -> Errors.notFound("ACCOUNT_NOT_FOUND", "Account not found", "Akaunti haikupatikana"));
            // Crediting: the account must belong to the person whose ID was scanned, or to the card + PIN customer.
            String idNumber = jdbc.sql("SELECT document_number FROM identity_verifications WHERE id = :v")
                    .param("v", q.verification()).query(String.class).single();
            String holderId = core.customer(a.customerId()).map(CoreBanking.Customer::idNumber).orElse("");
            boolean mine = holderId.equals(idNumber) || ctx.optionalCustomerId().map(a.customerId()::equals).orElse(false);
            if (!mine) throw Errors.forbidden("This account does not belong to the person whose ID was scanned.");
            accountId = a.id();
        }
        jdbc.sql("UPDATE fx_quotes SET used_at = now() WHERE id = :id").param("id", req.quoteId()).update();
        String id = Ids.next("fx");
        jdbc.sql("""
                INSERT INTO fx_exchanges (id, session_id, terminal_id, quote_id, currency, rate, destination_type, account_id, status)
                VALUES (:id, :s, :t, :q, :c, :r, :d, :a, 'AWAITING_CASH')""")
                .param("id", id).param("s", ctx.sessionId()).param("t", ctx.terminalId()).param("q", req.quoteId())
                .param("c", q.currency()).param("r", q.rate()).param("d", req.destination().type()).param("a", accountId).update();
        audit.session(ctx, "FX_EXCHANGE_OPENED", "exchangeId", id, "quoteId", req.quoteId(), "destination", req.destination().type());
        return Map.of("exchangeId", id, "status", "AWAITING_CASH");
    }

    // ------------------------------------------------------------------ X-4

    public record ForeignNotes(@NotBlank String denomination, @NotNull @Min(0) Integer count, List<String> serials) {}

    public record ForeignRejected(@Min(0) Integer count) {}

    public record CountRequest(@NotBlank String currency, @NotNull List<@Valid ForeignNotes> accepted,
                               @Valid ForeignRejected rejected) {}

    private record X(String id, String status, String currency, BigDecimal rate, String destination, String accountId,
                     String accepted, BigDecimal foreign, BigDecimal local, String txRef, String coreRef,
                     BigDecimal newBalance, String withdrawalId) {}

    private X load(Ctx ctx, String id) {
        return jdbc.sql("""
                SELECT id, status, currency, rate, destination_type, account_id, accepted, foreign_total, local_amount,
                       transaction_ref, core_ref, new_balance, withdrawal_id
                FROM fx_exchanges WHERE id = :id AND session_id = :s FOR UPDATE""")
                .param("id", id).param("s", ctx.sessionId())
                .query((rs, n) -> new X(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBigDecimal(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getBigDecimal(8), rs.getBigDecimal(9),
                        rs.getString(10), rs.getString(11), rs.getBigDecimal(12), rs.getString(13)))
                .optional().orElseThrow(() -> Errors.notFound("EXCHANGE_NOT_FOUND", "Exchange not found", "Ubadilishaji haukupatikana"));
    }

    @PutMapping("/exchanges/{exchangeId}/count")
    @Transactional
    public Map<String, Object> count(Ctx ctx, @PathVariable String exchangeId, @Valid @RequestBody CountRequest req) {
        X x = load(ctx, exchangeId);
        if (!Set.of("AWAITING_CASH", "COUNTED").contains(x.status())) throw Errors.wrongState("This exchange is " + x.status().toLowerCase() + ".");
        if (!x.currency().equals(req.currency())) throw Errors.validation("currency", "Expected " + x.currency() + " notes");
        BigDecimal total = BigDecimal.ZERO;
        int notes = 0;
        List<Cassettes.Note> accepted = new ArrayList<>();
        List<String> serials = new ArrayList<>();
        for (ForeignNotes n : req.accepted()) {
            int d;
            try {
                d = Integer.parseInt(n.denomination());
            } catch (NumberFormatException e) {
                throw Errors.validation("accepted.denomination", "Unknown denomination " + n.denomination());
            }
            if (!NOTES.get(x.currency()).contains(d)) throw Errors.validation("accepted.denomination", "Not a " + x.currency() + " note: " + d);
            if (n.count() == 0) continue;
            notes += n.count();
            total = total.add(BigDecimal.valueOf((long) d * n.count()));
            accepted.add(new Cassettes.Note(String.valueOf(d), n.count()));
            if (n.serials() != null) serials.addAll(n.serials());
        }
        if (notes == 0) throw Errors.validation("accepted", "No notes were accepted");
        if (notes > Limits.FX_MAX_NOTES) throw Errors.depositLimitExceeded(Limits.FX_MAX_NOTES, Money.kes(Limits.DEPOSIT_MAX_AMOUNT));
        BigDecimal local = total.multiply(x.rate()).setScale(2, RoundingMode.HALF_UP);
        BigDecimal exact = local;
        if ("CASH".equals(x.destination())) {
            // Paid in notes: round down to KES 100, and stay within the cash limit.
            local = local.divide(Limits.MULTIPLE_OF, 0, RoundingMode.DOWN).multiply(Limits.MULTIPLE_OF).setScale(2);
            if (local.compareTo(Limits.PER_WITHDRAWAL) > 0) throw Errors.limitExceeded(Money.kes(Limits.PER_WITHDRAWAL));
        }
        int rejected = req.rejected() == null || req.rejected().count() == null ? 0 : req.rejected().count();
        jdbc.sql("""
                UPDATE fx_exchanges SET status = 'COUNTED', accepted = :a, serials = :s, rejected_count = :r,
                       foreign_total = :f, local_amount = :l, updated_at = now() WHERE id = :id""")
                .param("a", json.write(accepted)).param("s", json.write(serials)).param("r", rejected)
                .param("f", total).param("l", local).param("id", exchangeId).update();
        audit.session(ctx, "FX_NOTES_COUNTED", "exchangeId", exchangeId, "currency", x.currency(),
                "foreignTotal", total.toPlainString(), "notes", notes, "serials", serials.size(), "returned", rejected);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("exchangeId", exchangeId);
        out.put("foreignTotal", Money.of(total, x.currency()));
        out.put("rate", x.rate().setScale(2, RoundingMode.HALF_UP).toPlainString());
        out.put("localAmount", Money.kes(local));
        if (local.compareTo(exact) != 0) out.put("roundedFrom", Money.kes(exact));
        out.put("commission", Money.kes(BigDecimal.ZERO));
        out.put("returnedNotes", rejected);
        return out;
    }

    // ------------------------------------------------------------------ X-5 and cancel

    public record ConfirmRequest(@NotNull @Valid Money confirmedLocalAmount) {}

    @PostMapping("/exchanges/{exchangeId}/confirm")
    public ResponseEntity<Object> confirm(Ctx ctx, @PathVariable String exchangeId, @Valid @RequestBody ConfirmRequest req) {
        return idempotency.run(ctx, "X-5:" + exchangeId, req, HttpStatus.OK, () -> doConfirm(ctx, exchangeId, req));
    }

    private Map<String, Object> doConfirm(Ctx ctx, String exchangeId, ConfirmRequest req) {
        X x = load(ctx, exchangeId);
        if (!"COUNTED".equals(x.status())) throw Errors.wrongState("Count the notes first (X-4).").detail("status", x.status());
        if (req.confirmedLocalAmount().value().compareTo(x.local()) != 0) {
            throw Errors.wrongState("The confirmed amount does not match the conversion.").detail("localAmount", Money.kes(x.local()));
        }
        List<Cassettes.Note> notes = json.read(x.accepted(), NOTE_LIST);
        String txRef = refs.next("FX");
        String narrative = "FX buy " + x.currency() + " " + x.foreign().toPlainString() + " @" + x.rate().toPlainString() + " " + txRef;
        // Foreign notes into the kiosk's foreign cash GL, against the bank's FX position.
        String notesRef = core.transferGl(Cassettes.cashGl(x.currency(), ctx.deviceId()), "GL-FX-POSITION-" + x.currency(),
                x.foreign(), x.currency(), narrative);
        cassettes.add(ctx.terminalId(), x.currency(), notes);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("exchangeId", exchangeId);
        if ("ACCOUNT".equals(x.destination())) {
            CoreBanking.Posting p = core.credit(x.accountId(), x.local(), "KES", narrative, "GL-FX-POSITION-KES");
            jdbc.sql("""
                    UPDATE fx_exchanges SET status = 'CREDITED', transaction_ref = :tr, core_ref = :cr, new_balance = :b,
                           updated_at = now() WHERE id = :id""")
                    .param("tr", txRef).param("cr", p.coreRef() + "," + notesRef).param("b", p.balanceAfter())
                    .param("id", exchangeId).update();
            audit.session(ctx, "FX_CREDITED", "exchangeId", exchangeId, "transactionRef", txRef,
                    "localAmount", x.local().toPlainString());
            out.put("status", "CREDITED");
            out.put("transactionRef", txRef);
            out.put("newBalance", Money.kes(p.balanceAfter()));
        } else {
            // KES paid out as cash: a withdrawal the kiosk finishes with W-3 (taken) or W-4 (retracted).
            List<Cassettes.Note> mix = cassettes.mix(ctx.terminalId(), "KES", x.local(), null);
            String kesRef = core.transferGl("GL-FX-POSITION-KES", Cassettes.cashGl("KES", ctx.deviceId()), x.local(), "KES", narrative);
            String wdId = Ids.next("wd");
            withdrawals.insert(wdId, ctx, null, null, "FX_KES_OUT", "KES", x.local(), x.local(), null, exchangeId, mix, txRef,
                    kesRef, null);
            jdbc.sql("""
                    UPDATE fx_exchanges SET status = 'AUTHORISED', transaction_ref = :tr, core_ref = :cr, withdrawal_id = :w,
                           updated_at = now() WHERE id = :id""")
                    .param("tr", txRef).param("cr", notesRef + "," + kesRef).param("w", wdId).param("id", exchangeId).update();
            audit.session(ctx, "FX_CASH_AUTHORISED", "exchangeId", exchangeId, "withdrawalId", wdId, "transactionRef", txRef,
                    "localAmount", x.local().toPlainString());
            out.put("status", "AUTHORISED");
            out.put("transactionRef", txRef);
            out.put("withdrawalId", wdId);
            out.put("dispense", Map.of("currency", "KES", "mix", mix));
            out.put("presentTimeoutSec", WithdrawalController.PRESENT_TIMEOUT_SEC);
        }
        return out;
    }

    public record CancelRequest(@Size(max = 40) String reason) {}

    /** Before X-5: give the foreign notes back (D-4 lists an open exchange with this action). */
    @PostMapping("/exchanges/{exchangeId}/cancel")
    @AllowEndedSession
    @Transactional
    public Map<String, Object> cancel(Ctx ctx, @PathVariable String exchangeId, @RequestBody(required = false) CancelRequest req) {
        X x = load(ctx, exchangeId);
        if ("CANCELLED".equals(x.status())) return Map.of("exchangeId", exchangeId, "status", "CANCELLED", "returnCash", false);
        if (!Set.of("AWAITING_CASH", "COUNTED").contains(x.status())) {
            throw Errors.wrongState("This exchange is already " + x.status().toLowerCase() + ".");
        }
        jdbc.sql("UPDATE fx_exchanges SET status = 'CANCELLED', updated_at = now() WHERE id = :id").param("id", exchangeId).update();
        audit.session(ctx, "FX_EXCHANGE_CANCELLED", "exchangeId", exchangeId,
                "reason", req == null || req.reason() == null ? "CUSTOMER_CANCELLED" : req.reason());
        return Map.of("exchangeId", exchangeId, "status", "CANCELLED", "returnCash", true);
    }
}
