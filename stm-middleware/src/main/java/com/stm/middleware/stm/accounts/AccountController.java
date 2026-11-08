package com.stm.middleware.stm.accounts;

import com.stm.middleware.backend.Collections;
import com.stm.middleware.backend.CoreBanking;
import com.stm.middleware.stm.cards.Owned;
import com.stm.middleware.stm.core.*;
import com.stm.middleware.stm.device.DeviceHealthService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;

/** Balance (S-3), deposit target check (K-1) and withdrawal limits (W-1). */
@RestController
@RequestMapping("/stm/v1/accounts")
public class AccountController {
    private final CoreBanking core;
    private final Collections collections;
    private final Owned owned;
    private final Limits limits;
    private final DeviceHealthService health;
    private final SessionStore sessions;
    private final JdbcClient jdbc;
    private final StmAudit audit;

    public AccountController(CoreBanking core, Collections collections, Owned owned, Limits limits,
                             DeviceHealthService health, SessionStore sessions, JdbcClient jdbc, StmAudit audit) {
        this.core = core;
        this.collections = collections;
        this.owned = owned;
        this.limits = limits;
        this.health = health;
        this.sessions = sessions;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @GetMapping("/{accountId}/balance")
    public Map<String, Object> balance(Ctx ctx, @PathVariable String accountId) {
        CoreBanking.Account a = owned.account(ctx, accountId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("accountId", a.id());
        out.put("masked", a.masked());
        out.put("type", a.type());
        out.put("ledger", Money.of(a.ledger(), a.currency()));
        out.put("available", Money.of(a.available(), a.currency()));
        out.put("asOf", OffsetDateTime.now());
        return out;
    }

    public record ValidateRequest(@NotBlank @Pattern(regexp = "ACCOUNT|PAYBILL") String type,
                                  @Size(max = 20) String accountNumber, @Size(max = 10) String paybill,
                                  @Size(max = 40) String accountReference,
                                  @Pattern(regexp = "CASH_DEPOSIT") String purpose) {}

    /** "Achieng Wanjiru Otieno" -> "Achieng W. Otieno": enough to recognise, not the full record. */
    static String accountName(String full) {
        String[] p = full.trim().split("\\s+");
        if (p.length <= 2) return full;
        StringBuilder b = new StringBuilder(p[0]);
        for (int i = 1; i < p.length - 1; i++) b.append(' ').append(p[i].charAt(0)).append('.');
        return b.append(' ').append(p[p.length - 1]).toString();
    }

    @PostMapping("/validate")
    @Transactional
    public Map<String, Object> validate(Ctx ctx, @Valid @RequestBody ValidateRequest req) {
        health.requireService(ctx.terminalId(), "cashDeposit");
        sessions.setFlow(ctx.sessionId(), "CASH_DEPOSIT");
        String ref = Ids.next("tg");
        Map<String, Object> out = new LinkedHashMap<>();
        if ("ACCOUNT".equals(req.type())) {
            String no = req.accountNumber() == null ? "" : req.accountNumber().replaceAll("\\s", "");
            if (!no.matches("\\d{10,13}")) throw Errors.validation("accountNumber", "Enter 10 to 13 digits");
            CoreBanking.Account a = core.accountByNumber(no).filter(x -> "ACTIVE".equals(x.status()))
                    .orElseThrow(() -> Errors.notFound("ACCOUNT_NOT_FOUND", "We could not find this account.",
                            "Hatukupata akaunti hii."));
            String name = accountName(core.customer(a.customerId()).orElseThrow().fullName());
            String masked = a.typeLabel() + " " + a.masked();
            jdbc.sql("""
                    INSERT INTO deposit_targets (ref, session_id, type, account_id, account_name, masked)
                    VALUES (:r, :s, 'ACCOUNT', :a, :n, :m)""")
                    .param("r", ref).param("s", ctx.sessionId()).param("a", a.id()).param("n", name).param("m", masked).update();
            String branch = jdbc.sql("SELECT name FROM branches WHERE code = :c").param("c", a.branch())
                    .query(String.class).optional().orElse(a.branch());
            out.put("valid", true);
            out.put("targetRef", ref);
            out.put("accountName", name);
            out.put("masked", masked);
            out.put("branch", branch);
        } else {
            if (req.paybill() == null || req.accountReference() == null) {
                throw Errors.validation(req.paybill() == null ? "paybill" : "accountReference", "paybill and accountReference are required");
            }
            Collections.Biller b = collections.paybill(req.paybill(), req.accountReference())
                    .orElseThrow(() -> Errors.notFound("PAYBILL_NOT_FOUND", "This Paybill number is not recognised.",
                            "Nambari hii ya Paybill haitambuliki."));
            String masked = "Paybill " + b.paybill() + " · " + req.accountReference();
            jdbc.sql("""
                    INSERT INTO deposit_targets (ref, session_id, type, paybill, account_reference, account_name, masked)
                    VALUES (:r, :s, 'PAYBILL', :p, :ar, :n, :m)""")
                    .param("r", ref).param("s", ctx.sessionId()).param("p", b.paybill()).param("ar", req.accountReference())
                    .param("n", b.name()).param("m", masked).update();
            out.put("valid", true);
            out.put("targetRef", ref);
            out.put("accountName", b.name());
            out.put("masked", masked);
        }
        out.put("limits", Map.of("maxNotes", Limits.DEPOSIT_MAX_NOTES, "maxAmount", Money.kes(Limits.DEPOSIT_MAX_AMOUNT)));
        audit.session(ctx, "DEPOSIT_TARGET_CHECKED", "targetRef", ref, "type", req.type());
        return out;
    }

    @GetMapping("/{accountId}/withdrawal-limits")
    public Map<String, Object> withdrawalLimits(Ctx ctx, @PathVariable String accountId) {
        CoreBanking.Account a = owned.account(ctx, accountId);
        BigDecimal daily = limits.dailyRemaining(ctx.customerId());
        BigDecimal fee = jdbc.sql("SELECT fee FROM tariffs WHERE code = 'CASH_WDL_STM'").query(BigDecimal.class).optional()
                .orElse(BigDecimal.ZERO);
        BigDecimal cap = a.available().min(Limits.PER_WITHDRAWAL).min(daily);
        List<String> quick = new ArrayList<>();
        for (long q : new long[]{2000, 5000, 10000}) if (cap.compareTo(BigDecimal.valueOf(q)) >= 0) quick.add(String.valueOf(q));
        sessions.setFlow(ctx.sessionId(), "CASH_WITHDRAWAL");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("available", Money.of(a.available(), a.currency()));
        out.put("perTransactionMax", Money.kes(Limits.PER_WITHDRAWAL));
        out.put("dailyRemaining", Money.kes(daily));
        out.put("multipleOf", Limits.MULTIPLE_OF.toPlainString());
        out.put("fee", Money.kes(fee));
        out.put("quickAmounts", quick);
        return out;
    }
}
