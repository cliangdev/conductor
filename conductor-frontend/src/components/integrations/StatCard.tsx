import React from 'react';

/** A presentational stat card used across the connector dashboards. */
export function StatCard({ label, value }: { label: string; value: React.ReactNode }) {
  return (
    <div className="bg-card rounded-lg border border-border p-4">
      <div className="text-2xl font-bold text-foreground">{value}</div>
      <div className="text-xs text-muted-foreground mt-1">{label}</div>
    </div>
  );
}
