package com.blueriver.brits.webrtc.service;

import com.blueriver.brits.webrtc.entity.CallStatus;

/** Published when a call reaches a terminal state. The Transaction Orchestrator listens with @EventListener
 *  to hold escrow or roll back if a call dies mid-transaction. */
public record CallEndedEvent(String callId, String txId, String terminalId, String tellerId, CallStatus status, String reason) {}
