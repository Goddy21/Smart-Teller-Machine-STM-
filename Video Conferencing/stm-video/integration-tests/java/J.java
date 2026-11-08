import com.fasterxml.jackson.databind.*; import java.nio.file.*;
public class J { public static void main(String[] a) throws Exception {
  JsonNode info = new ObjectMapper().readTree(Files.readString(Path.of("/tmp/egress_info.json")));
  // the exact expressions from RecordingService.onEgress
  String eid = info.path("egressId").asText(); String status = info.path("status").asText();
  JsonNode f = info.path("fileResults").path(0); if (f.isMissingNode()) f = info.path("file");
  String key = f.path("filename").asText(""); long size = f.path("size").asLong(0); long ms = f.path("duration").asLong(0) / 1_000_000;
  System.out.println("eid=" + eid + " status=" + status + " key=" + key + " size=" + size + " durationMs=" + ms);
  if (!eid.equals("EG_1") || !status.equals("EGRESS_COMPLETE") || size != 1234567L || ms != 7250L || !key.endsWith(".webm")) { System.out.println("FAIL"); System.exit(1); }
  JsonNode legacy = new ObjectMapper().readTree("{\"egressId\":\"E\",\"status\":\"EGRESS_COMPLETE\",\"file\":{\"filename\":\"k.webm\",\"size\":\"5\",\"duration\":\"2000000\"}}");
  JsonNode g = legacy.path("fileResults").path(0); if (g.isMissingNode()) g = legacy.path("file");
  System.out.println("legacy 'file' fallback -> " + g.path("filename").asText("") + " " + g.path("size").asLong(0) + " " + g.path("duration").asLong(0) / 1_000_000 + "ms");
  System.out.println("PASS RecordingService JSON navigation reads int64-as-string correctly on real Jackson");
} }
