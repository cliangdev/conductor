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
