package com.stm.middleware.orchestrator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Resolves transactions stuck in-flight (bank timeout, kiosk or middleware crash mid-call).
 * Safe to run on several instances: updates are optimistic-locked. Add ShedLock if you want a single runner.
 */
@Component
public class Reconciler {
    private static final Logger log = LoggerFactory.getLogger(Reconciler.class);

    private final TransactionRepository repo;
    private final TransactionService service;

    public Reconciler(TransactionRepository repo, TransactionService service) {
        this.repo = repo;
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${stm.reconcile.interval-ms:30000}")
    public void run() {
        sweep(TxState.UNKNOWN_OUTCOME, 10);
        sweep(TxState.CREDIT_PENDING, 120);
        sweep(TxState.COMPENSATING, 60);
    }

    private void sweep(TxState state, int olderThanSeconds) {
        for (Tx t : repo.findByState(state, olderThanSeconds)) {
            try {
                service.reconcile(t);
            } catch (Exception e) {
                log.warn("Reconcile failed for tx {} ({}): {}", t.id(), state, e.toString());
            }
        }
    }
}
