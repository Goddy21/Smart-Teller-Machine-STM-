package com.stm.middleware.operators;

import java.util.List;
import java.util.Map;

/** What each kind of terminal user may do on the kiosk's maintenance screens. The kiosk app shows only these. */
public final class OperatorRoles {
    private OperatorRoles() {}

    public static final List<String> ROLES = List.of("CUSTODIAN", "CIT", "ENGINEER", "SUPERVISOR");

    public static final Map<String, List<String>> FUNCTIONS = Map.of(
            "CUSTODIAN", List.of("CASH_REPLENISH", "CASH_POSITION", "CLEAR_BINS", "LOAD_CARD_STOCK", "LOAD_PAPER", "VIEW_STATUS"),
            "CIT", List.of("CASH_REPLENISH", "CASH_POSITION", "CLEAR_BINS", "VIEW_STATUS"),
            "ENGINEER", List.of("DIAGNOSTICS", "MODULE_RESET", "LOAD_PAPER", "LOAD_CARD_STOCK", "SERVICE_MODE", "VIEW_STATUS",
                    "VIEW_LOGS"),
            "SUPERVISOR", List.of("CASH_REPLENISH", "CASH_POSITION", "CLEAR_BINS", "LOAD_CARD_STOCK", "LOAD_PAPER", "DIAGNOSTICS",
                    "MODULE_RESET", "SERVICE_MODE", "VIEW_STATUS", "VIEW_LOGS", "END_OF_DAY"));
}
