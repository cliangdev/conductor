'use client';

import { useCallback, useEffect, useState } from 'react';
import { Button } from '@/components/ui/button';
import { useToast } from '@/components/ui/toast';
import { useAuth } from '@/contexts/AuthContext';
import {
  listConnections,
  deleteConnection,
  listConnectionWebhookEvents,
  installGitHubApp,
  listGitHubRepositories,
  apiErrorMessage,
} from '@/lib/api';
import type { ConnectionSummary, WebhookEventSummary, GitHubRepositoriesResponse, ApiError } from '@/lib/api';
import { useCan } from '@/contexts/PermissionsContext';

const CONNECTOR_ID = 'github';

export default function GitHubConnectorPage({ projectId }: { projectId: string }) {
  const { accessToken } = useAuth();
  const { showToast } = useToast();
  const canMutate = useCan('integration.manage');

  const [connections, setConnections] = useState<ConnectionSummary[]>([]);
  const [connectionsLoading, setConnectionsLoading] = useState(true);
  const [connectionsError, setConnectionsError] = useState<string | null>(null);
  const [repos, setRepos] = useState<Record<string, GitHubRepositoriesResponse | null>>({});

  const [events, setEvents] = useState<WebhookEventSummary[]>([]);
  const [eventsLoading, setEventsLoading] = useState(true);

  const [installing, setInstalling] = useState(false);
  const [installError, setInstallError] = useState<string | null>(null);
  const [disconnecting, setDisconnecting] = useState<string | null>(null);

  const fetchConnections = useCallback(async () => {
    if (!accessToken) return;
    try {
      const conns = await listConnections(projectId, CONNECTOR_ID, accessToken);
      setConnections(conns);
      setConnectionsError(null);
      const entries = await Promise.all(
        conns.map(async (c) => {
          try {
            return [c.id, await listGitHubRepositories(projectId, c.id, accessToken)] as const;
          } catch {
            return [c.id, null] as const;
          }
        })
      );
      setRepos(Object.fromEntries(entries));
    } catch (err) {
      setConnectionsError(
        (err as ApiError).status === 403
          ? 'access_denied'
          : apiErrorMessage(err, 'Failed to load GitHub connection.')
      );
    } finally {
      setConnectionsLoading(false);
    }
  }, [accessToken, projectId]);

  const fetchEvents = useCallback(async (conns: ConnectionSummary[]) => {
    if (!accessToken || conns.length === 0) {
      setEvents([]);
      setEventsLoading(false);
      return;
    }
    try {
      const perConnection = await Promise.all(
        conns.map((c) =>
          listConnectionWebhookEvents(projectId, CONNECTOR_ID, c.id, accessToken).catch(() => [])
        )
      );
      const merged = perConnection
        .flat()
        .sort((a, b) => new Date(b.receivedAt).getTime() - new Date(a.receivedAt).getTime())
        .slice(0, 20);
      setEvents(merged);
    } catch {
      // non-fatal
    } finally {
      setEventsLoading(false);
    }
  }, [accessToken, projectId]);

  useEffect(() => { fetchConnections(); }, [fetchConnections]);
  useEffect(() => { if (!connectionsLoading) fetchEvents(connections); }, [connectionsLoading, connections, fetchEvents]);

  async function handleInstall() {
    if (!accessToken) return;
    setInstalling(true);
    setInstallError(null);
    try {
      const { installUrl } = await installGitHubApp(projectId, accessToken);
      window.location.href = installUrl;
    } catch (err) {
      setInstallError(apiErrorMessage(err, 'Could not start the GitHub installation. Please try again.'));
      setInstalling(false);
    }
  }

  async function handleDisconnect(connectionId: string) {
    if (!accessToken) return;
    setDisconnecting(connectionId);
    try {
      await deleteConnection(projectId, CONNECTOR_ID, connectionId, accessToken);
      setConnections((prev) => prev.filter((c) => c.id !== connectionId));
      setRepos((prev) => {
        const next = { ...prev };
        delete next[connectionId];
        return next;
      });
    } catch (err) {
      showToast(apiErrorMessage(err, 'Failed to disconnect. Please try again.'), 'error');
    } finally {
      setDisconnecting(null);
    }
  }

  if (connectionsLoading) {
    return (
      <div className="max-w-6xl mx-auto px-4 sm:px-6 lg:px-8 py-8">
        <p className="text-sm text-muted-foreground">Loading…</p>
      </div>
    );
  }

  if (connectionsError === 'access_denied') {
    return (
      <div className="max-w-6xl mx-auto px-4 sm:px-6 lg:px-8 py-8">
        <p className="text-sm text-destructive" role="alert">
          Access denied. You do not have permission to view this integration.
        </p>
      </div>
    );
  }

  if (connectionsError) {
    return (
      <div className="max-w-6xl mx-auto px-4 sm:px-6 lg:px-8 py-8">
        <p className="text-sm text-destructive" role="alert">{connectionsError}</p>
      </div>
    );
  }

  return (
    <div className="max-w-6xl mx-auto px-4 sm:px-6 lg:px-8 py-8">
      <h1 className="text-2xl font-bold text-foreground mb-1">GitHub</h1>
      <p className="text-sm text-muted-foreground mb-6">
        Install the Conductor GitHub App and choose which repositories it can access. When a pull request
        whose body contains <code className="font-mono text-xs">closes conductor/KEY-123</code> is merged, the
        matching issue moves to Done.
      </p>

      {connections.length === 0 ? (
        /* Not connected — install CTA */
        <div className="bg-card rounded-lg border border-border p-8 text-center">
          <h2 className="text-base font-semibold text-foreground mb-1">Connect GitHub</h2>
          <p className="text-sm text-muted-foreground mb-5 max-w-md mx-auto">
            Install the Conductor app on your GitHub account or organization and pick the repositories to
            connect — no webhook URLs or secrets to copy.
          </p>
          {canMutate ? (
            <>
              <Button type="button" onClick={handleInstall} disabled={installing}>
                {installing ? 'Redirecting…' : 'Install on GitHub'}
              </Button>
              {installError && (
                <p className="mt-3 text-sm text-destructive" role="alert">{installError}</p>
              )}
            </>
          ) : (
            <p className="text-sm text-muted-foreground">
              Ask a project admin to connect GitHub.
            </p>
          )}
        </div>
      ) : (
        /* Connected — one card per installation */
        <div className="space-y-6">
          {connections.map((conn) => {
            const data = repos[conn.id];
            return (
              <div key={conn.id} className="bg-card rounded-lg border border-border p-6">
                <div className="flex flex-wrap items-center justify-between gap-3 mb-4">
                  <div className="min-w-0">
                    <h2 className="text-base font-semibold text-foreground truncate">
                      {conn.label || data?.accountLogin || 'GitHub installation'}
                    </h2>
                    <p className="text-xs text-muted-foreground">
                      {data?.repositorySelection === 'all'
                        ? 'All repositories'
                        : `${data?.repositories.length ?? 0} ${(data?.repositories.length ?? 0) === 1 ? 'repository' : 'repositories'}`}
                    </p>
                  </div>
                  {canMutate && (
                    <div className="flex items-center gap-2 shrink-0">
                      {data?.installationHtmlUrl && (
                        <a href={data.installationHtmlUrl} target="_blank" rel="noopener noreferrer">
                          <Button type="button" size="sm" variant="outline">
                            Add or remove repositories
                          </Button>
                        </a>
                      )}
                      <Button
                        type="button"
                        size="sm"
                        variant="ghost"
                        onClick={() => handleDisconnect(conn.id)}
                        disabled={disconnecting === conn.id}
                        className="text-destructive hover:text-destructive hover:bg-destructive/10"
                      >
                        {disconnecting === conn.id ? 'Disconnecting…' : 'Disconnect'}
                      </Button>
                    </div>
                  )}
                </div>

                {data === undefined ? (
                  <p className="text-sm text-muted-foreground">Loading repositories…</p>
                ) : data === null ? (
                  <p className="text-sm text-muted-foreground">Couldn’t load repositories from GitHub.</p>
                ) : data.repositories.length === 0 ? (
                  <p className="text-sm text-muted-foreground">
                    No repositories selected yet.{' '}
                    {canMutate && data.installationHtmlUrl && (
                      <a
                        href={data.installationHtmlUrl}
                        target="_blank"
                        rel="noopener noreferrer"
                        className="text-primary hover:underline"
                      >
                        Add some on GitHub →
                      </a>
                    )}
                  </p>
                ) : (
                  <ul className="divide-y divide-border">
                    {data.repositories.map((repo) => (
                      <li key={repo.fullName} className="flex items-center justify-between gap-3 py-2 first:pt-0 last:pb-0">
                        <span className="text-sm font-mono text-foreground truncate">{repo.fullName}</span>
                        <span className="text-xs text-muted-foreground shrink-0">
                          {repo.private ? 'Private' : 'Public'}
                        </span>
                      </li>
                    ))}
                  </ul>
                )}
              </div>
            );
          })}
        </div>
      )}

      {/* Recent Webhook Events */}
      <div className="bg-card rounded-lg border border-border p-6 mt-6">
        <h2 className="text-base font-semibold text-foreground mb-4">Recent Webhook Events</h2>
        {eventsLoading ? (
          <p className="text-sm text-muted-foreground">Loading…</p>
        ) : events.length === 0 ? (
          <p className="text-sm text-muted-foreground">No webhook events received yet.</p>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="text-left text-xs text-muted-foreground border-b border-border">
                  <th className="pb-2 pr-4 font-medium">Time</th>
                  <th className="pb-2 pr-4 font-medium">Event type</th>
                  <th className="pb-2 pr-4 font-medium">Status</th>
                  <th className="pb-2 font-medium">Details</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border">
                {events.map((event) => (
                  <tr key={event.id}>
                    <td className="py-2 pr-4 text-muted-foreground whitespace-nowrap">
                      {new Date(event.receivedAt).toLocaleString()}
                    </td>
                    <td className="py-2 pr-4 font-mono">{event.eventType}</td>
                    <td className="py-2 pr-4">
                      <span className={
                        event.status === 'PROCESSED' ? 'text-green-600 dark:text-green-400' :
                        event.status === 'PENDING' ? 'text-yellow-600 dark:text-yellow-400' :
                        'text-destructive'
                      }>
                        {event.status}
                      </span>
                    </td>
                    <td className="py-2 text-muted-foreground text-xs">
                      {event.errorMessage ?? (event.status === 'PROCESSED' ? '—' : '')}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  );
}
