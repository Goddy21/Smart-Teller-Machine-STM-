package com.stm.middleware.backend;

import java.util.List;
import java.util.Optional;

/** Card management system (CMS) and HSM. PIN blocks are passed through, never decrypted by the Gateway. */
public interface CardSystem {

    record Card(String id, String customerId, String accountId, String maskedPan, String product, String status,
                String expiry, int pinTriesLeft) {}

    record PinCheck(boolean ok, int triesLeft) {}

    Optional<Card> card(String cardId);

    Optional<Card> byPanToken(String panToken);

    List<Card> cards(String customerId);

    /** Creates the card record, linked to the account. Returns it with status INACTIVE_PIN_REQUIRED. */
    Card create(String customerId, String accountId, String product, String nameOnCard);

    /** Encrypted EMV data for the kiosk's card printer. */
    String personalisationPayload(Card card, String nameOnCard);

    void setPin(String cardId, String pinBlock, String format, String keySerialNumber);

    PinCheck verifyPin(String cardId, String pinBlock, String format, String keySerialNumber);

    /** A PIN-policy check the HSM can do on the clear PIN inside its boundary. null = OK, else the broken rule. */
    String pinPolicyViolation(String pinBlock, String format, String keySerialNumber);

    Card activate(String cardId);

    Card block(String cardId, String reason);

    /** Retained or spoiled at the kiosk. */
    void destroy(String cardId, String reason);
}
