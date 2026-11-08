/** Shared HTTP plumbing for both backends. */

export class ApiError extends Error {
  readonly status: number;
  readonly code: string;

  constructor(status: number, code: string, message: string) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.code = code;
  }
}

export interface RequestOptions {
  method?: string;
  body?: unknown;
  headers?: Record<string, string>;
  signal?: AbortSignal;
}

/** Sends JSON, returns parsed JSON (or undefined for an empty body). Throws ApiError on any failure. */
export async function request<T>(url: string, opts: RequestOptions = {}): Promise<T> {
  const res = await send(url, opts);
  const text = await res.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

/** Same as request() but returns the raw Response (for images and other binary content). */
export async function send(url: string, opts: RequestOptions = {}): Promise<Response> {
  const headers: Record<string, string> = { Accept: "application/json", ...opts.headers };
  if (opts.body !== undefined) headers["Content-Type"] = "application/json";

  let res: Response;
  try {
    res = await fetch(url, {
      method: opts.method ?? "GET",
      headers,
      body: opts.body !== undefined ? JSON.stringify(opts.body) : undefined,
      signal: opts.signal,
    });
  } catch (e) {
    if ((e as Error).name === "AbortError") throw e;
    throw new ApiError(0, "NETWORK_ERROR", "The service is unreachable. Check that it is running.");
  }
  if (!res.ok) {
    let code = "";
    let message = "";
    try {
      const body = JSON.parse(await res.text());
      // middleware: {code, message}; call-control: {error}
      code = body.code ?? "";
      message = body.message ?? body.error ?? "";
    } catch {
      /* empty or not JSON (Spring Security answers 401/403 with no body) */
    }
    if (!code && res.status === 401) { code = "UNAUTHORIZED"; message = "Your session ended. Please sign in again."; }
    if (!code && res.status === 403) { code = "FORBIDDEN"; message = "Your role does not allow this. Ask a supervisor or an administrator."; }
    if (res.status === 502 || res.status === 504) { code = "SERVICE_DOWN"; message = "The service is not running."; }
    code ||= `HTTP_${res.status}`;
    message ||= res.statusText || code;
    throw new ApiError(res.status, code, message);
  }
  return res;
}

export interface SseHandlers {
  [event: string]: (data: unknown) => void;
}

/**
 * Server-Sent Events over fetch(). The browser's EventSource cannot send headers (X-Portal-Key, X-Teller-Id),
 * so the stream is read by hand. Resolves when the server closes the stream; rejects on errors.
 * onOpen fires once the server has accepted; onData fires for every chunk, pings included (proof of life).
 */
export async function readSse(url: string, headers: Record<string, string>, handlers: SseHandlers,
                              signal: AbortSignal, onOpen?: () => void, onData?: () => void): Promise<void> {
  const res = await send(url, { headers: { ...headers, Accept: "text/event-stream" }, signal });
  if (!res.body) throw new ApiError(0, "NO_STREAM", "The event stream has no body");
  onOpen?.();
  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buf = "";
  for (;;) {
    const { done, value } = await reader.read();
    if (done) return;
    onData?.();
    buf += decoder.decode(value, { stream: true }).replace(/\r\n/g, "\n");
    let i: number;
    while ((i = buf.indexOf("\n\n")) >= 0) {
      const block = buf.slice(0, i);
      buf = buf.slice(i + 2);
      let event = "message";
      let data = "";
      for (const line of block.split("\n")) {
        if (line.startsWith("event:")) event = line.slice(6).trim();
        else if (line.startsWith("data:")) data += line.slice(5).trim();
      }
      if (data && handlers[event]) {
        try { handlers[event](JSON.parse(data)); } catch { /* ignore a malformed event */ }
      }
    }
  }
}
