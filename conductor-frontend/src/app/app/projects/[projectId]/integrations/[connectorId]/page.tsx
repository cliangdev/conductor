'use client';
export const dynamic = 'force-dynamic';

import { useParams } from 'next/navigation';
import { Breadcrumb } from '@/components/layout/PageHeader';
import PostHogConnectorPage from '@/components/integrations/PostHogConnectorPage';
import GcpBillingConnectorPage from '@/components/integrations/GcpBillingConnectorPage';
import GitHubConnectorPage from '@/components/integrations/GitHubConnectorPage';
import RevenueCatConnectorPage from '@/components/integrations/RevenueCatConnectorPage';
import GscConnectorPage from '@/components/integrations/GscConnectorPage';
import AppleSearchAdsConnectorPage from '@/components/integrations/AppleSearchAdsConnectorPage';

// Display names for the breadcrumb. Mirrors the connectors enumerated below.
const CONNECTOR_LABELS: Record<string, string> = {
  posthog: 'PostHog',
  'gcp-billing': 'GCP Billing',
  github: 'GitHub',
  revenuecat: 'RevenueCat',
  gsc: 'Search Console',
  'apple-search-ads': 'Apple Search Ads',
};

function ConnectorBody({ projectId, connectorId }: { projectId: string; connectorId: string }) {
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

export default function ConnectorPage() {
  const { projectId, connectorId } = useParams<{ projectId: string; connectorId: string }>();
  const label = CONNECTOR_LABELS[connectorId] ?? connectorId;

  return (
    <>
      <div className="max-w-4xl mx-auto px-4 sm:px-6 pt-6">
        <Breadcrumb
          items={[
            { label: 'Integrations', href: `/app/projects/${projectId}/integrations` },
            { label },
          ]}
        />
      </div>
      <ConnectorBody projectId={projectId} connectorId={connectorId} />
    </>
  );
}
