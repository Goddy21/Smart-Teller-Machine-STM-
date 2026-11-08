import { defineConfig, loadEnv } from "vite";
import react from "@vitejs/plugin-react";

// The portal talks to two backends through this dev server, so the browser sees one origin (no CORS):
//   /api/v1/...  -> STM middleware (sessions, terminals, deposits, audit)        default http://localhost:8080
//   /api/...     -> call-control (video calls, teller presence, call evidence)    default http://localhost:8090
// Order matters: the more specific /api/v1 rule must come first.
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), "");
  const middleware = env.STM_MIDDLEWARE_URL || "http://localhost:8080";
  const callControl = env.STM_CALL_CONTROL_URL || "http://localhost:8090";
  return {
    plugins: [react()],
    server: {
      port: 5174,
      proxy: {
        // The proxy makes these same-origin calls, so drop the browser's Origin header: otherwise the middleware's
        // CORS check sees http://localhost:5174 as a foreign site and answers 403.
        "/api/v1": { target: middleware, changeOrigin: true, configure: (p) => p.on("proxyReq", (req) => req.removeHeader("origin")) },
        "/api": { target: callControl, changeOrigin: true, configure: (p) => p.on("proxyReq", (req) => req.removeHeader("origin")) },
      },
    },
  };
});
