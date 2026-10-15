'use client';

import { useCallback, useEffect, useState } from 'react';
import { useAuth } from '@/contexts/AuthContext';
import { apiErrorMessage, listRemoteMcpTools, type RemoteMcpToolsResponse } from '@/lib/api';
import { useConnectorCatalogItem } from './ConnectorCatalogContext';
import { Button } from '@/components/ui/button';

/** One remote MCP connection's live tool list, with a Refresh that bypasses the server-side cache. */
function ConnectionTools({
  projectId,
  connectionId,
  label,
}: {
  projectId: string;
  connectionId: string;
  label: string;
}) {
  const { accessToken } = useAuth();
  const [result, setResult] = useState<RemoteMcpToolsResponse | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  const load = useCallback(
    async (refresh: boolean) => {
      if (!accessToken) return;
      setLoading(true);
      try {
        const r = await listRemoteMcpTools(projectId, connectionId, accessToken, refresh);
        setResult(r.error ? null : r);
        setError(r.error ?? null);
      } catch (e) {
        setResult(null);
        setError(apiErrorMessage(e, 'Could not load the tool list.'));
      } finally {
        setLoading(false);
      }
    },
    [projectId, connectionId, accessToken],
  );

  useEffect(() => {
    load(false);
  }, [load]);

  return (
    <div className="bg-card border border-border rounded-lg p-5">
      <div className="flex items-center justify-between gap-3 mb-3">
        <div className="min-w-0">
          <p className="text-sm font-medium text-foreground truncate">{label}</p>
          {result && (
            <p className="text-xs text-muted-foreground">
              {result.toolCount} {result.toolCount === 1 ? 'tool' : 'tools'}
            </p>
          )}
        </div>
        <Button type="button" size="sm" variant="outline" onClick={() => load(true)} disabled={loading}>
          {loading ? 'Loading…' : 'Refresh'}
        </Button>
      </div>
      {error && <p className="text-sm text-destructive">{error}</p>}
      {result && result.tools.length === 0 && (
        <p className="text-sm text-muted-foreground">This server offers no tools.</p>
      )}
      {result && result.tools.length > 0 && (
        <ul className="divide-y divide-border">
          {result.tools.map((t) => (
            <li key={t.name} className="py-2.5">
              <span className="font-mono text-xs font-semibold text-foreground">{t.name}</span>
              {t.description && <p className="text-sm text-muted-foreground mt-0.5">{t.description}</p>}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

export default function RemoteMcpToolsPanel({ projectId }: { projectId: string }) {
  const { item } = useConnectorCatalogItem();
  const connections = item?.connections ?? [];

  return (
    <div className="max-w-6xl mx-auto px-4 sm:px-6 lg:px-8 py-8 space-y-4">
      <p className="text-xs text-muted-foreground">
        The tools each connected server offers. Agents only get the ones you tick on the agent&apos;s Tools list.
      </p>
      {connections.length === 0 ? (
        <div className="bg-card border border-border rounded-lg p-8 text-center">
          <p className="text-sm text-muted-foreground">Connect a server to see its tools.</p>
        </div>
      ) : (
        connections.map((c, i) => (
          <ConnectionTools
            key={c.id}
            projectId={projectId}
            connectionId={c.id}
            label={c.label || `Server ${i + 1}`}
          />
        ))
      )}
    </div>
  );
}
