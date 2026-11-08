import type { Role } from "./types";

/** Production: the gateway sets identity headers (mTLS for kiosks, portal session for tellers) and nothing is needed here.
 *  Local dev: call configureWebrtc({ devHeaders: { "X-Teller-Id": "teller1" }, devQuery: "teller=teller1" }) and run the
 *  backend with brits.webrtc.dev-identity=true. */
export const webrtcConfig = {
  apiBase: "/api/v1/stm/calls",
  wsPath: "/stm/ws/signaling",
  devHeaders: undefined as Record<string, string> | undefined,
  devQuery: undefined as string | undefined,
};

export function configureWebrtc(patch: Partial<typeof webrtcConfig>): void { Object.assign(webrtcConfig, patch); }

export function signalingUrl(_role: Role): string {
  const scheme = location.protocol === "https:" ? "wss" : "ws";
  return `${scheme}://${location.host}${webrtcConfig.wsPath}${webrtcConfig.devQuery ? "?" + webrtcConfig.devQuery : ""}`;
}
