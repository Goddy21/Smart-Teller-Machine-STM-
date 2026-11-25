package com.stm.middleware.backend;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Core banking as the Gateway needs it. Vendor-neutral: map each method to the core's own service
 * (T24, Finacle, Flexcube ...) in a real adapter. The demo profile uses backend.sim.SimulatedBank.
 * Implementations throw StmException (INSUFFICIENT_FUNDS, CORE_UNAVAILABLE ...) for business failures.
 */
public interface CoreBanking {

    record Customer(String id, String idNumber, String fullName, String firstName, String mobile, String kraPin) {}

    /**
     * status: ACTIVE, DORMANT (no customer activity for the bank's dormancy period), FROZEN or CLOSED.
     * statusReason explains FROZEN / CLOSED (for example COURT_ORDER); lastActivityAt is the last customer-initiated movement.
     */
    record Account(String id, String number, String customerId, String type, String productCode, String currency,
                   BigDecimal ledger, BigDecimal available, String branch, String status, boolean cardEligible,
                   OffsetDateTime lastActivityAt, String statusReason) {
        public boolean active() { return "ACTIVE".equals(status); }
        public boolean dormant() { return "DORMANT".equals(status); }
        public String masked() {
            return "••• " + number.substring(Math.max(0, number.length() - 4));
        }
        public String typeLabel() {
            return switch (type) {
                case "SAVINGS" -> "Savings";
                case "CURRENT" -> "Current";
                case "JUNIOR_SAVER" -> "Junior Saver";
                default -> type;
            };
        }
    }

    /** The customer's KYC record as core banking holds it (for refresh and risk decisions). */
    record KycProfile(OffsetDateTime lastReviewedAt, String riskRating, boolean pep, String sanctions, List<String> flags) {}

    /** The bank's own fingerprint enrolment for a customer (none for customers who never enrolled). */
    /**
     * fingers: the bank's fingerprint templates; matchable: at least one of them can really be compared with a live
     * print (a matcher template); faceOnFile: the bank holds a reference photo (account opening).
     */
    record Biometrics(boolean enrolled, List<String> fingers, OffsetDateTime enrolledAt, String source,
                      boolean faceOnFile, String faceSource, boolean matchable) {}

    /** template: the matcher template (FingerprintMatching) when the print could be read, else what the kiosk sent. */
    record FingerTemplate(String finger, byte[] template, int quality) {}

    /** The specimen signature on the account mandate. */
    record Signature(String id, OffsetDateTime capturedAt, String source) {}

    record SignatureImage(byte[] content, String mimeType) {}

    /** profileJson: occupation, employer, monthlyIncome, sourceOfFunds and nextOfKin from "Your details" (B-8). */
    record NewCustomer(String idNumber, String fullName, String firstName, String mobile, String kraPin, String addressJson,
                       String email, String profileJson) {}

    record Opened(String customerId, Account account) {}

    record Posting(String coreRef, BigDecimal previousBalance, BigDecimal balanceAfter, OffsetDateTime postedAt) {}

    record Line(OffsetDateTime date, String narrative, BigDecimal amount, String direction, BigDecimal balance) {}

    Optional<Customer> customer(String customerId);

    Optional<Customer> customerByIdNumber(String idNumber);

    /** KYC review date, risk rating (LOW, MEDIUM, HIGH), PEP and sanctions status, other flags. */
    KycProfile kycProfile(String customerId);

    Optional<Account> account(String accountId);

    Optional<Account> accountByNumber(String accountNumber);

    List<Account> accounts(String customerId);

    /** Creates the CIF with KYC and opens the account (B-12). */
    Opened openCustomerAndAccount(NewCustomer customer, String productCode, String accountType, String branchCode);

    /** Debit a customer account to a GL (fees, withdrawals, FX sells). INSUFFICIENT_FUNDS when not covered. */
    Posting debit(String accountId, BigDecimal amount, String currency, String narrative, String creditGl);

    /** Credit a customer account from a GL (deposits, FX buys). */
    Posting credit(String accountId, BigDecimal amount, String currency, String narrative, String debitGl);

    /** GL to GL (Paybill collections, foreign cash to KES cash). */
    String transferGl(String debitGl, String creditGl, BigDecimal amount, String currency, String narrative);

    /** Reverses every leg of a posting. Idempotent: reversing twice returns the first reversal. */
    String reverse(String coreRef, String narrative);

    List<Line> history(String accountId, OffsetDateTime since, int limit);

    // ---- customer maintenance

    /** DORMANT -> ACTIVE after the customer has been re-identified. ACCOUNT_RESTRICTED for FROZEN / CLOSED. */
    Account reactivate(String accountId, String reference);

    /** When the phone number last changed, if ever. */
    Optional<OffsetDateTime> mobileChangedAt(String customerId);

    /** True when another customer already uses this number. */
    boolean mobileInUse(String mobile, String exceptCustomerId);

    void changeMobile(String customerId, String newMobile, String channel, String reference);

    Biometrics biometrics(String customerId);

    /** Stores the bank's templates for these fingers (replacing earlier ones for the same finger). */
    Biometrics enrolBiometrics(String customerId, List<FingerTemplate> fingers, String source);

    /**
     * Matches a live fingerprint against the bank's enrolled prints. template: the live print's matcher template when
     * the middleware could make one; it is compared with every enrolled finger, so the label does not decide.
     * Empty when the customer is not enrolled (or, without a comparable print, not for this finger).
     */
    Optional<IdentityServices.MatchResult> matchEnrolledFingerprint(String customerId, String finger, byte[] template, int quality);

    /** Stores the bank's reference photo of the customer (replacing an earlier one). */
    void enrolFace(String customerId, byte[] image, String source);

    /** Matches a live face against the bank's reference photo. Empty when the bank holds none. */
    Optional<IdentityServices.MatchResult> matchEnrolledFace(String customerId, byte[] image, double livenessScore);

    Optional<Signature> signature(String customerId);

    Optional<SignatureImage> signatureImage(String customerId);

    /** Registers a new specimen signature; the previous one becomes SUPERSEDED. */
    Signature registerSignature(String customerId, byte[] image, String mimeType, String source);

    BigDecimal glBalance(String gl, String currency);
}
