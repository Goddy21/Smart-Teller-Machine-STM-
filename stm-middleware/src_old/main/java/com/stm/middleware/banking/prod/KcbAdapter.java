package com.stm.middleware.banking.prod;

import com.stm.middleware.banking.api.BankingException;
import com.stm.middleware.banking.api.CardManagementPort;
import com.stm.middleware.banking.api.CoreBankingPort;
import com.stm.middleware.banking.api.EkycPort;
import com.stm.middleware.banking.api.NotificationPort;
import com.stm.middleware.banking.api.PaymentSwitchPort;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * PHASE 2 PLACEHOLDER. Replace with real KCB integrations (split into one class per port when specs arrive):
 * CBS (REST/SOAP), payment switch (ISO 8583 / ISO 20022), CMS, IPRS/eKYC, SMS/email gateway.
 * Keep KCB field mapping in here only. Wrap outbound calls with timeouts, circuit breakers and retries
 * (idempotent calls only).
 */
@Component
@Profile("prod")
public class KcbAdapter implements CoreBankingPort, PaymentSwitchPort, CardManagementPort, EkycPort, NotificationPort {

    private static BankingException nyi() {
        return new BankingException("NOT_IMPLEMENTED", "KCB adapter not implemented yet (Phase 2)");
    }

    @Override public Optional<CustomerInfo> lookupCustomer(String customerId) { throw nyi(); }
    @Override public AccountInfo openAccount(OpenAccountRequest request) { throw nyi(); }
    @Override public Optional<AccountInfo> getAccount(String accountNo) { throw nyi(); }
    @Override public Optional<AccountInfo> findAccount(LookupMethod method, String value) { throw nyi(); }

    @Override public CreditResult credit(CreditRequest request) { throw nyi(); }
    @Override public CreditResult queryStatus(String reference) { throw nyi(); }

    @Override public CardIssuance issueCard(IssueCardRequest request) { throw nyi(); }
    @Override public void activateCard(String cardRef) { throw nyi(); }
    @Override public void cancelCard(String cardRef, String reason) { throw nyi(); }

    @Override public KycResult verifyIdentity(IdentityCheck check) { throw nyi(); }
    @Override public BiometricResult matchFace(String subjectRef, byte[] faceImage) { throw nyi(); }
    @Override public BiometricResult matchFingerprint(String subjectRef, byte[] template) { throw nyi(); }

    @Override public void send(Channel channel, String to, String subject, String body) { throw nyi(); }
}
