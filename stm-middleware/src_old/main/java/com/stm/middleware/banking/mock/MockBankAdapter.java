package com.stm.middleware.banking.mock;

import com.stm.middleware.banking.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Demo life-saver: in-memory registry (eKYC), CBS, payment switch, card management and notifications.
 * Scenario behaviour is driven by the seeded personas (see {@link #personas()} and GET /api/v1/kiosk/demo/personas).
 */
@Component
@Profile("demo")
public class MockBankAdapter implements CoreBankingPort, PaymentSwitchPort, CardManagementPort, EkycPort, NotificationPort {
    private static final Logger log = LoggerFactory.getLogger(MockBankAdapter.class);

    public static final String TIMEOUT_CUSTOMER = "CUST-004";
    public static final String DECLINE_CUSTOMER = "CUST-005";

    /** faceBehavior / fingerBehavior: MATCH | FAIL_ONCE (retry works) | FAIL_ALWAYS (needs a teller). */
    public record Persona(String customerId, String surname, String givenNames, LocalDate dateOfBirth, String sex,
                          String nationality, String passportNumber, LocalDate passportExpiry, String nationalId,
                          String riskLevel, boolean sanctionsHit, String faceBehavior, String fingerBehavior,
                          boolean existingCustomer, String mobile, String email, String scenario) {
        public String fullName() { return givenNames + " " + surname; }
    }

    private final Map<String, Persona> byId = new ConcurrentHashMap<>();
    private final Map<String, Persona> byPassport = new ConcurrentHashMap<>();
    private final Map<String, Persona> byNationalId = new ConcurrentHashMap<>();
    private final Map<String, CustomerInfo> customers = new ConcurrentHashMap<>();
    private final Map<String, AccountInfo> accounts = new ConcurrentHashMap<>();
    private final Map<String, String> primaryAccount = new ConcurrentHashMap<>();
    private final Map<String, AccountInfo> accountsByReference = new ConcurrentHashMap<>();
    private final Map<String, BigDecimal> balances = new ConcurrentHashMap<>();
    private final Map<String, CreditResult> credits = new ConcurrentHashMap<>();
    private final Map<String, CreditRequest> hidden = new ConcurrentHashMap<>();
    private final Map<String, CardIssuance> cardsByReference = new ConcurrentHashMap<>();
    private final Map<String, String> cardStatus = new ConcurrentHashMap<>();
    private final Map<String, String> cardAccount = new ConcurrentHashMap<>();
    private final Map<String, Integer> faceAttempts = new ConcurrentHashMap<>();
    private final Map<String, Integer> fingerAttempts = new ConcurrentHashMap<>();
    private final AtomicLong accountSeq = new AtomicLong(1000000100L);
    private final SecureRandom rng = new SecureRandom();
    private final long latencyMs;

    public MockBankAdapter(@Value("${stm.mock.latency-ms:0}") long latencyMs) {
        this.latencyMs = latencyMs;
        LocalDate exp = LocalDate.of(2031, 9, 2);
        seed(new Persona("CUST-001", "KAMAU", "WANJIKU", LocalDate.of(1988, 5, 20), "F", "KEN", "BK123456",
                LocalDate.of(2030, 1, 15), "12345678", "LOW", false, "MATCH", "MATCH", true,
                "+254700000001", "wanjiku@example.com", "Existing customer: card issue, deposit happy path"));
        seed(new Persona("CUST-002", "SMITH", "JOHN", LocalDate.of(1980, 11, 2), "M", "GBR", "P9876543",
                LocalDate.of(2029, 6, 30), null, "LOW", false, "MATCH", "MATCH", true,
                "+254700000002", "john@example.com", "Existing foreign customer"));
        seed(new Persona("CUST-003", "PERSON", "RISKY", LocalDate.of(1975, 1, 1), "M", "KEN", "R0000003",
                exp, "99999999", "HIGH", true, "MATCH", "MATCH", false, null, null,
                "Sanctions hit: kiosk gets a neutral decline (no tipping-off)"));
        seed(new Persona("CUST-004", "TESTER", "TIMEOUT", LocalDate.of(1985, 2, 2), "M", "KEN", "T0000004",
                exp, "11111111", "LOW", false, "MATCH", "MATCH", true, "+254700000004", null,
                "Deposit: bank times out, reconciler resolves"));
        seed(new Persona("CUST-005", "TESTER", "DECLINE", LocalDate.of(1986, 3, 3), "F", "KEN", "D0000005",
                exp, "22222222", "LOW", false, "MATCH", "MATCH", true, "+254700000005", null,
                "Deposit: bank declines, cash returned"));
        seed(new Persona("CUST-006", "OKAFOR", "AMARA N", LocalDate.of(1990, 3, 14), "F", "KEN", "A1234567",
                exp, null, "LOW", false, "MATCH", "MATCH", false, null, null,
                "NEW customer happy path (matches the kiosk UI mock-ups)"));
        seed(new Persona("CUST-007", "TESTER", "FACE RETRY", LocalDate.of(1991, 4, 4), "F", "KEN", "B0000007",
                exp, null, "LOW", false, "FAIL_ONCE", "MATCH", false, null, null,
                "Face fails once then matches (Error B, retry face)"));
        seed(new Persona("CUST-008", "TESTER", "FINGER FAIL", LocalDate.of(1992, 5, 5), "M", "KEN", "B0000008",
                exp, null, "LOW", false, "MATCH", "FAIL_ALWAYS", false, null, null,
                "Fingerprint never matches: escalate to a video teller"));
        seed(new Persona("CUST-009", "REVIEW", "HIGH RISK", LocalDate.of(1983, 6, 6), "F", "KEN", "H0000009",
                exp, null, "HIGH", false, "MATCH", "MATCH", false, null, null,
                "High risk, no sanctions hit: teller must approve before account opens"));
        cardStatus.put("TEST-CARD-001", "ACTIVE");
        cardAccount.put("TEST-CARD-001", "1000000001");
    }

    private void seed(Persona p) {
        byId.put(p.customerId(), p);
        if (p.passportNumber() != null) byPassport.put(p.passportNumber(), p);
        if (p.nationalId() != null) byNationalId.put(p.nationalId(), p);
        if (p.existingCustomer()) {
            customers.put(p.customerId(), new CustomerInfo(p.customerId(), p.surname(), p.givenNames(),
                    p.nationality(), p.mobile(), p.email()));
            String no = "100000000" + p.customerId().substring(p.customerId().length() - 1);
            AccountInfo acc = new AccountInfo(no, p.customerId(), "KES", "SAVINGS", "NBO-01");
            accounts.put(no, acc);
            primaryAccount.put(p.customerId(), no);
            balances.put(no, BigDecimal.ZERO);
        }
    }

    public List<Persona> personas() {
        return byId.values().stream().sorted(Comparator.comparing(Persona::customerId)).toList();
    }

    public Optional<String> accountNoOf(String customerId) {
        return Optional.ofNullable(primaryAccount.get(customerId));
    }

    public BigDecimal balance(String accountNo) {
        return balances.getOrDefault(accountNo, BigDecimal.ZERO);
    }

    public String cardStatusOf(String cardRef) {
        return cardStatus.get(cardRef);
    }

    private void pause() {
        if (latencyMs > 0) {
            try { Thread.sleep(latencyMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    // ---------------------------------------------------------------- eKYC
    @Override
    public KycResult verifyIdentity(IdentityCheck c) {
        pause();
        Persona p = "PASSPORT".equalsIgnoreCase(c.documentType())
                ? byPassport.get(c.documentNumber()) : byNationalId.get(c.documentNumber());
        if (p == null) return new KycResult(false, null, null, false, false, "NOT_FOUND");
        if (c.dateOfBirth() != null && !c.dateOfBirth().equals(p.dateOfBirth())) {
            return new KycResult(false, null, null, false, false, "DETAILS_MISMATCH");
        }
        boolean review = p.sanctionsHit() || "HIGH".equals(p.riskLevel());
        return new KycResult(true, p.customerId(), p.riskLevel(), p.sanctionsHit(), review,
                p.sanctionsHit() ? "SANCTIONS_WATCHLIST" : null);
    }

    @Override
    public BiometricResult matchFace(String subjectRef, byte[] faceImage) {
        return match(subjectRef, faceAttempts, true);
    }

    @Override
    public BiometricResult matchFingerprint(String subjectRef, byte[] template) {
        return match(subjectRef, fingerAttempts, false);
    }

    private BiometricResult match(String subjectRef, Map<String, Integer> attempts, boolean face) {
        pause();
        Persona p = byId.get(subjectRef);
        if (p == null) return new BiometricResult(false, 0, "SUBJECT_NOT_FOUND");
        String behavior = face ? p.faceBehavior() : p.fingerBehavior();
        int n = attempts.merge(subjectRef, 1, Integer::sum);
        boolean ok = switch (behavior) {
            case "FAIL_ALWAYS" -> false;
            case "FAIL_ONCE" -> n > 1;
            default -> true;
        };
        if (ok) attempts.remove(subjectRef);   // so FAIL_ONCE fails once again in the next demo session
        return new BiometricResult(ok, ok ? 93 : 41, ok ? null : "NO_MATCH");
    }

    // ---------------------------------------------------------------- CBS
    @Override
    public Optional<CustomerInfo> lookupCustomer(String customerId) {
        return Optional.ofNullable(customers.get(customerId));
    }

    @Override
    public synchronized AccountInfo openAccount(OpenAccountRequest r) {
        pause();
        AccountInfo prior = accountsByReference.get(r.reference());
        if (prior != null) return prior;
        Persona p = byId.get(r.registryRef());
        if (p == null) throw new BankingException("CUSTOMER_NOT_FOUND", "Unknown customer");
        if (p.sanctionsHit()) throw new BankingException("ACCOUNT_OPENING_DECLINED", "Account opening declined");
        if (p.existingCustomer()) throw new BankingException("CUSTOMER_ALREADY_EXISTS", "Customer already has an account");
        customers.computeIfAbsent(p.customerId(), k -> new CustomerInfo(k, p.surname(), p.givenNames(),
                p.nationality(), r.mobile(), r.email()));
        String no = String.valueOf(accountSeq.incrementAndGet());
        AccountInfo acc = new AccountInfo(no, p.customerId(), r.currency(), r.productCode(), r.branchCode());
        accounts.put(no, acc);
        accountsByReference.put(r.reference(), acc);
        primaryAccount.putIfAbsent(p.customerId(), no);
        balances.put(no, BigDecimal.ZERO);
        return acc;
    }

    @Override
    public Optional<AccountInfo> getAccount(String accountNo) {
        return Optional.ofNullable(accounts.get(accountNo));
    }

    @Override
    public Optional<AccountInfo> findAccount(LookupMethod method, String value) {
        pause();
        return switch (method) {
            case ACCOUNT_NUMBER -> Optional.ofNullable(accounts.get(value));
            case ID_NUMBER -> Optional.ofNullable(byNationalId.get(value)).flatMap(p -> accountOfCustomer(p.customerId()));
            case PASSPORT_NUMBER -> Optional.ofNullable(byPassport.get(value)).flatMap(p -> accountOfCustomer(p.customerId()));
            case CARD_TOKEN -> Optional.ofNullable(cardAccount.get(value))
                    .filter(x -> "ACTIVE".equals(cardStatus.get(value)))
                    .map(accounts::get);
        };
    }

    private Optional<AccountInfo> accountOfCustomer(String customerId) {
        return Optional.ofNullable(primaryAccount.get(customerId)).map(accounts::get);
    }

    // ---------------------------------------------------------------- Payment switch
    @Override
    public synchronized CreditResult credit(CreditRequest r) {
        pause();
        CreditResult prior = credits.get(r.reference());
        if (prior != null) return prior;
        if (hidden.containsKey(r.reference())) return CreditResult.unknown("BANK_TIMEOUT");
        AccountInfo acc = accounts.get(r.accountNo());
        if (acc == null) return remember(r, new CreditResult(Status.DECLINED, null, "ACCOUNT_NOT_FOUND"));
        if (TIMEOUT_CUSTOMER.equals(acc.customerId())) {
            hidden.put(r.reference(), r);            // bank processes it, but the caller never hears back
            return CreditResult.unknown("BANK_TIMEOUT");
        }
        if (DECLINE_CUSTOMER.equals(acc.customerId())) {
            return remember(r, new CreditResult(Status.DECLINED, null, "DO_NOT_HONOUR"));
        }
        return post(r);
    }

    @Override
    public synchronized CreditResult queryStatus(String reference) {
        CreditResult c = credits.get(reference);
        if (c != null) return c;
        CreditRequest h = hidden.remove(reference);
        if (h != null) return post(h);
        return new CreditResult(Status.NOT_FOUND, null, null);
    }

    private CreditResult post(CreditRequest r) {
        balances.merge(r.accountNo(), r.amount(), BigDecimal::add);
        return remember(r, new CreditResult(Status.APPROVED, "MOCK-" + UUID.randomUUID().toString().substring(0, 8), null));
    }

    private CreditResult remember(CreditRequest r, CreditResult res) {
        credits.put(r.reference(), res);
        return res;
    }

    // ---------------------------------------------------------------- Card management
    @Override
    public synchronized CardIssuance issueCard(IssueCardRequest r) {
        pause();
        CardIssuance prior = cardsByReference.get(r.reference());
        if (prior != null) return prior;
        if (!accounts.containsKey(r.accountNo())) throw new BankingException("ACCOUNT_NOT_FOUND", "Unknown account");
        if (r.encryptedPinBlock() == null || !r.encryptedPinBlock().matches("[0-9A-Fa-f]{16,64}")) {
            throw new BankingException("INVALID_PIN_BLOCK", "PIN block rejected");
        }
        String cardRef = "CARD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        StringBuilder last4 = new StringBuilder();
        for (int i = 0; i < 4; i++) last4.append(rng.nextInt(10));
        String masked = "************" + last4;
        String expiry = YearMonth.now().plusYears(4).format(DateTimeFormatter.ofPattern("MM/yy"));
        // DEMO ONLY: a real CMS returns an encrypted personalisation blob; this one carries no PAN and no PIN.
        String payload = Base64.getEncoder().encodeToString(
                ("DEMO-ONLY|" + cardRef + "|" + masked + "|" + expiry + "|" + r.embossedName())
                        .getBytes(StandardCharsets.UTF_8));
        CardIssuance ci = new CardIssuance(cardRef, masked, expiry, payload);
        cardsByReference.put(r.reference(), ci);
        cardStatus.put(cardRef, "RESERVED");
        cardAccount.put(cardRef, r.accountNo());
        return ci;
    }

    @Override
    public void activateCard(String cardRef) {
        if (cardStatus.replace(cardRef, "ACTIVE") == null) throw new BankingException("CARD_NOT_FOUND", "Unknown card");
    }

    @Override
    public void cancelCard(String cardRef, String reason) {
        cardStatus.replace(cardRef, "CANCELLED");
    }

    // ---------------------------------------------------------------- Notifications
    @Override
    public void send(Channel channel, String to, String subject, String body) {
        String masked = to == null || to.length() < 5 ? "***" : to.substring(0, 3) + "***" + to.substring(to.length() - 2);
        log.info("[DEMO NOTIFICATION] {} to {} | {}", channel, masked, subject);
    }
}
