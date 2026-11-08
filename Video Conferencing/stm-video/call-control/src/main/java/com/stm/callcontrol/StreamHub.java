package com.stm.callcontrol;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** SSE connections are local to a node, but the event that should reach them can originate on any node.
 *  Every event goes through Redis pub/sub; each node delivers it to the emitters it holds. */
@Component
public class StreamHub {
  public static final String CHANNEL = "stm:events";
  private static final Logger log = LoggerFactory.getLogger(StreamHub.class);
  public record Ev(String target, String key, String name, Object data) {}

  private final Map<String, SseEmitter> kiosk = new ConcurrentHashMap<>(), teller = new ConcurrentHashMap<>(), display = new ConcurrentHashMap<>();
  private final StringRedisTemplate redis;
  private final ObjectMapper om;

  public StreamHub(StringRedisTemplate redis, ObjectMapper om) { this.redis = redis; this.om = om; }

  private Map<String, SseEmitter> map(String target) { return "kiosk".equals(target) ? kiosk : "display".equals(target) ? display : teller; }

  public SseEmitter open(String target, String key) {
    SseEmitter e = new SseEmitter(0L);
    Map<String, SseEmitter> m = map(target);
    m.put(key, e);
    Runnable drop = () -> { if (m.remove(key, e)) forget(target, key); };
    e.onCompletion(drop); e.onTimeout(drop); e.onError(x -> drop.run());
    // Send something at once: Spring holds the response headers until the first write, and a browser's fetch()
    // only resolves on headers. Without this the console waited for the first 15 s ping before it knew it was connected.
    try { e.send(SseEmitter.event().comment("open")); } catch (Exception ex) { drop.run(); }
    mark(target, key);
    return e;
  }

  // ---- liveness: a stream that silently died (laptop slept, Wi-Fi changed) is only noticed when we write to it.
  // Every 15 s each node pings its streams; a failed ping drops the stream. A Redis marker (any node) tells the
  // teller console, through its heartbeat, whether the service still has a live event stream for it.
  static final Duration STREAM_TTL = Duration.ofSeconds(45);
  private static String streamKey(String target, String key) { return CallStore.P + "stream:" + target + ":" + key; }
  private void mark(String target, String key) {
    try { redis.opsForValue().set(streamKey(target, key), "1", STREAM_TTL); } catch (Exception ex) { log.warn("stream marker failed", ex); }
  }
  /** A kiosk stream belongs to one call and one page, so its marker can go at once. Teller markers just expire:
   *  a teller who reconnected to another node may already have re-marked theirs. */
  private void forget(String target, String key) {
    if (!"kiosk".equals(target)) return;
    try { redis.delete(streamKey(target, key)); } catch (Exception ex) { log.warn("stream marker delete failed", ex); }
  }
  public boolean hasStream(String target, String key) {
    try { return Boolean.TRUE.equals(redis.hasKey(streamKey(target, key))); } catch (Exception ex) { return true; }
  }
  @Scheduled(fixedDelay = 15_000)
  public void keepalive() { ping("kiosk"); ping("teller"); ping("display"); }
  private void ping(String target) {
    Map<String, SseEmitter> m = map(target);
    m.forEach((k, e) -> {
      try { e.send(SseEmitter.event().comment("ping")); mark(target, k); }
      catch (Exception ex) { if (m.remove(k, e)) forget(target, k); log.info("{} {} event stream is gone", target, k); }
    });
  }

  public void publish(String target, String key, String name, Object data) {
    try { redis.convertAndSend(CHANNEL, om.writeValueAsString(new Ev(target, key, name, data))); }
    catch (Exception e) { log.error("event publish failed {} {}", target, name, e); }
  }

  public void localSend(String target, String key, String name, Object data) {
    SseEmitter e = key == null ? null : map(target).get(key);
    if (e == null) return;
    try { e.send(SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON)); }
    catch (Exception ex) { map(target).remove(key, e); }
  }

  public void onMessage(String json) {
    try { Ev ev = om.readValue(json, Ev.class); localSend(ev.target(), ev.key(), ev.name(), ev.data()); }
    catch (Exception e) { log.error("bad event", e); }
  }
}
