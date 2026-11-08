# Real-stack integration tests (both designs)

Run in an Ubuntu 24.04 sandbox **without Docker and without Maven Central**, so neither Spring Boot backend could be started.
Everything those backends depend on was run for real instead. Scripts use `/tmp` paths; adjust for your machine.

| Suite | Real components | What it proves |
|---|---|---|
| `suite1_redis.py` | Redis 7.0.15 | The three Lua scripts, plus concurrency: 50 calls x 10 tellers x 16 threads gives exactly 10 claims and no teller is rung twice; 40 threads claiming one call gives one winner; 30 racing state changes give one success; 1000 deadlines over 8 workers are each handled once. |
| `suite2_pg.py` | PostgreSQL 16.15 | The shipped `V1__audit.sql` applies; `call_events` rejects UPDATE/DELETE/TRUNCATE; duplicate webhook inserts once; the sealing claim (`FOR UPDATE SKIP LOCKED`) hands each of 40 rows to exactly one of 6 workers; stale claims are retaken; the recording-health query across 7 scenarios; microsecond timestamps survive. |
| `java/AuditChainCheck.java` | the real `AuditLog`, real Jackson 2.14 | Hash chain appends, verifies, and detects an edited payload, a deleted event and a forged hash; a DB failure never throws into a live call; Python recomputes Java's hashes for 5 timestamps. |
| `suite3_livekit.py` + `java/LkTool.java` | LiveKit server 1.13.11, Redis, real WebRTC participants (Python SDK), the real `LiveKitTokens` | Java-minted tokens are accepted; expired and wrong-secret tokens are refused; face / id-cam / mic published as the kiosk does reach the teller and a hidden supervisor with real media; `fsm` and `ctl` data topics; hidden supervisor is invisible and cannot publish; Egress API demands the roomRecord grant; **all 16 real webhooks pass the Java `verifyWebhook()`** and tampered ones fail; webhook payloads carry the fields the code reads. |
| `suite3b_egress_schema.py` | LiveKit's protobuf schema | The exact Egress request body and the egress_ended paths are valid (Twirp silently ignores unknown fields, so this strict check is what matters). |
| `java/J.java` | real Jackson | `RecordingService`'s JSON expressions read int64-as-string values correctly. |
| `suite4_turn.py` + `java/TurnTool.java` | Coturn 4.6.1, aioice, the real `TurnCredentialService` | Java-issued credentials get relay allocations over UDP and TCP; expired, wrong-secret, extended-expiry, re-used-under-another-identity, made-up and missing credentials are all refused. |
| `suite4_media.cjs` | Coturn, native WebRTC (`@roamhq/wrtc`), the real compiled `WebRTCService.ts` x2 | With all media forced through the relay: both sides connect; the teller gets two streams labelled face and desk and they really are the face (320 px) and desk (480 px) cameras; audio and video flow both ways; the `fsm` data channel delivers; mute silences the far end and camera-off blacks the picture. |

In the project folders: `stm-webrtc/.../CallRoutingServiceTest` (18 scenarios) and `stm-video/.../CallServiceWebhookTest`
(8 scenarios) run the real routing/webhook logic against in-memory doubles. Run them with `mvn test`.

## Found by running the real stack
1. **Bug, fixed:** a hidden supervisor's join/leave produces its own LiveKit webhooks. `CallService` counted every identity, so a
   supervisor leaving put a connected call on hold (and told the orchestrator to hold escrow), and a supervisor joining could
   revive a call whose teller was gone. Only the call's own kiosk and teller count now. The regression test fails without the
   fix (2 of 8) and passes with it.
2. **Twirp ignores unknown JSON fields:** a wrong field name in the Egress request would not error. Validate against the schema.
3. **int64 values are strings in protojson** (`size`, `duration`). `asLong()` handles it; a strict typed parser would not.
4. **Auditors recomputing hashes** must format timestamps as ISO-8601 UTC with 0, 3, 6 or 9 fractional digits (Java's
   `Instant.toString`), e.g. `...30.100Z` and `...30.123400Z`. Verified against Python.
5. Without a running egress worker the Egress API answers 503 "no response from servers" for a valid record token.

## NOT covered (still unverified)
- **Neither Spring Boot app was started.** Not run: controllers, WebSocket handshake and config, Spring Data Redis wiring and
  pub/sub (`StreamHub`), JPA/Hibernate mapping, Flyway, `RecordingService` and `AuditController` as wired.
- **SQL Server** (the P2P design's database) was not available; its T-SQL migration has never been executed.
- **Real recording:** Egress needs Docker and a browser engine; no file was recorded, sealed, or fetched via a pre-signed URL
  (MinIO / Object Lock not run).
- **Real browsers:** `kiosk-video.js`, the teller console and the React components were never run in a browser;
  `livekit-client` (JS), `getUserMedia`, `ImageCapture` and autoplay policy are untested.
- TURN over TLS (`turns:` on 5349), ICE restart after a network break, NAT traversal across real networks, load and soak.

## Reproduce
    apt-get install -y redis-server coturn postgresql libjackson2-databind-java libjackson2-core-java libjackson2-annotations-java
    pip install livekit livekit-api psycopg2-binary redis aioice      # on Debian add --ignore-installed PyJWT
    npm i @roamhq/wrtc                                                   # native WebRTC for suite4_media.cjs
    # LiveKit server 1.13.11 binary from github.com/livekit/livekit/releases; JDK 21; build the TS with `npm test` in stm-webrtc/frontend
    # compile java/LkTool.java and java/TurnTool.java with the project's LiveKitTokens / TurnCredentialService + Jackson,
    # start_stack.sh starts Redis, the webhook receiver and LiveKit, then run the suites.
Longer term, replace this with Testcontainers (Redis, Postgres/SQL Server, MinIO, LiveKit + Egress) in `mvn verify`.
