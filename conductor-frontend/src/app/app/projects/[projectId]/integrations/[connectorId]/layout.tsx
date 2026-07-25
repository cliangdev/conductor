'use client';

import { useEffect, useState } from 'react';
import { useParams } from 'next/navigation';
import { Breadcrumb } from '@/components/layout/PageHeader';
import { Tabs } from '@/components/ui/tabs';
import WorkflowToolsPanel from '@/components/integrations/WorkflowToolsPanel';
import ConnectorDocsPanel from '@/components/integrations/ConnectorDocsPanel';
import { useAuth } from '@/contexts/AuthContext';
import { listIntegrations } from '@/lib/api';

// Known-connector labels render instantly, before the catalog fetch below resolves — avoids a
// breadcrumb flash of the raw connector id for the common cases. Any connector not listed here
// (e.g. a newly added one) still gets its real name once the fetch completes.
const CONNECTOR_LABELS: Record<string, string> = {
  gcp: 'Google Cloud',
  posthog: 'PostHog',
  'gcp-billing': 'GCP Billing',
  github: 'GitHub',
  revenuecat: 'RevenueCat',
  gsc: 'Search Console',
  'apple-search-ads': 'Apple Search Ads',
};

type Tab = 'overview' | 'tools' | 'docs';

export default function ConnectorLayout({ children }: { children: React.ReactNode }) {
  const { projectId, connectorId } = useParams<{ projectId: string; connectorId: string }>();
  const { accessToken } = useAuth();
  const [tab, setTab] = useState<Tab>('overview');
  const [catalogLabel, setCatalogLabel] = useState<string | null>(null);

  useEffect(() => {
    // Clear first — otherwise switching connectors would briefly show the previous one's label.
    setCatalogLabel(null);
    if (!accessToken || !projectId) return;
    listIntegrations(projectId, accessToken)
      .then((all) => setCatalogLabel(all.find((i) => i.connectorId === connectorId)?.name ?? null))
      .catch(() => {});
  }, [projectId, connectorId, accessToken]);

  const label = catalogLabel ?? CONNECTOR_LABELS[connectorId] ?? connectorId;

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

      <div className="max-w-6xl mx-auto px-4 sm:px-6 lg:px-8 mt-4">
        <Tabs
          value={tab}
          onValueChange={(v) => setTab(v as Tab)}
          items={[
            { value: 'overview', label: 'Overview' },
            { value: 'tools', label: 'Tools' },
            { value: 'docs', label: 'Documentation' },
          ]}
        />
      </div>

      {tab === 'overview' && children}
      {tab === 'tools' && <WorkflowToolsPanel projectId={projectId} connectorId={connectorId} />}
      {tab === 'docs' && <ConnectorDocsPanel connectorId={connectorId} />}
    </>
  );
}
