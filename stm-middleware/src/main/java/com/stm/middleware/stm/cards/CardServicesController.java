package com.stm.middleware.stm.cards;

import com.stm.middleware.backend.CardSystem;
import com.stm.middleware.backend.CoreBanking;
import com.stm.middleware.stm.core.*;
import com.stm.middleware.stm.receipts.SmsService;
import com.stm.middleware.stm.services.Safeguards;
import com.stm.middleware.stm.services.ServiceEvents;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Looking after a card the customer already has (P-1 .. P-5):
 *   P-1 status, P-2 PIN change (knows the PIN), P-3 PIN reset (forgot it), P-4 temporary block, P-5 unblock.
 * PIN change and the temporary block work with any customer token (card + PIN, or code + thumbprint).
 * PIN reset and unblock need the code + thumbprint check (STEP_UP_REQUIRED otherwise).
 */
@RestController
@RequestMapping("/stm/v1/cards")
public class CardServicesController {
    private final Owned owned;
    private final CoreBanking core;
    private final CardSystem cms;
    private final SmsService sms;
    private final Safeguards safeguards;
    private final ServiceEvents events;
    private final Refs refs;
    private final StmAudit audit;

    public CardServicesController(Owned owned, CoreBanking core, CardSystem cms, SmsService sms, Safeguards safeguards,
                                  ServiceEvents events, Refs refs, StmAudit audit) {
        this.owned = owned;
        this.core = core;
        this.cms = cms;
        this.sms = sms;
        this.safeguards = safeguards;
        this.events = events;
        this.refs = refs;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ P-1

    @GetMapping("/{cardId}/status")
    public Map<String, Object> status(Ctx ctx, @PathVariable String cardId) {
        CardSystem.Card card = owned.card(ctx, cardId);
        return CardView.of(card, core.account(card.accountId()).orElse(null), ctx.sw());
    }

    // ------------------------------------------------------------------ P-2

    public record PinChangeRequest(@NotBlank @Pattern(regexp = "[0-9A-Fa-f]{16,32}") String currentPinBlock,
                                   @NotBlank @Pattern(regexp = "[0-9A-Fa-f]{16,32}") String newPinBlock,
                                   @Size(max = 40) String format, @Size(max = 40) String keySerialNumber,
                                   Boolean confirmed) {}

    @PostMapping("/{cardId}/pin/change")
    @Transactional(noRollbackFor = StmException.class)   // a wrong current PIN still counts
    public Map<String, Object> changePin(Ctx ctx, @PathVariable String cardId, @Valid @RequestBody PinChangeRequest req) {
        CardSystem.Card card = owned.card(ctx, cardId);
        requireConfirmed(req.confirmed());
        if (!"ACTIVE".equals(card.status())) throw notActive(card);
        CardSystem.PinCheck check = cms.verifyPin(cardId, req.currentPinBlock().toUpperCase(), req.format(), req.keySerialNumber());
        if (!check.ok()) {
            audit.session(ctx, "PIN_INCORRECT", "cardId", cardId, "attemptsLeft", check.triesLeft(), "during", "PIN_CHANGE");
            if (check.triesLeft() <= 0) throw pinLocked();
            throw Errors.pinIncorrect(check.triesLeft());
        }
        if (req.newPinBlock().equalsIgnoreCase(req.currentPinBlock())) {
            throw Errors.of(HttpStatus.UNPROCESSABLE_ENTITY, "PIN_UNCHANGED", "Choose a PIN different from your current one.",
                    "Chagua PIN tofauti na ya sasa.", false);
        }
        String rule = cms.pinPolicyViolation(req.newPinBlock(), req.format(), req.keySerialNumber());
        if (rule != null) throw Errors.pinPolicy(rule);
        cms.changePin(cardId, req.newPinBlock().toUpperCase(), req.format(), req.keySerialNumber());
        String ref = refs.next("SVC");
        notify(ctx, card, "CARD_PIN_CHANGED");
        events.record(ctx, card.customerId(), "PIN_CHANGED", card.maskedPan(), ref, "cardId", cardId);
        return done(card, ref, "pinChanged");
    }

    // ------------------------------------------------------------------ P-3

    public record PinResetRequest(@NotBlank @Pattern(regexp = "[0-9A-Fa-f]{16,32}") String newPinBlock,
                                  @Size(max = 40) String format, @Size(max = 40) String keySerialNumber,
                                  Boolean confirmed) {}

    /** The customer forgot the PIN or locked the card: after the code + thumbprint check they choose a new one. */
    @PostMapping("/{cardId}/pin/reset")
    @Transactional
    public Map<String, Object> resetPin(Ctx ctx, @PathVariable String cardId, @Valid @RequestBody PinResetRequest req) {
        CardSystem.Card card = owned.card(ctx, cardId);
        ctx.requireStrongAuth();
        safeguards.requireSettledMobile(card.customerId());
        requireConfirmed(req.confirmed());
        if (!"ACTIVE".equals(card.status()) && !"PIN_LOCKED".equals(card.status())) throw notActive(card);
        String rule = cms.pinPolicyViolation(req.newPinBlock(), req.format(), req.keySerialNumber());
        if (rule != null) throw Errors.pinPolicy(rule);
        boolean wasLocked = "PIN_LOCKED".equals(card.status());
        CardSystem.Card after = cms.resetPin(cardId, req.newPinBlock().toUpperCase(), req.format(), req.keySerialNumber());
        String ref = refs.next("SVC");
        notify(ctx, after, "CARD_PIN_CHANGED");
        events.record(ctx, card.customerId(), "PIN_RESET", card.maskedPan(), ref, "cardId", cardId, "unlocked", wasLocked);
        Map<String, Object> out = done(after, ref, "pinReset");
        out.put("unlocked", wasLocked);
        return out;
    }

    // ------------------------------------------------------------------ P-4, P-5

    public record TempBlockRequest(@Pattern(regexp = "MISPLACED|SUSPICIOUS|TRAVEL|OTHER") String reason) {}

    /** A freeze the customer can lift later. For a lost or stolen card use C-10 (permanent) and order a replacement. */
    @PostMapping("/{cardId}/temporary-block")
    @Transactional
    public Map<String, Object> temporaryBlock(Ctx ctx, @PathVariable String cardId,
                                              @Valid @RequestBody(required = false) TempBlockRequest req) {
        CardSystem.Card card = owned.card(ctx, cardId);
        if ("BLOCKED".equals(card.status())) throw Errors.cardBlockedPermanent(card.blockedReason());
        String reason = req == null || req.reason() == null ? "MISPLACED" : req.reason();
        boolean already = "TEMP_BLOCKED".equals(card.status());
        CardSystem.Card after = cms.temporaryBlock(cardId, reason);
        String ref = refs.next("SVC");
        if (!already) {
            notify(ctx, after, "CARD_TEMP_BLOCKED");
            events.record(ctx, card.customerId(), "CARD_TEMP_BLOCKED", card.maskedPan(), ref, "cardId", cardId, "reason", reason);
        }
        return done(after, ref, "temporarilyBlocked");
    }

    @PostMapping("/{cardId}/unblock")
    @Transactional
    public Map<String, Object> unblock(Ctx ctx, @PathVariable String cardId) {
        CardSystem.Card card = owned.card(ctx, cardId);
        ctx.requireStrongAuth();
        if ("BLOCKED".equals(card.status())) throw Errors.cardBlockedPermanent(card.blockedReason());
        if ("PIN_LOCKED".equals(card.status())) {
            throw Errors.wrongState("This card is locked by wrong PINs. Reset the PIN instead.").detail("nextStep", "PIN_RESET");
        }
        safeguards.requireSettledMobile(card.customerId());
        boolean already = "ACTIVE".equals(card.status());
        CardSystem.Card after = cms.unblock(cardId);
        String ref = refs.next("SVC");
        if (!already) {
            notify(ctx, after, "CARD_UNBLOCKED");
            events.record(ctx, card.customerId(), "CARD_UNBLOCKED", card.maskedPan(), ref, "cardId", cardId);
        }
        return done(after, ref, "unblocked");
    }

    // ------------------------------------------------------------------ helpers

    private Map<String, Object> done(CardSystem.Card card, String ref, String flag) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cardId", card.id());
        out.put("maskedPan", card.maskedPan());
        out.put("status", card.status());
        out.put(flag, true);
        out.put("reference", ref);
        return out;
    }

    private void notify(Ctx ctx, CardSystem.Card card, String template) {
        core.customer(card.customerId()).map(CoreBanking.Customer::mobile)
                .ifPresent(m -> sms.send(ctx.sessionId(), template, m, ctx.lang(), Map.of("card", card.maskedPan())));
    }

    private static void requireConfirmed(Boolean confirmed) {
        if (!Boolean.TRUE.equals(confirmed)) {
            throw Errors.of(HttpStatus.UNPROCESSABLE_ENTITY, "PIN_NOT_CONFIRMED", "The two PIN entries did not match.",
                    "PIN mbili hazikulingana.", false);
        }
    }

    private static StmException notActive(CardSystem.Card card) {
        return switch (card.status()) {
            case "TEMP_BLOCKED" -> Errors.cardTempBlocked().detail("nextStep", "UNBLOCK");
            case "BLOCKED" -> Errors.cardBlockedPermanent(card.blockedReason());
            case "EXPIRED" -> Errors.cardExpired();
            case "INACTIVE_PIN_REQUIRED" -> Errors.wrongState("This card has no PIN yet. Use New Card Setup.").detail("nextStep", "NEW_CARD_SETUP");
            case "PIN_LOCKED" -> pinLocked();
            default -> Errors.wrongState("This card cannot be used for that.").detail("cardStatus", card.status());
        };
    }

    private static StmException pinLocked() {
        return Errors.pinLocked();
    }
}
