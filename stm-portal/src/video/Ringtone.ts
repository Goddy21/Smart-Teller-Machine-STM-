/**
 * Incoming-call alert for the teller: a synthesized phone ring (no audio file), a flashing tab title and, when the
 * portal tab is in the background, a desktop notification.
 *
 * Browsers only allow sound after the user has interacted with the page, so unlock() is called from the
 * "Go available" click; any later click or key press also unlocks it.
 */
export class Ringtone {
  private ctx: AudioContext | null = null;
  private loop: ReturnType<typeof setInterval> | undefined;
  private flash: ReturnType<typeof setInterval> | undefined;
  private title = "";
  private note: Notification | null = null;

  constructor() {
    if (typeof window === "undefined") return;
    const once = () => this.unlock();
    window.addEventListener("pointerdown", once, { passive: true });
    window.addEventListener("keydown", once);
  }

  /** Call from a click: creates/resumes the audio context and asks once for notification permission. */
  unlock() {
    try {
      const AC = window.AudioContext ?? (window as unknown as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext;
      if (!this.ctx && AC) this.ctx = new AC();
      if (this.ctx?.state === "suspended") void this.ctx.resume();
    } catch { /* no audio on this machine */ }
    if ("Notification" in window && Notification.permission === "default") void Notification.requestPermission().catch(() => undefined);
  }

  start(text: string) {
    this.stop();
    this.unlock();
    this.burst();
    this.loop = setInterval(() => this.burst(), 3000);       // ring-ring … pause … ring-ring

    this.title = document.title;
    let on = false;
    this.flash = setInterval(() => { on = !on; document.title = on ? "📞 Incoming call" : this.title; }, 800);

    if (document.hidden && "Notification" in window && Notification.permission === "granted") {
      try {
        this.note = new Notification("Incoming video call", { body: text, tag: "stm-ring", requireInteraction: true });
        this.note.onclick = () => { window.focus(); this.note?.close(); };
      } catch { /* notifications unavailable */ }
    }
  }

  stop() {
    clearInterval(this.loop);
    this.loop = undefined;
    if (this.flash !== undefined) { clearInterval(this.flash); this.flash = undefined; document.title = this.title || document.title; }
    this.note?.close();
    this.note = null;
  }

  /** Two short dual-tone rings (UK-style 400 + 450 Hz), about 1.2 s in total. */
  private burst() {
    const ctx = this.ctx;
    if (!ctx || ctx.state !== "running") return;
    const t0 = ctx.currentTime + 0.02;
    for (const offset of [0, 0.6]) {
      const gain = ctx.createGain();
      gain.connect(ctx.destination);
      const start = t0 + offset, end = start + 0.4;
      gain.gain.setValueAtTime(0, start);
      gain.gain.linearRampToValueAtTime(0.18, start + 0.02);
      gain.gain.setValueAtTime(0.18, end - 0.04);
      gain.gain.linearRampToValueAtTime(0, end);
      for (const f of [400, 450]) {
        const osc = ctx.createOscillator();
        osc.type = "sine";
        osc.frequency.value = f;
        osc.connect(gain);
        osc.start(start);
        osc.stop(end + 0.01);
      }
    }
  }
}
