'use client';
export const dynamic = 'force-dynamic';

import { useParams } from 'next/navigation';
import PostHogConnectorPage from '@/components/integrations/PostHogConnectorPage';
import GcpBillingConnectorPage from '@/components/integrations/GcpBillingConnectorPage';
import GitHubConnectorPage from '@/components/integrations/GitHubConnectorPage';
import RevenueCatConnectorPage from '@/components/integrations/RevenueCatConnectorPage';
import GscConnectorPage from '@/components/integrations/GscConnectorPage';
import AppleSearchAdsConnectorPage from '@/components/integrations/AppleSearchAdsConnectorPage';
import GcpConnectorPage from '@/components/integrations/GcpConnectorPage';
import GenericConnectorPage from '@/components/integrations/GenericConnectorPage';

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
