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
    private final com.stm.middleware.stm.onboarding.KycChecks kycChecks;
    private final Json json;

    public CustomerController(CoreBanking core, CardSystem cards, DeviceHealthService health, SessionStore sessions,
                              Captures captures, JdbcClient jdbc, StmAudit audit,
                              com.stm.middleware.stm.onboarding.KycChecks kycChecks, Json json) {
        this.kycChecks = kycChecks;
        this.json = json;
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
        // Which finger to ask for: one the bank holds (from account opening or a kiosk/branch enrolment), so the print
        // is matched against the bank's own template. RIGHT_THUMB when none is enrolled (then IPRS is the reference).
        CoreBanking.Biometrics bio = core.biometrics(c.id());
        List<String> fingers = bio.fingers() == null ? List.of() : bio.fingers();
        out.put("enrolledFingers", fingers);
        out.put("fingerToScan", fingers.contains("RIGHT_THUMB") || fingers.isEmpty() ? "RIGHT_THUMB" : fingers.get(0));
        out.put("bankFaceOnFile", bio.faceOnFile());
        return out;
    }

    /** "Achieng Wanjiru Otieno" -> "A. W. Otieno" */
    static String displayName(String full) {
        String[] p = full.trim().split("\\s+");
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < p.length - 1; i++) b.append(p[i].charAt(0)).append(". ");
        return b.append(p[p.length - 1]).toString();
    }

    /** Every card of the customer (a customer may hold several), each with its state and what can be done with it. */
    @GetMapping("/{customerId}/cards")
    public Map<String, Object> cards(Ctx ctx, @PathVariable String customerId) {
        ctx.requireCustomer(customerId);
        Map<String, CoreBanking.Account> accounts = new HashMap<>();
        for (CoreBanking.Account a : core.accounts(customerId)) accounts.put(a.id(), a);
        List<Map<String, Object>> list = new ArrayList<>();
        for (CardSystem.Card card : cards.cards(customerId)) {
            if ("DESTROYED".equals(card.status())) continue;
            list.add(CardView.of(card, accounts.get(card.accountId()), ctx.sw()));
        }
        return Map.of("cards", list);
    }

    /**
     * Every account with its status: ACTIVE, DORMANT (reactivate first, M-2), FROZEN or CLOSED (teller).
     * eligibleFor=CARD adds whether a new card can be linked to it.
     */
    @GetMapping("/{customerId}/accounts")
    public Map<String, Object> accounts(Ctx ctx, @PathVariable String customerId,
                                        @RequestParam(required = false) String eligibleFor) {
        ctx.requireCustomer(customerId);
        Map<String, Integer> cardCount = new HashMap<>();
        Set<String> withCard = new HashSet<>();
        for (CardSystem.Card card : cards.cards(customerId)) {
            if ("DESTROYED".equals(card.status())) continue;
            cardCount.merge(card.accountId(), 1, Integer::sum);
            if (Set.of("ACTIVE", "INACTIVE_PIN_REQUIRED", "TEMP_BLOCKED").contains(card.status())) withCard.add(card.accountId());
        }
        List<Map<String, Object>> list = new ArrayList<>();
        for (CoreBanking.Account a : core.accounts(customerId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("accountId", a.id());
            m.put("type", a.type());
            m.put("masked", a.masked());
            m.put("available", Money.of(a.available(), a.currency()));
            m.put("status", a.status());
            m.put("canTransact", a.active());
            m.put("lastActivityAt", a.lastActivityAt());
            if (a.dormant()) {
                m.put("statusText", ctx.sw() ? "Imelala: ifufue ili kuitumia" : "Dormant: reactivate it to use it");
                m.put("nextStep", "ACCOUNT_REACTIVATION");
            } else if (!a.active()) {
                m.put("statusText", ctx.sw() ? "Haiwezi kutumika hapa: zungumza na mhudumu" : "Cannot be used here: talk to a teller");
                m.put("nextStep", "TELLER");
            }
            boolean has = withCard.contains(a.id());
            m.put("hasCard", has);
            m.put("cards", cardCount.getOrDefault(a.id(), 0));
            if ("CARD".equals(eligibleFor)) {
                boolean eligible = a.cardEligible() && !has && a.active();
                m.put("eligible", eligible);
                if (!a.cardEligible()) m.put("reason", "Cards aren't available for this account type");
                else if (a.dormant()) m.put("reason", "This account is dormant. Reactivate it first");
                else if (!a.active()) m.put("reason", "This account cannot be used here");
                else if (has) m.put("reason", "This account already has a card");
            }
            list.add(m);
        }
        return Map.of("accounts", list);
    }

    // ------------------------------------------------------------------ C-0: everything for the card journeys at once

    /**
     * The customer with their accounts, cards and KYC in one answer, in the shapes the kiosk's card journeys use
     * (CardServicesAccount, CardServicesCard). Needs the customer token from B-5; another customer's id gives 403.
     * Card status is mapped to the kiosk's set; the gateway's own status stays in stmStatus with its actions.
     */
    @GetMapping("/{customerId}")
    public Map<String, Object> summary(Ctx ctx, @PathVariable String customerId) {
        ctx.requireCustomer(customerId);
        CoreBanking.Customer c = core.customer(customerId)
                .orElseThrow(() -> Errors.notFound("CUSTOMER_NOT_FOUND", "Customer not found", "Mteja hakupatikana"));
        List<CoreBanking.Account> accts = core.accounts(customerId);
        List<CardSystem.Card> owned = cards.cards(customerId).stream().filter(k -> !"DESTROYED".equals(k.status())).toList();
        Set<String> withLiveCard = new HashSet<>();
        for (CardSystem.Card k : owned) {
            if (Set.of("ACTIVE", "INACTIVE_PIN_REQUIRED", "TEMP_BLOCKED", "PIN_LOCKED").contains(k.status())) withLiveCard.add(k.accountId());
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("customerId", c.id());
        out.put("fullName", c.fullName());
        out.put("nameOnCard", nameOnCard(c.fullName()));
        out.put("kyc", kyc(customerId));

        List<Map<String, Object>> accounts = new ArrayList<>();
        for (CoreBanking.Account a : accts) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("accountId", a.id());
            m.put("accountNumber", a.number());
            m.put("display", a.masked());
            m.put("type", "JUNIOR_SAVER".equals(a.type()) ? "JUNIOR" : a.type());
            Product prod = product(a.productCode());
            m.put("productName", prod.name() != null ? prod.name() : Owned.accountLabel(a));
            m.put("currency", a.currency());
            m.put("availableBalance", Money.of(a.available(), a.currency()));
            m.put("status", a.status());
            // allowedCardProducts: the card types this account's product allows (empty = never a card on it).
            // newCardAllowed: whether a new card can be ordered on it right now (active, no live card yet).
            m.put("allowedCardProducts", a.cardEligible() ? prod.cardProducts() : List.of());
            boolean live = withLiveCard.contains(a.id());
            boolean now = a.cardEligible() && a.active() && !live;
            m.put("newCardAllowed", now);
            m.put("newCardBlockedReason", now ? null : !a.cardEligible() ? "NOT_ALLOWED_FOR_PRODUCT"
                    : !a.active() ? "ACCOUNT_" + a.status() : "HAS_CARD");
            accounts.add(m);
        }
        out.put("accounts", accounts);

        List<Map<String, Object>> list = new ArrayList<>();
        for (CardSystem.Card k : owned) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("cardId", k.id());
            m.put("accountId", k.accountId());
            m.put("cardProduct", k.product());
            String pan = k.maskedPan() == null ? "" : k.maskedPan().replaceAll("[^0-9]", "");
            m.put("maskedPan", "•••• " + (pan.length() >= 4 ? pan.substring(pan.length() - 4) : pan));
            m.put("expiry", k.expiry());
            m.put("status", kioskStatus(k));
            m.put("blockReason", List.of("BLOCKED", "TEMP_BLOCKED").contains(k.status()) ? k.blockedReason() : null);
            List<String> actions = CardView.actions(k);
            m.put("kioskReplaceAllowed", actions.contains("REPLACEMENT") || actions.contains("BLOCK_AND_REPLACE"));
            m.put("renewalAtBranch", null);
            m.put("stmStatus", k.status());
            m.put("actions", actions);
            list.add(m);
        }
        out.put("cards", list);
        audit.session(ctx, "CUSTOMER_SUMMARY_VIEWED", "customerId", customerId, "accounts", accounts.size(), "cards", list.size());
        return out;
    }

    private record Product(String name, List<String> cardProducts) {}

    private Product product(String code) {
        if (code == null) return new Product(null, List.of("CLASSIC"));
        return jdbc.sql("SELECT name, card_products FROM products WHERE code = :c").param("c", code)
                .query((rs, n) -> new Product(rs.getString(1),
                        rs.getString(2) == null ? List.of("CLASSIC") : json.read(rs.getString(2), Json.STRINGS)))
                .optional().orElse(new Product(null, List.of("CLASSIC")));
    }

    /** Gateway card status → the kiosk's: ACTIVE, INACTIVE, HOTLISTED, FRAUD_BLOCKED, EXPIRED, CLOSED. */
    static String kioskStatus(CardSystem.Card k) {
        String reason = k.blockedReason() == null ? "" : k.blockedReason().toUpperCase();
        boolean fraud = reason.contains("FRAUD") || reason.contains("SUSPICIOUS");
        return switch (k.status()) {
            case "ACTIVE", "PIN_LOCKED" -> "ACTIVE";            // PIN_LOCKED: the card works once the PIN is reset (stmStatus says so)
            case "INACTIVE_PIN_REQUIRED" -> "INACTIVE";
            case "TEMP_BLOCKED", "BLOCKED" -> fraud ? "FRAUD_BLOCKED" : "HOTLISTED";
            case "EXPIRED" -> "EXPIRED";
            default -> "CLOSED";
        };
    }

    /** COMPLETE, INCOMPLETE or EXPIRED, with what is missing in plain words. */
    private Map<String, Object> kyc(String customerId) {
        Map<String, Object> s = kycChecks.status(customerId);
        @SuppressWarnings("unchecked") List<String> flags = (List<String>) s.getOrDefault("flags", List.of());
        List<String> missing = new ArrayList<>();
        if (flags.contains("KRA_PIN_MISSING")) missing.add("KRA PIN certificate");
        if (flags.contains("ADDRESS_UNVERIFIED")) missing.add("Proof of address");
        if (flags.contains("KYC_REFRESH_DUE")) missing.add("Updated ID and KYC details");
        boolean allowed = Boolean.TRUE.equals(s.get("selfServiceAllowed"));
        String status = allowed ? "COMPLETE" : "REFRESH_DUE".equals(s.get("status")) ? "EXPIRED" : "INCOMPLETE";
        if (!allowed && missing.isEmpty()) missing.add("A review by bank staff");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", status);
        m.put("missing", allowed ? List.of() : missing);
        return m;
    }

    /** "Patrick Musonye Tendwa" → "PATRICK M TENDWA" (capitals, at most 26 characters). */
    static String nameOnCard(String fullName) {
        if (fullName == null || fullName.isBlank()) return null;
        String[] p = fullName.trim().toUpperCase().replaceAll("[^A-Z' -]", "").split("\\s+");
        String s = p.length == 1 ? p[0] : p.length == 2 ? p[0] + " " + p[1]
                : p[0] + " " + p[1].charAt(0) + " " + p[p.length - 1];
        return s.length() <= 26 ? s : s.substring(0, 26).trim();
    }
}
