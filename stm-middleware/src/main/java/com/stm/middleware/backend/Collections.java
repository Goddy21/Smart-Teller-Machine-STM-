package com.stm.middleware.backend;

import java.util.Optional;

/** The bank's collections / M-PESA Paybill service (K-1 Paybill deposits). */
public interface Collections {
    record Biller(String paybill, String name, String settlementGl) {}

    Optional<Biller> paybill(String paybill, String accountReference);
}
