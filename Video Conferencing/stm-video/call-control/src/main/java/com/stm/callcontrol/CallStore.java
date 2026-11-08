package com.stm.callcontrol;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;

/** All shared state lives here, so any call-control node can handle any request. */
@Component
public class CallStore {
  static final String P = "{stm}:";   // hash tag: every key lands in one Cluster slot, which the Lua scripts need
  static final Duration TELLER_TTL = Duration.ofSeconds(60);
  private final StringRedisTemplate r;
  private final RedisScript<String> claimScript = script("lua/claim.lua", String.class);
  private final RedisScript<Long> casScript = script("lua/cas.lua", Long.class);
  @SuppressWarnings("rawtypes") private final RedisScript<List> dueScript = script("lua/due.lua", List.class);

  public CallStore(StringRedisTemplate r) { this.r = r; }

  private static <T> RedisScript<T> script(String path, Class<T> type) {
    DefaultRedisScript<T> s = new DefaultRedisScript<>();
    s.setScriptSource(new ResourceScriptSource(new ClassPathResource(path)));
    s.setResultType(type);
    return s;
  }

  private String callKey(String id) { return P + "call:" + id; }
  private String tellerKey(String id) { return P + "teller:" + id; }

  // ---- calls ----
  public void create(String id, String terminalId, String lang, String txId, long now) {
    r.opsForHash().putAll(callKey(id), Map.of("id", id, "terminalId", terminalId, "lang", lang, "txId", txId,
        "state", CallState.REQUESTED.name(), "createdAt", Long.toString(now)));
    r.expire(callKey(id), Duration.ofDays(7));   // durable call records belong in Postgres; this is working state
  }

  public Call get(String id) {
    Map<Object, Object> m = r.opsForHash().entries(callKey(id));
    return m.isEmpty() ? null : new Call(m);
  }

  /** Atomic state change: succeeds only if the call is still in {@code from}. op: SET, CLEAR or NONE for tellerId. */
  public boolean cas(String id, CallState from, CallState to, String op, String tellerId) {
    Long ok = r.execute(casScript, List.of(callKey(id)), from.name(), to.name(), op, tellerId == null ? "" : tellerId);
    return ok != null && ok == 1L;
  }

  /** Atomically move a QUEUED call to RINGING with the first available matching teller. Returns the teller id or null. */
  public String claim(Call c) {
    return r.execute(claimScript, List.of(callKey(c.id), P + "queue", P + "tellers"), P, c.lang, c.id);
  }

  // ---- queue (ordered by original request time, so a re-queued call keeps its place) ----
  public void enqueue(String id, long score) { r.opsForZSet().add(P + "queue", id, score); }
  public void dequeue(String id) { r.opsForZSet().remove(P + "queue", id); }
  public Set<String> queued() { Set<String> s = r.opsForZSet().range(P + "queue", 0, -1); return s == null ? Set.of() : s; }

  // ---- tellers (presence expires unless heartbeats arrive) ----
  public void setPresence(String id, boolean available, Set<String> langs) {
    r.opsForHash().putAll(tellerKey(id), Map.of("available", available ? "1" : "0", "langs", "," + String.join(",", langs) + ","));
    r.opsForSet().add(P + "tellers", id);
    r.expire(tellerKey(id), TELLER_TTL);
  }
  public void setAvailable(String id, boolean available) {
    if (Boolean.TRUE.equals(r.hasKey(tellerKey(id)))) {
      r.opsForHash().put(tellerKey(id), "available", available ? "1" : "0");
      r.expire(tellerKey(id), TELLER_TTL);
    }
  }
  public void heartbeat(String id) { r.expire(tellerKey(id), TELLER_TTL); }
  /** True/false from the teller's presence, null when the presence has expired. */
  public Boolean isAvailable(String id) {
    Object v = r.opsForHash().get(tellerKey(id), "available");
    return v == null ? null : "1".equals(v);
  }

  // ---- which call a terminal is in (for the kiosk's upper "teller display" screen) ----
  public void setTerminalCall(String terminalId, String callId) { r.opsForValue().set(P + "terminal-call:" + terminalId, callId, Duration.ofHours(2)); }
  public String getTerminalCall(String terminalId) { return r.opsForValue().get(P + "terminal-call:" + terminalId); }
  public void clearTerminalCall(String terminalId, String callId) {
    if (callId.equals(getTerminalCall(terminalId))) r.delete(P + "terminal-call:" + terminalId);
  }

  // ---- who is in the room ----
  public long addPresent(String callId, String identity) {
    r.opsForSet().add(callKey(callId) + ":present", identity);
    r.expire(callKey(callId) + ":present", Duration.ofDays(1));
    Long n = r.opsForSet().size(callKey(callId) + ":present");
    return n == null ? 0 : n;
  }
  public void removePresent(String callId, String identity) { r.opsForSet().remove(callKey(callId) + ":present", identity); }

  // ---- deadlines (ring timeout, reconnect window); survive restarts, handled by exactly one node ----
  public void schedule(String kind, String callId, long dueMs) { r.opsForZSet().add(P + "deadlines", kind + ":" + callId, dueMs); }
  @SuppressWarnings("unchecked")
  public List<String> takeDue(long nowMs) {
    List<String> out = r.execute(dueScript, List.of(P + "deadlines"), Long.toString(nowMs));
    return out == null ? List.of() : out;
  }
}
