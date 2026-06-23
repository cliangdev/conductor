'use client';
export const dynamic = 'force-dynamic';

import { useParams } from 'next/navigation';
import PostHogConnectorPage from '@/components/integrations/PostHogConnectorPage';
import GcpBillingConnectorPage from '@/components/integrations/GcpBillingConnectorPage';
import GitHubConnectorPage from '@/components/integrations/GitHubConnectorPage';
import RevenueCatConnectorPage from '@/components/integrations/RevenueCatConnectorPage';
import GscConnectorPage from '@/components/integrations/GscConnectorPage';
import AppleSearchAdsConnectorPage from '@/components/integrations/AppleSearchAdsConnectorPage';

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
    default:
      return (
        <div className="max-w-4xl mx-auto px-4 py-8">
          <div className="text-center py-12">
            <p className="text-muted-foreground">Unknown connector: {connectorId}</p>
          </div>
        </div>
      );
  }
}
