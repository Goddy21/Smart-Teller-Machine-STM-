// Development: the Vite dev server forwards gateway calls to the STM server, so the browser sees one origin (no CORS).
// Keep baseUrl "" in StmClient. The server uses a private certificate, hence secure: false (development only).
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

const SERVER = "https://10.8.0.1:8443";   // over the WireGuard VPN

export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      "/stm": { target: SERVER, changeOrigin: true, secure: false },
      "/ws": { target: SERVER.replace("https", "wss"), ws: true, changeOrigin: true, secure: false },
    },
  },
});
