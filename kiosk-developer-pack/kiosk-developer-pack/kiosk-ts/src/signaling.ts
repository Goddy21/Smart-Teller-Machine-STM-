import type { StmClient } from "./client";
import type { KioskMessage, ServerMessage } from "./types";

type Handler<T> = (msg: T) => void;
type Handlers = { [K in ServerMessage["type"]]?: Handler<Extract<ServerMessage, { type: K }>>[] };

export interface KioskSignalingOptions {
  /** ws(s):// origin of the middleware. Default: derived from client.baseUrl, or from window.location when that is "". */
  wsUrl?: string;
  /** Reconnect automatically after an unexpected close. Default true. */
  reconnect?: boolean;
  /** Called on every connection state change, e.g. to show a "teller unavailable" banner. */
  onStateChange?: (state: "connecting" | "open" | "closed") => void;
}

/**
 * Kiosk side of the signaling WebSocket (/ws/signaling): remote commands from the portal's Kiosk fleet page.
 *
 * Browsers cannot put an Authorization header on a WebSocket, so each connect first fetches a one-time ticket over
 * HTTPS (POST /stm/v1/signaling/ticket, device token) and opens /ws/signaling?ticket=...  Tickets live ~30 s and
 * work once; every reconnect fetches a fresh one.
 *
 *   const sig = new KioskSignaling(stm);
 *   sig.on("COMMAND", m => m.command === "LOCK" ? showOutOfService() : m.command === "UNLOCK" ? showIdle() : ...);
 *   await sig.connect();
 *
 * "Talk to a teller" does not use this socket any more: see StmClient.requestTeller (D-8) and TellerCall.media.
 * Teller decisions on applications reach the kiosk through B-13 (poll application()).
 */
export class KioskSignaling {
  private ws: WebSocket | null = null;
  private handlers: Handlers = {};
  private stopped = false;
  private attempt = 0;
  private retryTimer: ReturnType<typeof setTimeout> | null = null;
  private readonly reconnect: boolean;

  private readonly client: StmClient;
  private readonly opts: KioskSignalingOptions;

  constructor(client: StmClient, opts: KioskSignalingOptions = {}) {
    this.client = client;
    this.opts = opts;
    this.reconnect = opts.reconnect ?? true;
  }

  get connected(): boolean {
    return this.ws?.readyState === WebSocket.OPEN;
  }

  on<K extends ServerMessage["type"]>(type: K, fn: Handler<Extract<ServerMessage, { type: K }>>): () => void {
    const list = (this.handlers[type] ??= []) as Handler<Extract<ServerMessage, { type: K }>>[];
    list.push(fn);
    return () => {
      const i = list.indexOf(fn);
      if (i >= 0) list.splice(i, 1);
    };
  }

  /** Resolves once the socket is open. Rejects if the ticket request or the first handshake fails. */
  async connect(): Promise<void> {
    this.stopped = false;
    this.opts.onStateChange?.("connecting");
    let ticket: string;
    try {
      ({ ticket } = await this.client.signalingTicket());
    } catch (e) {
      this.opts.onStateChange?.("closed");
      this.scheduleReconnect();
      throw e;
    }
    const url = `${this.wsBase()}/ws/signaling?ticket=${encodeURIComponent(ticket)}`;

    await new Promise<void>((resolve, reject) => {
      const ws = new WebSocket(url);
      let opened = false;
      ws.onopen = () => {
        opened = true;
        this.attempt = 0;
        this.ws = ws;
        this.opts.onStateChange?.("open");
        resolve();
      };
      ws.onmessage = (ev) => this.dispatch(ev.data);
      ws.onclose = () => {
        if (this.ws === ws) this.ws = null;
        this.opts.onStateChange?.("closed");
        if (!opened) reject(new Error("Signaling handshake rejected"));
        this.scheduleReconnect();
      };
    });
  }

  send(msg: KioskMessage): void {
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN) throw new Error("Signaling socket is not open");
    this.ws.send(JSON.stringify(msg));
  }

  close(): void {
    this.stopped = true;
    if (this.retryTimer) clearTimeout(this.retryTimer);
    this.ws?.close(1000, "kiosk shutdown");
    this.ws = null;
  }

  private dispatch(data: unknown) {
    if (typeof data !== "string") return;
    let msg: ServerMessage;
    try { msg = JSON.parse(data); } catch { return; }
    const list = this.handlers[msg.type] as Handler<ServerMessage>[] | undefined;
    list?.forEach((fn) => {
      try { fn(msg); } catch (e) { console.error("signaling handler failed", e); }
    });
  }

  /** Back-off 1 s, 2 s, 4 s ... capped at 30 s, with jitter so a fleet doesn't reconnect in lockstep. */
  private scheduleReconnect() {
    if (this.stopped || !this.reconnect || this.retryTimer) return;
    const delay = Math.min(30_000, 1000 * 2 ** this.attempt++) * (0.75 + Math.random() * 0.5);
    this.retryTimer = setTimeout(() => {
      this.retryTimer = null;
      this.connect().catch(() => this.scheduleReconnect());
    }, delay);
  }

  private wsBase(): string {
    if (this.opts.wsUrl) return this.opts.wsUrl.replace(/\/+$/, "");
    const http = this.client.origin || `${location.protocol}//${location.host}`;
    return http.replace(/^http/, "ws");
  }
}
