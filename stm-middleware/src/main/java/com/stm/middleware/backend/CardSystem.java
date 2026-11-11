package com.stm.middleware.backend;

import java.util.List;
import java.util.Optional;

/** Card management system (CMS) and HSM. PIN blocks are passed through, never decrypted by the Gateway. */
public interface CardSystem {

    /**
     * status: INACTIVE_PIN_REQUIRED (new, no PIN yet), ACTIVE, TEMP_BLOCKED (frozen by the customer, can be lifted),
     * BLOCKED (permanent: blockedReason LOST, STOLEN, DAMAGED or EXPIRED), PIN_LOCKED, EXPIRED, DESTROYED.
     */
    record Card(String id, String customerId, String accountId, String maskedPan, String product, String status,
                String expiry, int pinTriesLeft, String blockedReason, boolean pinSet, java.time.OffsetDateTime blockedAt) {}

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

    /** ACTIVE -> TEMP_BLOCKED. The customer can lift it with {@link #unblock}. */
    Card temporaryBlock(String cardId, String reason);

    /** TEMP_BLOCKED -> ACTIVE. Never lifts a permanent block. */
    Card unblock(String cardId);

    /** Changes the PIN of a card that already has one (verify the current PIN first). */
    void changePin(String cardId, String newPinBlock, String format, String keySerialNumber);

    /** Sets a new PIN after the customer was re-identified and clears a PIN lock (PIN_LOCKED -> ACTIVE). */
    Card resetPin(String cardId, String newPinBlock, String format, String keySerialNumber);

    /** Retained or spoiled at the kiosk. */
    void destroy(String cardId, String reason);
}
