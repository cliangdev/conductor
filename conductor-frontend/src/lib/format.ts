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

/**
 * Format the elapsed time between a start and (optional) end ISO timestamp as "Xm Ys",
 * defaulting the end to now. Returns '—' when `startedAt` is falsy (null/undefined/''),
 * which guards against jobs the backend serializes with `startedAt: null` (e.g. PENDING/SKIPPED).
 */
export function formatElapsed(startedAt?: string | null, completedAt?: string | null): string {
  if (!startedAt) return '—';
  const endMs = completedAt ? new Date(completedAt).getTime() : Date.now();
  const totalSeconds = Math.floor((endMs - new Date(startedAt).getTime()) / 1000);
  return formatDuration(totalSeconds);
}

/** Format an absolute timestamp using the viewer's locale (e.g. for a detail-page sub-header). */
export function formatDate(date: string | Date): string {
  return new Date(date).toLocaleString();
}

/** Format a timestamp as a short relative string: "just now", "5m ago", "2h ago", "3d ago". */
export function timeAgo(date: string | Date): string {
  const diffMs = Date.now() - new Date(date).getTime();
  const minutes = Math.floor(diffMs / 60000);
  if (minutes < 1) return 'just now';
  if (minutes < 60) return `${minutes}m ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}h ago`;
  const days = Math.floor(hours / 24);
  return `${days}d ago`;
}

/** Format a ratio (0–1) as a percentage string (e.g. 0.52 → "52.0%"). */
export function formatPercent(ratio: number): string {
  return `${(ratio * 100).toFixed(1)}%`;
}
