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

    record Account(String id, String number, String customerId, String type, String productCode, String currency,
                   BigDecimal ledger, BigDecimal available, String branch, String status, boolean cardEligible) {
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

    record NewCustomer(String idNumber, String fullName, String firstName, String mobile, String kraPin, String addressJson) {}

    record Opened(String customerId, Account account) {}

    record Posting(String coreRef, BigDecimal previousBalance, BigDecimal balanceAfter, OffsetDateTime postedAt) {}

    record Line(OffsetDateTime date, String narrative, BigDecimal amount, String direction, BigDecimal balance) {}

    Optional<Customer> customer(String customerId);

    Optional<Customer> customerByIdNumber(String idNumber);

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

    BigDecimal glBalance(String gl, String currency);
}
