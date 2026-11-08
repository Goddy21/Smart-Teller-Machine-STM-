package com.stm.middleware.banking.api;

import java.math.BigDecimal;

public interface PaymentSwitchPort {
    /** Must be idempotent on {@code request.reference()}. */
    CreditResult credit(CreditRequest request);

    /** Used by the reconciler to resolve unknown outcomes. */
    CreditResult queryStatus(String reference);

    record CreditRequest(String reference, String accountNo, BigDecimal amount, String currency) {}

    enum Status { APPROVED, DECLINED, UNKNOWN, NOT_FOUND }

    record CreditResult(Status status, String bankReference, String reason) {
        public static CreditResult unknown(String reason) {
            return new CreditResult(Status.UNKNOWN, null, reason);
        }
    }
}
