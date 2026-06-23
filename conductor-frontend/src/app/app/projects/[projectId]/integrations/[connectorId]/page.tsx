'use client';
export const dynamic = 'force-dynamic';

import { useParams } from 'next/navigation';
import PostHogConnectorPage from '@/components/integrations/PostHogConnectorPage';
import GcpBillingConnectorPage from '@/components/integrations/GcpBillingConnectorPage';
import GitHubConnectorPage from '@/components/integrations/GitHubConnectorPage';

export default function ConnectorPage() {
  const { projectId, connectorId } = useParams<{ projectId: string; connectorId: string }>();

  switch (connectorId) {
    case 'posthog':
      return <PostHogConnectorPage projectId={projectId} />;
    case 'gcp-billing':
      return <GcpBillingConnectorPage projectId={projectId} />;
    case 'github':
      return <GitHubConnectorPage projectId={projectId} />;
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
