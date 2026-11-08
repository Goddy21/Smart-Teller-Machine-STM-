# STM Video Conferencing

**To run and test it on your laptop and the STM, follow [TESTING.md](TESTING.md).**

A customer at an STM taps "Talk to a teller" and is connected by video to a teller in the STM Portal. The teller sees the
customer's face and ID camera and a mirror of the kiosk screen, approves or rejects, and every call is recorded and audited.

## One project, one design
This folder is the **LiveKit (SFU) design**. It is the one that can meet your architecture diagram's "video session
recordings" (recording happens on the server) and the supervisor dashboard (a supervisor can listen in silently).

`alternatives/p2p-webrtc/` is the other design (peer-to-peer WebRTC + Coturn, from the document you shared). It is complete
but is a **different system**: it cannot record on the server or let a supervisor listen in. It reuses names such as
`CallController` and `CallService` for different code, so **never copy files between the two folders**. If you choose it,
delete everything outside `alternatives/` and move that folder up.

**Decide before building further: your database.** This design uses PostgreSQL (audit and recordings tables), Redis (queue,
teller presence) and S3-compatible storage with Object Lock. If BRITS runs on SQL Server, the audit layer needs porting:
`V1__audit.sql` and the SQL in `RecordingService`/`AuditLog` use PostgreSQL features. The P2P alternative is written for SQL Server.

## Layout
    call-control/        Spring Boot 3 / Java 21 service: queue, teller assignment, tokens, webhooks, recording, audit
      pom.xml
      src/main/java/com/stm/callcontrol/   all Java (package must match this folder)
      src/main/resources/                  application.yml, lua/ (3 scripts), db/migration/V1__audit.sql
      src/test/java/com/stm/callcontrol/   CallServiceWebhookTest
    kiosk-client/        kiosk-video.js, TellerVideoPane.jsx   -> copy into your kiosk React app (e.g. src/video/)
                         kiosk-test.html                       -> dev test page for the STM (no React app or eCAT needed)
    teller-console/      index.html                            -> single-file console for the STM Portal
    media-plane/         docker-compose.yml, livekit.yaml      -> Linux staging (host networking, TURN)
                         docker-compose.windows.yml, livekit.lan.yaml -> laptop + STM on a LAN (Windows)
    vendor/              livekit-client 2.23.0 (offline copy used by the test pages)
    scripts/             Windows PowerShell: firewall, start-infra, start-livekit, start-call-control, run-e2e; STM launcher
    e2e/                 run.mjs: two-browser automated call test (Playwright, fake cameras)
    integration-tests/   real-stack test suites and what they proved (see its README)
    docs/                DESIGN.md (call flow, recording, audit), FILE-MAP.md (where your old files go)
    tools/               audit-old-files.mjs
    alternatives/        p2p-webrtc/  (separate design, self-contained)

## Open it in IntelliJ (Windows)
1. Unzip into a **new** folder, for example `C:\Goddie\STM Middleware\video-conferencing`. Do not unzip over the old one.
2. IntelliJ: File > Open > choose `call-control\pom.xml` > Open as Project (JDK 21). To add it to your existing STM
   Middleware project instead: Maven tool window > + > choose the same pom.
3. The "Suggested plugins: Spring" pop-up is an IntelliJ Ultimate feature. You do not need it: Maven builds without it.
4. `integration-tests/java/*.java` are standalone test helpers, not part of the Maven build. Leave them unmarked as source.

## Run it locally
See [TESTING.md](TESTING.md). In short: `scripts\start-infra.ps1`, `scripts\start-livekit.ps1`, `scripts\start-call-control.ps1`
(dev profile: serves the teller console at `/teller/index.html` and the kiosk test page at `/kiosk/kiosk-test.html`, and
`DevGateway` stands in for the Terminal API Gateway). Never run the `dev` profile in production.
Your own `docker-compose.local.yml` and `livekit.local.yaml` belong in `media-plane/` beside the originals.

## Keep it tidy
- One file lives in exactly one place. Java files go in `call-control/src/main/java/com/stm/callcontrol/`.
- Old downloads: run `node tools/audit-old-files.mjs "<your old folder>"`. It tells you, file by file, which are current,
  which are older versions to replace, which are yours, and which project files you are missing. Run it before you edit anything here.

## Status
Changes in this version (Nov 2026): teller can end a call while the customer is gone, and ending is idempotent (a teller was
stuck unavailable after the 60 s reconnect window); the service tells the teller console when it ends a call; the kiosk
closes itself on ENDED/ABANDONED; camera selection falls back by index and survives a single-camera machine; re-taking
cameras after an eCAT handoff no longer publishes a second mic; the teller console kept the wrong call after Accept;
hosts/URLs are environment variables; `storage.egress-endpoint` for Egress in Docker. 3 new unit tests.

Earlier status:
Verified on real servers (Redis, PostgreSQL, LiveKit, Coturn, native WebRTC): token minting, webhook signatures, Lua
atomicity, audit tables and hash chain, media flow. **Not verified:** the Spring app itself was never started, real recording
through Egress, MinIO Object Lock, and any browser code. Details and one bug found by this testing are in
`integration-tests/README.md`. Not built yet: teller rejoin/reassign, supervisor monitoring UI, auth at the gateway,
a Testcontainers `mvn verify`.
