package com.stm.middleware.stm.cards;

import com.stm.middleware.backend.CardSystem;
import com.stm.middleware.backend.CoreBanking;
import com.stm.middleware.stm.core.*;
import com.stm.middleware.stm.device.DeviceHealthService;
import com.stm.middleware.stm.onboarding.IdentityController;
import com.stm.middleware.stm.receipts.SmsService;
import com.stm.middleware.stm.support.Captures;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Card services start here: find the customer (C-1), then OTP (B-9/B-10) and thumbprint (B-5 with customerId)
 * give the customerToken that C-2 .. C-10 need.
 */
@RestController
@RequestMapping("/stm/v1/customers")
public class CustomerController {
    private final CoreBanking core;
    private final CardSystem cards;
    private final DeviceHealthService health;
    private final SessionStore sessions;
    private final Captures captures;
    private final JdbcClient jdbc;
    private final StmAudit audit;

    public CustomerController(CoreBanking core, CardSystem cards, DeviceHealthService health, SessionStore sessions,
                              Captures captures, JdbcClient jdbc, StmAudit audit) {
        this.core = core;
        this.cards = cards;
        this.health = health;
        this.sessions = sessions;
        this.captures = captures;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public record LookupRequest(@NotBlank @Pattern(regexp = "ACCOUNT_NUMBER|ID_DOCUMENT") String by,
                                @Size(max = 20) String accountNumber,
                                @Pattern(regexp = "NATIONAL_ID|PASSPORT") String documentType,
                                @Size(max = 30) String documentNumber, IdentityController.Images images) {}

    @PostMapping("/lookup")
    @Transactional(noRollbackFor = StmException.class)
    public Map<String, Object> lookup(Ctx ctx, @Valid @RequestBody LookupRequest req) {
        health.requireService(ctx.terminalId(), "cardServices");
        sessions.setFlow(ctx.sessionId(), "CARD_SERVICES");
        Optional<CoreBanking.Customer> customer;
        if ("ACCOUNT_NUMBER".equals(req.by())) {
            String no = req.accountNumber() == null ? "" : req.accountNumber().replaceAll("\\s", "");
            if (!no.matches("\\d{10,13}")) throw Errors.validation("accountNumber", "Enter 10 to 13 digits");
            customer = core.accountByNumber(no).flatMap(a -> core.customer(a.customerId()));
        } else {
            if (req.documentNumber() == null) throw Errors.validation("documentNumber", "documentNumber is required");
            customer = core.customerByIdNumber(req.documentNumber().replace(" ", "").toUpperCase());
        }
        CoreBanking.Customer c = customer.orElseThrow(() -> {
            audit.session(ctx, "CUSTOMER_LOOKUP_FAILED", "by", req.by());
            return Errors.notFound("CUSTOMER_NOT_FOUND", "We could not find an account with these details.",
                    "Hatukupata akaunti yenye maelezo haya.");
        });
        String ref = Ids.next("lk");
        jdbc.sql("INSERT INTO customer_lookups (ref, session_id, customer_id, lookup_by) VALUES (:r, :s, :c, :b)")
                .param("r", ref).param("s", ctx.sessionId()).param("c", c.id()).param("b", req.by()).update();
        if (req.images() != null) captures.store("LOOKUP", ref, "FRONT", Captures.decode("images.front", req.images().front()));
        int accounts = core.accounts(c.id()).size();
        audit.session(ctx, "CUSTOMER_LOOKUP", "lookupRef", ref, "customerId", c.id(), "by", req.by());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("lookupRef", ref);
        out.put("customerId", c.id());
        out.put("displayName", displayName(c.fullName()));
        out.put("maskedMobile", SmsService.mask(c.mobile()));
        out.put("accountsFound", accounts);
        out.put("verificationRequired", List.of("OTP", "FINGERPRINT"));
        return out;
    }

    /** "Achieng Wanjiru Otieno" -> "A. W. Otieno" */
    static String displayName(String full) {
        String[] p = full.trim().split("\\s+");
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < p.length - 1; i++) b.append(p[i].charAt(0)).append(". ");
        return b.append(p[p.length - 1]).toString();
    }

    @GetMapping("/{customerId}/cards")
    public Map<String, Object> cards(Ctx ctx, @PathVariable String customerId) {
        ctx.requireCustomer(customerId);
        List<Map<String, Object>> list = new ArrayList<>();
        for (CardSystem.Card card : cards.cards(customerId)) {
            if ("DESTROYED".equals(card.status())) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("cardId", card.id());
            m.put("maskedPan", card.maskedPan());
            m.put("product", card.product());
            m.put("status", card.status());
            m.put("expiry", card.expiry());
            m.put("linkedAccountId", card.accountId());
            list.add(m);
        }
        return Map.of("cards", list);
    }

    @GetMapping("/{customerId}/accounts")
    public Map<String, Object> accounts(Ctx ctx, @PathVariable String customerId,
                                        @RequestParam(required = false) String eligibleFor) {
        ctx.requireCustomer(customerId);
        Set<String> withCard = new HashSet<>();
        for (CardSystem.Card card : cards.cards(customerId)) {
            if (Set.of("ACTIVE", "INACTIVE_PIN_REQUIRED").contains(card.status())) withCard.add(card.accountId());
        }
        List<Map<String, Object>> list = new ArrayList<>();
        for (CoreBanking.Account a : core.accounts(customerId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("accountId", a.id());
            m.put("type", a.type());
            m.put("masked", a.masked());
            m.put("available", Money.of(a.available(), a.currency()));
            boolean has = withCard.contains(a.id());
            m.put("hasCard", has);
            if ("CARD".equals(eligibleFor)) {
                boolean eligible = a.cardEligible() && !has && "ACTIVE".equals(a.status());
                m.put("eligible", eligible);
                if (!a.cardEligible()) m.put("reason", "Cards aren't available for this account type");
                else if (has) m.put("reason", "This account already has a card");
            }
            list.add(m);
        }
        return Map.of("accounts", list);
    }
}
