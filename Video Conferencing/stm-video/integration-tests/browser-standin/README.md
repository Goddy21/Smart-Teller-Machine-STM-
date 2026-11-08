Test double, not part of the product. `server.mjs` speaks call-control's HTTP contract (paths, headers, SSE events,
LiveKit token claims, dev-gateway cookies) so the browser code could be verified against a real LiveKit server in an
environment without Maven Central. Use the real call-control for everything else.
    LIVEKIT_PUBLIC_URL=ws://<ip>:7880 node integration-tests/browser-standin/server.mjs
    node e2e/run.mjs http://<ip>:8080
