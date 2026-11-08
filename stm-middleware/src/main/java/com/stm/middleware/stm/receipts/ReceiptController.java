package com.stm.middleware.stm.receipts;

import com.stm.middleware.backend.CoreBanking;
import com.stm.middleware.stm.core.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Receipts (R-1) and SMS (R-2). The Gateway writes the receipt text so wording, masking and the legal footer are
 * controlled centrally; the device agent prints it and reports the result.
 */
@RestController
@RequestMapping("/stm/v1")
public class ReceiptController {
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm", Locale.ENGLISH);

    private final JdbcClient jdbc;
    private final Json json;
    private final SmsService sms;
    private final CoreBanking core;
    private final StmAudit audit;
    private final String bankName;
    private final String receiptBaseUrl;

    public ReceiptController(JdbcClient jdbc, Json json, SmsService sms, CoreBanking core, StmAudit audit,
                             @Value("${stm.receipt.bank-name:BLUE RIVER SMART TELLER}") String bankName,
                             @Value("${stm.receipt.verify-url:https://bank.example/r/}") String receiptBaseUrl) {
        this.jdbc = jdbc;
        this.json = json;
        this.sms = sms;
        this.core = core;
        this.audit = audit;
        this.bankName = bankName;
        this.receiptBaseUrl = receiptBaseUrl;
    }

    /** What a receipt says about one transaction, found by its reference. */
    private record Tx(String title, List<String> body, String mobile, String smsTemplate, Map<String, String> smsParams) {}

    public record ReceiptRequest(@NotBlank @Size(max = 30) String transactionRef,
                                 @NotBlank @Pattern(regexp = "PRINT|SMS|NONE") String delivery,
                                 @Pattern(regexp = "en|sw") String language) {}

    @PostMapping("/receipts")
    @ResponseStatus(HttpStatus.CREATED)
    @AllowEndedSession
    @Transactional
    public Map<String, Object> receipt(Ctx ctx, @Valid @RequestBody ReceiptRequest req) {
        String lang = req.language() != null ? req.language() : ctx.lang();
        Tx tx = find(ctx, req.transactionRef());
        String id = Ids.next("rc");
        List<String> lines = new ArrayList<>();
        lines.add(bankName + "  " + ctx.deviceId());
        lines.add(OffsetDateTime.now().format(WHEN).toUpperCase());
        lines.add("-".repeat(32));
        lines.add(tx.title());
        lines.addAll(tx.body());
        lines.add("REF " + req.transactionRef());
        lines.add("-".repeat(32));
        lines.add("sw".equals(lang) ? "ASANTE KWA KUTUMIA HUDUMA ZETU" : "THANK YOU FOR BANKING WITH US");
        lines.add("Regulated by the Central Bank of Kenya");
        String qr = receiptBaseUrl + id;
        String printStatus = "PRINT".equals(req.delivery()) ? "PENDING" : "NOT_PRINTED";
        jdbc.sql("""
                INSERT INTO receipts (id, session_id, transaction_ref, delivery, language, lines, qr, print_status)
                VALUES (:id, :s, :r, :d, :l, :lines, :qr, :ps)""")
                .param("id", id).param("s", ctx.sessionId()).param("r", req.transactionRef()).param("d", req.delivery())
                .param("l", lang).param("lines", json.write(lines)).param("qr", qr).param("ps", printStatus).update();
        String smsStatus = null;
        if ("SMS".equals(req.delivery())) {
            if (tx.mobile() == null) throw Errors.validation("delivery", "No mobile number is known for this transaction");
            smsStatus = sms.send(ctx.sessionId(), tx.smsTemplate(), tx.mobile(), lang, tx.smsParams()).status();
        }
        audit.session(ctx, "RECEIPT_ISSUED", "receiptId", id, "transactionRef", req.transactionRef(), "delivery", req.delivery());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("receiptId", id);
        out.put("lines", lines);
        out.put("qr", qr);
        out.put("printStatusUrl", "/stm/v1/receipts/" + id);
        if (smsStatus != null) out.put("sms", smsStatus);
        return out;
    }

    public record PrintStatus(@NotBlank @Pattern(regexp = "PRINTED|FAILED|PAPER_OUT") String status) {}

    /** Device agent: the printer's result for printStatusUrl. */
    @PutMapping("/receipts/{receiptId}")
    @AllowEndedSession
    public Map<String, Object> printed(Ctx ctx, @PathVariable String receiptId, @Valid @RequestBody PrintStatus req) {
        int n = jdbc.sql("UPDATE receipts SET print_status = :st WHERE id = :id AND session_id = :s")
                .param("st", req.status()).param("id", receiptId).param("s", ctx.sessionId()).update();
        if (n == 0) throw Errors.notFound("RECEIPT_NOT_FOUND", "Receipt not found", "Risiti haikupatikana");
        return Map.of("receiptId", receiptId, "printStatus", req.status());
    }

    @GetMapping("/receipts/{receiptId}")
    @AllowEndedSession
    public Map<String, Object> get(Ctx ctx, @PathVariable String receiptId) {
        return jdbc.sql("SELECT id, transaction_ref, delivery, print_status, lines FROM receipts WHERE id = :id AND session_id = :s")
                .param("id", receiptId).param("s", ctx.sessionId())
                .query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("receiptId", rs.getString(1));
                    m.put("transactionRef", rs.getString(2));
                    m.put("delivery", rs.getString(3));
                    m.put("printStatus", rs.getString(4));
                    m.put("lines", json.read(rs.getString(5), Json.STRINGS));
                    return m;
                }).optional().orElseThrow(() -> Errors.notFound("RECEIPT_NOT_FOUND", "Receipt not found", "Risiti haikupatikana"));
    }

    // ------------------------------------------------------------------ R-2

    public record SmsRequest(@NotBlank @Size(max = 40) String template, @NotBlank @Size(max = 20) String mobile,
                             @Pattern(regexp = "en|sw") String language, @NotNull Map<String, @Size(max = 120) String> params) {}

    @PostMapping("/notifications/sms")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @AllowEndedSession
    public Map<String, Object> sms(Ctx ctx, @Valid @RequestBody SmsRequest req) {
        if ("OTP".equals(req.template())) throw Errors.validation("template", "OTPs are sent with B-9");
        String mobile = SmsService.normalise("mobile", req.mobile());
        SmsService.Sent s = sms.send(ctx.sessionId(), req.template(), mobile, req.language() != null ? req.language() : ctx.lang(),
                req.params());
        return Map.of("messageId", s.messageId(), "status", s.status());
    }

    // ------------------------------------------------------------------ lookups by reference

    private Tx find(Ctx ctx, String ref) {
        String sid = ctx.sessionId();
        String kind = ref.startsWith("STM-") && ref.indexOf('-', 4) > 0 ? ref.substring(4, ref.indexOf('-', 4)) : "";
        return switch (kind) {
            case "DEP" -> jdbc.sql("""
                    SELECT d.total - d.fee AS amt, d.target_label, d.depositor_mobile, t.account_id FROM cash_deposits d
                    JOIN deposit_targets t ON t.ref = d.target_ref WHERE d.transaction_ref = :r AND d.session_id = :s""")
                    .param("r", ref).param("s", sid)
                    .query((rs, n) -> {
                        String amt = Money.display(rs.getBigDecimal(1), "KES");
                        String mobile = rs.getString(3) != null ? rs.getString(3) : mobileOfAccount(rs.getString(4));
                        return new Tx("CASH DEPOSIT       " + amt, List.of("TO " + rs.getString(2).toUpperCase()), mobile,
                                "CASH_DEPOSIT_RECEIPT", Map.of("amount", amt, "account", rs.getString(2), "ref", ref));
                    }).optional().orElseThrow(() -> notFound(ref));
            case "WDL" -> jdbc.sql("""
                    SELECT amount, currency, account_id, balance_after FROM cash_withdrawals
                    WHERE transaction_ref = :r AND session_id = :s""")
                    .param("r", ref).param("s", sid)
                    .query((rs, n) -> {
                        String amt = Money.display(rs.getBigDecimal(1), rs.getString(2));
                        String acc = accountLabel(rs.getString(3));
                        List<String> body = new ArrayList<>(List.of("FROM " + acc.toUpperCase()));
                        BigDecimal bal = rs.getBigDecimal(4);
                        if (bal != null) body.add("BALANCE " + Money.display(bal, "KES"));
                        return new Tx("CASH WITHDRAWAL    " + amt, body, mobileOfAccount(rs.getString(3)),
                                "CASH_WITHDRAWAL_RECEIPT", Map.of("amount", amt, "account", acc, "ref", ref));
                    }).optional().orElseThrow(() -> notFound(ref));
            case "RTR" -> jdbc.sql("""
                    SELECT local_amount, account_id, credit_back_by, status FROM cash_withdrawals
                    WHERE reversal_ref = :r AND session_id = :s""")
                    .param("r", ref).param("s", sid)
                    .query((rs, n) -> {
                        String amt = Money.display(rs.getBigDecimal(1), "KES");
                        String acc = rs.getString(2) == null ? "your account" : accountLabel(rs.getString(2));
                        return new Tx("CASH RETRACTED     " + amt, List.of("RETURNED TO " + acc.toUpperCase(),
                                "STATUS " + rs.getString(4)), mobileOfAccount(rs.getString(2)), "CASH_REVERSAL",
                                Map.of("amount", amt, "account", acc, "ref", ref));
                    }).optional().orElseThrow(() -> notFound(ref));
            case "FX" -> jdbc.sql("""
                    SELECT currency, foreign_total, rate, local_amount, destination_type, account_id FROM fx_exchanges
                    WHERE transaction_ref = :r AND session_id = :s""")
                    .param("r", ref).param("s", sid)
                    .query((rs, n) -> {
                        String foreign = Money.display(rs.getBigDecimal(2), rs.getString(1));
                        String amt = Money.display(rs.getBigDecimal(4), "KES");
                        String rate = "1 " + rs.getString(1) + " = " + rs.getBigDecimal(3).setScale(2) + " KES";
                        String dest = "ACCOUNT".equals(rs.getString(5)) ? "TO " + accountLabel(rs.getString(6)).toUpperCase() : "PAID IN CASH";
                        return new Tx("FOREX EXCHANGE", List.of("RECEIVED " + foreign, "RATE " + rate, "KES " + amt, dest),
                                mobileOfAccount(rs.getString(6)), "FX_RECEIPT",
                                Map.of("foreign", foreign, "rate", rate, "amount", amt, "ref", ref));
                    }).optional().orElseThrow(() -> notFound(ref));
            case "CRD" -> jdbc.sql("""
                    SELECT service, masked_pan, card_product, account_id, fee_amount FROM card_orders WHERE reference = :r AND session_id = :s""")
                    .param("r", ref).param("s", sid)
                    .query((rs, n) -> {
                        String acc = accountLabel(rs.getString(4));
                        return new Tx(rs.getString(1).replace('_', ' '), List.of("CARD " + rs.getString(2) + " " + rs.getString(3),
                                "LINKED TO " + acc.toUpperCase(), "FEE " + Money.display(rs.getBigDecimal(5), "KES")),
                                mobileOfAccount(rs.getString(4)), "CARD_ACTIVATED",
                                Map.of("card", rs.getString(2), "account", acc, "ref", ref));
                    }).optional().orElseThrow(() -> notFound(ref));
            case "STMT" -> jdbc.sql("SELECT type, account_id, fee, balance_after FROM statements WHERE reference = :r AND session_id = :s")
                    .param("r", ref).param("s", sid)
                    .query((rs, n) -> {
                        String acc = accountLabel(rs.getString(2));
                        String fee = Money.display(rs.getBigDecimal(3), "KES");
                        return new Tx(rs.getString(1) + " STATEMENT", List.of("ACCOUNT " + acc.toUpperCase(), "FEE " + fee,
                                "BALANCE " + Money.display(rs.getBigDecimal(4), "KES")), mobileOfAccount(rs.getString(2)),
                                "STATEMENT_FEE", Map.of("amount", fee, "type", rs.getString(1).toLowerCase(), "account", acc, "ref", ref));
                    }).optional().orElseThrow(() -> notFound(ref));
            case "ACC" -> jdbc.sql("""
                    SELECT a.account_number, p.name, a.mobile, a.full_name FROM applications a JOIN products p ON p.code = a.product_code
                    WHERE a.reference = :r AND a.session_id = :s""")
                    .param("r", ref).param("s", sid)
                    .query((rs, n) -> new Tx("ACCOUNT OPENED", List.of(rs.getString(2).toUpperCase(),
                            "ACCOUNT NO " + rs.getString(1), rs.getString(4).toUpperCase()), rs.getString(3),
                            "GENERIC_RECEIPT", Map.of("ref", ref, "summary", rs.getString(2) + " " + rs.getString(1))))
                    .optional().orElseThrow(() -> notFound(ref));
            default -> throw notFound(ref);
        };
    }

    private String accountLabel(String accountId) {
        if (accountId == null) return "";
        return core.account(accountId).map(a -> a.typeLabel() + " " + a.masked()).orElse(accountId);
    }

    private String mobileOfAccount(String accountId) {
        if (accountId == null) return null;
        return core.account(accountId).flatMap(a -> core.customer(a.customerId())).map(CoreBanking.Customer::mobile).orElse(null);
    }

    private static StmException notFound(String ref) {
        return Errors.notFound("TRANSACTION_NOT_FOUND", "No transaction " + ref + " in this session", "Muamala haukupatikana");
    }
}
