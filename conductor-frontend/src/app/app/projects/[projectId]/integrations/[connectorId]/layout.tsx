'use client';

import { useState } from 'react';
import { useParams } from 'next/navigation';
import { Breadcrumb } from '@/components/layout/PageHeader';
import WorkflowToolsPanel from '@/components/integrations/WorkflowToolsPanel';

const CONNECTOR_LABELS: Record<string, string> = {
  posthog: 'PostHog',
  'gcp-billing': 'GCP Billing',
  github: 'GitHub',
  revenuecat: 'RevenueCat',
  gsc: 'Search Console',
  'apple-search-ads': 'Apple Search Ads',
};

type Tab = 'overview' | 'tool-metadata';

const TABS: { id: Tab; label: string }[] = [
  { id: 'overview', label: 'Overview' },
  { id: 'tool-metadata', label: 'Tool Metadata' },
];

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
        <div className="flex gap-1 border-b border-border">
          {TABS.map((t) => (
            <button
              key={t.id}
              onClick={() => setTab(t.id)}
              className={`px-4 py-2 text-sm font-medium border-b-2 -mb-px transition-colors ${
                tab === t.id
                  ? 'border-primary text-foreground'
                  : 'border-transparent text-muted-foreground hover:text-foreground'
              }`}
            >
              {t.label}
            </button>
          ))}
        </div>
      </div>

      {tab === 'overview' ? children : (
        <WorkflowToolsPanel projectId={projectId} connectorId={connectorId} />
      )}
    </>
  );
}
