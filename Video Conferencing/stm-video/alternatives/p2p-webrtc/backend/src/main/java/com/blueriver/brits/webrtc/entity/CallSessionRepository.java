package com.blueriver.brits.webrtc.entity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CallSessionRepository extends JpaRepository<CallSession, String> {
  List<CallSession> findByTxIdOrderByRequestedAtAsc(String txId);
}
