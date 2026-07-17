'use client';

import { useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import { ArrowRightIcon } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { useAuth } from '@/contexts/AuthContext';
import { useCan } from '@/contexts/PermissionsContext';
import { listConnections, createConnection, deleteConnection, apiErrorMessage } from '@/lib/api';
import type { ConnectionSummary } from '@/lib/api';
import { parseServiceAccountKey } from '@/lib/serviceAccountKey';
import { PageHeader } from '@/components/layout/PageHeader';
import { ServiceAccountKeyField } from './ServiceAccountKeyField';
import RuntimeTargetsPanel from './RuntimeTargetsPanel';

const CONNECTOR_ID = 'gcp';

const STATUS_BADGE: Record<ConnectionSummary['status'], { variant: 'status-done' | 'destructive' | 'secondary'; label: string }> = {
  ACTIVE: { variant: 'status-done', label: 'Active' },
  NEEDS_SETUP: { variant: 'secondary', label: 'Needs setup' },
  ERROR: { variant: 'destructive', label: 'Error' },
  DISABLED: { variant: 'secondary', label: 'Disabled' },
};

/**
 * gcp is a multi-instance connector (`singleInstance: false`), so the hub page always routes it
 * here rather than through the generic connect modal — this page owns its own connect form (same
 * shape as the modal's JSON branch), mirroring how GitHubConnectorPage owns its own install flow.
 */
export default function GcpConnectorPage({ projectId }: { projectId: string }) {
  const { accessToken } = useAuth();
  const canMutate = useCan('integration.manage');

  const [connections, setConnections] = useState<ConnectionSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);

  const [keyText, setKeyText] = useState('');
  const [keyError, setKeyError] = useState<string | null>(null);
  const [connecting, setConnecting] = useState(false);
  const [connectError, setConnectError] = useState<string | null>(null);

  const [disconnecting, setDisconnecting] = useState<string | null>(null);

  const fetchConnections = useCallback(async () => {
    if (!accessToken) return;
    try {
      const conns = await listConnections(projectId, CONNECTOR_ID, accessToken);
      setConnections(conns);
      setLoadError(null);
    } catch (err) {
      setLoadError(apiErrorMessage(err, 'Failed to load Google Cloud connections.'));
    } finally {
      setLoading(false);
    }
  }, [accessToken, projectId]);

  useEffect(() => { fetchConnections(); }, [fetchConnections]);

  const handleKeyChange = (value: string) => {
    setKeyText(value);
    if (!value.trim()) { setKeyError(null); return; }
    const parsed = parseServiceAccountKey(value);
    setKeyError(parsed.valid ? null : (parsed.error ?? 'Invalid key'));
  };

  async function handleConnect(e: React.FormEvent) {
    e.preventDefault();
    if (!accessToken) return;
    const parsed = parseServiceAccountKey(keyText);
    if (!parsed.valid) {
      setKeyError(parsed.error ?? 'Invalid key');
      return;
    }
    setConnecting(true);
    setConnectError(null);
    try {
      await createConnection(projectId, CONNECTOR_ID, { serviceAccountKey: keyText }, accessToken);
      setKeyText('');
      await fetchConnections();
    } catch (err) {
      setConnectError(apiErrorMessage(err, 'Connection failed'));
    } finally {
      setConnecting(false);
    }
  }

  async function handleDisconnect(connectionId: string) {
    if (!accessToken) return;
    setDisconnecting(connectionId);
    try {
      await deleteConnection(projectId, CONNECTOR_ID, connectionId, accessToken);
      setConnections((prev) => prev.filter((c) => c.id !== connectionId));
    } catch (err) {
      setConnectError(apiErrorMessage(err, 'Failed to remove connection. Please try again.'));
    } finally {
      setDisconnecting(null);
    }
  }

  const connectForm = (
    <form onSubmit={handleConnect} className="space-y-4">
      <ServiceAccountKeyField
        label="Service Account Key"
        hint="GCP Console → IAM & Admin → Service Accounts → your SA → Keys → Add Key → JSON"
        required
        value={keyText}
        error={keyError}
        onChange={handleKeyChange}
      />
      {connectError && <p className="text-sm text-destructive">{connectError}</p>}
      <Button type="submit" disabled={connecting || !!keyError || !keyText.trim()}>
        {connecting ? 'Connecting…' : 'Connect'}
      </Button>
    </form>
  );

  return (
    <div className="max-w-6xl mx-auto px-4 sm:px-6 lg:px-8 py-8">
      <PageHeader
        title="Google Cloud"
        description="Infrastructure · Service account — run claude-code workflow steps as Cloud Run jobs in your own GCP project."
      />

      {loading ? (
        <div className="animate-pulse h-32 bg-muted rounded-lg" />
      ) : loadError ? (
        <p className="text-sm text-destructive" role="alert">{loadError}</p>
      ) : (
        <div className="space-y-6">
          {connections.length === 0 ? (
            canMutate ? (
              <div className="bg-card rounded-lg border border-border p-8 max-w-md">
                <h2 className="text-lg font-semibold text-foreground mb-1">Connect Google Cloud</h2>
                <p className="text-sm text-muted-foreground mb-6">
                  Paste a service account JSON key to let Conductor provision Cloud Run jobs in your project.
                </p>
                {connectForm}
              </div>
            ) : (
              <div className="bg-card rounded-lg border border-border p-8 text-center max-w-md">
                <p className="text-sm text-muted-foreground">Ask a project admin to connect Google Cloud.</p>
              </div>
            )
          ) : (
            <>
              <div className="space-y-3">
                {connections.map((conn) => {
                  const badge = STATUS_BADGE[conn.status];
                  return (
                    <div key={conn.id} className="bg-card rounded-lg border border-border p-4 flex items-center gap-4">
                      <div className="flex-1 min-w-0">
                        <div className="font-medium text-sm text-foreground truncate">
                          {conn.label || 'Service account'}
                        </div>
                        {conn.fetchedAt && (
                          <div className="text-xs text-muted-foreground">
                            Last checked {new Date(conn.fetchedAt).toLocaleString()}
                          </div>
                        )}
                      </div>
                      <Badge variant={badge.variant}>{badge.label}</Badge>
                      {canMutate && (
                        <button
                          onClick={() => handleDisconnect(conn.id)}
                          disabled={disconnecting === conn.id}
                          className="text-xs font-medium text-destructive hover:underline disabled:opacity-50 flex-shrink-0"
                        >
                          {disconnecting === conn.id ? 'Removing…' : 'Remove'}
                        </button>
                      )}
                    </div>
                  );
                })}
              </div>

              {canMutate && (
                <details className="bg-card rounded-lg border border-border p-4">
                  <summary className="cursor-pointer text-sm font-medium text-foreground">
                    Add another service account
                  </summary>
                  <div className="mt-4 max-w-md">{connectForm}</div>
                </details>
              )}
            </>
          )}

          <Link
            href={`/app/projects/${projectId}/settings/providers`}
            className="flex items-center gap-1 text-sm text-primary hover:underline"
          >
            Claude Code credential moved to Settings → AI Providers
            <ArrowRightIcon className="h-3.5 w-3.5" />
          </Link>

          <RuntimeTargetsPanel projectId={projectId} connections={connections} />
        </div>
      )}
    </div>
  );
}
