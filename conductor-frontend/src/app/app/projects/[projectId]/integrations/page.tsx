'use client';

export const dynamic = 'force-dynamic';

import { useEffect, useState } from 'react';
import { useParams } from 'next/navigation';
import { useAuth } from '@/contexts/AuthContext';
import { apiGet } from '@/lib/api';
import Link from 'next/link';
import { PuzzleIcon } from 'lucide-react';

interface IntegrationListItem {
  connectorId: string;
  name: string;
  category: string;
  authType: string;
  description: string;
  iconLabel: string;
  connected: boolean;
  healthStatus: string | null;
  fetchedAt: string | null;
  configFields: Array<{ fieldKey: string; label: string; hint: string | null; secret: boolean }>;
}

export default function IntegrationsPage() {
  const { projectId } = useParams<{ projectId: string }>();
  const { accessToken } = useAuth();
  const [integrations, setIntegrations] = useState<IntegrationListItem[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (!accessToken || !projectId) return;
    apiGet<IntegrationListItem[]>(`/api/v1/projects/${projectId}/integrations`, accessToken)
      .then((data) => setIntegrations(data))
      .catch(console.error)
      .finally(() => setLoading(false));
  }, [projectId, accessToken]);

  const connected = integrations.filter((i) => i.connected);

  if (loading) {
    return (
      <div className="max-w-4xl mx-auto px-4 py-8">
        <div className="animate-pulse space-y-4">
          <div className="h-8 bg-muted rounded w-48" />
          <div className="grid grid-cols-2 gap-4">
            {[1, 2].map((i) => (
              <div key={i} className="h-32 bg-muted rounded-lg" />
            ))}
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="max-w-4xl mx-auto px-4 py-8">
      <div className="mb-6">
        <h1 className="text-2xl font-bold text-foreground">Integrations</h1>
        <p className="text-sm text-muted-foreground mt-1">Connected apps</p>
      </div>

      {connected.length === 0 ? (
        <div className="bg-card rounded-lg border border-border p-12 text-center">
          <PuzzleIcon className="h-10 w-10 text-muted-foreground mx-auto mb-4" />
          <h2 className="text-lg font-medium text-foreground mb-2">No integrations connected yet</h2>
          <p className="text-sm text-muted-foreground mb-6">
            Connect your tools — billing, analytics, marketing — to see live metrics alongside your PRDs.
          </p>
          <Link
            href={`/app/projects/${projectId}/settings/integrations`}
            className="inline-flex items-center gap-1.5 text-sm font-medium text-primary hover:underline"
          >
            Browse available integrations →
          </Link>
        </div>
      ) : (
        <>
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
            {connected.map((integration) => (
              <Link
                key={integration.connectorId}
                href={`/app/projects/${projectId}/integrations/${integration.connectorId}`}
                className="bg-card rounded-lg border border-border p-5 flex items-center gap-3 hover:border-primary/50 transition-colors"
              >
                <div className="h-9 w-9 rounded-md bg-muted flex items-center justify-center text-sm font-bold text-foreground flex-shrink-0">
                  {integration.iconLabel}
                </div>
                <div className="flex-1 min-w-0">
                  <div className="font-medium text-foreground text-sm">{integration.name}</div>
                  <div className="text-xs text-muted-foreground truncate">{integration.description}</div>
                </div>
                <span className="inline-flex items-center gap-1 text-xs text-green-600 dark:text-green-400 font-medium flex-shrink-0">
                  <span className="h-1.5 w-1.5 rounded-full bg-green-500 inline-block" />
                  Connected
                </span>
              </Link>
            ))}
          </div>

          <div className="mt-6">
            <Link
              href={`/app/projects/${projectId}/settings/integrations`}
              className="text-sm text-muted-foreground hover:text-foreground"
            >
              + Add integration
            </Link>
          </div>
        </>
      )}
    </div>
  );
}
