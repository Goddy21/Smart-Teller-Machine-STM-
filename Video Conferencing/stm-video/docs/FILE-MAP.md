# Where your existing files go

Your folders (from the screenshots) mix two designs and several download dates. This maps each one to its place in this project.
If a file name appears in more than one of your folders, only the copy from this project counts.

| Your folder / file | Belongs to | Put it at |
|---|---|---|
| `Architecture/CallRoutingService.java` | P2P alternative | `alternatives/p2p-webrtc/backend/src/main/java/com/blueriver/brits/webrtc/service/` |
| `Architecture/CallRoutingServiceTest.java` | P2P alternative | `alternatives/p2p-webrtc/backend/src/test/java/com/blueriver/brits/webrtc/service/` |
| `Architecture/WebRTCSignalingHandler.java` | P2P alternative | `alternatives/p2p-webrtc/backend/src/main/java/com/blueriver/brits/webrtc/websocket/` |
| `Architecture/KioskAssist.tsx` | P2P alternative | `alternatives/p2p-webrtc/frontend/src/components/KioskAssist/` |
| `Architecture/TellerConsole.tsx` | P2P alternative | `alternatives/p2p-webrtc/frontend/src/components/TellerConsole/` |
| `Architecture/WebRTCService.ts` | P2P alternative | `alternatives/p2p-webrtc/frontend/src/services/webrtc/` |
| `Architecture/turnserver.conf` | P2P alternative | `alternatives/p2p-webrtc/infra/` |
| `Architecture/README.md` | P2P alternative | `alternatives/p2p-webrtc/README.md` |
| `Recording and audit linkage/AuditController.java, AuditLog.java, RecordingService.java` | main | `call-control/src/main/java/com/stm/callcontrol/` |
| `Recording and audit linkage/V1__audit.sql` | main | `call-control/src/main/resources/db/migration/` |
| `Redis-backed store/CallService.java, CallStore.java, StreamHub.java` | main | `call-control/src/main/java/com/stm/callcontrol/` (**CallService here is an intermediate version; the final one is in this project**) |
| `Redis-backed store/claim.lua` | main | `call-control/src/main/resources/lua/` (the project also needs `cas.lua` and `due.lua`) |
| `Teller console UI [call-control]/` Java files, `pom.xml`, `application.yml` | main | `call-control/` (`.java` under `src/main/java/com/stm/callcontrol/`, `application.yml` under `src/main/resources/`) |
| `docker-compose.yml`, `livekit.yaml` | main | `media-plane/` |
| `docker-compose.local.yml`, `livekit.local.yaml` | **yours** (not from me) | `media-plane/`, keep them |
| `index.html` (and the `Teller console UI` subfolder) | main | `teller-console/index.html` |
| `kiosk-video.js`, `TellerVideoPane.jsx` | main | `kiosk-client/` |
| `.idea/` | IntelliJ | leave alone; it is regenerated |

Files this project has that your screenshots do not show: `RedisConfig.java`, `ObjectStore.java`, `Call.java`,
`cas.lua`, `due.lua`, `CallServiceWebhookTest.java`. The audit tool lists exactly what you are missing.

Rule of thumb: when two copies of a file differ, the one in this project is the latest, unless you edited it yourself.
In that case compare them first (IntelliJ: select both files > Compare Files).
