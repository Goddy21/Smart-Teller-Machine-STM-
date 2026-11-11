package com.stm.middleware.stm.core;

import org.springframework.http.HttpStatus;

/**
 * Every error code the kiosk must handle, with customer-facing English and Kiswahili text.
 * The spec's list (IDENTITY_MISMATCH ... CORE_UNAVAILABLE) plus the protocol errors (session, token, idempotency).
 */
public final class Errors {
    private Errors() {}

    public static StmException of(HttpStatus s, String code, String en, String sw, boolean retryable) {
        return new StmException(s, code, en, sw, retryable);
    }

    // ---------------------------------------------------------------- protocol
    public static StmException validation(String field, String en) {
        return of(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", en, "Taarifa uliyoweka si sahihi. Tafadhali angalia na ujaribu tena.", false)
                .detail("field", field);
    }
    public static StmException missingHeader(String header) {
        return of(HttpStatus.BAD_REQUEST, "MISSING_HEADER", header + " header is required.",
                "Kichwa " + header + " kinahitajika.", false).detail("header", header);
    }
    public static StmException deviceUnauthorized() {
        return of(HttpStatus.UNAUTHORIZED, "DEVICE_UNAUTHORIZED", "This machine is not signed in. Refresh the device token.",
                "Mashine hii haijaingia. Onyesha upya tokeni ya kifaa.", true);
    }
    public static StmException sessionInvalid() {
        return of(HttpStatus.UNAUTHORIZED, "SESSION_INVALID", "Your session has ended. Please start again.",
                "Kipindi chako kimeisha. Tafadhali anza tena.", false);
    }
    public static StmException customerTokenRequired() {
        return of(HttpStatus.UNAUTHORIZED, "CUSTOMER_TOKEN_REQUIRED", "Please verify your identity first.",
                "Tafadhali thibitisha utambulisho wako kwanza.", false);
    }
    public static StmException customerTokenInvalid() {
        return of(HttpStatus.UNAUTHORIZED, "CUSTOMER_TOKEN_INVALID", "Your verification has expired. Please verify again.",
                "Uthibitisho wako umeisha muda. Tafadhali thibitisha tena.", false);
    }
    public static StmException forbidden(String en) {
        return of(HttpStatus.FORBIDDEN, "FORBIDDEN", en, "Huduma hii hairuhusiwi.", false);
    }
    public static StmException notFound(String code, String en, String sw) {
        return of(HttpStatus.NOT_FOUND, code, en, sw, false);
    }
    public static StmException wrongState(String en) {
        return of(HttpStatus.CONFLICT, "WRONG_STATE", en, "Hatua hii haiwezi kufanyika sasa.", false);
    }
    public static StmException idempotencyConflict() {
        return of(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT",
                "This Idempotency-Key was already used for a different request.",
                "Ufunguo huu tayari umetumika kwa ombi tofauti.", false);
    }
    public static StmException inProgress() {
        return of(HttpStatus.CONFLICT, "REQUEST_IN_PROGRESS", "The same request is still being processed. Try again shortly.",
                "Ombi hilo bado linashughulikiwa. Jaribu tena baada ya muda mfupi.", true);
    }
    public static StmException idempotencyKeyRequired() {
        return of(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key header is required for this call.",
                "Kichwa Idempotency-Key kinahitajika.", false);
    }
    public static StmException serviceUnavailable(String service) {
        return of(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", "This service is not available on this machine right now.",
                "Huduma hii haipatikani kwenye mashine hii kwa sasa.", true).detail("service", service);
    }

    // ---------------------------------------------------------------- spec business errors
    public static StmException identityMismatch(String result, int attemptsLeft) {
        return of(HttpStatus.UNPROCESSABLE_ENTITY, "IDENTITY_MISMATCH",
                "We could not match your document with the national registry. Rescan it, or ask a teller for help.",
                "Hatukuweza kulinganisha kitambulisho chako na sajili ya taifa. Kichanganue tena au omba msaada wa karani.", false)
                .detail("result", result).detail("attemptsLeft", attemptsLeft);
    }
    public static StmException biometricNoMatch(int attemptsLeft) {
        return of(HttpStatus.UNPROCESSABLE_ENTITY, "BIOMETRIC_NO_MATCH",
                "Your face or thumbprint did not match the registry record.",
                "Uso au alama ya kidole chako haikulingana na rekodi ya sajili.", attemptsLeft > 0)
                .detail("attemptsLeft", attemptsLeft);
    }
    public static StmException existingCustomer(String verificationId) {
        return of(HttpStatus.CONFLICT, "EXISTING_CUSTOMER",
                "You already bank with us. Use Card Services to get a card for your account.",
                "Tayari wewe ni mteja wetu. Tumia Huduma za Kadi kupata kadi ya akaunti yako.", false)
                .detail("verificationId", verificationId);
    }
    public static StmException kraPinInvalid(String reason) {
        return of(HttpStatus.UNPROCESSABLE_ENTITY, "KRA_PIN_INVALID", "This KRA PIN is not valid for your ID number.",
                "PIN hii ya KRA si sahihi kwa nambari yako ya kitambulisho.", false).detail("field", "kraPin").detail("reason", reason);
    }
    public static StmException screeningReferred(String applicationId) {
        return of(HttpStatus.FORBIDDEN, "SCREENING_REFERRED",
                "We need a teller to complete your application. Tap Talk to a teller.",
                "Tunahitaji karani kukamilisha ombi lako. Gusa Ongea na karani.", false).detail("applicationId", applicationId);
    }
    public static StmException otpIncorrect(int attemptsLeft) {
        return of(HttpStatus.UNAUTHORIZED, "OTP_INCORRECT", "That code is not correct.", "Nambari hiyo si sahihi.", attemptsLeft > 0)
                .detail("attemptsLeft", attemptsLeft);
    }
    public static StmException otpExpired() {
        return of(HttpStatus.UNAUTHORIZED, "OTP_EXPIRED", "That code has expired. Request a new one.",
                "Nambari hiyo imeisha muda. Omba nyingine.", true);
    }
    public static StmException pinIncorrect(int attemptsLeft) {
        return of(HttpStatus.UNAUTHORIZED, "PIN_INCORRECT", "Wrong PIN. Please try again.", "PIN si sahihi. Tafadhali jaribu tena.", true)
                .detail("attemptsLeft", attemptsLeft);
    }
    public static StmException pinTriesExceeded() {
        return of(HttpStatus.FORBIDDEN, "PIN_TRIES_EXCEEDED",
                "Too many wrong PINs. Your card has been kept for your safety. Please visit a branch.",
                "Umekosea PIN mara nyingi. Kadi yako imehifadhiwa kwa usalama wako. Tafadhali tembelea tawi.", false)
                .detail("cardAction", "RETAIN");
    }
    /** Locked by wrong PINs (here or at another machine): the customer resets the PIN in Card Services (P-3). */
    public static StmException pinLocked() {
        return of(HttpStatus.FORBIDDEN, "PIN_LOCKED",
                "Too many wrong PINs, so this card is locked. Reset the PIN in Card Services to use it again.",
                "Umekosea PIN mara nyingi, kwa hivyo kadi hii imefungwa. Weka PIN mpya kwenye Huduma za Kadi ili uitumie tena.", false)
                .detail("nextStep", "PIN_RESET");
    }
    public static StmException cardBlocked(String cardAction) {
        return of(HttpStatus.FORBIDDEN, "CARD_BLOCKED", "This card is blocked.", "Kadi hii imezuiwa.", false)
                .detail("cardAction", cardAction);
    }
    public static StmException cardTempBlocked() {
        return of(HttpStatus.FORBIDDEN, "CARD_TEMP_BLOCKED",
                "This card is temporarily blocked. Unblock it in Card Services to use it again.",
                "Kadi hii imezuiwa kwa muda. Ifungue kwenye Huduma za Kadi ili uitumie tena.", false).detail("cardAction", "RETURN");
    }
    public static StmException cardBlockedPermanent(String reason) {
        return of(HttpStatus.CONFLICT, "CARD_BLOCKED_PERMANENT",
                "This card was blocked for good and cannot be unblocked. Order a replacement card.",
                "Kadi hii imezuiwa kabisa na haiwezi kufunguliwa. Agiza kadi mpya.", false)
                .detail("reason", reason).detail("nextStep", "CARD_REPLACEMENT");
    }
    /** The customer token came from card + PIN, but this action needs the code and thumbprint check (C-1, B-9, B-5). */
    public static StmException stepUpRequired() {
        return of(HttpStatus.FORBIDDEN, "STEP_UP_REQUIRED",
                "For your safety, confirm it is you with a code to your phone and your thumbprint first.",
                "Kwa usalama wako, thibitisha ni wewe kwa msimbo kwenye simu yako na alama ya kidole kwanza.", false)
                .detail("nextStep", "CUSTOMER_LOOKUP").detail("required", java.util.List.of("OTP", "FINGERPRINT"));
    }
    public static StmException accountDormant(String accountId) {
        return of(HttpStatus.FORBIDDEN, "ACCOUNT_DORMANT",
                "This account is dormant because it has not been used for a long time. Reactivate it first.",
                "Akaunti hii imelala kwa sababu haijatumika kwa muda mrefu. Ifufue kwanza.", false)
                .detail("accountId", accountId).detail("nextStep", "ACCOUNT_REACTIVATION");
    }
    public static StmException accountRestricted(String status) {
        return of(HttpStatus.FORBIDDEN, "ACCOUNT_RESTRICTED", "This account cannot be used here. Please talk to a teller.",
                "Akaunti hii haiwezi kutumika hapa. Tafadhali zungumza na mhudumu.", false)
                .detail("accountStatus", status).detail("offerTeller", true);
    }
    /** After a change of phone number, sensitive services wait (stm.services.mobile-change-hold-hours). */
    public static StmException mobileRecentlyChanged(long hoursLeft) {
        return of(HttpStatus.FORBIDDEN, "MOBILE_RECENTLY_CHANGED",
                "Your phone number was changed recently. For your safety this service is on hold for a short time. A teller can help you now.",
                "Nambari yako ya simu ilibadilishwa hivi karibuni. Kwa usalama wako huduma hii imesitishwa kwa muda mfupi. Mhudumu anaweza kukusaidia sasa.", false)
                .detail("hoursLeft", hoursLeft).detail("offerTeller", true);
    }
    public static StmException cardExpired() {
        return of(HttpStatus.FORBIDDEN, "CARD_EXPIRED", "This card has expired. Use Card Services to replace it.",
                "Kadi hii imeisha muda. Tumia Huduma za Kadi kuibadilisha.", false).detail("cardAction", "RETURN");
    }
    public static StmException insufficientFunds(Money available) {
        return of(HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_FUNDS",
                "Your available balance is lower than the amount requested.",
                "Salio lako ni chini ya kiasi ulichoomba.", false).detail("available", available);
    }
    public static StmException limitExceeded(Money remaining) {
        return of(HttpStatus.UNPROCESSABLE_ENTITY, "LIMIT_EXCEEDED", "This is more than your remaining limit.",
                "Kiasi hiki ni zaidi ya kikomo chako kilichobaki.", false).detail("remaining", remaining);
    }
    public static StmException cassetteUnavailable(String currency) {
        return of(HttpStatus.CONFLICT, "CASSETTE_UNAVAILABLE",
                "This machine cannot pay out that amount right now. Choose a different amount or currency.",
                "Mashine hii haiwezi kutoa kiasi hicho sasa. Chagua kiasi au sarafu nyingine.", false).detail("currency", currency);
    }
    public static StmException quoteExpired() {
        return of(HttpStatus.CONFLICT, "QUOTE_EXPIRED", "The rate has expired. We will fetch a new one.",
                "Kiwango kimeisha muda. Tutapata kipya.", true);
    }
    public static StmException depositLimitExceeded(int maxNotes, Money maxAmount) {
        return of(HttpStatus.UNPROCESSABLE_ENTITY, "DEPOSIT_LIMIT_EXCEEDED", "Please insert fewer notes.",
                "Tafadhali weka noti chache zaidi.", false).detail("maxNotes", maxNotes).detail("maxAmount", maxAmount);
    }
    public static StmException coreUnavailable() {
        return of(HttpStatus.SERVICE_UNAVAILABLE, "CORE_UNAVAILABLE", "Service unavailable. Please try again later.",
                "Huduma haipatikani. Tafadhali jaribu tena baadaye.", true);
    }
    public static StmException pinPolicy(String rule) {
        return of(HttpStatus.UNPROCESSABLE_ENTITY, "PIN_POLICY", "Choose a PIN that is harder to guess.",
                "Chagua PIN ambayo ni vigumu kubahatisha.", false).detail("rule", rule);
    }
    public static StmException consentRequired(String code) {
        return of(HttpStatus.UNPROCESSABLE_ENTITY, "CONSENT_REQUIRED", "Please accept the required terms to continue.",
                "Tafadhali kubali masharti yanayohitajika ili kuendelea.", false).detail("consent", code);
    }
    public static StmException applicationExpired() {
        return of(HttpStatus.CONFLICT, "APPLICATION_EXPIRED", "This application has expired. Please start again.",
                "Ombi hili limeisha muda. Tafadhali anza tena.", false);
    }
    public static StmException videoUnavailable() {
        return of(HttpStatus.SERVICE_UNAVAILABLE, "VIDEO_UNAVAILABLE", "Video help is not available right now.",
                "Msaada wa video haupatikani kwa sasa.", true);
    }
    public static StmException resendTooSoon(long waitSec) {
        return of(HttpStatus.TOO_MANY_REQUESTS, "RESEND_TOO_SOON", "Please wait before requesting another code.",
                "Tafadhali subiri kabla ya kuomba nambari nyingine.", true).detail("retryAfterSec", waitSec);
    }
}
