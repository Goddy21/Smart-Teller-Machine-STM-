#!/usr/bin/env bash
# One-time setup on the server. Makes .env (random secrets), a private certificate authority and the server's
# HTTPS certificate for its IP address, and livekit.yaml. Safe to run again: it keeps an existing .env and CA.
#   ./setup.sh 10.20.30.40          # the server's IP address on the bank network
#   ./setup.sh 10.20.30.40 8443     # when another web server (httpd, nginx) already uses port 443
set -euo pipefail
cd "$(dirname "$0")"

IP="${1:-}"
PORT="${2:-443}"
if [ -z "$IP" ]; then
  IP="$(hostname -I 2>/dev/null | awk '{print $1}')"
  echo "No IP given: using $IP (pass it as the first argument if this is wrong)."
fi
[[ "$IP" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "Not an IPv4 address: $IP"; exit 1; }
[[ "$PORT" =~ ^[0-9]+$ ]] || { echo "Not a port number: $PORT"; exit 1; }
if [ "$PORT" = "443" ]; then HOSTPORT="$IP"; else HOSTPORT="$IP:$PORT"; fi
rnd() { openssl rand -base64 48 | tr -dc 'A-Za-z0-9' | head -c "$1"; }
setkv() { if grep -q "^$1=" .env; then sed -i "s|^$1=.*|$1=$2|" .env; else echo "$1=$2" >> .env; fi; }

# ---- .env
if [ ! -f .env ]; then
  cat > .env <<ENV
SERVER_IP=$IP
HTTPS_PORT=$PORT
PUBLIC_HOST=$HOSTPORT
DB_PASSWORD=$(rnd 32)
PROVISIONING_SECRET=$(rnd 32)
PORTAL_API_KEY=$(rnd 32)
LIVEKIT_SECRET=$(rnd 48)
# First portal sign-in: user "admin" with this password (you are asked to change it).
ADMIN_PASSWORD=$(rnd 16)
# true lets scripts (demo-journey.ps1, Postman portal folder) use X-Portal-Key. Keep false on a shared server.
ALLOW_PORTAL_KEY=false
ENV
  chmod 600 .env
  echo "Created .env with new secrets."
else
  setkv SERVER_IP "$IP"; setkv HTTPS_PORT "$PORT"; setkv PUBLIC_HOST "$HOSTPORT"
  echo "Kept the existing .env (address set to https://$HOSTPORT)."
fi
set -a; . ./.env; set +a

# ---- certificates: a private CA (install ca.crt on the STM and staff PCs) and a server certificate for the IP
mkdir -p certs
if [ ! -f certs/ca.key ]; then
  openssl req -x509 -newkey rsa:3072 -nodes -days 3650 -subj "/O=Blue River/CN=Smart Teller Private CA" \
    -keyout certs/ca.key -out certs/ca.crt 2>/dev/null
  echo "Created the private CA (certs/ca.crt)."
fi
openssl req -newkey rsa:2048 -nodes -subj "/O=Blue River/CN=$IP" -keyout certs/server.key -out certs/server.csr 2>/dev/null
printf "subjectAltName=IP:%s\nextendedKeyUsage=serverAuth\nkeyUsage=digitalSignature,keyEncipherment\nbasicConstraints=CA:FALSE\n" "$IP" > certs/server.ext
openssl x509 -req -in certs/server.csr -CA certs/ca.crt -CAkey certs/ca.key -CAcreateserial -days 825 \
  -extfile certs/server.ext -out certs/server.crt 2>/dev/null
rm -f certs/server.csr certs/server.ext
chmod 600 certs/*.key
cp certs/ca.crt stm/stm-server-ca.crt
echo "Created the HTTPS certificate for $IP (valid 825 days)."

# ---- LiveKit
cat > livekit.yaml <<LK
port: 7880
log_level: info
rtc:
  tcp_port: 7881
  udp_port: 7882            # one UDP port for every call
  use_external_ip: false
  node_ip: $IP              # the address the STM and staff PCs reach for media
keys:
  stmkey: $LIVEKIT_SECRET
webhook:
  api_key: stmkey
  urls:
    - http://call-control:8080/webhooks/livekit
LK
chmod 644 livekit.yaml
echo "Created livekit.yaml."

cat <<DONE

Next:
  docker compose up -d --build        # first build takes 5-10 minutes
  docker compose ps                   # all services "running"
Then open https://$HOSTPORT/ and sign in as admin / $ADMIN_PASSWORD
Firewall: open TCP $PORT and 7881, UDP 7882 to this server.
Copy deploy/stm/ to each STM and run install-server-certificate.ps1 there (as Administrator).
DONE
