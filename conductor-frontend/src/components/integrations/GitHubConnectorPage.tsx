'use client';

import { useCallback, useEffect, useState } from 'react';
import { Button } from '@/components/ui/button';
import { Modal } from '@/components/ui/modal';
import { useToast } from '@/components/ui/toast';
import { useAuth } from '@/contexts/AuthContext';
import {
  apiGet,
  listConnections,
  createConnection,
  deleteConnection,
  listConnectionWebhookEvents,
} from '@/lib/api';
import type { ConnectionSummary, ConnectionResponse, WebhookEventSummary } from '@/lib/api';
import type { Member } from '@/types';

interface ApiError extends Error {
  status?: number;
}

const CONNECTOR_ID = 'github';

function webhookUrlFor(connectionId: string): string {
  return `${process.env.NEXT_PUBLIC_API_URL}/api/v1/webhooks/${CONNECTOR_ID}/${connectionId}`;
}

export default function GitHubConnectorPage({ projectId }: { projectId: string }) {
  const { accessToken, user } = useAuth();
  const { showToast } = useToast();

  const [members, setMembers] = useState<Member[]>([]);
  const [membersLoading, setMembersLoading] = useState(true);

  const [connections, setConnections] = useState<ConnectionSummary[]>([]);
  const [connectionsLoading, setConnectionsLoading] = useState(true);
  const [connectionsError, setConnectionsError] = useState<string | null>(null);

  const [events, setEvents] = useState<WebhookEventSummary[]>([]);
  const [eventsLoading, setEventsLoading] = useState(true);

  const [copiedKey, setCopiedKey] = useState<string | null>(null);

  // Add-repository modal
  const [addOpen, setAddOpen] = useState(false);
  const [addRepoFullName, setAddRepoFullName] = useState('');
  const [addLabel, setAddLabel] = useState('');
  const [addError, setAddError] = useState<string | null>(null);
  const [addSubmitting, setAddSubmitting] = useState(false);
  const [created, setCreated] = useState<ConnectionResponse | null>(null);

  const fetchMembers = useCallback(async () => {
    if (!accessToken) return;
    try {
      const data = await apiGet<Member[]>(`/api/v1/projects/${projectId}/members`, accessToken);
      setMembers(data);
    } catch {
      // non-fatal
    } finally {
      setMembersLoading(false);
    }
  }, [accessToken, projectId]);

  const fetchConnections = useCallback(async () => {
    if (!accessToken) return;
    try {
      const data = await listConnections(projectId, CONNECTOR_ID, accessToken);
      setConnections(data);
      setConnectionsError(null);
    } catch (err) {
      const apiErr = err as ApiError;
      setConnectionsError(apiErr.status === 403 ? 'access_denied' : 'Failed to load repositories.');
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

  useEffect(() => { fetchMembers(); }, [fetchMembers]);
  useEffect(() => { fetchConnections(); }, [fetchConnections]);
  useEffect(() => { if (!connectionsLoading) fetchEvents(connections); }, [connectionsLoading, connections, fetchEvents]);

  const currentUserRole = members.find((m) => m.userId === user?.id)?.role;
  const isAdmin = currentUserRole === 'ADMIN';

  async function copy(text: string, key: string) {
    try {
      await navigator.clipboard.writeText(text);
      setCopiedKey(key);
      setTimeout(() => setCopiedKey((k) => (k === key ? null : k)), 2000);
    } catch {
      showToast('Failed to copy to clipboard', 'error');
    }
  }

  function openAddModal() {
    setAddRepoFullName('');
    setAddLabel('');
    setAddError(null);
    setCreated(null);
    setAddOpen(true);
  }

  async function handleAddSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (!accessToken) return;

    const repoFullName = addRepoFullName.trim();
    if (!repoFullName) {
      setAddError('Repository (owner/name) is required.');
      return;
    }

    setAddSubmitting(true);
    setAddError(null);
    try {
      const result = await createConnection(
        projectId,
        CONNECTOR_ID,
        { label: addLabel.trim() || repoFullName, configJson: { repoFullName } },
        accessToken
      );
      setCreated(result);
      await fetchConnections();
      showToast('Repository added.');
    } catch (err) {
      const apiErr = err as ApiError;
      if (apiErr.status === 403) {
        setAddError('You do not have permission to add repositories.');
      } else if (apiErr.status === 409) {
        setAddError('This repository is already registered.');
      } else {
        setAddError('Failed to add repository. Please try again.');
      }
    } finally {
      setAddSubmitting(false);
    }
  }

  async function handleDelete(connectionId: string) {
    if (!accessToken) return;
    try {
      await deleteConnection(projectId, CONNECTOR_ID, connectionId, accessToken);
      setConnections((prev) => prev.filter((c) => c.id !== connectionId));
    } catch (err) {
      const apiErr = err as ApiError;
      showToast(
        apiErr.status === 403
          ? 'You do not have permission to remove repositories.'
          : 'Failed to remove repository. Please try again.',
        'error'
      );
    }
  }

  if (membersLoading || connectionsLoading) {
    return (
      <div className="max-w-2xl mx-auto px-4 py-8">
        <p className="text-sm text-muted-foreground">Loading…</p>
      </div>
    );
  }

  if (!isAdmin) {
    return (
      <div className="max-w-2xl mx-auto px-4 py-8">
        <p className="text-sm text-muted-foreground">
          You don&apos;t have permission to manage GitHub settings.
        </p>
      </div>
    );
  }

  if (connectionsError === 'access_denied') {
    return (
      <div className="max-w-2xl mx-auto px-4 py-8">
        <p className="text-sm text-destructive" role="alert">
          Access denied. You do not have permission to view this integration.
        </p>
      </div>
    );
  }

  if (connectionsError) {
    return (
      <div className="max-w-2xl mx-auto px-4 py-8">
        <p className="text-sm text-destructive" role="alert">{connectionsError}</p>
      </div>
    );
  }

  return (
    <div className="max-w-2xl mx-auto px-4 py-8">
      <h1 className="text-2xl font-bold text-foreground mb-1">GitHub</h1>
      <p className="text-sm text-muted-foreground mb-6">
        Register repositories to receive pull-request webhooks. When a PR whose body contains{' '}
        <code className="font-mono text-xs">closes conductor/KEY-123</code> is merged, the matching issue
        moves to Done.
      </p>

      {/* Repositories (connections) */}
      <div className="bg-card rounded-lg border border-border p-6">
        <div className="flex items-center justify-between mb-4">
          <h2 className="text-base font-semibold text-foreground">Repositories</h2>
          <Button type="button" size="sm" onClick={openAddModal}>
            Add Repository
          </Button>
        </div>

        {connections.length === 0 ? (
          <p className="text-sm text-muted-foreground">No repositories registered yet.</p>
        ) : (
          <div className="divide-y divide-border">
            {connections.map((conn) => (
              <div key={conn.id} className="flex items-center justify-between py-3 first:pt-0 last:pb-0">
                <div className="min-w-0 flex-1">
                  <p className="text-sm font-medium text-foreground truncate">{conn.label || conn.id}</p>
                  <button
                    type="button"
                    onClick={() => copy(webhookUrlFor(conn.id), `url-${conn.id}`)}
                    className="text-xs text-muted-foreground hover:text-foreground truncate block font-mono text-left"
                    title="Copy webhook URL"
                  >
                    {copiedKey === `url-${conn.id}` ? 'Copied webhook URL!' : webhookUrlFor(conn.id)}
                  </button>
                </div>
                <div className="flex items-center gap-3 ml-4 shrink-0">
                  {conn.status === 'ACTIVE' ? (
                    <span className="flex items-center gap-1 text-xs text-green-600 dark:text-green-400">
                      <span aria-hidden="true">✓</span> Configured
                    </span>
                  ) : conn.status === 'NEEDS_SETUP' ? (
                    <span className="text-xs text-yellow-600 dark:text-yellow-400">Needs setup</span>
                  ) : (
                    <span className="text-xs text-muted-foreground">{conn.status}</span>
                  )}
                  <Button
                    type="button"
                    variant="ghost"
                    size="sm"
                    aria-label={`Delete ${conn.label || conn.id}`}
                    onClick={() => handleDelete(conn.id)}
                    className="text-destructive hover:text-destructive hover:bg-destructive/10"
                  >
                    Delete
                  </Button>
                </div>
              </div>
            ))}
          </div>
        )}
      </div>

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

      {/* Add Repository modal */}
      <Modal
        open={addOpen}
        onOpenChange={(open) => { if (!open) setAddOpen(false); }}
        title="Add Repository"
        description="Register a GitHub repository to receive webhook events."
      >
        {created ? (
          <div className="space-y-4">
            <div className="rounded-md bg-yellow-50 dark:bg-yellow-900/20 border border-yellow-200 dark:border-yellow-800 p-3">
              <p className="text-xs text-yellow-700 dark:text-yellow-400">
                Copy the signing secret now — it won&apos;t be shown again. Add a webhook in your repo at{' '}
                <strong>Settings → Webhooks → Add webhook</strong>, paste the URL and secret, set content type to{' '}
                <strong>application/json</strong>, and select <strong>Pull requests</strong> events.
              </p>
            </div>
            <div>
              <label className="block text-sm font-medium text-foreground mb-1">Webhook URL</label>
              <div className="flex items-center gap-2">
                <input
                  readOnly
                  value={created.webhookUrl ?? webhookUrlFor(created.id)}
                  className="flex-1 rounded-md border border-input bg-muted text-foreground px-3 py-2 text-sm font-mono focus:outline-none"
                  onFocus={(e) => e.target.select()}
                />
                <Button type="button" variant="outline" size="sm"
                  onClick={() => copy(created.webhookUrl ?? webhookUrlFor(created.id), 'new-url')}>
                  {copiedKey === 'new-url' ? 'Copied!' : 'Copy'}
                </Button>
              </div>
            </div>
            <div>
              <label className="block text-sm font-medium text-foreground mb-1">Signing Secret</label>
              <div className="flex items-center gap-2">
                <input
                  readOnly
                  value={created.webhookSecret ?? ''}
                  className="flex-1 rounded-md border border-input bg-muted text-foreground px-3 py-2 text-sm font-mono focus:outline-none"
                  onFocus={(e) => e.target.select()}
                />
                <Button type="button" variant="outline" size="sm"
                  onClick={() => copy(created.webhookSecret ?? '', 'new-secret')}>
                  {copiedKey === 'new-secret' ? 'Copied!' : 'Copy'}
                </Button>
              </div>
            </div>
            <div className="flex pt-2">
              <Button type="button" onClick={() => setAddOpen(false)}>Done</Button>
            </div>
          </div>
        ) : (
          <form onSubmit={handleAddSubmit} noValidate className="space-y-4">
            <div>
              <label htmlFor="add-repo-fullname" className="block text-sm font-medium text-foreground mb-1">
                Repository <span className="text-destructive">*</span>
              </label>
              <input
                id="add-repo-fullname"
                type="text"
                value={addRepoFullName}
                onChange={(e) => setAddRepoFullName(e.target.value)}
                placeholder="owner/repo"
                className="w-full rounded-md border border-input bg-background text-foreground px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-ring focus:border-transparent font-mono"
              />
            </div>
            <div>
              <label htmlFor="add-repo-label" className="block text-sm font-medium text-foreground mb-1">
                Label <span className="text-muted-foreground font-normal">(optional)</span>
              </label>
              <input
                id="add-repo-label"
                type="text"
                value={addLabel}
                onChange={(e) => setAddLabel(e.target.value)}
                placeholder="e.g. Frontend"
                className="w-full rounded-md border border-input bg-background text-foreground px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-ring focus:border-transparent"
              />
            </div>

            {addError && <p className="text-sm text-destructive" role="alert">{addError}</p>}

            <div className="flex gap-3 pt-2">
              <Button type="submit" disabled={addSubmitting}>
                {addSubmitting ? 'Saving…' : 'Add'}
              </Button>
              <Button type="button" variant="outline" onClick={() => setAddOpen(false)} disabled={addSubmitting}>
                Cancel
              </Button>
            </div>
          </form>
        )}
      </Modal>
    </div>
  );
}
