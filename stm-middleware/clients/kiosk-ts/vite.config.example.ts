// Development: let Vite forward gateway and WebSocket calls to the middleware, so the browser sees one origin.
// With this proxy, leave baseUrl "" in StmClient. No CORS is involved.
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      "/stm": "http://localhost:8080",
      "/ws": { target: "ws://localhost:8080", ws: true },
    },
  },
});
