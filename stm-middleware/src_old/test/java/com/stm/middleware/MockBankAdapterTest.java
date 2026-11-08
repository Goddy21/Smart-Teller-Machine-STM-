package com.stm.middleware;

import com.stm.middleware.banking.api.BankingException;
import com.stm.middleware.banking.api.CardManagementPort.CardIssuance;
import com.stm.middleware.banking.api.CardManagementPort.IssueCardRequest;
import com.stm.middleware.banking.api.CoreBankingPort.AccountInfo;
import com.stm.middleware.banking.api.CoreBankingPort.LookupMethod;
import com.stm.middleware.banking.api.CoreBankingPort.OpenAccountRequest;
import com.stm.middleware.banking.api.EkycPort.IdentityCheck;
import com.stm.middleware.banking.api.PaymentSwitchPort.CreditRequest;
import com.stm.middleware.banking.api.PaymentSwitchPort.CreditResult;
import com.stm.middleware.banking.api.PaymentSwitchPort.Status;
import com.stm.middleware.banking.mock.MockBankAdapter;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class MockBankAdapterTest {
    private final MockBankAdapter bank = new MockBankAdapter(0);

    private static CreditRequest credit(String ref, String acc, String amt) {
        return new CreditRequest(ref, acc, new BigDecimal(amt), "KES");
    }

    private static OpenAccountRequest open(String ref, String registryRef) {
        return new OpenAccountRequest(ref, registryRef, "X", LocalDate.of(1990, 3, 14), "KEN", "PASSPORT", "A1234567",
                "+254700111222", null, "SAVINGS", "KES", "BASIC", "NBO-01");
    }

    // ---------------- payment switch (deposit flow)
    @Test
    void creditIsIdempotentOnReference() {
        CreditResult a = bank.credit(credit("tx-1", "1000000001", "500"));
        CreditResult b = bank.credit(credit("tx-1", "1000000001", "500"));
        assertEquals(Status.APPROVED, a.status());
        assertEquals(a.bankReference(), b.bankReference());
        assertEquals(0, new BigDecimal("500").compareTo(bank.balance("1000000001")));
    }

    @Test
    void timeoutPersonaReturnsUnknownThenResolvesToApprovedOnQuery() {
        assertEquals(Status.UNKNOWN, bank.credit(credit("tx-2", "1000000004", "100")).status());
        assertEquals(Status.UNKNOWN, bank.credit(credit("tx-2", "1000000004", "100")).status());
        assertEquals(Status.APPROVED, bank.queryStatus("tx-2").status());
        assertEquals(0, new BigDecimal("100").compareTo(bank.balance("1000000004")));
    }

    @Test
    void declinePersonaIsDeclinedAndUnknownReferenceNotFound() {
        assertEquals(Status.DECLINED, bank.credit(credit("tx-3", "1000000005", "100")).status());
        assertEquals(Status.NOT_FOUND, bank.queryStatus("never-sent").status());
    }

    // ---------------- identity and biometrics
    @Test
    void registryMatchesPassportAndDateOfBirth() {
        var ok = bank.verifyIdentity(new IdentityCheck("PASSPORT", "A1234567", "OKAFOR", "AMARA N", LocalDate.of(1990, 3, 14), "KEN"));
        assertTrue(ok.verified());
        assertEquals("CUST-006", ok.subjectRef());
        var wrongDob = bank.verifyIdentity(new IdentityCheck("PASSPORT", "A1234567", "OKAFOR", "AMARA N", LocalDate.of(1990, 3, 15), "KEN"));
        assertFalse(wrongDob.verified());
        assertFalse(bank.verifyIdentity(new IdentityCheck("PASSPORT", "ZZ999999", "X", "Y", null, "KEN")).verified());
    }

    @Test
    void sanctionedAndHighRiskPersonasAreFlagged() {
        var s = bank.verifyIdentity(new IdentityCheck("PASSPORT", "R0000003", "PERSON", "RISKY", LocalDate.of(1975, 1, 1), "KEN"));
        assertTrue(s.sanctionsHit());
        assertThrows(BankingException.class, () -> bank.openAccount(open("s-1", "CUST-003")));
        var h = bank.verifyIdentity(new IdentityCheck("PASSPORT", "H0000009", "REVIEW", "HIGH RISK", LocalDate.of(1983, 6, 6), "KEN"));
        assertTrue(h.manualReviewRequired());
        assertFalse(h.sanctionsHit());
    }

    @Test
    void faceFailsOnceThenMatchesAndFingerAlwaysFailsForThePersonasThatSayso() {
        byte[] img = new byte[16];
        assertFalse(bank.matchFace("CUST-007", img).matched());
        assertTrue(bank.matchFace("CUST-007", img).matched());
        assertFalse(bank.matchFace("CUST-007", img).matched());   // counter reset: fails once again next session
        assertFalse(bank.matchFingerprint("CUST-008", img).matched());
        assertFalse(bank.matchFingerprint("CUST-008", img).matched());
        assertTrue(bank.matchFace("CUST-006", img).matched());
    }

    // ---------------- core banking
    @Test
    void newCustomerCanOpenAccountAndOpeningIsIdempotentOnReference() {
        AccountInfo a = bank.openAccount(open("sess-1", "CUST-006"));
        AccountInfo b = bank.openAccount(open("sess-1", "CUST-006"));
        assertEquals(a.accountNo(), b.accountNo());
        assertEquals("CUST-006", a.customerId());
        assertEquals(a.accountNo(), bank.findAccount(LookupMethod.PASSPORT_NUMBER, "A1234567").orElseThrow().accountNo());
        assertEquals("OKAFOR", bank.lookupCustomer("CUST-006").orElseThrow().surname());
    }

    @Test
    void existingCustomerCannotOpenASecondAccountThroughTheKiosk() {
        assertThrows(BankingException.class, () -> bank.openAccount(open("sess-2", "CUST-001")));
    }

    @Test
    void accountLookupByNumberAndNationalId() {
        assertTrue(bank.findAccount(LookupMethod.ACCOUNT_NUMBER, "1000000001").isPresent());
        assertTrue(bank.findAccount(LookupMethod.ID_NUMBER, "12345678").isPresent());
        assertTrue(bank.findAccount(LookupMethod.ACCOUNT_NUMBER, "0000000000").isEmpty());
        assertTrue(bank.findAccount(LookupMethod.CARD_TOKEN, "TEST-CARD-001").isPresent());
    }

    // ---------------- cards
    @Test
    void cardIssueIsIdempotentActivationAndCancellationWork() {
        IssueCardRequest r = new IssueCardRequest("sess-9:card", "1000000001", "WANJIKU KAMAU", "0123456789ABCDEF", "KEY1");
        CardIssuance a = bank.issueCard(r);
        CardIssuance b = bank.issueCard(r);
        assertEquals(a.cardRef(), b.cardRef());
        assertTrue(a.maskedPan().startsWith("************"));
        assertEquals("RESERVED", bank.cardStatusOf(a.cardRef()));
        bank.activateCard(a.cardRef());
        assertEquals("ACTIVE", bank.cardStatusOf(a.cardRef()));
        bank.cancelCard(a.cardRef(), "test");
        assertEquals("CANCELLED", bank.cardStatusOf(a.cardRef()));
    }

    @Test
    void invalidPinBlockIsRejected() {
        assertThrows(BankingException.class,
                () -> bank.issueCard(new IssueCardRequest("sess-10:card", "1000000001", "X", "1234", null)));
    }
}
