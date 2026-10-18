'use client';
export const dynamic = 'force-dynamic';

import nextDynamic from 'next/dynamic';
import { useParams } from 'next/navigation';
import { Skeleton } from '@/components/ui/skeleton';

// Only one connector's page is ever on screen, and several pull in recharts, so each is its own chunk
// loaded when that connector is opened instead of all eight (and recharts) riding in this route's bundle.
function ConnectorPageSkeleton() {
  return (
    <div className="space-y-3" aria-busy="true" aria-label="Loading">
      <Skeleton className="h-6 w-56" />
      <Skeleton className="h-4 w-80" />
      <Skeleton className="h-40 w-full" />
    </div>
  );
}

const PostHogConnectorPage = nextDynamic(() => import('@/components/integrations/PostHogConnectorPage'), { loading: ConnectorPageSkeleton });
const GcpBillingConnectorPage = nextDynamic(() => import('@/components/integrations/GcpBillingConnectorPage'), { loading: ConnectorPageSkeleton });
const GitHubConnectorPage = nextDynamic(() => import('@/components/integrations/GitHubConnectorPage'), { loading: ConnectorPageSkeleton });
const RevenueCatConnectorPage = nextDynamic(() => import('@/components/integrations/RevenueCatConnectorPage'), { loading: ConnectorPageSkeleton });
const GscConnectorPage = nextDynamic(() => import('@/components/integrations/GscConnectorPage'), { loading: ConnectorPageSkeleton });
const AppleSearchAdsConnectorPage = nextDynamic(() => import('@/components/integrations/AppleSearchAdsConnectorPage'), { loading: ConnectorPageSkeleton });
const GcpConnectorPage = nextDynamic(() => import('@/components/integrations/GcpConnectorPage'), { loading: ConnectorPageSkeleton });
const GenericConnectorPage = nextDynamic(() => import('@/components/integrations/GenericConnectorPage'), { loading: ConnectorPageSkeleton });

// The breadcrumb lives in the persistent layout; this page only renders the
// per-connector body, which is the part that re-renders on connector switch.
export default function ConnectorPage() {
  const { projectId, connectorId } = useParams<{ projectId: string; connectorId: string }>();

  switch (connectorId) {
    case 'posthog':
      return <PostHogConnectorPage projectId={projectId} />;
    case 'gcp-billing':
      return <GcpBillingConnectorPage projectId={projectId} />;
    case 'github':
      return <GitHubConnectorPage projectId={projectId} />;
    case 'revenuecat':
      return <RevenueCatConnectorPage projectId={projectId} />;
    case 'gsc':
      return <GscConnectorPage projectId={projectId} />;
    case 'apple-search-ads':
      return <AppleSearchAdsConnectorPage projectId={projectId} />;
    case 'gcp':
      return <GcpConnectorPage projectId={projectId} />;
    default:
      // Any connector without a bespoke dashboard (e.g. action-only connectors like Discord)
      // falls through to a generic overview driven by the connector catalog.
      return <GenericConnectorPage projectId={projectId} connectorId={connectorId} />;
  }
}
