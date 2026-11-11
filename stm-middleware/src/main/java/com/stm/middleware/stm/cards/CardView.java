package com.stm.middleware.stm.cards;

import com.stm.middleware.backend.CardSystem;
import com.stm.middleware.backend.CoreBanking;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A card as the kiosk shows it: its state in plain words, why, and what the customer can do next.
 * status: INACTIVE_PIN_REQUIRED, ACTIVE, TEMP_BLOCKED, BLOCKED, PIN_LOCKED, EXPIRED.
 */
public final class CardView {
    private CardView() {}

    public static Map<String, Object> of(CardSystem.Card c, CoreBanking.Account account, boolean sw) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cardId", c.id());
        m.put("maskedPan", c.maskedPan());
        m.put("product", c.product());
        m.put("status", c.status());
        m.put("statusText", text(c, sw));
        if (c.blockedReason() != null && List.of("BLOCKED", "TEMP_BLOCKED").contains(c.status())) m.put("reason", c.blockedReason());
        if (c.blockedAt() != null && List.of("BLOCKED", "TEMP_BLOCKED").contains(c.status())) m.put("since", c.blockedAt());
        m.put("expiry", c.expiry());
        boolean expired = "EXPIRED".equals(c.status());
        m.put("expired", expired);
        m.put("expiresSoon", !expired && YearMonth.parse(c.expiry()).isBefore(YearMonth.now().plusMonths(2)));
        m.put("pinSet", c.pinSet());
        m.put("pinAttemptsLeft", c.pinTriesLeft());
        m.put("linkedAccountId", c.accountId());
        if (account != null) {
            m.put("linkedAccount", Owned.accountLabel(account));
            m.put("accountStatus", account.status());
        }
        m.put("usable", "ACTIVE".equals(c.status()) && (account == null || account.active()));
        m.put("actions", actions(c));
        return m;
    }

    /** What the kiosk may offer for this card. */
    static List<String> actions(CardSystem.Card c) {
        List<String> a = new ArrayList<>();
        switch (c.status()) {
            case "ACTIVE" -> { a.add("PIN_CHANGE"); a.add("PIN_RESET"); a.add("TEMPORARY_BLOCK"); a.add("BLOCK_AND_REPLACE"); }
            case "INACTIVE_PIN_REQUIRED" -> a.add("NEW_CARD_SETUP");
            case "TEMP_BLOCKED" -> { a.add("UNBLOCK"); a.add("BLOCK_AND_REPLACE"); }
            case "PIN_LOCKED" -> { a.add("PIN_RESET"); a.add("REPLACEMENT"); }
            case "BLOCKED", "EXPIRED" -> a.add("REPLACEMENT");
            default -> { }
        }
        return a;
    }

    static String text(CardSystem.Card c, boolean sw) {
        String r = c.blockedReason() == null ? "" : c.blockedReason();
        return switch (c.status()) {
            case "ACTIVE" -> sw ? "Inafanya kazi" : "Active";
            case "INACTIVE_PIN_REQUIRED" -> sw ? "Kadi mpya: weka PIN ili kuitumia" : "New card: set a PIN to start using it";
            case "TEMP_BLOCKED" -> sw ? "Imezuiwa kwa muda na wewe" : "Temporarily blocked by you";
            case "PIN_LOCKED" -> sw ? "Imefungwa baada ya PIN kukosewa mara nyingi" : "Locked after too many wrong PINs";
            case "EXPIRED" -> sw ? "Imeisha muda" : "Expired";
            case "BLOCKED" -> switch (r) {
                case "LOST" -> sw ? "Imezuiwa: iliripotiwa kupotea" : "Blocked: reported lost";
                case "STOLEN" -> sw ? "Imezuiwa: iliripotiwa kuibiwa" : "Blocked: reported stolen";
                case "DAMAGED" -> sw ? "Imezuiwa: imeharibika" : "Blocked: damaged";
                case "EXPIRED" -> sw ? "Imezuiwa: imeisha muda" : "Blocked: expired";
                default -> sw ? "Imezuiwa" : "Blocked";
            };
            default -> c.status();
        };
    }
}
