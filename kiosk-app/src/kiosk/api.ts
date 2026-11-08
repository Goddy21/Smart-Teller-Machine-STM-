import { StmClient } from "../stm";

/** One client for the whole app. The token comes from .env.local (VITE_STM_TOKEN). */
export const stm = new StmClient({
  baseUrl: import.meta.env.VITE_STM_URL ?? "",
  token: import.meta.env.VITE_STM_TOKEN,
});

/** Demo helpers (persona picker on the passport screen). Set VITE_STM_DEMO=false on a real kiosk. */
export const DEMO = import.meta.env.VITE_STM_DEMO !== "false";

/** How long the screen may sit untouched before "Are you still there?" appears. */
export const IDLE_MS = 60_000;
/** Countdown on the timeout screen, as in the design. */
export const TIMEOUT_SECONDS = 20;
/** How often the idle and menu screens re-check machine status. */
export const STATUS_POLL_MS = 30_000;

const NATIONALITIES: Record<string, string> = {
  KEN: "KENYAN", UGA: "UGANDAN", TZA: "TANZANIAN", RWA: "RWANDAN", BDI: "BURUNDIAN", SSD: "SOUTH SUDANESE",
  ETH: "ETHIOPIAN", SOM: "SOMALI", NGA: "NIGERIAN", GHA: "GHANAIAN", ZAF: "SOUTH AFRICAN", COD: "CONGOLESE",
  GBR: "BRITISH", USA: "AMERICAN", IND: "INDIAN", CHN: "CHINESE", DEU: "GERMAN", FRA: "FRENCH",
};

/** ICAO code -> label shown on screen ("KEN" -> "KENYAN"). Unknown codes are shown as-is. */
export function nationality(code: string): string {
  return NATIONALITIES[code] ?? code;
}

/** "1990-03-14" -> "14 MAR 1990" (or the locale's equivalent). Dates are calendar dates, so format in UTC. */
export function passportDate(iso: string, locale: string): string {
  const d = new Date(`${iso}T00:00:00Z`);
  if (Number.isNaN(d.getTime())) return iso;
  if (locale.startsWith("en")) { // as printed in passports: 14 MAR 1990
    const months = ["JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"];
    return `${String(d.getUTCDate()).padStart(2, "0")} ${months[d.getUTCMonth()]} ${d.getUTCFullYear()}`;
  }
  return d.toLocaleDateString(locale, { day: "2-digit", month: "short", year: "numeric", timeZone: "UTC" })
    .replace(/[.,]/g, "").toUpperCase();
}
