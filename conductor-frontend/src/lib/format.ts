/**
 * Format a numeric amount as currency. Defaults to USD with no fractional digits,
 * matching the compact money display used across the connector dashboards.
 */
export function formatUsd(n: number, currency = 'USD'): string {
  return n.toLocaleString(undefined, {
    style: 'currency',
    currency,
    maximumFractionDigits: 0,
  });
}

/** Format a duration in seconds as "Xm Ys" (e.g. 279 → "4m 39s"). */
export function formatDuration(seconds: number): string {
  const m = Math.floor(seconds / 60);
  const s = Math.round(seconds % 60);
  return m > 0 ? `${m}m ${s}s` : `${s}s`;
}

/** Format a ratio (0–1) as a percentage string (e.g. 0.52 → "52.0%"). */
export function formatPercent(ratio: number): string {
  return `${(ratio * 100).toFixed(1)}%`;
}
