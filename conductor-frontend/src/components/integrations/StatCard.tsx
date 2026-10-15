import React from 'react';

/**
 * A presentational stat card used across the connector dashboards and the marketing overview.
 * `children` renders beneath the label (change vs. previous, a note, a sparkline).
 */
export function StatCard({
  label,
  value,
  children,
  className = '',
}: {
  label: string;
  value: React.ReactNode;
  children?: React.ReactNode;
  className?: string;
}) {
  return (
    <div className={`bg-card rounded-lg border border-border p-4 ${className}`.trim()}>
      <div className="text-2xl font-bold text-foreground">{value}</div>
      <div className="text-xs text-muted-foreground mt-1">{label}</div>
      {children}
    </div>
  );
}
