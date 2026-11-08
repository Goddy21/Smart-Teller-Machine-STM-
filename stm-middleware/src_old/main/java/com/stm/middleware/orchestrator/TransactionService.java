package com.stm.middleware.orchestrator;

import com.stm.middleware.audit.AuditService;
import com.stm.middleware.banking.api.CoreBankingPort;
import com.stm.middleware.banking.api.PaymentSwitchPort;
import com.stm.middleware.banking.api.PaymentSwitchPort.CreditRequest;
import com.stm.middleware.banking.api.PaymentSwitchPort.CreditResult;
import com.stm.middleware.common.ApiException;
import com.stm.middleware.gateway.TerminalPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Deposit orchestration. Rules:
 *  - Every state change is its own short DB transaction (state + ledger + audit commit together).
 *  - The bank is NEVER called while a DB transaction is open.
 *  - Cash is recorded in the escrow ledger BEFORE the bank credit is attempted.
 *  - Every operation is idempotent: replays return the current state instead of failing.
 */
@Service
public class TransactionService {
    private static final Logger log = LoggerFactory.getLogger(TransactionService.class);

    private final TransactionRepository repo;
    private final EscrowLedgerRepository ledger;
    private final AuditService audit;
    private final PaymentSwitchPort paymentSwitch;
    private final CoreBankingPort coreBanking;
    private final TransactionTemplate txTemplate;

    public TransactionService(TransactionRepository repo, EscrowLedgerRepository ledger, AuditService audit,
                              PaymentSwitchPort paymentSwitch, CoreBankingPort coreBanking,
                              TransactionTemplate txTemplate) {
        this.repo = repo;
        this.ledger = ledger;
        this.audit = audit;
        this.paymentSwitch = paymentSwitch;
        this.coreBanking = coreBanking;
        this.txTemplate = txTemplate;
    }

    // ------------------------------------------------------------------ public API

    public Tx create(TerminalPrincipal p, String idempotencyKey, String customerId, String accountNo, String currency) {
        var existing = repo.findByIdempotencyKey(p.terminalId(), idempotencyKey);
        if (existing.isPresent()) return existing.get();

        var account = coreBanking.getAccount(accountNo)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "Unknown account"));
        if (!account.customerId().equals(customerId) || !account.currency().equals(currency)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "ACCOUNT_MISMATCH",
                    "Account does not belong to this customer or currency");
        }

        UUID id = UUID.randomUUID();
        try {
            txTemplate.executeWithoutResult(s -> {
                repo.insert(id, p.terminalId(), customerId, accountNo, currency, idempotencyKey);
                audit.record("TX_INITIATED", id.toString(), p.terminalId(),
                        Map.of("accountNo", accountNo, "currency", currency));
            });
        } catch (DuplicateKeyException race) {
            return repo.findByIdempotencyKey(p.terminalId(), idempotencyKey).orElseThrow();
        }
        return repo.findById(id).orElseThrow();
    }

    public Tx get(TerminalPrincipal p, UUID id) {
        return load(p, id);
    }

    public Tx escrow(TerminalPrincipal p, UUID id, BigDecimal amount) {
        Tx t = load(p, id);
        if (t.state() == TxState.CASH_ESCROWED && t.amount() != null && t.amount().compareTo(amount) == 0) return t; // replay
        if (t.state() != TxState.INITIATED) {
            throw new ApiException(HttpStatus.CONFLICT, "ILLEGAL_STATE", "Cannot escrow cash in state " + t.state());
        }
        return move(t, TxState.CASH_ESCROWED, amount, null, null, "CASH_ACCEPTED", "TX_CASH_ESCROWED");
    }

    public Tx commit(TerminalPrincipal p, UUID id) {
        Tx t = load(p, id);
        if (t.state() != TxState.CASH_ESCROWED) {
            if (t.state() == TxState.INITIATED) {
                throw new ApiException(HttpStatus.CONFLICT, "ILLEGAL_STATE", "No cash escrowed yet");
            }
            return t; // replay or already in flight / finished: return current state
        }
        t = move(t, TxState.CREDIT_PENDING, null, null, null, null, "TX_CREDIT_PENDING");

        CreditResult result;
        try {
            result = paymentSwitch.credit(new CreditRequest(t.id().toString(), t.accountNo(), t.amount(), t.currency()));
        } catch (RuntimeException e) {
            log.warn("Bank call failed for tx {}: {}", t.id(), e.toString());
            result = CreditResult.unknown("BANK_ERROR");
        }
        return applyCreditResult(t, result);
    }

    public Tx cancel(TerminalPrincipal p, UUID id) {
        Tx t = load(p, id);
        return switch (t.state()) {
            case INITIATED -> move(t, TxState.CANCELLED, null, null, "CUSTOMER_CANCELLED", null, "TX_CANCELLED");
            case CASH_ESCROWED -> compensate(t, "CUSTOMER_CANCELLED");
            case CREDIT_PENDING, UNKNOWN_OUTCOME -> throw new ApiException(HttpStatus.CONFLICT, "IN_FLIGHT",
                    "Bank outcome pending; cannot cancel");
            default -> t;
        };
    }

    /** Called by the Reconciler for stuck transactions. */
    public void reconcile(Tx t) {
        switch (t.state()) {
            case CREDIT_PENDING, UNKNOWN_OUTCOME -> applyCreditResult(t, paymentSwitch.queryStatus(t.id().toString()));
            case COMPENSATING -> move(t, TxState.ROLLED_BACK, null, null, null, "CASH_RETURN_REQUIRED", "TX_ROLLED_BACK");
            default -> { }
        }
    }

    // ------------------------------------------------------------------ internals

    private Tx applyCreditResult(Tx t, CreditResult r) {
        return switch (r.status()) {
            case APPROVED -> move(t, TxState.COMMITTED, null, r.bankReference(), null, "CREDIT_POSTED", "TX_COMMITTED");
            case DECLINED, NOT_FOUND -> compensate(t, r.reason() != null ? r.reason() : "BANK_DECLINED");
            case UNKNOWN -> t.state() == TxState.UNKNOWN_OUTCOME
                    ? t
                    : move(t, TxState.UNKNOWN_OUTCOME, null, null, r.reason(), null, "TX_UNKNOWN_OUTCOME");
        };
    }

    /** Cash was taken but will not be credited: record the obligation to hand it back to the customer. */
    private Tx compensate(Tx t, String reason) {
        Tx c = move(t, TxState.COMPENSATING, null, null, reason, null, "TX_COMPENSATING");
        return move(c, TxState.ROLLED_BACK, null, null, null, "CASH_RETURN_REQUIRED", "TX_ROLLED_BACK");
    }

    private Tx move(Tx t, TxState to, BigDecimal amount, String bankRef, String reason,
                    String ledgerType, String auditEvent) {
        if (!t.state().canGoTo(to)) {
            throw new ApiException(HttpStatus.CONFLICT, "ILLEGAL_TRANSITION", t.state() + " -> " + to);
        }
        Tx next = t.next(to, amount, bankRef, reason);
        final Tx n = (to == TxState.COMMITTED) ? next.withoutFailureReason() : next;
        txTemplate.executeWithoutResult(s -> {
            if (repo.update(n, t.version()) != 1) {
                throw new ApiException(HttpStatus.CONFLICT, "CONCURRENT_UPDATE", "Transaction was modified concurrently");
            }
            if (ledgerType != null) {
                ledger.append(n.id(), ledgerType, n.amount(), n.currency());
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("from", t.state().name());
            payload.put("to", to.name());
            if (n.amount() != null) payload.put("amount", n.amount());
            if (n.bankReference() != null) payload.put("bankReference", n.bankReference());
            if (n.failureReason() != null) payload.put("reason", n.failureReason());
            audit.record(auditEvent, n.id().toString(), n.terminalId(), payload);
        });
        return n;
    }

    private Tx load(TerminalPrincipal p, UUID id) {
        return repo.findById(id)
                .filter(t -> t.terminalId() == p.terminalId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TX_NOT_FOUND", "Transaction not found"));
    }
}
