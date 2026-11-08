package com.stm.middleware.orchestrator;

/**
 * Deposit transaction lifecycle.
 *
 * INITIATED -> CASH_ESCROWED -> CREDIT_PENDING -> COMMITTED
 *                  |                 |  \--> UNKNOWN_OUTCOME --(reconciler)--> COMMITTED | COMPENSATING
 *                  |                 \------> COMPENSATING -> ROLLED_BACK (cash must be returned) | FAILED
 *                  \--> COMPENSATING (customer cancel)
 * INITIATED -> CANCELLED
 */
public enum TxState {
    INITIATED, CASH_ESCROWED, CREDIT_PENDING, UNKNOWN_OUTCOME, COMPENSATING,
    COMMITTED, ROLLED_BACK, CANCELLED, FAILED;

    public boolean canGoTo(TxState n) {
        return switch (this) {
            case INITIATED -> n == CASH_ESCROWED || n == CANCELLED;
            case CASH_ESCROWED -> n == CREDIT_PENDING || n == COMPENSATING;
            case CREDIT_PENDING -> n == COMMITTED || n == UNKNOWN_OUTCOME || n == COMPENSATING;
            case UNKNOWN_OUTCOME -> n == COMMITTED || n == COMPENSATING;
            case COMPENSATING -> n == ROLLED_BACK || n == FAILED;
            default -> false;
        };
    }

    public boolean isTerminal() {
        return this == COMMITTED || this == ROLLED_BACK || this == CANCELLED || this == FAILED;
    }
}
