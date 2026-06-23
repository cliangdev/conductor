import { ExternalLink } from 'lucide-react';

type HealthStatus = 'HEALTHY' | 'DEGRADED' | 'SETUP_REQUIRED' | null;

const PILL: Record<
  Exclude<HealthStatus, null>,
  { label: string; text: string; dot: string }
> = {
  HEALTHY: {
    label: 'Connected',
    text: 'text-green-600 dark:text-green-400',
    dot: 'bg-green-500',
  },
  DEGRADED: {
    label: 'Degraded',
    text: 'text-yellow-600 dark:text-yellow-400',
    dot: 'bg-yellow-500',
  },
  SETUP_REQUIRED: {
    label: 'Setup required',
    text: 'text-muted-foreground',
    dot: 'bg-muted-foreground',
  },
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
  const pill = status ? PILL[status] : null;
  return (
    <div className="mb-6 flex items-start justify-between">
      <div>
        <h1 className="text-2xl font-bold text-foreground">{title}</h1>
        {subtitle && <p className="text-sm text-muted-foreground mt-1">{subtitle}</p>}
      </div>
      <div className="flex items-center gap-2">
        {pill && (
          <span className={`inline-flex items-center gap-1 text-xs font-medium ${pill.text}`}>
            <span className={`h-1.5 w-1.5 rounded-full inline-block ${pill.dot}`} />
            {pill.label}
          </span>
        )}
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
