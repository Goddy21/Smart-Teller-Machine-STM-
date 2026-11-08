package com.blueriver.brits.webrtc.service;

import com.blueriver.brits.webrtc.entity.CallSession;
import com.blueriver.brits.webrtc.entity.CallSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Live calls in memory (fast validation of every signaling message) with every change written to SQL Server.
 *  A database failure raises an ALERT but never drops a live call. */
@Service
public class CallSessionService {
  private static final Logger log = LoggerFactory.getLogger(CallSessionService.class);
  private final CallSessionRepository repo;
  private final Map<String, CallSession> active = new ConcurrentHashMap<>();

  public CallSessionService(CallSessionRepository repo) { this.repo = repo; }

  public CallSession create(String terminalId, String txId) {
    CallSession c = new CallSession(UUID.randomUUID().toString(), terminalId, txId);
    active.put(c.id, c);
    save(c);
    return c;
  }

  public CallSession require(String id) {
    CallSession c = id == null ? null : active.get(id);
    if (c == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no active call " + id);
    return c;
  }

  public Optional<CallSession> find(String id) { return Optional.ofNullable(active.get(id)); }
  public Collection<CallSession> activeCalls() { return active.values(); }

  public Optional<CallSession> activeForTerminal(String terminalId) {
    return active.values().stream().filter(c -> c.terminalId.equals(terminalId)).findFirst();
  }
  public Optional<CallSession> activeForTeller(String tellerId) {
    return active.values().stream().filter(c -> tellerId.equals(c.tellerId)).findFirst();
  }

  public void update(CallSession c) {
    save(c);
    if (c.status.isTerminal()) active.remove(c.id);
  }

  private void save(CallSession c) {
    try { repo.save(c); } catch (Exception e) { log.error("ALERT call_session write failed call={}", c.id, e); }
  }
}
