package com.stm.middleware.stm.cards;

import com.stm.middleware.backend.CardSystem;
import com.stm.middleware.backend.CoreBanking;
import com.stm.middleware.stm.core.Ctx;
import com.stm.middleware.stm.core.Errors;
import org.springframework.stereotype.Component;

/** Loads accounts and cards that belong to the verified customer (X-Customer-Token), else 404 / 403. */
@Component
public class Owned {
    private final CoreBanking core;
    private final CardSystem cards;

    public Owned(CoreBanking core, CardSystem cards) {
        this.core = core;
        this.cards = cards;
    }

    public CoreBanking.Account account(Ctx ctx, String accountId) {
        CoreBanking.Account a = core.account(accountId)
                .orElseThrow(() -> Errors.notFound("ACCOUNT_NOT_FOUND", "Account not found", "Akaunti haikupatikana"));
        if (!a.customerId().equals(ctx.customerId())) {
            throw Errors.notFound("ACCOUNT_NOT_FOUND", "Account not found", "Akaunti haikupatikana");
        }
        return a;
    }

    public CardSystem.Card card(Ctx ctx, String cardId) {
        CardSystem.Card c = cards.card(cardId)
                .orElseThrow(() -> Errors.notFound("CARD_NOT_FOUND", "Card not found", "Kadi haikupatikana"));
        if (!c.customerId().equals(ctx.customerId())) {
            throw Errors.notFound("CARD_NOT_FOUND", "Card not found", "Kadi haikupatikana");
        }
        return c;
    }

    public static String accountLabel(CoreBanking.Account a) {
        return a.typeLabel() + " " + a.masked();
    }
}
