# STM WebRTC: peer-to-peer teller calls (Spring Boot signaling + Coturn)

    infra/       Coturn (docker-compose + turnserver.conf, time-limited credentials)
    backend/     Spring Boot 3 / Java 21, package com.blueriver.brits.webrtc (signaling, queue, TURN credentials, SQL Server audit)
    frontend/    React 18 + TypeScript: WebRTCService, SignalingClient, hooks, KioskAssist, TellerConsole, VideoCall

Built from the "extend the existing Spring Boot backend, Coturn separate" plan. The sibling folder `stm-video/`
(LiveKit SFU) is a different architecture and is untouched.

## Call flow
1. Kiosk `POST /api/v1/stm/calls/request`; the call is queued and the kiosk gets `CALL_QUEUED` on the socket.
2. The dispatcher offers it to an available teller (`CALL_INCOMING`, 20 s to answer, then the next teller).
3. Teller `POST .../accept`. **Only now** both sides get `CALL_ACCEPTED` and the kiosk opens its camera and microphone.
4. Both `GET .../turn-credentials` (HMAC credentials valid 10 minutes, accepted calls only).
5. Kiosk sends `CALL_OFFER` (with `meta.streams` naming the face and desk streams), teller answers, ICE flows both ways.
   The server validates sender, call state and role on every message and forwards only whitelisted fields.
6. `MEDIA_CONNECTED` marks the call CONNECTED. Either side ends with `/end`.

Socket messages: `PRESENCE`, `CALL_QUEUED`, `CALL_RINGING`, `CALL_INCOMING`, `CALL_ACCEPTED`, `CALL_OFFER`, `CALL_ANSWER`,
`ICE_CANDIDATE`, `MEDIA_CONNECTED`, `MEDIA_FAILED`, `CALL_ENDED`, `CALL_FAILED`, `CALL_REJECTED`, `ERROR`, `PING`.

## Run it
1. `infra`: put certs in `infra/certs/`, set the same secret in `turnserver.conf` and `application.yml`, open 3478/5349 and
   49160-49300 (UDP+TCP), `docker compose up -d`.
2. `backend`: SQL Server reachable (Flyway creates `call_session`), `mvn spring-boot:run`. Local only:
   `brits.webrtc.dev-identity=true` lets the socket URL carry `?teller=` / `?terminal=`.
3. `frontend`: `npm i`, `npm run typecheck`, `npm test`. Render `<TellerConsole />` in the portal and
   `<KioskAssist devices={{ face, desk, audio }} fsm={{ name, model }} />` in the kiosk app.
   Dev: `configureWebrtc({ devHeaders: { "X-Teller-Id": "t1" }, devQuery: "teller=t1" })`.

## What was actually verified
- Backend routing logic: 18 scenarios pass (`CallRoutingServiceTest`): happy path, no media before accept, role and
  sender checks, reject, ring timeout, missed-call-marks-away, cancel while queued/ringing, disconnect while ringing,
  reconnect grace (kept and expired), teller drop mid-call, no-teller timeout, one call per terminal, busy teller, media failure.
  Run here against stand-ins for Spring, so the logic is real but the framework wiring is not.
- All Java in both folders: no syntax errors under `javac 21` (it cannot resolve Spring/JPA/AWS types without Maven).
- Frontend: strict `tsc` clean; WebRTCService logic tested against a fake peer connection (`npm test`):
  ICE candidates held until the remote description, face/desk roles from meta, desk camera optional, mute toggles.

## Verified against a real stack (see `../../integration-tests/`)
- `TurnCredentialService` credentials are accepted by real Coturn 4.6.1 (UDP and TCP) and expired, forged, extended or re-used
  credentials are refused.
- Two real `WebRTCService` instances connect with ALL media forced through that Coturn relay: face/desk streams are mapped
  correctly from the offer metadata, audio and video flow both ways, the `fsm` data channel delivers, mute and camera-off work.

## NOT verified
- Real Spring startup, the WebSocket handshake, JPA mapping, Flyway; SQL Server was not available at all.
- Any real browser (cameras, autoplay policy, NAT traversal across real networks), TURN over TLS, ICE restart after a break.
- The React components have not been rendered or looked at.

## Design choices and limits (read before production)
- **Peer to peer: no server-side recording and no supervisor silent monitoring.** Your architecture diagram lists "video
  session recordings" under Audit & Compliance; this design cannot produce them. The `stm-video` SFU design can.
- **Single node.** Calls, queue and sockets are in one JVM's memory. Two instances need sticky routing plus a shared broker.
- **Identity** comes from gateway headers (`X-Terminal-Id` / `X-Teller-Id`), which the gateway must set and strip from client
  traffic. Replace `CallIdentity.resolve` and `IdentityHandshakeInterceptor` with BRITS authentication.
- **Screen mirroring** sends the kiosk's screen name and a data model over the data channel, not pixels (`getDisplayMedia` is
  awkward in kiosk mode). `PIN_ENTRY` and `CARD_DATA_ENTRY` are masked in `useKioskCall`; add every sensitive screen.
- **Camera contention** with the eCAT daemon is unresolved: confirm with GRG which process owns each camera.
- A dropped signaling socket does not end a call for 30 s (media is independent); the orchestrator can listen for
  `CallEndedEvent` to hold escrow or roll back. No transaction approvals are included (the plan does not cover them).
- Not built: TURN credential rotation mid-call, teller rejoin/reassign after a failed call, load testing.
