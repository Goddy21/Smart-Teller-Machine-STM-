import type { SignalingMessage } from "./types";

export type SocketStatus = "connecting" | "open" | "closed";
type Handler = (m: SignalingMessage) => void;

/** WebSocket to the Spring Boot signaling endpoint with automatic reconnect and a keep-alive ping. */
export class SignalingClient {
  private ws: WebSocket | null = null;
  private handlers = new Set<Handler>();
  private pingTimer: number | undefined;
  private retryTimer: number | undefined;
  private retries = 0;
  private stopped = false;

  constructor(private url: string, private onStatus?: (s: SocketStatus) => void) {}

  connect(): void { this.stopped = false; this.open(); }

  private open(): void {
    this.onStatus?.("connecting");
    const ws = new WebSocket(this.url);
    this.ws = ws;
    ws.onopen = () => {
      this.retries = 0;
      this.onStatus?.("open");
      this.pingTimer = window.setInterval(() => this.send({ type: "PING" }), 25_000);
    };
    ws.onmessage = ev => {
      let m: SignalingMessage;
      try { m = JSON.parse(ev.data as string) as SignalingMessage; } catch { return; }
      this.handlers.forEach(h => h(m));
    };
    ws.onclose = () => {
      window.clearInterval(this.pingTimer);
      this.onStatus?.("closed");
      if (!this.stopped) this.retryTimer = window.setTimeout(() => this.open(), Math.min(1000 * 2 ** this.retries++, 10_000));
    };
  }

  on(handler: Handler): () => void { this.handlers.add(handler); return () => this.handlers.delete(handler); }

  send(m: SignalingMessage): boolean {
    if (this.ws?.readyState !== WebSocket.OPEN) return false;
    this.ws.send(JSON.stringify(m));
    return true;
  }

  close(): void {
    this.stopped = true;
    window.clearInterval(this.pingTimer);
    window.clearTimeout(this.retryTimer);
    this.ws?.close();
  }
}
