import { createContext, useContext, useSyncExternalStore } from "react";
import type { User } from "./auth";
import type { PortalApi } from "./api/portal";
import type { TellerConsole } from "./video/TellerConsole";

export type Page = "overview" | "sessions" | "applications" | "video" | "fleet" | "cash" | "cards" | "reconciliation" | "audit"
  | "reference" | "simulator" | "users";

export interface PortalCtx {
  /** The signed-in portal user (from the middleware). */
  user: User;
  portal: PortalApi;
  teller: TellerConsole;
  go: (page: Page, param?: string) => void;
  /** Opens the session drawer from anywhere (e.g. the video page or the overview). */
  openSession: (id: string) => void;
  toast: (text: string, tone?: "ok" | "error" | "info") => void;
  signOut: () => void;
  changePassword: () => void;
}

export const Ctx = createContext<PortalCtx | null>(null);

export function usePortal(): PortalCtx {
  const c = useContext(Ctx);
  if (!c) throw new Error("usePortal outside the portal shell");
  return c;
}

/** Live state of the teller's video console. */
export function useTeller() {
  const { teller } = usePortal();
  const state = useSyncExternalStore(teller.subscribe, teller.getState);
  return { teller, state };
}

export const TELLER_LIMIT = Number(import.meta.env.VITE_TELLER_LIMIT ?? 5000);
export const RECORDING_REQUIRED_ABOVE = Number(import.meta.env.VITE_RECORDING_REQUIRED_ABOVE ?? 1000);
