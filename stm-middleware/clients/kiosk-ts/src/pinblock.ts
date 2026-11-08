/**
 * DEVELOPMENT ONLY. Builds the clear ISO 9564 PIN field the demo profile's simulated HSM accepts, e.g. 2580 -> 042580FFFFFFFFFF.
 * On a real Smart Teller the encrypting PIN pad (EPP) produces the PIN block and the clear PIN never reaches this app;
 * the prod profile has no simulated HSM and rejects these blocks.
 */
export function devPinBlock(pin: string): string {
  if (!/^\d{4,12}$/.test(pin)) throw new Error("PIN must be 4 to 12 digits");
  const s = "0" + pin.length.toString(16).toUpperCase() + pin;
  return s.padEnd(16, "F");
}
