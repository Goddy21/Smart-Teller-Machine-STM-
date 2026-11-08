package com.stm.middleware;

import com.stm.middleware.orchestrator.TxState;
import org.junit.jupiter.api.Test;

import static com.stm.middleware.orchestrator.TxState.*;
import static org.junit.jupiter.api.Assertions.*;

class TxStateTest {
    @Test
    void happyPathIsAllowed() {
        assertTrue(INITIATED.canGoTo(CASH_ESCROWED));
        assertTrue(CASH_ESCROWED.canGoTo(CREDIT_PENDING));
        assertTrue(CREDIT_PENDING.canGoTo(COMMITTED));
    }

    @Test
    void unknownOutcomeCanResolveEitherWay() {
        assertTrue(CREDIT_PENDING.canGoTo(UNKNOWN_OUTCOME));
        assertTrue(UNKNOWN_OUTCOME.canGoTo(COMMITTED));
        assertTrue(UNKNOWN_OUTCOME.canGoTo(COMPENSATING));
    }

    @Test
    void cannotCommitWithoutEscrowedCash() {
        assertFalse(INITIATED.canGoTo(CREDIT_PENDING));
        assertFalse(INITIATED.canGoTo(COMMITTED));
    }

    @Test
    void terminalStatesHaveNoExits() {
        for (TxState s : TxState.values()) {
            if (s.isTerminal()) {
                for (TxState n : TxState.values()) assertFalse(s.canGoTo(n), s + " -> " + n);
            }
        }
    }
}
