package com.stm.middleware.journey;

import com.stm.middleware.banking.api.CoreBankingPort.LookupMethod;
import com.stm.middleware.banking.api.NotificationPort;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Request/response shapes of the kiosk and portal session APIs. */
public final class SessionDtos {
    private SessionDtos() {}

    // ------------------------------------------------------------------ requests (kiosk)
    public record CreateSessionRequest(@NotNull JourneyType journey, String language) {}

    public record PassportRequest(@NotBlank String mrzLine1, @NotBlank String mrzLine2) {}

    public record DetailsRequest(@NotBlank String mobile, String email, boolean consentAccepted) {}

    public record LookupRequest(@NotNull LookupMethod method, @NotBlank @Size(max = 64) String value) {}

    public record FaceRequest(@NotBlank @Size(max = 3_000_000) String imageBase64, boolean livenessPassed) {}

    public record FingerprintRequest(@NotBlank @Size(max = 200_000) String templateBase64, String finger) {}

    public record ProductRequest(@NotBlank String productCode) {}

    public enum SignatureMethod { DIGITAL, PAPER }

    public record SignRequest(@NotNull SignatureMethod method, @Size(max = 400_000) String signaturePngBase64,
                              boolean accepted, @NotBlank String agreementVersion) {}

    public record CardOfferRequest(boolean issueNow) {}

    /** encryptedPinBlock comes from the encrypting PIN pad (EPP). The middleware never sees the clear PIN. */
    public record CardIssueRequest(@NotBlank String embossedName, @NotBlank String encryptedPinBlock, String pinKeyId) {}

    public record DispenseResultRequest(boolean success, String errorCode) {}

    public record HardwareFaultRequest(@NotBlank String device, String errorCode) {}

    public record SummaryRequest(@NotNull NotificationPort.Channel channel) {}

    public record EscalateRequest(@Size(max = 200) String reason) {}

    // ------------------------------------------------------------------ requests (portal)
    /** tellerId is ignored for signed-in users (the session decides who acted); only the shared script key uses it. */
    public record DecisionRequest(@NotBlank String decision, @Size(max = 50) String tellerId,
                                  @Size(max = 200) String notes) {}

    // ------------------------------------------------------------------ responses (kiosk)
    public record AccountView(String accountNo, String branch, String productCode, String currency) {}

    public record CardView(String status, String maskedPan, String embossedName) {}

    public record SessionView(UUID id, String journey, String status, String step, int stepNumber, int totalSteps,
                              String language, boolean identityVerified, String faceStatus, int faceAttemptsRemaining,
                              String fingerStatus, int fingerAttemptsRemaining, boolean reviewPending,
                              boolean escalated, String tier, String productCode, AccountView account,
                              CardView card, String errorRef) {}

    public record PassportResult(String documentNumber, String surname, String givenNames, String fullName,
                                 LocalDate dateOfBirth, String nationalityCode, String sex, LocalDate expiryDate,
                                 String nextStep, String accountMasked) {}

    public record LookupResult(String accountMasked, String nextStep) {}

    public record BiometricResponse(String modality, boolean matched, int attempt, int attemptsRemaining,
                                    boolean referToTeller, boolean identityVerified, String nextStep) {}

    public record DocumentAccepted(UUID id, String type, String sha256, int sizeBytes) {}

    public record CardIssuanceResponse(String cardRef, String maskedPan, String expiry, String personalisationPayload) {}

    public record FaultResponse(String errorRef, String accountNo, String branchName, String helpline) {}

    // ------------------------------------------------------------------ responses (portal)
    public record PortalSessionView(UUID id, String terminalCode, String journey, String status, String step,
                                    boolean escalated, String escalationReason, String identityStatus,
                                    String faceStatus, int faceAttempts, String fingerStatus, int fingerAttempts,
                                    boolean reviewRequired, String reviewStatus, String customerName,
                                    LocalDate dateOfBirth, String documentNumberMasked, String nationality,
                                    String tier, String productCode, String accountNo, String cardStatus,
                                    String errorRef, List<DocumentRepository.DocMeta> documents,
                                    OffsetDateTime createdAt, OffsetDateTime updatedAt) {}
}
