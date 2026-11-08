package com.blueriver.brits.webrtc.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** One assistance call. Timestamps are UTC. Fields are public for brevity; only CallRoutingService mutates them. */
@Entity
@Table(name = "call_session")
public class CallSession {
  @Id @Column(length = 36) public String id;
  @Column(name = "terminal_id", nullable = false, length = 64) public String terminalId;
  @Column(name = "teller_id", length = 64) public String tellerId;
  @Column(name = "tx_id", length = 64) public String txId;
  @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) public CallStatus status;
  @Column(name = "end_reason", length = 64) public String endReason;
  @Column(name = "requested_at", nullable = false) public LocalDateTime requestedAt;
  @Column(name = "accepted_at") public LocalDateTime acceptedAt;
  @Column(name = "connected_at") public LocalDateTime connectedAt;
  @Column(name = "ended_at") public LocalDateTime endedAt;

  /** Tellers who already declined or missed this call, so it is not offered to them again. */
  @Transient public final Set<String> triedTellers = ConcurrentHashMap.newKeySet();

  protected CallSession() {}

  public CallSession(String id, String terminalId, String txId) {
    this.id = id; this.terminalId = terminalId; this.txId = txId;
    this.status = CallStatus.QUEUED;
    this.requestedAt = LocalDateTime.now(ZoneOffset.UTC);
  }

  public String kioskIdentity() { return "kiosk:" + terminalId; }
  public String tellerIdentity() { return tellerId == null ? null : "teller:" + tellerId; }
}
