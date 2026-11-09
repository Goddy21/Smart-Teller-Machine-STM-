# Deploying the Smart Teller platform to a company server

Everything runs on one Linux server with Docker: STM middleware, STM portal, call-control, LiveKit, PostgreSQL and
Redis. Staff and STMs reach it at one HTTPS address, `https://<server IP>`. Your laptop is no longer needed.

```
  STM (Edge/Chrome) ─┐                       ┌─ /           portal (staff)
  Staff PCs ─────────┼─ https://SERVER_IP ─ nginx ─ /stm/v1, /api/v1, /ws   middleware ── PostgreSQL (stm)
                     │      443              ├─ /api, /kiosk  call-control ── Redis, PostgreSQL (callcontrol)
                     │                       └─ /livekit      LiveKit (signaling)
                     └─ media ─ 7881/tcp, 7882/udp ─ LiveKit
```

## What the server needs

- Linux (Ubuntu 22.04/24.04, RHEL 9 or similar), 4 CPU, 8 GB RAM, 30 GB disk.
- Docker Engine with the compose plugin (`docker compose version` works), and internet access to Docker Hub,
  Maven Central and npm for the first build.
- A fixed IP address on the bank network, reachable from the STMs and staff PCs.
- Firewall open from the bank network to the server: **TCP 443 (or the port you choose), 7881** and **UDP 7882**. Nothing else.
- **Port 443 already used** by another web server (httpd, nginx)? Give `setup.sh` another port, for example
  `bash setup.sh 192.168.70.3 8443`. Everything then lives at `https://192.168.70.3:8443`, and every address below
  gets `:8443` after the IP.

## 1. Pack the code on your laptop

```powershell
powershell -ExecutionPolicy Bypass -File "C:\Goddie\STM Middleware\deploy\make-server-bundle.ps1"
```
This creates `C:\Goddie\STM Middleware\stm-server.tgz` (about 1 MB: no node_modules, builds or secrets).
Copy it to the server, with WinSCP or:
```powershell
scp "C:\Goddie\STM Middleware\stm-server.tgz" youruser@SERVER_IP:/tmp/
```

## 2. Unpack and set up on the server

```bash
sudo mkdir -p /opt/stm && sudo chown $USER /opt/stm
tar -xzf /tmp/stm-server.tgz -C /opt/stm
cd /opt/stm/deploy
bash setup.sh 10.20.30.40          # your server's IP (add a port, e.g. 8443, if 443 is taken)
```
`setup.sh` creates:
- `.env`: random passwords and keys, plus the first `admin` password. It is printed at the end; keep it safe.
- `certs/`: a private certificate authority and the HTTPS certificate for the IP. `stm/stm-server-ca.crt` is the copy
  for the STMs and staff PCs.
- `livekit.yaml`: the video server's settings.

Run it again if the IP or port changes, then `docker compose up -d`. It keeps the existing passwords and CA and makes a new certificate.

## 3. Build and start

```bash
docker compose up -d --build       # the first build takes 5–10 minutes
docker compose ps                  # every service "running" (postgres "healthy")
docker compose logs -f middleware  # Ctrl+C to stop following
```
Open `https://SERVER_IP/` on your PC and sign in as `admin` with the password from step 2. You are asked to change it.
Then create accounts for tellers and supervisors under **Portal accounts**.

## 4. Each STM and each staff PC: trust the server's certificate

Copy the `deploy/stm/` folder from the server to the STM (USB stick or network share). In an **Administrator** PowerShell:
```powershell
cd <the copied stm folder>
.\install-server-certificate.ps1
```
Close every Edge and Chrome window afterwards. With HTTPS and a trusted certificate, the browser allows the cameras
and microphone normally, so the `--unsafely-treat-insecure-origin-as-secure` flag and the old browser policy are no
longer needed (the script removes that policy). Staff PCs need the same certificate, or the browser shows a warning.
Group Policy can push `stm-server-ca.crt` to every PC.

Then, on the STM:
```
.\open-kiosk-test.cmd 10.20.30.40 STM-01                  # with a port: 10.20.30.40:8443
.\list-screens.ps1
.\open-teller-display.cmd 10.20.30.40 STM-01 1280 0
```

## 5. Register the kiosks

Each machine is registered once. Use the provisioning secret from `.env`:
```bash
cd /opt/stm/deploy && . ./.env
curl -sk -X POST https://$SERVER_IP/api/v1/terminals/register \
  -H "X-Provisioning-Secret: $PROVISIONING_SECRET" -H "Content-Type: application/json" \
  -d '{"terminalCode": "STM-NBO-MOI-03", "branchCode": "012"}'
```
The answer holds the terminal token, shown only once. Give it to the kiosk app (D-1). In Postman, set `baseUrl` to
`https://SERVER_IP` and `provisioningSecret` to the value from `.env`, and turn off SSL certificate verification
(or import `certs/ca.crt` under Settings → Certificates).

## Everyday commands (in `/opt/stm/deploy`)

| Task | Command |
|---|---|
| Status | `docker compose ps` |
| Logs | `docker compose logs -f middleware` (or `call-control`, `web`, `livekit`) |
| Restart one service | `docker compose restart call-control` |
| Stop / start everything | `docker compose stop` / `docker compose up -d` |
| Update after code changes | make a new bundle, unpack it over `/opt/stm`, then `docker compose up -d --build` |
| Back up the databases | `docker compose exec -T postgres pg_dumpall -U stm > backup-$(date +%F).sql` |
| Restore | `docker compose exec -T postgres psql -U stm -d postgres < backup-2026-11-09.sql` |
| Start from an empty database | `docker compose down -v` then `docker compose up -d` (**deletes all data**) |

Unpacking a new bundle never overwrites `.env`, `certs/` or `livekit.yaml`. Data lives in the Docker volumes
`stm_pgdata` and `stm_redisdata` and survives restarts and rebuilds.

## Addresses

| Who | Address |
|---|---|
| Staff portal | `https://SERVER_IP/` |
| Kiosk gateway (kiosk app) | `https://SERVER_IP/stm/v1` |
| Kiosk test page | `https://SERVER_IP/kiosk/kiosk-test.html?terminal=STM-01` |
| Upper-screen display | `https://SERVER_IP/kiosk/teller-display.html?terminal=STM-01` |
| API documentation | `https://SERVER_IP/swagger-ui.html` |

## What this setup is, and is not

This is a **pilot** setup on the bank network. Before real customers and money:
- **Bank systems are simulated.** The middleware runs the `demo` profile: core banking, IPRS/KRA, cards and SMS are
  the simulators, and every OTP is 123456. The `prod` profile needs the real adapters first.
- **call-control runs its `dev` profile.** The kiosk test page, the upper display and the portal identify themselves
  with cookies and headers that are not checked against a sign-in. Fine on a closed network for a pilot. Production
  needs a gateway in front that checks them against the staff sign-in.
- **No mutual TLS for kiosks.** Kiosks sign in with their terminal token. The `prod` profile turns on client
  certificates.
- **No call recording.** Egress and MinIO are not deployed, so `RECORDING_ENABLED=false`.
- **The shared portal key is off** (`ALLOW_PORTAL_KEY=false`). `demo-journey.ps1` and the Postman portal requests
  need it; set it to `true` in `.env` and run `docker compose up -d` if you want them against this server.
- **Kiosk app on its own address:** if the kiosk app is served from somewhere other than this server (for
  example `http://localhost:5173` during development), add that origin to `.env` as
  `EXTRA_CORS_ORIGINS=http://localhost:5173` (comma-separated) and run `docker compose up -d`.
- **Fonts:** the portal loads its fonts from Google Fonts. PCs without internet show a standard font instead.

## Troubleshooting

| Symptom | Check |
|---|---|
| Browser warns "not secure" | The CA is not installed on that PC (step 4), or the certificate was made for another IP: run `setup.sh` with the right IP, then `docker compose restart web`. |
| Video connects, but no picture or sound | UDP 7882 or TCP 7881 is blocked between the PC and the server, or `node_ip` in `livekit.yaml` is wrong. |
| "Talk to a teller" says video help is unavailable | `docker compose logs call-control`; the middleware reaches it at `http://call-control:8080`. |
| Portal shows "The STM middleware is not running" | `docker compose logs middleware`; the first start waits for PostgreSQL to be healthy. |
| Build fails downloading | The server needs internet access to Docker Hub, Maven Central and npm during `--build`. |
