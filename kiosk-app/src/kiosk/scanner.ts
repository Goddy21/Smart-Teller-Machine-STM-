/**
 * Passport scanner adapter.
 *
 * On the real kiosk the scanner is driven by the local GRG eCAT service (ws://localhost:8863/cmd). Its job here is
 * only to return the two MRZ lines; the middleware does all the checking. Replace demoScan with an eCAT
 * implementation of the same shape when the SDK wrapper is ready.
 */
export interface MrzRead {
  mrzLine1: string;
  mrzLine2: string;
}

export type ScanProgress = (percent: number) => void;

/** Pretends to read a passport: animates progress for ~2 s, then returns the given MRZ lines. */
export function demoScan(mrz: MrzRead, onProgress: ScanProgress, signal: AbortSignal): Promise<MrzRead> {
  return new Promise((resolve, reject) => {
    let p = 0;
    const timer = setInterval(() => {
      p = Math.min(100, p + 7 + Math.random() * 8);
      onProgress(Math.round(p));
      if (p >= 100) {
        clearInterval(timer);
        resolve(mrz);
      }
    }, 150);
    signal.addEventListener("abort", () => {
      clearInterval(timer);
      reject(new DOMException("Scan cancelled", "AbortError"));
    });
  });
}

/** MRZ lines that fail the check digits, to demo Error A. */
export const UNREADABLE_MRZ: MrzRead = {
  mrzLine1: "P<KENUNREADABLE<<DOCUMENT<<<<<<<<<<<<<<<<<<<",
  mrzLine2: "X0000000<0KEN9001011F3001019<<<<<<<<<<<<<<00",
};
