package com.stm.middleware.stm.onboarding;

import java.time.OffsetDateTime;

public record Application(String id, String sessionId, long terminalId, String productCode, String termsId,
                          String status, String verificationId, int identityAttempts, boolean biometricsPassed,
                          int biometricAttempts, String fullName, String idNumber, String kraPin, String mobile,
                          String address, String occupation, String sourceOfFunds, String screening, boolean otpVerified,
                          String signatureId, String customerId, String accountId, String accountNumber,
                          String cardProduct, String nameOnCard, String reference, String referralStatus,
                          String tellerOverride, OffsetDateTime createdAt, OffsetDateTime expiresAt) {

    public boolean expired() {
        return expiresAt.isBefore(OffsetDateTime.now()) && !"ACCOUNT_OPENED".equals(status);
    }

    public boolean closed() {
        return "CANCELLED".equals(status) || "REJECTED".equals(status) || "EXPIRED".equals(status);
    }

    /** What the kiosk should show next (B-13 resume). */
    public String nextStep() {
        return switch (status) {
            case "CONSENTED" -> "ID_SCAN";
            case "IDENTITY_CAPTURED" -> "BIOMETRICS";
            case "BIOMETRICS_VERIFIED" -> "REVIEW";
            case "IDENTITY_VERIFIED" -> "KYC";
            case "KYC_CAPTURED" -> "OTP";
            case "REFERRED" -> "TELLER";
            case "PHONE_VERIFIED" -> "SIGNATURE";
            case "SIGNATURE_CAPTURED" -> "SUBMIT";
            case "ACCOUNT_OPENED" -> "CARD";
            default -> "NONE";
        };
    }
}
