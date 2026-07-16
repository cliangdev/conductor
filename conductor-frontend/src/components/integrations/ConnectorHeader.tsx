import { ExternalLink } from 'lucide-react';
import { PageHeader } from '@/components/layout/PageHeader';
import { StatusBadge } from '@/components/ui/status-badge';
import { Button, buttonVariants } from '@/components/ui/button';
import { cn } from '@/lib/utils';

type HealthStatus = 'HEALTHY' | 'DEGRADED' | 'SETUP_REQUIRED' | null;

const HEALTH_STATUS: Record<Exclude<HealthStatus, null>, { status: string; label: string }> = {
  HEALTHY: { status: 'done', label: 'Connected' },
  DEGRADED: { status: 'in_progress', label: 'Degraded' },
  SETUP_REQUIRED: { status: 'in_progress', label: 'Setup required' },
};

/**
 * The shared dashboard header for connector pages, built on the PageHeader idiom: title, a health
 * StatusBadge in the status slot, and the "Open <vendor>" link + Refresh button as actions. No
 * breadcrumb here — ConnectorLayout already renders the one `Integrations / {name}` breadcrumb
 * above the tabs, shared by every connector page regardless of which tab or component renders.
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
    <PageHeader
      className="mb-6"
      title={title}
      description={subtitle}
      status={health && <StatusBadge status={health.status} label={health.label} />}
      actions={
        <>
          <a
            href={externalUrl}
            target="_blank"
            rel="noopener noreferrer"
            className={cn(buttonVariants({ variant: 'outline', size: 'sm' }), 'gap-1.5')}
          >
            <ExternalLink className="h-3 w-3" />
            {externalLabel}
          </a>
          <Button variant="outline" size="sm" onClick={onRefresh} disabled={refreshing}>
            {refreshing ? 'Refreshing…' : 'Refresh'}
          </Button>
        </>
      }
    />
  );
}
