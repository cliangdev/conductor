'use client';

import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { useToast } from '@/components/ui/toast';
import { useAuth } from '@/contexts/AuthContext';
import {
  listConnections,
  deleteConnection,
  listConnectionWebhookEvents,
  installGitHubApp,
  listGitHubRepositories,
  bindGitHubPat,
  apiErrorMessage,
} from '@/lib/api';
import type { ConnectionSummary, WebhookEventSummary, GitHubRepositoriesResponse, ApiError } from '@/lib/api';
import { useCan } from '@/contexts/PermissionsContext';
import { Skeleton } from '@/components/ui/skeleton';
import { PageHeader } from '@/components/layout/PageHeader';
import { ArrowUpRight } from 'lucide-react';

const CONNECTOR_ID = 'github';

/** Days-until-expiry → the file's existing inline-color-by-state convention (see webhook events table). */
export function getPatExpiryStatus(tokenExpiresAt?: string | null): { label: string; className: string } {
  if (!tokenExpiresAt) return { label: 'No expiration', className: 'text-status-done' };
  const msPerDay = 24 * 60 * 60 * 1000;
  const daysUntil = Math.ceil((new Date(tokenExpiresAt).getTime() - Date.now()) / msPerDay);
  if (daysUntil < 0) return { label: 'Expired', className: 'text-destructive' };
  if (daysUntil === 0) return { label: 'Expires today', className: 'text-destructive' };
  const label = `Expires in ${daysUntil} ${daysUntil === 1 ? 'day' : 'days'}`;
  if (daysUntil <= 7) return { label, className: 'text-destructive' };
  if (daysUntil <= 30) return { label, className: 'text-status-progress' };
  return { label, className: 'text-status-done' };
}

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

  const [patFormOpen, setPatFormOpen] = useState(false);
  const [patToken, setPatToken] = useState('');
  const [patLabel, setPatLabel] = useState('');
  const [patExpiresAt, setPatExpiresAt] = useState('');
  const [patTokenError, setPatTokenError] = useState<string | null>(null);
  const [patSubmitting, setPatSubmitting] = useState(false);

  const fetchConnections = useCallback(async () => {
    if (!accessToken) return;
    try {
      const conns = await listConnections(projectId, CONNECTOR_ID, accessToken);
      setConnections(conns);
      setConnectionsError(null);
      const appConns = conns.filter((c) => c.authType !== 'PAT');
      const entries = await Promise.all(
        appConns.map(async (c) => {
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

  function openPatForm(existingLabel?: string | null) {
    setPatToken('');
    setPatLabel(existingLabel ?? '');
    setPatExpiresAt('');
    setPatTokenError(null);
    setPatFormOpen(true);
  }

  function closePatForm() {
    setPatFormOpen(false);
    setPatToken('');
    setPatLabel('');
    setPatExpiresAt('');
    setPatTokenError(null);
  }

  async function handleBindPat(e: FormEvent) {
    e.preventDefault();
    if (!accessToken) return;
    if (!patToken.trim()) {
      setPatTokenError('Token is required.');
      return;
    }
    setPatSubmitting(true);
    try {
      await bindGitHubPat(
        projectId,
        {
          token: patToken,
          ...(patLabel.trim() ? { label: patLabel.trim() } : {}),
          ...(patExpiresAt ? { expiresAt: `${patExpiresAt}T00:00:00Z` } : {}),
        },
        accessToken,
      );
      closePatForm();
      await fetchConnections();
    } catch (err) {
      showToast(apiErrorMessage(err, 'Failed to save personal access token. Please try again.'), 'error');
    } finally {
      setPatSubmitting(false);
    }
  }

  if (connectionsLoading) {
    return (
      <div className="max-w-6xl mx-auto px-4 sm:px-6 lg:px-8 py-8">
        <div className="space-y-4">
          <Skeleton className="h-8 w-32" />
          <Skeleton className="h-32 w-full" />
        </div>
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

  const appConnections = connections.filter((c) => c.authType !== 'PAT');
  const patConnection = connections.find((c) => c.authType === 'PAT') ?? null;
  const patExpiry = getPatExpiryStatus(patConnection?.tokenExpiresAt);

  const patForm = (
    <form onSubmit={handleBindPat} className="mt-4 space-y-3 border-t border-border pt-4">
      <div>
        <Label htmlFor="pat-token">Personal access token</Label>
        <Input
          id="pat-token"
          type="password"
          autoComplete="off"
          value={patToken}
          onChange={(e) => { setPatToken(e.target.value); setPatTokenError(null); }}
          placeholder="ghp_…"
        />
        {patTokenError && (
          <p className="mt-1 text-xs text-destructive" role="alert">{patTokenError}</p>
        )}
      </div>
      <div>
        <Label htmlFor="pat-label">Label (optional)</Label>
        <Input
          id="pat-label"
          value={patLabel}
          onChange={(e) => setPatLabel(e.target.value)}
          placeholder="e.g. Deploy token"
        />
      </div>
      <div>
        <Label htmlFor="pat-expires-at">Expiration (optional)</Label>
        <Input
          id="pat-expires-at"
          type="date"
          value={patExpiresAt}
          onChange={(e) => setPatExpiresAt(e.target.value)}
        />
        <p className="mt-1 text-xs text-muted-foreground">
          Only used if GitHub doesn&apos;t report an expiration for this token.
        </p>
      </div>
      <div className="flex items-center gap-2 pt-1">
        <Button type="submit" size="sm" disabled={patSubmitting}>
          {patSubmitting ? 'Saving…' : 'Save token'}
        </Button>
        <Button type="button" size="sm" variant="ghost" onClick={closePatForm} disabled={patSubmitting}>
          Cancel
        </Button>
      </div>
    </form>
  );

  return (
    <div className="max-w-6xl mx-auto px-4 sm:px-6 lg:px-8 py-8">
      <PageHeader
        title="GitHub"
        description={
          <>
            Install the Conductor GitHub App and choose which repositories it can access, or bind a
            project-level Personal Access Token. When a pull request whose body contains{' '}
            <code className="font-mono text-xs">closes conductor/KEY-123</code> is merged, the matching
            issue moves to Done.
          </>
        }
      />

      {appConnections.length === 0 ? (
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
              {!patConnection && (
                <div className="mt-3">
                  {!patFormOpen ? (
                    <Button type="button" size="sm" variant="ghost" onClick={() => openPatForm()}>
                      Use a Personal Access Token instead
                    </Button>
                  ) : (
                    <div className="max-w-sm mx-auto text-left">{patForm}</div>
                  )}
                </div>
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
          {appConnections.map((conn) => {
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
                        className="inline-flex items-center gap-0.5 text-primary hover:underline"
                      >
                        Add some on GitHub
                        <ArrowUpRight className="h-3 w-3" />
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

          {canMutate && !patConnection && (
            <div className="bg-card rounded-lg border border-border p-6">
              {!patFormOpen ? (
                <>
                  <h2 className="text-base font-semibold text-foreground mb-1">Personal Access Token</h2>
                  <p className="text-sm text-muted-foreground mb-3">
                    Bind a project-level GitHub token for permissions the app install doesn&apos;t grant. It
                    takes precedence over the app connection when both are present.
                  </p>
                  <Button type="button" size="sm" variant="outline" onClick={() => openPatForm()}>
                    Use a Personal Access Token instead
                  </Button>
                </>
              ) : (
                <>
                  <h2 className="text-base font-semibold text-foreground mb-1">Personal Access Token</h2>
                  {patForm}
                </>
              )}
            </div>
          )}
        </div>
      )}

      {patConnection && (
        <div className="bg-card rounded-lg border border-border p-6 mt-6">
          {!patFormOpen ? (
            <>
              <div className="flex flex-wrap items-center justify-between gap-3">
                <div className="min-w-0">
                  <h2 className="text-base font-semibold text-foreground truncate">
                    {patConnection.label || 'Personal Access Token'}
                  </h2>
                  <p className={`text-xs mt-1 ${patExpiry.className}`}>{patExpiry.label}</p>
                </div>
                {canMutate && (
                  <div className="flex items-center gap-2 shrink-0">
                    <Button
                      type="button"
                      size="sm"
                      variant="outline"
                      onClick={() => openPatForm(patConnection.label)}
                    >
                      Replace token
                    </Button>
                    <Button
                      type="button"
                      size="sm"
                      variant="ghost"
                      onClick={() => handleDisconnect(patConnection.id)}
                      disabled={disconnecting === patConnection.id}
                      className="text-destructive hover:text-destructive hover:bg-destructive/10"
                    >
                      {disconnecting === patConnection.id ? 'Disconnecting…' : 'Disconnect'}
                    </Button>
                  </div>
                )}
              </div>
            </>
          ) : (
            <>
              <h2 className="text-base font-semibold text-foreground mb-1">Replace Personal Access Token</h2>
              {patForm}
            </>
          )}
        </div>
      )}

      {/* Recent Webhook Events */}
      <div className="bg-card rounded-lg border border-border p-6 mt-6">
        <h2 className="text-base font-semibold text-foreground mb-4">Recent Webhook Events</h2>
        {eventsLoading ? (
          <Skeleton className="h-24 w-full" />
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
                        event.status === 'PROCESSED' ? 'text-status-done' :
                        event.status === 'PENDING' ? 'text-status-progress' :
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
