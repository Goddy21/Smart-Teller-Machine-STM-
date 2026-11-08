import { useState } from "react";

/**
 * Charts follow the portal's validated two-series palette (brand blue #2f4fd6, coral #e0533f: passes lightness,
 * chroma, CVD and contrast checks on white). Thin marks, rounded data ends on the baseline, 2 px gaps,
 * recessive grid, hover tooltips, legend for two series, text in ink colours (never the series colour).
 */
export const SERIES = { a: "var(--series-1)", b: "var(--series-2)" } as const;

interface DayRow { day: string; a: number; b: number }

export function DailyBars({ rows, labels }: { rows: DayRow[]; labels: { a: string; b: string } }) {
  const [hover, setHover] = useState<number | null>(null);
  const W = 640, H = 220, L = 30, R = 8, T = 10, B = 26;
  const max = Math.max(4, ...rows.map((r) => Math.max(r.a, r.b)));
  const step = niceStep(max);
  const top = Math.ceil(max / step) * step;
  const plotW = W - L - R, plotH = H - T - B;
  const band = plotW / rows.length;
  const barW = Math.max(3, Math.min(12, (band - 8) / 2));
  const y = (v: number) => T + plotH - (v / top) * plotH;
  const ticks = Array.from({ length: top / step + 1 }, (_, i) => i * step);

  return (
    <div className="chart">
      <div className="legend">
        <span><i style={{ background: SERIES.a }} />{labels.a}</span>
        <span><i style={{ background: SERIES.b }} />{labels.b}</span>
      </div>
      <div className="chart__box">
        <svg viewBox={`0 0 ${W} ${H}`} role="img" aria-label={`${labels.a} and ${labels.b} per day, last ${rows.length} days`}>
          {ticks.map((t) => (
            <g key={t}>
              <line x1={L} x2={W - R} y1={y(t)} y2={y(t)} className="grid" />
              <text x={L - 6} y={y(t) + 4} className="axis" textAnchor="end">{t}</text>
            </g>
          ))}
          {rows.map((r, i) => {
            const cx = L + band * i + band / 2;
            const show = i % 2 === rows.length % 2 || rows.length <= 7;
            return (
              <g key={r.day}>
                {hover === i && <rect x={L + band * i + 1} y={T} width={band - 2} height={plotH} className="hoverband" />}
                <Bar x={cx - barW - 1} w={barW} y0={y(0)} y1={y(r.a)} fill={SERIES.a} />
                <Bar x={cx + 1} w={barW} y0={y(0)} y1={y(r.b)} fill={SERIES.b} />
                {show && <text x={cx} y={H - 8} className="axis" textAnchor="middle">{shortDay(r.day)}</text>}
                <rect x={L + band * i} y={T} width={band} height={plotH + B} fill="transparent"
                      onMouseEnter={() => setHover(i)} onMouseLeave={() => setHover(null)} />
              </g>
            );
          })}
          <line x1={L} x2={W - R} y1={y(0)} y2={y(0)} className="baseline" />
        </svg>
        {hover != null && (
          <div className="tooltip" style={{ left: `${((L + band * hover + band / 2) / W) * 100}%` }}>
            <strong>{longDay(rows[hover].day)}</strong>
            <span><i style={{ background: SERIES.a }} />{labels.a}<b>{rows[hover].a}</b></span>
            <span><i style={{ background: SERIES.b }} />{labels.b}<b>{rows[hover].b}</b></span>
          </div>
        )}
      </div>
    </div>
  );
}

/** Vertical bar with 4 px rounded top, square on the baseline. */
function Bar({ x, w, y0, y1, fill }: { x: number; w: number; y0: number; y1: number; fill: string }) {
  const h = y0 - y1;
  if (h <= 0) return null;
  const r = Math.min(4, w / 2, h);
  return <path d={`M${x},${y0} V${y1 + r} Q${x},${y1} ${x + r},${y1} H${x + w - r} Q${x + w},${y1} ${x + w},${y1 + r} V${y0} Z`} fill={fill} />;
}

/** Labelled horizontal bars for one measure (single series: no legend, the card title names it). */
export function HBars({ rows, color = SERIES.a, format = (n: number) => String(n) }: {
  rows: { label: string; value: number; note?: string }[]; color?: string; format?: (n: number) => string;
}) {
  const max = Math.max(1, ...rows.map((r) => r.value));
  return (
    <ul className="hbars">
      {rows.map((r) => (
        <li key={r.label} title={`${r.label}: ${format(r.value)}`}>
          <span className="hbars__label">{r.label}</span>
          <span className="hbars__track">
            <span className="hbars__fill" style={{ width: `${Math.max(r.value > 0 ? 2 : 0, (r.value / max) * 100)}%`, background: color }} />
          </span>
          <span className="hbars__value">{format(r.value)}</span>
        </li>
      ))}
    </ul>
  );
}

/** A thin meter, e.g. card stock. Status colour + text label, never colour alone. */
export function Meter({ value, max, tone }: { value: number; max: number; tone: "good" | "warn" | "bad" }) {
  return (
    <span className={`meter meter--${tone}`} role="meter" aria-valuenow={value} aria-valuemin={0} aria-valuemax={max}>
      <span style={{ width: `${Math.min(100, (value / max) * 100)}%` }} />
    </span>
  );
}

function niceStep(max: number) {
  const raw = max / 4;
  const p = 10 ** Math.floor(Math.log10(raw));
  return [1, 2, 5, 10].map((m) => m * p).find((s) => s >= raw) ?? p * 10;
}
function shortDay(d: string) {
  return new Date(`${d}T00:00:00Z`).toLocaleDateString("en-GB", { day: "numeric", month: "short", timeZone: "UTC" });
}
function longDay(d: string) {
  return new Date(`${d}T00:00:00Z`).toLocaleDateString("en-GB", { weekday: "short", day: "numeric", month: "long", timeZone: "UTC" });
}

/** Stacked daily columns for several series (colours come from the --series-n tokens, so they follow the theme). */
export function StackedDaily<K extends string>({ rows, series, label }: {
  rows: ({ day: string } & Record<K, number>)[]; series: { key: K; label: string }[]; label: string;
}) {
  const [hover, setHover] = useState<number | null>(null);
  const W = 640, H = 230, L = 30, R = 8, T = 10, B = 26;
  const total = (r: Record<K, number>) => series.reduce((n, s) => n + (r[s.key] ?? 0), 0);
  const max = Math.max(4, ...rows.map(total));
  const step = niceStep(max);
  const top = Math.ceil(max / step) * step;
  const plotW = W - L - R, plotH = H - T - B;
  const band = plotW / Math.max(1, rows.length);
  const barW = Math.max(4, Math.min(22, band - 10));
  const y = (v: number) => T + plotH - (v / top) * plotH;
  const ticks = Array.from({ length: top / step + 1 }, (_, i) => i * step);
  const color = (i: number) => `var(--series-${i + 1})`;

  return (
    <div className="chart">
      <div className="legend">
        {series.map((s, i) => <span key={s.key}><i style={{ background: color(i) }} />{s.label}</span>)}
      </div>
      <div className="chart__box">
        <svg viewBox={`0 0 ${W} ${H}`} role="img" aria-label={label}>
          {ticks.map((t) => (
            <g key={t}>
              <line x1={L} x2={W - R} y1={y(t)} y2={y(t)} className="grid" />
              <text x={L - 6} y={y(t) + 4} className="axis" textAnchor="end">{t}</text>
            </g>
          ))}
          {rows.map((r, i) => {
            const cx = L + band * i + band / 2;
            let acc = 0;
            const segs = series.map((s, si) => {
              const v = r[s.key] ?? 0;
              const y0 = y(acc), y1 = y(acc + v);
              acc += v;
              return v > 0 ? <rect key={s.key} x={cx - barW / 2} width={barW} y={y1 + 1} height={Math.max(0, y0 - y1 - 1)} rx={2} fill={color(si)} /> : null;
            });
            const show = i % 2 === rows.length % 2 || rows.length <= 7;
            return (
              <g key={r.day}>
                {hover === i && <rect x={L + band * i + 1} y={T} width={band - 2} height={plotH} className="hoverband" />}
                {segs}
                {show && <text x={cx} y={H - 8} className="axis" textAnchor="middle">{shortDay(r.day)}</text>}
                <rect x={L + band * i} y={T} width={band} height={plotH + B} fill="transparent"
                      onMouseEnter={() => setHover(i)} onMouseLeave={() => setHover(null)} />
              </g>
            );
          })}
          <line x1={L} x2={W - R} y1={y(0)} y2={y(0)} className="baseline" />
        </svg>
        {hover != null && (
          <div className="tooltip" style={{ left: `${((L + band * hover + band / 2) / W) * 100}%` }}>
            <strong>{longDay(rows[hover].day)}</strong>
            {series.map((s, i) => <span key={s.key}><i style={{ background: color(i) }} />{s.label}<b>{rows[hover][s.key]}</b></span>)}
          </div>
        )}
      </div>
    </div>
  );
}
