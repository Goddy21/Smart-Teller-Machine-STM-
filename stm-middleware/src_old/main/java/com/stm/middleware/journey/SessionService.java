package com.stm.middleware.journey;

import com.stm.middleware.audit.AuditService;
import com.stm.middleware.banking.api.BankingException;
import com.stm.middleware.banking.api.CardManagementPort;
import com.stm.middleware.banking.api.CardManagementPort.CardIssuance;
import com.stm.middleware.banking.api.CardManagementPort.IssueCardRequest;
import com.stm.middleware.banking.api.CoreBankingPort;
import com.stm.middleware.banking.api.CoreBankingPort.AccountInfo;
import com.stm.middleware.banking.api.CoreBankingPort.CustomerInfo;
import com.stm.middleware.banking.api.CoreBankingPort.LookupMethod;
import com.stm.middleware.banking.api.CoreBankingPort.OpenAccountRequest;
import com.stm.middleware.banking.api.EkycPort;
import com.stm.middleware.banking.api.EkycPort.BiometricResult;
import com.stm.middleware.banking.api.EkycPort.IdentityCheck;
import com.stm.middleware.banking.api.EkycPort.KycResult;
import com.stm.middleware.banking.api.NotificationPort;
import com.stm.middleware.common.ApiException;
import com.stm.middleware.gateway.TerminalPrincipal;
import com.stm.middleware.gateway.TerminalService;
import com.stm.middleware.journey.KioskStatusService.BranchInfo;
import com.stm.middleware.journey.ProductRepository.Product;
import com.stm.middleware.journey.SessionDtos.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Orchestrates the kiosk journeys. Rules:
 *  - The session step is the single source of truth; every endpoint checks it (409 ILLEGAL_STEP otherwise).
 *  - Every state change = compare-and-set on the step + audit record, in one short DB transaction.
 *  - Bank calls are made outside DB transactions and are idempotent on the session id.
 *  - Biometric templates and PINs are never stored. Audit payloads carry masked identifiers only.
 */
@Service
public class SessionService {
    private static final Logger log = LoggerFactory.getLogger(SessionService.class);

    static final int MAX_BIOMETRIC_ATTEMPTS = 3;
    static final int MAX_LOOKUP_ATTEMPTS = 5;
    private static final Set<String> LANGUAGES = Set.of("en", "sw", "fr");
    private static final Set<String> DOC_MIME = Set.of("image/jpeg", "image/png", "application/pdf");
    private static final Set<String> UPLOAD_TYPES = Set.of("PROOF_OF_ADDRESS", "SIGNED_AGREEMENT");
    private static final int MAX_DOC_BYTES = 6 * 1024 * 1024;
    private static final Pattern MOBILE = Pattern.compile("^\\+[1-9]\\d{7,14}$");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern PIN_BLOCK = Pattern.compile("^[0-9A-Fa-f]{16,64}$");
    private static final String REF_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RNG = new SecureRandom();

    private final SessionRepository sessions;
    private final DocumentRepository docs;
    private final ProductRepository products;
    private final AuditService audit;
    private final TransactionTemplate tx;
    private final KioskStatusService status;
    private final AgreementService agreements;
    private final ReceiptService receipts;
    private final TerminalService terminals;
    private final CoreBankingPort core;
    private final EkycPort ekyc;
    private final CardManagementPort cards;
    private final NotificationPort notifier;
    private final String helpline;

    public SessionService(SessionRepository sessions, DocumentRepository docs, ProductRepository products,
                          AuditService audit, TransactionTemplate tx, KioskStatusService status,
                          AgreementService agreements, ReceiptService receipts, TerminalService terminals,
                          CoreBankingPort core, EkycPort ekyc, CardManagementPort cards, NotificationPort notifier,
                          @Value("${stm.helpline:}") String helpline) {
        this.sessions = sessions;
        this.docs = docs;
        this.products = products;
        this.audit = audit;
        this.tx = tx;
        this.status = status;
        this.agreements = agreements;
        this.receipts = receipts;
        this.terminals = terminals;
        this.core = core;
        this.ekyc = ekyc;
        this.cards = cards;
        this.notifier = notifier;
        this.helpline = helpline;
    }

    // ================================================================== lifecycle

    public KioskSession create(TerminalPrincipal p, String key, JourneyType journey, String language) {
        var existing = sessions.findByIdempotencyKey(p.terminalId(), key);
        if (existing.isPresent()) return existing.get();
        String lang = (language != null && LANGUAGES.contains(language)) ? language : "en";
        status.assertAvailable(p.terminalId(), journey);

        // One customer at a time: anything still open on this kiosk is a previous customer's leftover.
        for (KioskSession old : sessions.findInProgressByTerminal(p.terminalId())) {
            cancelReservedCard(old);
            tryCommit(old, null, cardCols(old, "ABANDONED"), "SESSION_ABANDONED", map("step", old.step().name()));
        }

        UUID id = UUID.randomUUID();
        Step first = journey == JourneyType.OPEN_ACCOUNT ? Step.PASSPORT_SCAN : Step.FIND_ACCOUNT;
        try {
            tx.executeWithoutResult(t -> {
                sessions.insert(id, p.terminalId(), key, journey, lang, first);
                audit.record("SESSION_STARTED", id.toString(), p.terminalId(),
                        map("journey", journey.name(), "language", lang));
            });
        } catch (DuplicateKeyException race) {
            return sessions.findByIdempotencyKey(p.terminalId(), key).orElseThrow();
        }
        return sessions.find(id).orElseThrow();
    }

    public KioskSession get(TerminalPrincipal p, UUID id) {
        return load(p, id);
    }

    public KioskSession keepAlive(TerminalPrincipal p, UUID id) {
        KioskSession s = load(p, id);
        requireActive(s);
        sessions.touch(id);
        return s;
    }

    public KioskSession back(TerminalPrincipal p, UUID id) {
        KioskSession s = load(p, id);
        requireActive(s);
        Step prev = s.step().previous();
        if (prev == null || s.accountNo() != null) {
            throw new ApiException(HttpStatus.CONFLICT, "CANNOT_GO_BACK", "Back is not available from " + s.step());
        }
        commit(s, s.step(), map("step", prev), "STEP_BACK", map("from", s.step().name(), "to", prev.name()));
        return reload(s);
    }

    public KioskSession cancel(TerminalPrincipal p, UUID id) {
        KioskSession s = load(p, id);
        if (!s.inProgress()) return s;
        cancelReservedCard(s);
        tryCommit(s, null, cardCols(s, "CANCELLED"), "SESSION_CANCELLED",
                map("step", s.step().name(), "accountRetained", s.accountNo() != null));
        return reload(s);
    }

    public KioskSession complete(TerminalPrincipal p, UUID id) {
        KioskSession s = load(p, id);
        if ("COMPLETED".equals(s.status())) return s;
        requireActive(s);
        requireStep(s, Step.COMPLETE);
        commit(s, Step.COMPLETE, map("status", "COMPLETED", "completed_at", SessionRepository.NOW),
                "SESSION_COMPLETED", map("account", s.accountNo() != null, "card", s.cardStatus()));
        return reload(s);
    }

    public KioskSession escalate(TerminalPrincipal p, UUID id, String reason) {
        KioskSession s = load(p, id);
        requireActive(s);
        String r = reason == null || reason.isBlank() ? "CUSTOMER_REQUEST" : reason.trim();
        commit(s, null, map("escalated", true, "escalation_reason", r), "SESSION_ESCALATED",
                map("step", s.step().name(), "reason", r));
        return reload(s);
    }

    /** Closes sessions the kiosk abandoned (safety net: the kiosk has its own 20-second countdown). */
    public void expire(KioskSession s) {
        cancelReservedCard(s);
        tryCommit(s, null, cardCols(s, "TIMED_OUT"), "SESSION_TIMED_OUT", map("step", s.step().name()));
    }

    /** Data-protection housekeeping: remove documents and PII of sessions that never opened an account. */
    public void scrub(KioskSession s) {
        tx.executeWithoutResult(t -> {
            docs.deleteBySession(s.id());
            sessions.scrubPii(s.id());
            audit.record("SESSION_SCRUBBED", s.id().toString(), s.terminalId(), map("status", s.status()));
        });
    }

    // ================================================================== Journey A: steps 1-2 (and B passport path)

    public PassportResult submitPassport(TerminalPrincipal p, UUID id, String line1, String line2) {
        KioskSession s = load(p, id);
        requireActive(s);
        boolean cardJourney = s.journey() == JourneyType.DEBIT_CARD;
        requireStep(s, cardJourney ? Step.FIND_ACCOUNT : Step.PASSPORT_SCAN);

        Mrz mrz;
        try {
            mrz = Mrz.parse(line1, line2);
        } catch (Mrz.MrzException e) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "DOCUMENT_UNREADABLE",
                    "Unable to read the passport clearly. Nothing was saved.");
        }
        LocalDate today = LocalDate.now();
        if (mrz.expiryDate().isBefore(today)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "DOCUMENT_EXPIRED", "This passport has expired");
        }
        if (!cardJourney && mrz.dateOfBirth().plusYears(18).isAfter(today)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "NOT_ELIGIBLE",
                    "You are not eligible to open an account at this machine");
        }

        KycResult kyc = ekyc.verifyIdentity(new IdentityCheck("PASSPORT", mrz.documentNumber(), mrz.surname(),
                mrz.givenNames(), mrz.dateOfBirth(), mrz.nationality()));
        if (kyc.sanctionsHit()) throw decline(s, "SANCTIONS_HIT");
        if (!kyc.verified()) {
            audit.record("IDENTITY_NOT_VERIFIED", s.id().toString(), s.terminalId(),
                    map("reason", String.valueOf(kyc.reason()), "document", mask(mrz.documentNumber())));
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "IDENTITY_NOT_VERIFIED",
                    "We could not verify these details against the registry");
        }

        boolean review = !cardJourney && kyc.manualReviewRequired();
        Map<String, Object> cols = map(
                "surname", mrz.surname(), "given_names", mrz.givenNames(), "date_of_birth", mrz.dateOfBirth(),
                "nationality", mrz.nationality(), "sex", mrz.sex(), "document_type", "PASSPORT",
                "document_number", mrz.documentNumber(), "document_expiry", mrz.expiryDate(),
                "customer_id", kyc.subjectRef(), "review_required", review, "review_status", review ? "PENDING" : "NONE",
                "identity_status", "PENDING", "face_status", "PENDING", "face_attempts", 0,
                "finger_status", "PENDING", "finger_attempts", 0);

        String accountMasked = null;
        Step next;
        if (cardJourney) {
            AccountInfo acc = core.findAccount(LookupMethod.PASSPORT_NUMBER, mrz.documentNumber())
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND",
                            "No account found for these details"));
            cols.put("account_no", acc.accountNo());
            cols.put("customer_id", acc.customerId());
            accountMasked = maskAccount(acc.accountNo());
            next = Step.BIOMETRICS;
        } else {
            next = Step.DETAILS;
        }
        cols.put("step", next);
        commit(s, s.step(), cols, "PASSPORT_SCANNED",
                map("document", mask(mrz.documentNumber()), "nationality", mrz.nationality(), "reviewRequired", review));

        return new PassportResult(mrz.documentNumber(), mrz.surname(), mrz.givenNames(),
                (mrz.givenNames() + " " + mrz.surname()).trim(), mrz.dateOfBirth(), mrz.nationality(), mrz.sex(),
                mrz.expiryDate(), next.name(), accountMasked);
    }

    public KioskSession confirmDetails(TerminalPrincipal p, UUID id, String mobile, String email, boolean consent) {
        KioskSession s = load(p, id);
        requireActive(s);
        requireStep(s, Step.DETAILS);
        if (!consent) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "CONSENT_REQUIRED",
                    "Please accept the Terms & Conditions and consent to identity checks");
        }
        String m = mobile == null ? "" : mobile.replaceAll("\\s", "");
        if (!MOBILE.matcher(m).matches()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_MOBILE", "Enter the mobile number with its country code");
        }
        String e = email == null ? "" : email.trim();
        if (!e.isEmpty() && !EMAIL.matcher(e).matches()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_EMAIL", "Enter a valid email address");
        }
        Step next = s.identityVerified() ? afterIdentity(s) : Step.BIOMETRICS;
        Map<String, Object> cols = map("mobile", m, "consent_given", true,
                "consent_at", SessionRepository.NOW, "step", next);
        if (!e.isEmpty()) cols.put("email", e);
        commit(s, Step.DETAILS, cols, "DETAILS_CONFIRMED", map("mobile", maskTail(m), "email", !e.isEmpty()));
        return reload(s);
    }

    // ================================================================== Journey B: find account

    public LookupResult lookupAccount(TerminalPrincipal p, UUID id, LookupMethod method, String value) {
        KioskSession s = load(p, id);
        requireActive(s);
        requireStep(s, Step.FIND_ACCOUNT);
        if (s.lookupAttempts() >= MAX_LOOKUP_ATTEMPTS) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_ATTEMPTS",
                    "Too many attempts. Please connect to a teller.");
        }
        Optional<AccountInfo> found = core.findAccount(method, value.trim());
        if (found.isEmpty()) {
            commit(s, Step.FIND_ACCOUNT, map("lookup_attempts", s.lookupAttempts() + 1), "ACCOUNT_LOOKUP_FAILED",
                    map("method", method.name(), "attempt", s.lookupAttempts() + 1));
            throw new ApiException(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "No account found for these details");
        }
        AccountInfo acc = found.get();
        commit(s, Step.FIND_ACCOUNT, map("account_no", acc.accountNo(), "customer_id", acc.customerId(),
                "identity_status", "PENDING", "face_status", "PENDING", "face_attempts", 0,
                "finger_status", "PENDING", "finger_attempts", 0, "step", Step.BIOMETRICS),
                "ACCOUNT_LOOKED_UP", map("method", method.name(), "account", maskAccount(acc.accountNo())));
        return new LookupResult(maskAccount(acc.accountNo()), Step.BIOMETRICS.name());
    }

    // ================================================================== biometrics (A step 3, B ownership check)

    public BiometricResponse submitFace(TerminalPrincipal p, UUID id, byte[] image, boolean livenessPassed) {
        KioskSession s = load(p, id);
        requireActive(s);
        requireStep(s, Step.BIOMETRICS);
        if ("MATCHED".equals(s.faceStatus())) return bioResponse("FACE", s, true);
        requireAttemptsLeft(s.faceAttempts());
        String mime = imageMime(image);

        BiometricResult r = livenessPassed
                ? ekyc.matchFace(s.customerId(), image)
                : new BiometricResult(false, 0, "LIVENESS_FAILED");
        int attempts = s.faceAttempts() + 1;
        if (r.matched()) {
            docs.insert(s.id(), "FACE_CAPTURE", mime, image);
            commit(s, Step.BIOMETRICS, map("face_status", "MATCHED", "face_attempts", attempts),
                    "FACE_MATCHED", map("attempt", attempts, "score", r.score()));
        } else {
            commit(s, Step.BIOMETRICS, map("face_status", "FAILED", "face_attempts", attempts),
                    "FACE_MISMATCH", map("attempt", attempts, "reason", String.valueOf(r.reason())));
        }
        return bioResponse("FACE", advanceIfVerified(reload(s)), r.matched());
    }

    public BiometricResponse submitFingerprint(TerminalPrincipal p, UUID id, byte[] template) {
        KioskSession s = load(p, id);
        requireActive(s);
        requireStep(s, Step.BIOMETRICS);
        if ("MATCHED".equals(s.fingerStatus())) return bioResponse("FINGERPRINT", s, true);
        requireAttemptsLeft(s.fingerAttempts());
        if (template == null || template.length < 16) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_FINGERPRINT", "Fingerprint template rejected");
        }
        BiometricResult r = ekyc.matchFingerprint(s.customerId(), template);   // template is never stored
        int attempts = s.fingerAttempts() + 1;
        commit(s, Step.BIOMETRICS, map("finger_status", r.matched() ? "MATCHED" : "FAILED", "finger_attempts", attempts),
                r.matched() ? "FINGERPRINT_MATCHED" : "FINGERPRINT_MISMATCH",
                map("attempt", attempts, "reason", String.valueOf(r.reason())));
        return bioResponse("FINGERPRINT", advanceIfVerified(reload(s)), r.matched());
    }

    private void requireAttemptsLeft(int attempts) {
        if (attempts >= MAX_BIOMETRIC_ATTEMPTS) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "BIOMETRIC_ATTEMPTS_EXCEEDED",
                    "Verification unsuccessful. Please connect to a teller.");
        }
    }

    /** Both factors matched: record identity as verified and move to the next screen (CAS: only one caller wins). */
    private KioskSession advanceIfVerified(KioskSession s) {
        if (s.step() == Step.BIOMETRICS && "MATCHED".equals(s.faceStatus()) && "MATCHED".equals(s.fingerStatus())) {
            Step next = afterIdentity(s);
            tryCommit(s, Step.BIOMETRICS, map("identity_status", "VERIFIED", "step", next),
                    "IDENTITY_VERIFIED", map("next", next.name()));
            return reload(s);
        }
        return s;
    }

    private BiometricResponse bioResponse(String modality, KioskSession s, boolean matched) {
        int attempts = "FACE".equals(modality) ? s.faceAttempts() : s.fingerAttempts();
        int remaining = Math.max(0, MAX_BIOMETRIC_ATTEMPTS - attempts);
        return new BiometricResponse(modality, matched, attempts, remaining,
                !matched && remaining == 0 && !s.identityVerified(), s.identityVerified(), s.step().name());
    }

    private Step afterIdentity(KioskSession s) {
        if (s.journey() == JourneyType.DEBIT_CARD) return Step.CARD_PERSONALISE;
        return s.reviewBlocked() ? Step.REVIEW_PENDING : Step.SUPPORTING_DOCS;
    }

    // ================================================================== Journey A: steps 4-6

    public DocumentAccepted uploadDocument(TerminalPrincipal p, UUID id, String type, String mime, byte[] content) {
        KioskSession s = load(p, id);
        requireActive(s);
        if (type == null || !UPLOAD_TYPES.contains(type)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DOCUMENT_TYPE", "Unsupported document type");
        }
        requireStep(s, "PROOF_OF_ADDRESS".equals(type) ? Step.SUPPORTING_DOCS : Step.AGREEMENT);
        if (mime == null || !DOC_MIME.contains(mime)) {
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_FILE", "Use a JPEG, PNG or PDF file");
        }
        if (content == null || content.length == 0 || content.length > MAX_DOC_BYTES) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_FILE_SIZE", "File is empty or too large");
        }
        DocumentRepository.DocMeta d = docs.insert(s.id(), type, mime, content);
        audit.record("DOCUMENT_UPLOADED", s.id().toString(), s.terminalId(),
                map("type", type, "sha256", d.sha256(), "bytes", d.sizeBytes()));
        return new DocumentAccepted(d.id(), type, d.sha256(), d.sizeBytes());
    }

    public KioskSession acceptSupportingDocs(TerminalPrincipal p, UUID id) {
        KioskSession s = load(p, id);
        requireActive(s);
        requireStep(s, Step.SUPPORTING_DOCS);
        if (!docs.exists(s.id(), "PROOF_OF_ADDRESS")) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "DOCUMENT_REQUIRED", "Scan your proof of address first");
        }
        commit(s, Step.SUPPORTING_DOCS, map("tier", "STANDARD", "step", Step.PRODUCT),
                "SUPPORTING_DOCS_ACCEPTED", map("tier", "STANDARD"));
        return reload(s);
    }

    public KioskSession skipSupportingDocs(TerminalPrincipal p, UUID id) {
        KioskSession s = load(p, id);
        requireActive(s);
        requireStep(s, Step.SUPPORTING_DOCS);
        commit(s, Step.SUPPORTING_DOCS, map("tier", "BASIC", "step", Step.PRODUCT),
                "SUPPORTING_DOCS_SKIPPED", map("tier", "BASIC"));
        return reload(s);
    }

    public KioskSession selectProduct(TerminalPrincipal p, UUID id, String productCode) {
        KioskSession s = load(p, id);
        requireActive(s);
        requireStep(s, Step.PRODUCT);
        Product product = products.findActive(productCode)
                .orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "UNKNOWN_PRODUCT", "Unknown account type"));
        commit(s, Step.PRODUCT, map("product_code", product.code()), "PRODUCT_SELECTED", map("product", product.code()));
        return reload(s);
    }

    public KioskSession continueToAgreement(TerminalPrincipal p, UUID id) {
        KioskSession s = load(p, id);
        requireActive(s);
        requireStep(s, Step.PRODUCT);
        if (s.productCode() == null) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "PRODUCT_REQUIRED", "Select an account type first");
        }
        commit(s, Step.PRODUCT, map("step", Step.AGREEMENT), "AGREEMENT_STEP", map("product", s.productCode()));
        return reload(s);
    }

    /** A4 agreement as printable HTML. Allowed once a product is selected (step 5 "Print Terms & Conditions"). */
    public String agreementHtml(TerminalPrincipal p, UUID id) {
        KioskSession s = load(p, id);
        requireActive(s);
        requireStep(s, Step.PRODUCT, Step.AGREEMENT);
        if (s.productCode() == null) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "PRODUCT_REQUIRED", "Select an account type first");
        }
        Product product = products.findActive(s.productCode()).orElseThrow();
        audit.record("AGREEMENT_PRESENTED", s.id().toString(), s.terminalId(),
                map("version", AgreementService.VERSION, "product", product.code()));
        return agreements.render(s, product, status.branch(p.terminalId()).name());
    }

    /** Step 6: stores the signature, then opens the account (step 7). Safe to retry if the bank is unavailable. */
    public KioskSession sign(TerminalPrincipal p, UUID id, SignatureMethod method, String signaturePngBase64,
                             boolean accepted, String agreementVersion) {
        KioskSession s = load(p, id);
        requireActive(s);
        requireStep(s, Step.AGREEMENT);
        if (s.accountNo() != null) return s;
        if (!accepted) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "ACCEPTANCE_REQUIRED", "Please confirm the agreement");
        }
        if (!AgreementService.VERSION.equals(agreementVersion)) {
            throw new ApiException(HttpStatus.CONFLICT, "AGREEMENT_VERSION_MISMATCH", "The agreement has changed; print it again");
        }
        if (method == SignatureMethod.DIGITAL) {
            byte[] png = b64(signaturePngBase64);
            if (png.length < 8 || png.length > 300_000 || (png[0] & 0xFF) != 0x89 || png[1] != 'P' || png[2] != 'N' || png[3] != 'G') {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_SIGNATURE", "Signature image rejected");
            }
            docs.insert(s.id(), "SIGNATURE", "image/png", png);
        } else if (!docs.exists(s.id(), "SIGNED_AGREEMENT")) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SIGNED_AGREEMENT_REQUIRED", "Scan the signed agreement first");
        }
        commit(s, Step.AGREEMENT, map("signature_method", method.name(), "agreement_version", agreementVersion,
                "signed_at", SessionRepository.NOW), "AGREEMENT_SIGNED",
                map("method", method.name(), "version", agreementVersion));
        return openAccount(p, id);
    }

    /** Opens the account at the bank (idempotent on the session id) and moves to step 7. */
    public KioskSession openAccount(TerminalPrincipal p, UUID id) {
        KioskSession s = load(p, id);
        requireActive(s);
        if (s.accountNo() != null) return s;
        requireStep(s, Step.AGREEMENT);
        if (s.signatureMethod() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "NOT_SIGNED", "The agreement has not been signed");
        }
        if (!s.identityVerified() || s.reviewBlocked()) {
            throw new ApiException(HttpStatus.CONFLICT, "NOT_CLEARED", "Identity verification is not complete");
        }
        Product product = products.findActive(s.productCode()).orElseThrow();
        BranchInfo branch = status.branch(s.terminalId());
        AccountInfo acc;
        try {
            acc = core.openAccount(new OpenAccountRequest(s.id().toString(), s.customerId(), s.fullName(),
                    s.dateOfBirth(), s.nationality(), s.documentType(), s.documentNumber(), s.mobile(), s.email(),
                    product.code(), product.currency(), s.tier(), branch.code()));
        } catch (BankingException e) {
            audit.record("ACCOUNT_OPEN_REFUSED", s.id().toString(), s.terminalId(), map("code", e.code()));
            throw e;
        } catch (RuntimeException e) {
            log.warn("Account opening failed for session {}: {}", s.id(), e.toString());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "BANK_UNAVAILABLE",
                    "The bank is not available right now. Please try again.");
        }
        commit(s, Step.AGREEMENT, map("account_no", acc.accountNo(), "account_branch", branch.name(),
                "account_currency", product.currency(), "step", Step.ACCOUNT_OPENED),
                "ACCOUNT_OPENED", map("account", maskAccount(acc.accountNo()), "product", product.code(), "tier", String.valueOf(s.tier())));
        return reload(s);
    }

    // ================================================================== cards (B steps 2-4, or A step 7 "issue card now")

    public KioskSession cardOffer(TerminalPrincipal p, UUID id, boolean issueNow) {
        KioskSession s = load(p, id);
        requireActive(s);
        requireStep(s, Step.ACCOUNT_OPENED);
        if (issueNow) {
            status.assertCardAvailable(p.terminalId());
            commit(s, Step.ACCOUNT_OPENED, map("step", Step.CARD_PERSONALISE), "CARD_OFFER_ACCEPTED", map("issueNow", true));
        } else {
            commit(s, Step.ACCOUNT_OPENED, map("step", Step.COMPLETE), "CARD_OFFER_DECLINED", map("issueNow", false));
        }
        return reload(s);
    }

    public List<String> nameOptions(TerminalPrincipal p, UUID id) {
        KioskSession s = load(p, id);
        requireActive(s);
        requireStep(s, Step.CARD_PERSONALISE, Step.CARD_ISSUING);
        return namesFor(s);
    }

    private List<String> namesFor(KioskSession s) {
        CustomerInfo c = core.lookupCustomer(s.customerId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "CUSTOMER_NOT_FOUND", "Customer record not found"));
        return CardNames.options(c.givenNames(), c.surname());
    }

    public CardIssuanceResponse issueCard(TerminalPrincipal p, UUID id, String embossedName,
                                          String encryptedPinBlock, String pinKeyId) {
        KioskSession s = load(p, id);
        requireActive(s);
        requireStep(s, Step.CARD_PERSONALISE, Step.CARD_ISSUING);
        if (s.accountNo() == null || !s.identityVerified()) {
            throw new ApiException(HttpStatus.CONFLICT, "NOT_CLEARED", "Account ownership has not been verified");
        }
        if (!namesFor(s).contains(embossedName)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_NAME_OPTION", "Choose one of the offered names");
        }
        if (!PIN_BLOCK.matcher(encryptedPinBlock).matches()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_PIN_BLOCK", "PIN entry rejected");
        }
        if (s.step() == Step.CARD_PERSONALISE) status.assertCardAvailable(p.terminalId());

        CardIssuance ci;
        try {
            ci = cards.issueCard(new IssueCardRequest(s.id() + ":card", s.accountNo(), embossedName, encryptedPinBlock, pinKeyId));
        } catch (BankingException e) {
            audit.record("CARD_ISSUE_REFUSED", s.id().toString(), s.terminalId(), map("code", e.code()));
            throw e;
        } catch (RuntimeException e) {
            log.warn("Card issue failed for session {}: {}", s.id(), e.toString());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "CARD_SERVICE_UNAVAILABLE",
                    "Card service is not available right now. Please try again.");
        }
        if (s.step() == Step.CARD_PERSONALISE) {
            commit(s, Step.CARD_PERSONALISE, map("card_ref", ci.cardRef(), "card_masked_pan", ci.maskedPan(),
                    "card_status", "RESERVED", "embossed_name", embossedName, "step", Step.CARD_ISSUING),
                    "CARD_RESERVED", map("cardRef", ci.cardRef(), "pan", ci.maskedPan()));
        }
        return new CardIssuanceResponse(ci.cardRef(), ci.maskedPan(), ci.expiry(), ci.personalisationPayload());
    }

    /** The kiosk reports whether the dispenser actually delivered the card (step 3). */
    public KioskSession dispenseResult(TerminalPrincipal p, UUID id, boolean success, String errorCode) {
        KioskSession s = load(p, id);
        requireActive(s);
        requireStep(s, Step.CARD_ISSUING);
        if (s.cardRef() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "NO_CARD_RESERVED", "No card has been issued for this session");
        }
        if (success) {
            String cardStatus = "ISSUED";
            try {
                cards.activateCard(s.cardRef());
            } catch (RuntimeException e) {
                // The customer holds the card: never fail the journey. Operations must activate it.
                log.error("Card {} dispensed but activation failed: {}", s.cardRef(), e.toString());
                cardStatus = "ACTIVATION_PENDING";
            }
            commit(s, Step.CARD_ISSUING, map("card_status", cardStatus, "step", Step.COMPLETE),
                    "ISSUED".equals(cardStatus) ? "CARD_ISSUED" : "CARD_ACTIVATION_PENDING", map("cardRef", s.cardRef()));
            return reload(s);
        }
        // Dispense failed: cancel the card so there is no orphan live card; the account stays; card goes to branch.
        try {
            cards.cancelCard(s.cardRef(), "DISPENSE_FAILED");
        } catch (RuntimeException e) {
            log.error("Could not cancel undispensed card {}: {}", s.cardRef(), e.toString());
        }
        String errorRef = newErrorRef();
        BranchInfo branch = status.branch(s.terminalId());
        tx.executeWithoutResult(t -> {
            if (!sessions.update(s.id(), Step.CARD_ISSUING,
                    map("card_status", "DISPENSE_FAILED", "error_ref", errorRef, "step", Step.COMPLETE))) {
                throw new ApiException(HttpStatus.CONFLICT, "CONCURRENT_UPDATE", "Session was modified concurrently");
            }
            sessions.insertFulfilment(s.id(), s.cardRef(), s.accountNo(), branch.code(), errorRef);
            audit.record("CARD_DISPENSE_FAILED", s.id().toString(), s.terminalId(),
                    map("cardRef", s.cardRef(), "errorRef", errorRef, "errorCode", String.valueOf(errorCode)));
        });
        return reload(s);
    }

    /** Any other hardware fault (printer, scanner...): returns the reference to quote to support / print on a voucher. */
    public FaultResponse hardwareFault(TerminalPrincipal p, UUID id, String device, String errorCode) {
        KioskSession s = load(p, id);
        requireActive(s);
        String errorRef = newErrorRef();
        commit(s, null, map("error_ref", errorRef), "HARDWARE_FAULT",
                map("device", device, "errorCode", String.valueOf(errorCode), "errorRef", errorRef));
        return new FaultResponse(errorRef, s.accountNo(), status.branch(s.terminalId()).name(), helpline);
    }

    // ================================================================== receipt and summary

    public ReceiptService.Receipt receipt(TerminalPrincipal p, UUID id) {
        KioskSession s = load(p, id);
        return receipts.build(s, p.terminalCode(), status.branch(p.terminalId()).name(), helpline);
    }

    public void sendSummary(TerminalPrincipal p, UUID id, NotificationPort.Channel channel) {
        KioskSession s = load(p, id);
        requireStep(s, Step.COMPLETE);
        String to = contact(s, channel);
        if (to == null || to.isBlank()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "NO_CONTACT_ON_FILE", "No contact details on file");
        }
        ReceiptService.Receipt r = receipts.build(s, p.terminalCode(), status.branch(p.terminalId()).name(), helpline);
        try {
            notifier.send(channel, to, r.title(), receipts.asText(r));
        } catch (RuntimeException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "NOTIFICATION_UNAVAILABLE", "Could not send the summary");
        }
        audit.record("SUMMARY_SENT", s.id().toString(), s.terminalId(), map("channel", channel.name()));
    }

    private String contact(KioskSession s, NotificationPort.Channel channel) {
        String fromSession = channel == NotificationPort.Channel.EMAIL ? s.email() : s.mobile();
        if (fromSession != null) return fromSession;
        return core.lookupCustomer(s.customerId())
                .map(c -> channel == NotificationPort.Channel.EMAIL ? c.email() : c.mobile()).orElse(null);
    }

    // ================================================================== portal (teller console)

    public List<KioskSession> list(String statusFilter, Boolean escalated) {
        return sessions.list(statusFilter, escalated);
    }

    public KioskSession getForPortal(UUID id) {
        return sessions.find(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "SESSION_NOT_FOUND", "Session not found"));
    }

    public PortalSessionView portalView(KioskSession s) {
        return new PortalSessionView(s.id(), terminals.codeById(s.terminalId()).orElse("?"), s.journey().name(),
                s.status(), s.step().name(), s.escalated(), s.escalationReason(), s.identityStatus(),
                s.faceStatus(), s.faceAttempts(), s.fingerStatus(), s.fingerAttempts(), s.reviewRequired(),
                s.reviewStatus(), s.fullName(), s.dateOfBirth(), s.documentNumber() == null ? null : mask(s.documentNumber()),
                s.nationality(), s.tier(), s.productCode(), s.accountNo(), s.cardStatus(), s.errorRef(),
                docs.list(s.id()), s.createdAt(), s.updatedAt());
    }

    public DocumentRepository.DocContent documentForPortal(UUID sessionId, UUID docId, String viewer) {
        KioskSession s = getForPortal(sessionId);
        DocumentRepository.DocContent c = docs.content(sessionId, docId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "DOCUMENT_NOT_FOUND", "Document not found"));
        audit.record("DOCUMENT_VIEWED", sessionId.toString(), s.terminalId(), map("documentId", docId.toString(), "viewer", viewer));
        return c;
    }

    /** Remote teller approvals: APPROVE_IDENTITY (biometric override), APPROVE_REVIEW (high-risk clearance), REJECT. */
    public KioskSession decide(UUID id, String decision, String tellerId, String notes) {
        KioskSession s = getForPortal(id);
        requireActive(s);
        Map<String, Object> payload = map("decision", decision, "tellerId", tellerId, "notes", notes == null ? "" : notes);
        switch (decision) {
            case "APPROVE_IDENTITY" -> {
                requireStep(s, Step.BIOMETRICS);
                Step next = afterIdentity(s);
                commit(s, Step.BIOMETRICS, map("identity_status", "TELLER_VERIFIED", "face_status", "OVERRIDDEN",
                        "finger_status", "OVERRIDDEN", "step", next), "TELLER_DECISION", payload);
            }
            case "APPROVE_REVIEW" -> {
                if (!s.reviewRequired()) {
                    throw new ApiException(HttpStatus.CONFLICT, "NO_REVIEW_PENDING", "This session has no pending review");
                }
                Map<String, Object> cols = map("review_status", "APPROVED");
                if (s.step() == Step.REVIEW_PENDING) cols.put("step", Step.SUPPORTING_DOCS);
                commit(s, s.step(), cols, "TELLER_DECISION", payload);
            }
            case "REJECT" -> {
                cancelReservedCard(s);
                commit(s, null, cardCols(s, "REJECTED"), "TELLER_DECISION", payload);
            }
            default -> throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DECISION", "Unknown decision");
        }
        return reload(s);
    }

    // ================================================================== view

    public SessionView view(KioskSession s) {
        AccountView acc = s.accountNo() == null ? null
                : new AccountView(s.accountNo(), s.accountBranch(), s.productCode(), s.accountCurrency());
        CardView card = "NONE".equals(s.cardStatus()) ? null
                : new CardView(s.cardStatus(), s.cardMaskedPan(), s.embossedName());
        return new SessionView(s.id(), s.journey().name(), s.status(), s.step().name(),
                s.step().number(s.journey()), s.step().total(s.journey()), s.language(), s.identityVerified(),
                s.faceStatus(), Math.max(0, MAX_BIOMETRIC_ATTEMPTS - s.faceAttempts()),
                s.fingerStatus(), Math.max(0, MAX_BIOMETRIC_ATTEMPTS - s.fingerAttempts()),
                s.reviewBlocked(), s.escalated(), s.tier(), s.productCode(), acc, card, s.errorRef());
    }

    public static byte[] b64(String v) {
        try {
            return Base64.getDecoder().decode(v == null ? "" : v.trim());
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_BASE64", "Payload is not valid base64");
        }
    }

    // ================================================================== helpers

    private KioskSession load(TerminalPrincipal p, UUID id) {
        return sessions.find(id).filter(s -> s.terminalId() == p.terminalId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "SESSION_NOT_FOUND", "Session not found"));
    }

    private KioskSession reload(KioskSession s) {
        return sessions.find(s.id()).orElse(s);
    }

    private void requireActive(KioskSession s) {
        if (!s.inProgress()) {
            throw new ApiException(HttpStatus.CONFLICT, "SESSION_ENDED", "Session is " + s.status());
        }
    }

    private void requireStep(KioskSession s, Step... allowed) {
        for (Step a : allowed) if (s.step() == a) return;
        throw new ApiException(HttpStatus.CONFLICT, "ILLEGAL_STEP",
                "Expected " + Arrays.toString(allowed) + " but the session is at " + s.step());
    }

    /** Compare-and-set update + audit in one DB transaction. Returns false if the session moved on meanwhile. */
    private boolean tryCommit(KioskSession s, Step expected, Map<String, Object> cols, String event, Map<String, Object> payload) {
        boolean[] ok = {false};
        tx.executeWithoutResult(t -> {
            ok[0] = sessions.update(s.id(), expected, cols);
            if (ok[0]) audit.record(event, s.id().toString(), s.terminalId(), payload);
        });
        return ok[0];
    }

    private void commit(KioskSession s, Step expected, Map<String, Object> cols, String event, Map<String, Object> payload) {
        if (!tryCommit(s, expected, cols, event, payload)) {
            throw new ApiException(HttpStatus.CONFLICT, "CONCURRENT_UPDATE", "Session was modified concurrently; reload and retry");
        }
    }

    /** Terminal-state update that also marks a reserved card as cancelled. */
    private Map<String, Object> cardCols(KioskSession s, String newStatus) {
        Map<String, Object> cols = map("status", newStatus);
        if ("RESERVED".equals(s.cardStatus())) cols.put("card_status", "CANCELLED");
        return cols;
    }

    private void cancelReservedCard(KioskSession s) {
        if ("RESERVED".equals(s.cardStatus()) && s.cardRef() != null) {
            try {
                cards.cancelCard(s.cardRef(), "SESSION_ENDED");
            } catch (RuntimeException e) {
                log.error("Could not cancel reserved card {}: {}", s.cardRef(), e.toString());
            }
        }
    }

    /** Neutral message: never reveal a sanctions/AML reason to the customer (no tipping-off). */
    private ApiException decline(KioskSession s, String internalReason) {
        tryCommit(s, null, map("status", "DECLINED"), "IDENTITY_DECLINED", map("reason", internalReason));
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "UNABLE_TO_PROCEED",
                "We are unable to continue with this request. Please visit a branch.");
    }

    private String newErrorRef() {
        StringBuilder sb = new StringBuilder("E-");
        for (int i = 0; i < 8; i++) sb.append(REF_ALPHABET.charAt(RNG.nextInt(REF_ALPHABET.length())));
        return sb.toString();
    }

    private static String imageMime(byte[] b) {
        if (b != null && b.length > 8) {
            if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8) return "image/jpeg";
            if ((b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "image/png";
        }
        throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_IMAGE", "Image must be JPEG or PNG");
    }

    private static String mask(String v) {
        if (v == null) return null;
        return v.length() <= 3 ? "***" : "*".repeat(v.length() - 3) + v.substring(v.length() - 3);
    }

    private static String maskAccount(String no) {
        return no.length() <= 4 ? "****" : "****" + no.substring(no.length() - 4);
    }

    private static String maskTail(String v) {
        return v.length() <= 4 ? "****" : "*".repeat(v.length() - 4) + v.substring(v.length() - 4);
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }
}
