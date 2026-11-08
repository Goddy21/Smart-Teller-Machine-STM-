package com.stm.middleware.journey;

/** Server-side step of a kiosk session. The kiosk renders the screen that matches the current step. */
public enum Step {
    // Journey A: open account
    PASSPORT_SCAN, DETAILS, BIOMETRICS, REVIEW_PENDING, SUPPORTING_DOCS, PRODUCT, AGREEMENT, ACCOUNT_OPENED,
    // Journey B: debit card (also reached from A when the customer asks for a card)
    FIND_ACCOUNT, CARD_PERSONALISE, CARD_ISSUING,
    // Both
    COMPLETE;

    /** Where the Back button goes (null = no Back from here). */
    public Step previous() {
        return switch (this) {
            case DETAILS -> PASSPORT_SCAN;
            case SUPPORTING_DOCS -> DETAILS;
            case PRODUCT -> SUPPORTING_DOCS;
            case AGREEMENT -> PRODUCT;
            default -> null;
        };
    }

    /** Step number shown in the kiosk progress bar ("Step n of total"). */
    public int number(JourneyType journey) {
        return switch (this) {
            case PASSPORT_SCAN -> 1;
            case DETAILS -> 2;
            case BIOMETRICS -> journey == JourneyType.OPEN_ACCOUNT ? 3 : 1;
            case REVIEW_PENDING -> 3;
            case SUPPORTING_DOCS -> 4;
            case PRODUCT -> 5;
            case AGREEMENT -> 6;
            case ACCOUNT_OPENED -> 7;
            case FIND_ACCOUNT -> 1;
            case CARD_PERSONALISE -> 2;
            case CARD_ISSUING -> 3;
            case COMPLETE -> 4;
        };
    }

    public int total(JourneyType journey) {
        boolean flowA = switch (this) {
            case PASSPORT_SCAN, DETAILS, REVIEW_PENDING, SUPPORTING_DOCS, PRODUCT, AGREEMENT, ACCOUNT_OPENED -> true;
            case BIOMETRICS -> journey == JourneyType.OPEN_ACCOUNT;
            default -> false;
        };
        return flowA ? 7 : 4;
    }
}
