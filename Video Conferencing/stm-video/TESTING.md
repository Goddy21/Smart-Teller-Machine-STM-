# Testing a customer-teller video call: your laptop + the STM (same LAN)

Your **laptop** runs everything server-side (call-control, LiveKit, Redis, Postgres, MinIO) and the **teller console**.
The **STM** only opens a browser page. They must be on the same office network.

```
 STM (kiosk browser)                           Laptop
 kiosk-test.html  ── http :8080 (API, SSE) ──▶ call-control (Spring Boot, dev profile) ◀── teller console (localhost)
                  ── ws :7880, UDP 55000-55200 ▶ LiveKit (native .exe) ── webhooks ─▶ call-control
                                                Docker: Redis, Postgres, MinIO  [+ Egress for recording]
```

## 0. One-time setup on the laptop
1. Install JDK 21, Maven 3.9+, Docker Desktop, Node 18+ (Node only for the automated test).
2. Unzip this folder to a new place, e.g. `C:\Goddie\STM Middleware\stm-video`.
3. PowerShell **as administrator**: `powershell -ExecutionPolicy Bypass -File scripts\setup-firewall.ps1`
4. Set the laptop's network to **Private** (Settings > Network & internet > your network > Private).
5. Find the laptop's LAN IP: `powershell -File scripts\lan-ip.ps1` (example below: `192.168.1.50`).
   If it picks the wrong adapter (VPN, Docker), pass `-LanIp 192.168.1.50` to every start script.

Run the scripts from the project folder with `powershell -ExecutionPolicy Bypass -File scripts\<name>.ps1`.

## 1. Start the stack (three windows)
| Window | Command | Ready when you see |
|---|---|---|
| 1 | `scripts\start-infra.ps1` | `redis` and `postgres` running (`docker ps`) |
| 2 | `scripts\start-livekit.ps1` | `starting LiveKit server ... nodeIP: 192.168.1.50` |
| 3 | `scripts\start-call-control.ps1` | `Started CallControlApplication` and `DEV GATEWAY ACTIVE` |

First run of window 3 downloads Maven dependencies and Flyway creates the audit tables.
After editing the HTML/JS pages, restart window 3 (it copies them into the app).

## 2. Test on the laptop alone (two browser windows)
1. `http://localhost:8080/` shows the test pages and whether cameras are allowed.
2. Window A: **Teller console** > Teller ID `teller1`, languages `en,sw` > **Go available**.
3. Window B (a different browser, or an InPrivate window): **Kiosk test page** > **Test cameras** > **Talk to a teller** > **Continue**.
   A laptop has one camera: set "ID / desk camera" to *(none)*. The teller then sees no ID pane, which is expected.
4. Teller: **Accept**. Check the list in step 4 below.

Automated version (two headless browsers with fake cameras, 19 checks, about 15 s):
`powershell -ExecutionPolicy Bypass -File scripts\run-e2e.ps1` — must end with `RESULT: PASSED`.

## 3. On the STM
1. Copy `scripts\open-kiosk-test-on-stm.cmd` to the STM (USB stick) and run: `open-kiosk-test-on-stm.cmd 192.168.1.50 STM-01`.
   It opens Chrome/Edge in a separate profile with `--unsafely-treat-insecure-origin-as-secure=http://192.168.1.50:8080`.
   Without that flag the browser blocks cameras on a plain-http LAN address (the page tells you so in an orange banner).
2. Quick network check first, from the STM browser: `http://192.168.1.50:8080/` must load. If not: firewall (step 0.3/0.4).
3. Kiosk test page > **Test cameras**. The list shows every camera label. Pick the **face** camera (top, binocular/face
   unit) and the **ID / desk** camera (document scanner area). Note the labels: put them in `CAM_LABELS` in
   `kiosk-client/kiosk-video.js` for the real kiosk app.
   - `NotReadableError` / "could not start video source" = another program holds the camera, normally the **GRG eCAT
     daemon** (biometrics/binocular camera). Stop its camera use for the test, or test with a USB webcam first.
   - The two cameras of a binocular face unit (visible + IR) can show up as two devices: the IR one looks grey/black.
4. Laptop: teller console **Go available**. STM: **Talk to a teller** > **Continue**. Laptop: **Accept**.

### Notes from the first STM run (GRG STM, Nov 2026)
- In PowerShell run the launcher as `.\open-kiosk-test-on-stm.cmd 192.168.1.49 STM-01` (PowerShell needs the `.\`).
  Network check: `Test-NetConnection 192.168.1.49 -Port 8080` (8080, not 8000). Edge shows an "unsupported command-line
  flag" bar for the camera flag: expected, close it. No internet is needed on the STM.
- The teller sees three videos: **Customer face** (`DC003-EA(V)`), **Second camera / ID** (`LHT-820VM31B` by default) and
  **Customer screen (live)**: the whole STM screen, shared without a prompt thanks to the launcher's
  `--auto-select-desktop-capture-source="Entire screen"` flag. The live screen is hidden while the kiosk is on PIN/card
  screens. Untick "Share this kiosk screen" on the test page to turn it off. The kiosk page remembers your camera picks.
- Calls whose kiosk page was closed or reloaded are dropped automatically (no more ghost calls ringing the teller).
- Cameras the browser sees: `LHT-820VM31B`, `DC003-EA(V)` (visible lens of the binocular face unit), `DC003-EA(V)-IR`
  (its infrared twin: never pick it), `GRGBanking SIDR-001 Image Device` (ID scanner imaging). Close EYECoordinator first.

### Teller video on the STM's upper screen
The kiosk page (lower screen) plays the teller's voice. `kiosk/teller-display.html` shows the teller's camera full
screen on the upper monitor: idle it says "Need help? Tap Talk to a teller", during a call it shows the teller.
1. Copy `scripts\list-screens.ps1` and `scripts\open-teller-display-on-stm.cmd` to the STM.
2. On the STM: `powershell -ExecutionPolicy Bypass -File .\list-screens.ps1`. Note `x` and `y` of the upper monitor.
3. `.\open-teller-display-on-stm.cmd 192.168.1.49 STM-01 <x> <y>` (same terminal ID as the kiosk page). Close it with Alt+F4.
It joins each call as a hidden, video-only participant: the teller does not see it, and it is never recorded.

## 4. What to check during a call
- [ ] Teller sees the customer face and the ID/desk camera; zoom works on the ID pane.
- [ ] STM shows the teller's video; both sides hear each other (STM handset/speaker; use headphones on the laptop to avoid echo).
- [ ] STM "Cash deposit" button: the teller's *Customer screen* shows the fields. "PIN entry": the teller sees it hidden.
- [ ] Teller "Request ID snapshot": a still appears on the STM page (in production it goes to the KYC archive).
- [ ] Amount 500 > Approve: "Approval sent". Amount 9000: Approve stays disabled (teller limit 5000).
- [ ] Amount 1500 > Approve without recording running: **409 "recording is not active"** (correct: approvals at or above
      1000 need a healthy recording). Start with recording (section 5) to approve it.
- [ ] Teller **End call**: the STM returns to the start screen by itself.
- [ ] STM **Hang up** mid-call: the teller sees "customer disconnected"; End call still works. Left alone, the service
      ends the call after 60 s and the teller console resets itself.
- [ ] Audit trail: `http://localhost:8080/api/audit/transactions/<TX-ID shown on the STM page>?auditorId=me` returns the
      events (REQUESTED, STATE_*, RECORDING_NOTICE, DECISION, STATE_ENDED) and `"chain":{"ok":true}`.

## 5. With recording (optional)
MinIO no longer publishes free images on Docker Hub. Set `$env:MINIO_IMAGE` and `$env:MC_IMAGE` to images you can pull
(the default tries `quay.io/minio/minio` and `quay.io/minio/mc`) before the commands below.
`start-infra.ps1 -Recording` (adds LiveKit Egress) and `start-call-control.ps1 -Recording`. After a call, the audit URL
above lists `recordings` moving to `SEALED` (within ~15 s of the call ending); files are in MinIO at
`http://localhost:9001` (minioadmin/minioadmin), bucket `stm-recordings`. Egress on Docker Desktop is the least-tested
part of this setup; if recordings show `START_FAILED`, check `docker compose -f media-plane\docker-compose.windows.yml logs egress`.

## Troubleshooting
| Symptom | Cause / fix |
|---|---|
| `ports are not available ... bind: An attempt was made to access a socket in a way forbidden` | Windows reserved that port (Hyper-V/WSL). Check `netsh interface ipv4 show excludedportrange protocol=tcp`; pick a free one, e.g. `start-infra.ps1 -PgPort 5440` and `start-call-control.ps1 -PgPort 5440`. |
| Docker: `500 Internal Server Error ... dockerDesktopLinuxEngine` | Docker Desktop is still starting, or its WSL 2 engine is broken. Wait for "Engine running"; else Docker Desktop > Troubleshoot (bug icon) > Restart; else admin PowerShell `wsl --update`, then `wsl --shutdown`, reopen Docker Desktop. |
| Kiosk: "call request failed (400)" | No terminal ID reached call-control: call-control not in the dev profile (use the script). |
| Teller never sees the ring, then \"You missed a call\" | The console's event stream had died (sleep, network change). It now reconnects by itself within ~45 s; reload the console if it persists. |
| Kiosk stuck on QUEUED | No teller available with that language, or the teller's presence expired (keep the console open). |
| Both CONNECTED but no video, LiveKit log `publish time out` / ICE failed | UDP 55000-55200 blocked, wrong node IP (check window 2), or STM on another VLAN/guest Wi-Fi. |
| Browser console: WebSocket to `ws://...:7880` fails | Firewall TCP 7880, or `LIVEKIT_PUBLIC_URL` is `localhost` (start-call-control.ps1 sets the LAN IP). |
| No sound on the STM | Autoplay policy: the launcher passes `--autoplay-policy=no-user-gesture-required`; the kiosk Chromium needs the same policy. |
| `Could not accept the call: ...` on the teller | Read the message; 409 means the call already timed out (20 s ring) or was cancelled. |

## Moving to the real kiosk app (React 18 + Vite on the STM)
Copy `kiosk-client/kiosk-video.js` and `TellerVideoPane.jsx` into the app (e.g. `src/video/`), `npm i livekit-client@2.23.0`,
and proxy `/api` to call-control. Because Vite serves on `localhost`, the camera flag is not needed:
```js
// vite.config.js (dev only; in production the Terminal API Gateway does this with mTLS)
export default { server: { proxy: { "/api": { target: "http://192.168.1.50:8080", changeOrigin: true,
  headers: { "X-Terminal-Id": "STM-01" } } } } };
```
```jsx
const session = useMemo(() => new VideoSession({ getFsmState, uploadSnapshot, onState, onDropped,
  onEnded: () => navigate("/home"), cameras: { face: "Face", id: "Desk" } }), []);
// "Talk to a teller": await session.request(lang, txId); await session.ackConsent(true);
<TellerVideoPane session={session} />
// on every FSM screen change: session.mirror();   before eCAT biometrics: await session.withExclusiveCameras(() => ecat.capture());
```

## What was verified before handover (and what was not)
- Verified for real: LiveKit 1.13.11 with `livekit.lan.yaml`; the kiosk test page, `kiosk-video.js` and the teller
  console in Chromium with fake cameras, over `localhost` and over a LAN-style IP (19/19 e2e checks); media, data
  channel, snapshot, masking, end/cancel/hang-up flows.
- Verified with signature stubs only: the Java code compiles and the 11 `CallServiceWebhookTest` cases pass. Maven Central
  was not reachable in the build environment, so **the Spring Boot app itself has not been started yet**; for that run the
  e2e was driven by `integration-tests/browser-standin/server.mjs`, a test double with the same HTTP contract.
  Your first `start-call-control.ps1` + `run-e2e.ps1` is the first full real-stack run; send me the output if anything fails.
- Not verified: Docker Desktop on Windows, Egress recording, the real STM cameras and eCAT interplay.
