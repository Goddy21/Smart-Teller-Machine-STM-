package com.stm.middleware.banking.api;

public interface CardManagementPort {
    /**
     * Reserves, personalises and links a card to the account. MUST be idempotent on {@code request.reference()}.
     * The PIN arrives already encrypted by the kiosk's PIN pad (ISO 9564 block); never decrypt, log or store it here.
     */
    CardIssuance issueCard(IssueCardRequest request);

    /** Called once the kiosk confirms the card was physically dispensed. */
    void activateCard(String cardRef);

    /** Called when dispensing failed or the session was cancelled before dispensing. */
    void cancelCard(String cardRef, String reason);

    record IssueCardRequest(String reference, String accountNo, String embossedName,
                            String encryptedPinBlock, String pinKeyId) {}

    /** personalisationPayload is an opaque, encrypted blob for the card dispenser; never persist or log it. */
    record CardIssuance(String cardRef, String maskedPan, String expiry, String personalisationPayload) {}
}
