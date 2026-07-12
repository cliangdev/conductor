'use client';

import { useState } from 'react';
import { useParams } from 'next/navigation';
import { Breadcrumb } from '@/components/layout/PageHeader';
import { Tabs } from '@/components/ui/tabs';
import WorkflowToolsPanel from '@/components/integrations/WorkflowToolsPanel';
import ConnectorDocsPanel from '@/components/integrations/ConnectorDocsPanel';

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
  const [tab, setTab] = useState<Tab>('overview');
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
