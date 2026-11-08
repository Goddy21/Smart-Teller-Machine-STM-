package com.stm.callcontrol;

import java.util.Map;

/** Immutable snapshot of a call as read from Redis. */
public class Call {
  public final String id, terminalId, lang, txId, room, tellerId;
  public final long createdAt;
  public final CallState state;

  Call(Map<Object, Object> m) {
    this.id = (String) m.get("id");
    this.terminalId = (String) m.get("terminalId");
    this.lang = (String) m.get("lang");
    this.txId = (String) m.get("txId");
    this.tellerId = (String) m.get("tellerId");
    this.state = CallState.valueOf((String) m.get("state"));
    this.createdAt = Long.parseLong((String) m.get("createdAt"));
    this.room = "call-" + id;
  }
}
