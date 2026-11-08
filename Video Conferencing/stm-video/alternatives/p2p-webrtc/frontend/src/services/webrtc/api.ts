import { webrtcConfig } from "./config";
import type { CallResponse, IceConfig, QueueRow } from "./types";

async function call<T>(method: string, path: string, body?: unknown): Promise<T> {
  const res = await fetch(webrtcConfig.apiBase + path, {
    method,
    credentials: "include",
    headers: { "Content-Type": "application/json", ...(webrtcConfig.devHeaders ?? {}) },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (!res.ok) {
    let message = "";
    try { message = ((await res.json()) as { message?: string }).message ?? ""; } catch { /* no body */ }
    throw new Error(message || `Request failed (${res.status})`);
  }
  const text = await res.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

export const callApi = {
  request: (txId?: string) => call<CallResponse>("POST", "/request", { txId }),
  queue: () => call<QueueRow[]>("GET", "/queue"),
  accept: (id: string) => call<CallResponse>("POST", `/${id}/accept`),
  reject: (id: string) => call<void>("POST", `/${id}/reject`),
  end: (id: string) => call<void>("POST", `/${id}/end`),
  turnCredentials: (id: string) => call<IceConfig>("GET", `/${id}/turn-credentials`),
};
