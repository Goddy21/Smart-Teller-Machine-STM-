package com.stm.middleware.stm.cards;

import com.stm.middleware.backend.CardSystem;
import com.stm.middleware.backend.CoreBanking;
import com.stm.middleware.stm.core.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Card + PIN login (S-1, S-2) for statements, withdrawals and card-funded deposits. Card data comes from the chip
 * read by the device agent; the PIN arrives as an encrypted PIN block and only the HSM checks it.
 */
@RestController
@RequestMapping("/stm/v1/auth/card")
public class CardAuthController {
    private final CardSystem cms;
    private final CoreBanking core;
    private final CustomerTokens tokens;
    private final SessionStore sessions;
    private final JdbcClient jdbc;
    private final StmAudit audit;

    public CardAuthController(CardSystem cms, CoreBanking core, CustomerTokens tokens, SessionStore sessions,
                              JdbcClient jdbc, StmAudit audit) {
        this.cms = cms;
        this.core = core;
        this.tokens = tokens;
        this.sessions = sessions;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public record Emv(@Size(max = 32) String aid, @Size(max = 8) String atc) {}

    public record CardRequest(@NotBlank @Pattern(regexp = "EMV_CHIP|MAG_STRIPE|CONTACTLESS") String entryMode,
                              @NotBlank @Size(max = 40) String panToken, String track2Equivalent, @Valid Emv emv) {}

    @PostMapping
    @Transactional(noRollbackFor = StmException.class)
    public Map<String, Object> card(Ctx ctx, @Valid @RequestBody CardRequest req) {
        Optional<CardSystem.Card> found = cms.byPanToken(req.panToken());
        if (found.isEmpty()) {
            audit.session(ctx, "CARD_REJECTED", "reason", "UNKNOWN_CARD");
            throw Errors.notFound("CARD_NOT_FOUND", "This card is not recognised.", "Kadi hii haitambuliki.")
                    .detail("cardAction", "RETURN");
        }
        CardSystem.Card c = found.get();
        switch (c.status()) {
            case "PIN_LOCKED" -> {     // locked by wrong PINs earlier: give it back, the PIN is reset in Card Services
                audit.session(ctx, "CARD_REJECTED", "cardId", c.id(), "status", c.status(), "cardAction", "RETURN");
                throw Errors.pinLocked().detail("cardAction", "RETURN");
            }
            case "BLOCKED", "DESTROYED" -> {
                audit.session(ctx, "CARD_REJECTED", "cardId", c.id(), "status", c.status(), "cardAction", "RETAIN");
                throw Errors.cardBlocked("RETAIN");
            }
            case "EXPIRED" -> {
                audit.session(ctx, "CARD_REJECTED", "cardId", c.id(), "status", c.status(), "cardAction", "RETURN");
                throw Errors.cardExpired();
            }
            case "TEMP_BLOCKED" -> {   // the customer froze it: give it back, they can unblock it in Card Services
                audit.session(ctx, "CARD_REJECTED", "cardId", c.id(), "status", c.status(), "cardAction", "RETURN");
                throw Errors.cardTempBlocked().detail("nextStep", "UNBLOCK");
            }
            default -> { }
        }
        String ref = Ids.next("ca");
        jdbc.sql("""
                INSERT INTO card_auths (ref, session_id, card_id, masked_pan, customer_id, entry_mode)
                VALUES (:r, :s, :c, :m, :cu, :e)""")
                .param("r", ref).param("s", ctx.sessionId()).param("c", c.id()).param("m", c.maskedPan())
                .param("cu", c.customerId()).param("e", req.entryMode()).update();
        String first = core.customer(c.customerId()).map(CoreBanking.Customer::firstName).orElse(null);
        audit.session(ctx, "CARD_INSERTED", "cardAuthRef", ref, "cardId", c.id(), "entryMode", req.entryMode());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cardAuthRef", ref);
        out.put("maskedPan", c.maskedPan());
        out.put("cardStatus", c.status());
        out.put("pinAttemptsLeft", c.pinTriesLeft());
        out.put("customerFirstName", first);
        return out;
    }

    public record PinRequest(@NotBlank @Size(max = 40) String cardAuthRef,
                             @NotBlank @Pattern(regexp = "[0-9A-Fa-f]{16,32}") String pinBlock,
                             @Size(max = 40) String format, @Size(max = 40) String keySerialNumber) {}

    @PostMapping("/pin-verify")
    @Transactional(noRollbackFor = StmException.class)
    public Map<String, Object> pin(Ctx ctx, @Valid @RequestBody PinRequest req) {
        record Auth(String cardId, String customerId) {}
        Auth a = jdbc.sql("SELECT card_id, customer_id FROM card_auths WHERE ref = :r AND session_id = :s")
                .param("r", req.cardAuthRef()).param("s", ctx.sessionId())
                .query((rs, n) -> new Auth(rs.getString(1), rs.getString(2))).optional()
                .orElseThrow(() -> Errors.notFound("CARD_AUTH_NOT_FOUND", "Insert the card again", "Ingiza kadi tena"));
        CardSystem.Card card = cms.card(a.cardId()).orElseThrow();
        if (!"ACTIVE".equals(card.status())) {
            if ("PIN_LOCKED".equals(card.status())) throw Errors.pinTriesExceeded();
            if ("INACTIVE_PIN_REQUIRED".equals(card.status())) {
                throw Errors.wrongState("This card has no PIN yet. Use Card Services, New Card Setup.").detail("cardAction", "RETURN");
            }
            if ("TEMP_BLOCKED".equals(card.status())) throw Errors.cardTempBlocked().detail("nextStep", "UNBLOCK");
            throw Errors.cardBlocked("RETAIN");
        }
        CardSystem.PinCheck check = cms.verifyPin(a.cardId(), req.pinBlock().toUpperCase(), req.format(), req.keySerialNumber());
        if (!check.ok()) {
            audit.session(ctx, "PIN_INCORRECT", "cardId", a.cardId(), "attemptsLeft", check.triesLeft());
            if (check.triesLeft() <= 0) {
                audit.session(ctx, "CARD_RETAINED_PIN_TRIES", "cardId", a.cardId());
                throw Errors.pinTriesExceeded();
            }
            throw Errors.pinIncorrect(check.triesLeft());
        }
        jdbc.sql("UPDATE card_auths SET verified = true WHERE ref = :r").param("r", req.cardAuthRef()).update();
        String token = tokens.issue(ctx.sessionId(), a.customerId(), "CARD_PIN");
        CoreBanking.Customer c = core.customer(a.customerId()).orElseThrow();
        sessions.setCustomer(ctx.sessionId(), c.id(), c.fullName());
        List<Map<String, Object>> accounts = new ArrayList<>();
        List<Map<String, Object>> restricted = new ArrayList<>();   // dormant, frozen or closed: shown, not usable
        for (CoreBanking.Account acc : core.accounts(c.id())) {
            if (!"ACTIVE".equals(acc.status())) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("accountId", acc.id());
                m.put("type", acc.type());
                m.put("masked", acc.masked());
                m.put("status", acc.status());
                m.put("nextStep", acc.dormant() ? "ACCOUNT_REACTIVATION" : "TELLER");
                restricted.add(m);
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("accountId", acc.id());
            m.put("type", acc.type());
            m.put("masked", acc.masked());
            m.put("primary", acc.id().equals(card.accountId()));
            accounts.add(m);
        }
        accounts.sort(Comparator.comparing(m -> !(Boolean) m.get("primary")));
        audit.session(ctx, "CARD_LOGIN", "cardId", a.cardId(), "customerId", c.id());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("verified", true);
        out.put("customerToken", token);
        out.put("customerId", c.id());
        out.put("customerFirstName", c.firstName());
        out.put("accounts", accounts);
        if (!restricted.isEmpty()) out.put("restrictedAccounts", restricted);
        return out;
    }
}
