'use client';

import { useParams } from 'next/navigation';
import { Breadcrumb } from '@/components/layout/PageHeader';

// Display names for the breadcrumb. Mirrors the connectors handled by the page.
const CONNECTOR_LABELS: Record<string, string> = {
  posthog: 'PostHog',
  'gcp-billing': 'GCP Billing',
  github: 'GitHub',
  revenuecat: 'RevenueCat',
  gsc: 'Search Console',
  'apple-search-ads': 'Apple Search Ads',
};

/**
 * Persistent breadcrumb for the connector detail route. Stays mounted while
 * switching between connectors — only the connector body below re-renders.
 */
export default function ConnectorLayout({ children }: { children: React.ReactNode }) {
  const { projectId, connectorId } = useParams<{ projectId: string; connectorId: string }>();
  const label = CONNECTOR_LABELS[connectorId] ?? connectorId;

  return (
    <>
      <div className="max-w-6xl mx-auto px-4 sm:px-6 lg:px-8 pt-6">
        <Breadcrumb
          items={[
            { label: 'Integrations', href: `/app/projects/${projectId}/integrations` },
            { label },
          ]}
        />
      </div>
      {children}
    </>
  );
}
