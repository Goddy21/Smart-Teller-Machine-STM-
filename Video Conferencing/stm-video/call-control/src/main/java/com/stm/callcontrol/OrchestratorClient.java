package com.stm.callcontrol;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Boundary to the Transaction Orchestrator. Replace the logging with signed service-to-service calls.
 *  The orchestrator must re-validate teller, call state and limits itself; never trust this service blindly. */
@Component
public class OrchestratorClient {
  private static final Logger log = LoggerFactory.getLogger(OrchestratorClient.class);
  public void approve(String txId, String callId, String tellerId, boolean approve, long amount) {
    log.info("ORCH decision tx={} call={} teller={} approve={} amount={}", txId, callId, tellerId, approve, amount);
  }
  public void callInterrupted(String txId) { log.warn("ORCH hold escrow, pause timers tx={}", txId); }
  public void callAbandoned(String txId)   { log.warn("ORCH cancel + compensating rollback tx={}", txId); }
}
