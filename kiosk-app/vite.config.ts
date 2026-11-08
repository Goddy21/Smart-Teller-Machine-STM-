// Development: let Vite forward API and WebSocket calls to the middleware, so the browser sees one origin.
// With this proxy, leave VITE_STM_URL empty in .env.local. No CORS is involved.
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      "/api": "http://localhost:8080",
      "/ws": { target: "ws://localhost:8080", ws: true },
    },
  },
});
