package com.stm.middleware.orchestrator;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import com.stm.middleware.gateway.TerminalPrincipal;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/kiosk/transactions")
public class TransactionController {
    private final TransactionService service;

    public TransactionController(TransactionService service) {
        this.service = service;
    }

    public record CreateRequest(@NotBlank String customerId, @NotBlank String accountNo,
                                @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency) {}

    public record EscrowRequest(@NotNull @DecimalMin("0.01") BigDecimal amount) {}

    public record TxView(UUID id, String state, String customerId, String accountNo, String currency,
                         BigDecimal amount, String bankReference, String failureReason, boolean cashReturnRequired,
                         OffsetDateTime createdAt, OffsetDateTime updatedAt) {
        static TxView of(Tx t) {
            return new TxView(t.id(), t.state().name(), t.customerId(), t.accountNo(), t.currency(), t.amount(),
                    t.bankReference(), t.failureReason(), t.state() == TxState.ROLLED_BACK && t.amount() != null,
                    t.createdAt(), t.updatedAt());
        }
    }

    @PostMapping
    public ResponseEntity<TxView> create(@AuthenticationPrincipal TerminalPrincipal p,
                                         @RequestHeader("Idempotency-Key") String key,
                                         @Valid @RequestBody CreateRequest r) {
        Tx t = service.create(p, key, r.customerId(), r.accountNo(), r.currency());
        return ResponseEntity.status(HttpStatus.CREATED).body(TxView.of(t));
    }

    @GetMapping("/{id}")
    public TxView get(@AuthenticationPrincipal TerminalPrincipal p, @PathVariable UUID id) {
        return TxView.of(service.get(p, id));
    }

    /** Kiosk reports cash physically accepted into escrow (cash recycler). */
    @PostMapping("/{id}/escrow")
    public TxView escrow(@AuthenticationPrincipal TerminalPrincipal p, @PathVariable UUID id,
                         @Valid @RequestBody EscrowRequest r) {
        return TxView.of(service.escrow(p, id, r.amount()));
    }

    @PostMapping("/{id}/commit")
    public TxView commit(@AuthenticationPrincipal TerminalPrincipal p, @PathVariable UUID id) {
        return TxView.of(service.commit(p, id));
    }

    @PostMapping("/{id}/cancel")
    public TxView cancel(@AuthenticationPrincipal TerminalPrincipal p, @PathVariable UUID id) {
        return TxView.of(service.cancel(p, id));
    }
}
