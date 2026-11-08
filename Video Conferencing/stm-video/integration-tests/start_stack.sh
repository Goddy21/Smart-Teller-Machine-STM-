#!/bin/sh
# Starts Redis, the Java webhook receiver and LiveKit (idempotent). Uses PID files; never pkill -f.
export PATH=/tmp/jdk-21.0.5+11/bin:$PATH
CP=/usr/share/java/jackson-core.jar:/usr/share/java/jackson-databind.jar:/usr/share/java/jackson-annotations.jar
alive() { [ -f "$1" ] && kill -0 "$(cat "$1")" 2>/dev/null; }
redis-cli ping >/dev/null 2>&1 || redis-server --port 6379 --daemonize yes --save "" --appendonly no >/dev/null
alive /tmp/webhook.pid || { setsid nohup java -cp /tmp/lkt/out:$CP com.stm.callcontrol.LkTool webhooks 8099 /tmp/webhooks.jsonl > /tmp/webhook_srv.log 2>&1 < /dev/null & echo $! > /tmp/webhook.pid; }
alive /tmp/livekit.pid || { setsid nohup /tmp/lk/livekit-server --config /tmp/lk/lk.yaml > /tmp/lk/server.log 2>&1 < /dev/null & echo $! > /tmp/livekit.pid; }
sleep 5
curl -s -m 5 -o /dev/null -w "livekit http=%{http_code}\n" http://127.0.0.1:7880/
redis-cli ping
