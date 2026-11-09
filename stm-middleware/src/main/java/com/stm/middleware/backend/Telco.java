package com.stm.middleware.backend;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * Mobile network operator checks (Safaricom, Airtel, Telkom SIM-swap APIs in a real adapter).
 * The demo profile uses backend.sim.SimulatedTelco.
 */
public interface Telco {

    /** When the SIM behind this number was last swapped. Empty when the operator has no swap on record. */
    Optional<OffsetDateTime> lastSimSwap(String msisdn);
}
