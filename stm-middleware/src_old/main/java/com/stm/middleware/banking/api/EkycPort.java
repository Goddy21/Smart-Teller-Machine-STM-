package com.stm.middleware.banking.api;

import java.time.LocalDate;

/** Identity registry (e.g. IPRS), sanctions/PEP screening and biometric matching. */
public interface EkycPort {
    KycResult verifyIdentity(IdentityCheck check);

    /** subjectRef = the registry/customer reference returned by {@link #verifyIdentity} or the account holder id. */
    BiometricResult matchFace(String subjectRef, byte[] faceImage);

    BiometricResult matchFingerprint(String subjectRef, byte[] template);

    record IdentityCheck(String documentType, String documentNumber, String surname, String givenNames,
                         LocalDate dateOfBirth, String nationality) {}

    record KycResult(boolean verified, String subjectRef, String riskLevel, boolean sanctionsHit,
                     boolean manualReviewRequired, String reason) {}

    record BiometricResult(boolean matched, int score, String reason) {}
}
