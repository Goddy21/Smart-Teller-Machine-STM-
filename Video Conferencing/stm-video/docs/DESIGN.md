> Design notes for the main (LiveKit) project. Start at the root README.md.

# STM video teller: production scaffold

    media-plane/      LiveKit + Redis + Egress (docker compose), TURN, webhooks
    call-control/     Spring Boot 3 / Java 21: queue, claim, ring timeout, state machine, tokens, webhooks, decisions
    kiosk-client/     kiosk-video.js: call request, publish face + ID cam, FSM mirror, ID snapshot, camera handoff

Run (staging):
1. `cd media-plane && docker compose up -d` (replace the key, TURN domain and certs first; pin image versions).
2. `cd call-control && mvn spring-boot:run`
3. Teller: `PUT /api/tellers/me/presence {"available":true,"langs":["en"]}` then listen on `/api/tellers/me/events`.
4. Kiosk: `new VideoSession(deps).request("en", txId)`; it joins when the teller accepts.

Not yet built (in this order): real orchestrator client, supervisor hidden-participant tokens, auth at the gateway,
teller rejoin/reassign.
Nothing here has been compiled or load-tested; treat it as a starting point, not a release.

## Teller console
`teller-console/index.html` is a single-file console (livekit-client loaded from jsdelivr; bundle it for production).
Serve it behind the same gateway as `/api`. In dev, set the teller ID in the page; in production the gateway
injects `X-Teller-Id` from the teller session and the ID field should be removed.

## Multi-node call-control (Redis)
State, queue, teller presence and deadlines live in Redis (`CallStore`); the service holds no state, so run N nodes
behind an L4/HTTP balancer. Teller claim, state changes and due-timer pops are Lua scripts in
`src/main/resources/lua/` (tested against an emulated Redis, not yet a real one). Events cross nodes via pub/sub
(`StreamHub`). Tellers must heartbeat every ~20s or their presence expires after 60s.

Known gaps: a node that dies after popping a due deadline loses that timeout (add a reaper that re-checks RINGING/ON_HOLD
calls); Redis must be HA (Sentinel/Cluster, keys share the `{stm}` hash tag); no integration tests yet (use Testcontainers).

## Recording and audit
When a track named `face`, `id-cam` or `mic` is published, LiveKit's `track_published` webhook makes call-control start a
track egress into the Object Lock bucket (`recordings/<tx>/<call>/<who>-<track>-<sid>`). Four tracks are expected per call:
kiosk face, kiosk ID cam, kiosk mic, teller mic. On `egress_ended` the row becomes COMPLETE; a sweeper (safe on many
nodes, `FOR UPDATE SKIP LOCKED`) streams each file, records its SHA-256 and moves it to SEALED.

- `recordings` links every file to the transaction ID, with its hash, size, duration and `retention_until`.
- `call_events` is an append-only, hash-chained log of the call (state changes, notice shown, recording start/seal/failure,
  decisions, auditor access). The table rejects UPDATE/DELETE/TRUNCATE; chain verification recomputes every hash.
- Policy: an approval at or above `recording.required-above` is blocked (409) unless all four tracks are healthy;
  below it, the decision proceeds and an ALERT is logged. Audit write failures alert but never abort a live call.
- Auditor API (gateway sets `X-Auditor-Id`): `GET /api/audit/transactions/{txId}`, `GET /api/audit/recordings/{sid}/download-url`
  (5-minute pre-signed URL), `POST /api/audit/recordings/{sid}/verify`. Each access is itself logged to the chain.
- Kiosk: call `ackConsent()` when the customer taps Continue on the recording notice.

Verified since (see `../integration-tests/`): real LiveKit 1.13.11 accepts the Java-minted tokens, all real webhooks pass
`verifyWebhook()`, the Egress request body matches LiveKit's protobuf schema, the SQL runs on PostgreSQL 16, the Lua scripts
survive concurrent races on Redis 7. Still unverified: the Spring app itself (never started), a real recording through Egress,
MinIO Object Lock, and the browser code.
Production hardening: COMPLIANCE-mode retention in your WORM store, a Postgres role without UPDATE/DELETE on the audit tables,
and periodic anchoring of each day's chain heads to the WORM bucket so a database admin cannot silently rewrite history.

## Teller camera
The console publishes `teller-cam` (640x360, 24 fps) plus `mic`, with mute and camera-off buttons and a self-view. The kiosk
renders it with `<TellerVideoPane session={session} />` (`kiosk-client/TellerVideoPane.jsx`); it hides if the teller turns the
camera off. `teller-cam` is recorded and sealed like the other tracks but is NOT one of the four required for the recording
health check, so a failed teller camera never blocks an approval. Budget about 0.5 Mbps extra downstream per kiosk.

## Fix found by integration testing
A hidden supervisor's join and leave produce their own LiveKit webhooks. `CallService.onLiveKitEvent` now ignores any participant
that is not this call's own kiosk or teller; before, a supervisor leaving put a connected call on hold and triggered an escrow hold.
Covered by `CallServiceWebhookTest`. Auditors recomputing hashes must format timestamps like Java's `Instant.toString`
(0, 3, 6 or 9 fractional digits).
