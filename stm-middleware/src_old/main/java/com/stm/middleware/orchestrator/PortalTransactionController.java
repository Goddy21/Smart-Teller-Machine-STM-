package com.stm.middleware.orchestrator;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/portal/transactions")
public class PortalTransactionController {
    private final TransactionRepository repo;

    public PortalTransactionController(TransactionRepository repo) {
        this.repo = repo;
    }

    /** Operations view, e.g. ?state=UNKNOWN_OUTCOME to see transactions awaiting reconciliation. No state = the latest 200. */
    @GetMapping
    public List<TransactionController.TxView> byState(@RequestParam(required = false) TxState state) {
        List<Tx> rows = state == null ? repo.findRecent(200) : repo.findByState(state, 0);
        return rows.stream().map(TransactionController.TxView::of).toList();
    }
}
