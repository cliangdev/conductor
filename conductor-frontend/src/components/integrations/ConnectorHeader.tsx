import { ExternalLink } from 'lucide-react';
import { StatusBadge } from '@/components/ui/status-badge';

type HealthStatus = 'HEALTHY' | 'DEGRADED' | 'SETUP_REQUIRED' | null;

const HEALTH_STATUS: Record<Exclude<HealthStatus, null>, { status: string; label: string }> = {
  HEALTHY: { status: 'done', label: 'Connected' },
  DEGRADED: { status: 'in_progress', label: 'Degraded' },
  SETUP_REQUIRED: { status: 'in_progress', label: 'Setup required' },
};

/**
 * The shared dashboard header for connector pages: a health pill, an "Open <vendor>"
 * external link, and a Refresh button.
 */
export function ConnectorHeader({
  title,
  subtitle,
  status,
  externalUrl,
  externalLabel,
  onRefresh,
  refreshing,
}: {
  title: string;
  subtitle?: string;
  status: HealthStatus;
  externalUrl: string;
  externalLabel: string;
  onRefresh: () => void;
  refreshing: boolean;
}) {
  const health = status ? HEALTH_STATUS[status] : null;
  return (
    <div className="mb-6 flex items-start justify-between">
      <div>
        <h1 className="text-2xl font-bold text-foreground">{title}</h1>
        {subtitle && <p className="text-sm text-muted-foreground mt-1">{subtitle}</p>}
      </div>
      <div className="flex items-center gap-2">
        {health && <StatusBadge status={health.status} label={health.label} />}
        <a
          href={externalUrl}
          target="_blank"
          rel="noopener noreferrer"
          className="inline-flex items-center gap-1.5 rounded-md border border-border bg-background px-3 py-1.5 text-xs font-medium text-foreground hover:bg-muted"
        >
          <ExternalLink className="h-3 w-3" />
          {externalLabel}
        </a>
        <button
          onClick={onRefresh}
          disabled={refreshing}
          className="rounded-md border border-border bg-background px-3 py-1.5 text-xs font-medium text-foreground hover:bg-muted disabled:opacity-50"
        >
          {refreshing ? 'Refreshing…' : 'Refresh'}
        </button>
      </div>
    </div>
  );
}
