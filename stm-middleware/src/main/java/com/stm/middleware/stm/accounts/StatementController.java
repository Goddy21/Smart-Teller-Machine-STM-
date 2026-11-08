package com.stm.middleware.stm.accounts;

import com.stm.middleware.backend.CoreBanking;
import com.stm.middleware.stm.cards.Owned;
import com.stm.middleware.stm.core.*;
import com.stm.middleware.stm.device.DeviceHealthService;
import com.stm.middleware.stm.receipts.SmsService;
import com.stm.middleware.stm.support.SimplePdf;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Statements: options and fees (S-4), approve + generate (S-5), the stamped A4 PDF (S-6). */
@RestController
@RequestMapping("/stm/v1/statements")
public class StatementController {
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final List<Integer> PERIODS = List.of(1, 3, 6, 12);

    private final Owned owned;
    private final CoreBanking core;
    private final DeviceHealthService health;
    private final SessionStore sessions;
    private final Idempotency idempotency;
    private final Refs refs;
    private final SmsService sms;
    private final JdbcClient jdbc;
    private final Json json;
    private final StmAudit audit;

    public StatementController(Owned owned, CoreBanking core, DeviceHealthService health, SessionStore sessions,
                               Idempotency idempotency, Refs refs, SmsService sms, JdbcClient jdbc, Json json, StmAudit audit) {
        this.owned = owned;
        this.core = core;
        this.health = health;
        this.sessions = sessions;
        this.idempotency = idempotency;
        this.refs = refs;
        this.sms = sms;
        this.jdbc = jdbc;
        this.json = json;
        this.audit = audit;
    }

    private BigDecimal fee(String code) {
        return jdbc.sql("SELECT fee FROM tariffs WHERE code = :c").param("c", code).query(BigDecimal.class).single();
    }

    @GetMapping("/options")
    public Map<String, Object> options(Ctx ctx, @RequestParam String accountId) {
        health.requireService(ctx.terminalId(), "statements");
        CoreBanking.Account a = owned.account(ctx, accountId);
        sessions.setFlow(ctx.sessionId(), "STATEMENT");
        Map<String, Object> mini = new LinkedHashMap<>();
        mini.put("type", "MINI");
        mini.put("output", "RECEIPT");
        mini.put("transactions", 10);
        mini.put("fee", Money.of(fee("STMT_MINI"), a.currency()));
        Map<String, Object> full = new LinkedHashMap<>();
        full.put("type", "FULL");
        full.put("output", "A4");
        full.put("periodsMonths", PERIODS);
        full.put("fee", Money.of(fee("STMT_FULL"), a.currency()));
        full.put("stamped", true);
        return Map.of("options", List.of(mini, full));
    }

    public record StatementRequest(@NotBlank String accountId, @NotBlank @Pattern(regexp = "MINI|FULL") String type,
                                   Integer periodMonths, @NotNull Boolean feeAccepted) {}

    @PostMapping
    public ResponseEntity<Object> create(Ctx ctx, @Valid @RequestBody StatementRequest req) {
        return idempotency.run(ctx, "S-5", req, HttpStatus.CREATED, () -> doCreate(ctx, req));
    }

    private Map<String, Object> doCreate(Ctx ctx, StatementRequest req) {
        health.requireService(ctx.terminalId(), "statements");
        CoreBanking.Account a = owned.account(ctx, req.accountId());
        if (!req.feeAccepted()) throw Errors.validation("feeAccepted", "The customer must accept the fee");
        boolean mini = "MINI".equals(req.type());
        int months = mini ? 0 : Optional.ofNullable(req.periodMonths()).orElse(3);
        if (!mini && !PERIODS.contains(months)) throw Errors.validation("periodMonths", "periodMonths must be 1, 3, 6 or 12");
        BigDecimal fee = fee(mini ? "STMT_MINI" : "STMT_FULL");
        CoreBanking.Posting p = fee.signum() > 0
                ? core.debit(a.id(), fee, a.currency(), (mini ? "Mini" : "Full") + " statement fee " + ctx.deviceId(), "GL-FEES-STMT")
                : null;
        List<CoreBanking.Line> history = mini
                ? core.history(a.id(), OffsetDateTime.now().minusYears(5), 10)
                : core.history(a.id(), OffsetDateTime.now().minusMonths(months), 5000);
        List<Map<String, Object>> lines = new ArrayList<>();
        for (CoreBanking.Line l : history) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", l.date().format(DAY));
            m.put("narrative", l.narrative());
            m.put("amount", l.amount().setScale(2).toPlainString());
            m.put("direction", l.direction());
            m.put("balance", l.balance() == null ? null : l.balance().setScale(2).toPlainString());
            lines.add(m);
        }
        String id = Ids.next("st");
        String ref = refs.next("STMT");
        BigDecimal after = p != null ? p.balanceAfter() : a.ledger();
        int pages = mini ? 0 : SimplePdf.pages(lines.size() + 14);
        jdbc.sql("""
                INSERT INTO statements (id, session_id, customer_id, account_id, type, period_months, fee, currency, fee_ref,
                                        balance_after, pages, lines, reference)
                VALUES (:id, :s, :c, :a, :t, :pm, :f, :cur, :fr, :b, :pg, :l, :r)""")
                .param("id", id).param("s", ctx.sessionId()).param("c", ctx.customerId()).param("a", a.id())
                .param("t", req.type()).param("pm", mini ? null : months).param("f", fee).param("cur", a.currency())
                .param("fr", p == null ? null : p.coreRef()).param("b", after).param("pg", pages)
                .param("l", json.write(lines)).param("r", ref).update();
        audit.session(ctx, "STATEMENT_ISSUED", "statementId", id, "type", req.type(), "periodMonths", mini ? null : months,
                "fee", fee.toPlainString(), "feeRef", p == null ? null : p.coreRef(), "reference", ref);
        Map<String, Object> feeOut = new LinkedHashMap<>();
        feeOut.put("posted", p != null);
        feeOut.put("transactionRef", p == null ? null : p.coreRef());
        feeOut.put("amount", fee.setScale(2).toPlainString());
        feeOut.put("currency", a.currency());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("statementId", id);
        out.put("reference", ref);
        out.put("fee", feeOut);
        out.put("balanceAfterFee", Money.of(after, a.currency()));
        if (mini) {
            out.put("lines", lines);
        } else {
            out.put("pages", pages);
            out.put("documentUrl", "/stm/v1/statements/" + id + "/document");
        }
        return out;
    }

    @GetMapping("/{statementId}/document")
    @AllowEndedSession
    public ResponseEntity<byte[]> document(Ctx ctx, @PathVariable String statementId) {
        record S(String account, String type, Integer months, String lines, String reference, OffsetDateTime at,
                 String customer) {}
        S s = jdbc.sql("""
                SELECT account_id, type, period_months, lines, reference, created_at, customer_id FROM statements
                WHERE id = :id AND session_id = :sid""")
                .param("id", statementId).param("sid", ctx.sessionId())
                .query((rs, n) -> new S(rs.getString(1), rs.getString(2), (Integer) rs.getObject(3), rs.getString(4),
                        rs.getString(5), rs.getObject(6, OffsetDateTime.class), rs.getString(7)))
                .optional().orElseThrow(() -> Errors.notFound("STATEMENT_NOT_FOUND", "Statement not found", "Taarifa haikupatikana"));
        CoreBanking.Account a = core.account(s.account()).orElseThrow();
        CoreBanking.Customer c = core.customer(s.customer()).orElseThrow();
        List<String> out = new ArrayList<>();
        out.add("#BLUE RIVER SMART TELLER - ACCOUNT STATEMENT");
        out.add("");
        out.add("Account holder : " + c.fullName());
        out.add("Account        : " + a.typeLabel() + " " + a.number() + "   Branch " + a.branch());
        out.add("Period         : " + (s.months() == null ? "last 10 transactions" : "last " + s.months() + " month(s)")
                + " to " + s.at().format(DAY));
        out.add("Reference      : " + s.reference());
        out.add("");
        out.add(String.format("#%-11s %-40s %14s %14s", "Date", "Narrative", "Amount", "Balance"));
        for (Map<String, Object> l : json.read(s.lines(), Json.LIST_OF_MAPS)) {
            String amt = ("DR".equals(l.get("direction")) ? "-" : "") + l.get("amount");
            String narrative = String.valueOf(l.get("narrative"));
            out.add(String.format("%-11s %-40s %14s %14s", l.get("date"),
                    narrative.length() > 40 ? narrative.substring(0, 40) : narrative, amt,
                    l.get("balance") == null ? "" : l.get("balance")));
        }
        out.add("");
        out.add("Closing available balance: " + Money.display(a.available(), a.currency()));
        out.add("");
        out.add("#Digitally stamped by the bank. Verify at the branch with reference " + s.reference() + ".");
        byte[] pdf = SimplePdf.render(out, "Regulated by the Central Bank of Kenya. Deposits protected by KDIC. " + s.reference());
        audit.session(ctx, "STATEMENT_DOCUMENT_FETCHED", "statementId", statementId);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header("X-Pages", String.valueOf(SimplePdf.pages(out.size())))
                .header("Content-Disposition", "inline; filename=\"" + s.reference() + ".pdf\"")
                .body(pdf);
    }
}
