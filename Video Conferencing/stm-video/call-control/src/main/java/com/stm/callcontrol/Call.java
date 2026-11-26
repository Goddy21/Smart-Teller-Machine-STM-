package com.stm.callcontrol;

import java.util.Map;

/** Immutable snapshot of a call as read from Redis. */
public class Call {
  public final String id, terminalId, lang, txId, room, tellerId;
  /** Who ended the call (CUSTOMER, TELLER, SYSTEM) and why (CUSTOMER_HUNG_UP, NO_TELLER_AVAILABLE, ...); null while live. */
  public final String endedBy, endReason;
  public final long createdAt;
  public final CallState state;

  Call(Map<Object, Object> m) {
    this.id = (String) m.get("id");
    this.terminalId = (String) m.get("terminalId");
    this.lang = (String) m.get("lang");
    this.txId = (String) m.get("txId");
    this.tellerId = (String) m.get("tellerId");
    this.endedBy = (String) m.get("endedBy");
    this.endReason = (String) m.get("endReason");
    this.state = CallState.valueOf((String) m.get("state"));
    this.createdAt = Long.parseLong((String) m.get("createdAt"));
    this.room = "call-" + id;
  }
}
