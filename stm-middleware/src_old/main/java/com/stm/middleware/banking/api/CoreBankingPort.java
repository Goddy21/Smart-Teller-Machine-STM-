package com.stm.middleware.banking.api;

import java.time.LocalDate;
import java.util.Optional;

/** Bank-neutral core banking port. Bank-specific field mapping belongs in the adapter, never here. */
public interface CoreBankingPort {
    Optional<CustomerInfo> lookupCustomer(String customerId);

    /** Creates the customer (CIF) if needed and opens the account. MUST be idempotent on {@code request.reference()}. */
    AccountInfo openAccount(OpenAccountRequest request);

    Optional<AccountInfo> getAccount(String accountNo);

    /** Finds a customer's primary account by account number, national ID, passport number or card token. */
    Optional<AccountInfo> findAccount(LookupMethod method, String value);

    enum LookupMethod { ACCOUNT_NUMBER, ID_NUMBER, PASSPORT_NUMBER, CARD_TOKEN }

    record CustomerInfo(String customerId, String surname, String givenNames, String nationality,
                        String mobile, String email) {}

    record AccountInfo(String accountNo, String customerId, String currency, String productCode, String branchCode) {}

    record OpenAccountRequest(String reference, String registryRef, String fullName, LocalDate dateOfBirth,
                              String nationality, String documentType, String documentNumber,
                              String mobile, String email, String productCode, String currency,
                              String tier, String branchCode) {}
}
