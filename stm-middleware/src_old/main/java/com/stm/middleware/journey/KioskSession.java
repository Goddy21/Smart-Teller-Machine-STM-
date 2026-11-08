package com.stm.middleware.journey;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

public record KioskSession(
        UUID id, long terminalId, JourneyType journey, String language, Step step, String status,
        String surname, String givenNames, LocalDate dateOfBirth, String nationality, String sex,
        String documentType, String documentNumber, LocalDate documentExpiry,
        String mobile, String email, boolean consentGiven,
        String customerId, boolean reviewRequired, String reviewStatus,
        String identityStatus, String faceStatus, int faceAttempts, String fingerStatus, int fingerAttempts,
        String tier, String productCode, String signatureMethod,
        String accountNo, String accountBranch, String accountCurrency,
        String cardRef, String cardMaskedPan, String cardStatus, String embossedName, String errorRef,
        int lookupAttempts, boolean escalated, String escalationReason,
        OffsetDateTime createdAt, OffsetDateTime updatedAt) {

    public boolean identityVerified() {
        return "VERIFIED".equals(identityStatus) || "TELLER_VERIFIED".equals(identityStatus);
    }

    /** True while a teller still has to approve a high-risk customer. */
    public boolean reviewBlocked() {
        return reviewRequired && !"APPROVED".equals(reviewStatus);
    }

    public boolean inProgress() {
        return "IN_PROGRESS".equals(status);
    }

    public String fullName() {
        return ((givenNames == null ? "" : givenNames) + " " + (surname == null ? "" : surname)).trim();
    }
}
