'use client';

import { useEffect, useState, useCallback } from 'react';
import { useAuth } from '@/contexts/AuthContext';
import {
  listConnections,
  createConnection,
  fetchConnectionData,
  apiErrorMessage,
  type ConnectionDataResponse,
  type ConnectionSummary,
} from '@/lib/api';
import { useCan } from '@/contexts/PermissionsContext';
import {
  buildPostHogConfig,
  buildPostHogConfigPatch,
  effectiveHealth,
  EMPTY_POSTHOG_FORM,
  formatChartTick,
  formFromConfig,
  validatePostHogForm,
  type PostHogFormValues,
} from '@/lib/posthogSettings';
import { PostHogSettingsFields } from './PostHogSettingsFields';
import { ConnectionSettingsDialog } from './ConnectionSettingsDialog';
import { LineChart, Line, XAxis, YAxis, Tooltip, ResponsiveContainer } from 'recharts';
import { ExternalLink } from 'lucide-react';
import { ConnectorHeader } from './ConnectorHeader';
import { StatCard } from './StatCard';
import { Alert } from '@/components/ui/alert';
import { formatDuration, formatPercent } from '@/lib/format';

interface IntegrationData {
  series?: { date: string; count: number }[];
  total?: number;
  visitors?: number;
  sessions?: number;
  bounceRate?: number;
  avgSessionDuration?: number;
  topPages?: { path: string; visitors: number; pageviews: number }[];
  topSources?: { source: string; visitors: number }[];
  queryErrors?: string[];
}

export default function PostHogConnectorPage({ projectId }: { projectId: string }) {
  const { accessToken } = useAuth();
  const canMutate = useCan('integration.manage');
  const [connection, setConnection] = useState<ConnectionSummary | null>(null);
  const [response, setResponse] = useState<ConnectionDataResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [apiKey, setApiKey] = useState('');
  const [connectForm, setConnectForm] = useState<PostHogFormValues>(EMPTY_POSTHOG_FORM);
  const [connecting, setConnecting] = useState(false);
  const [connectError, setConnectError] = useState<string | null>(null);
  const [editing, setEditing] = useState(false);
  const [editForm, setEditForm] = useState<PostHogFormValues>(EMPTY_POSTHOG_FORM);

  // Whether a connection exists is decided by the connection list alone. The data fetch only grades
  // the numbers; a failed or setup-required fetch must never send a connected workspace back to the form.
  const loadData = useCallback(async (isRefresh = false) => {
    if (!accessToken) return;
    if (isRefresh) setRefreshing(true); else setLoading(true);
    setLoadError(null);
    try {
      const conns = await listConnections(projectId, 'posthog', accessToken);
      const conn = conns[0] ?? null;
      setConnection(conn);
      if (!conn) { setResponse(null); return; }
      try {
        const cached = await fetchConnectionData(projectId, 'posthog', conn.id, accessToken, false);
        setResponse(cached);
        if (isRefresh || cached.isStale || !cached.healthStatus) {
          if (!isRefresh) setRefreshing(true);
          const fresh = await fetchConnectionData(projectId, 'posthog', conn.id, accessToken, true);
          setResponse(fresh);
        }
      } catch (e) {
        console.error(e);
        setLoadError(apiErrorMessage(e, 'Could not load PostHog data'));
      }
    } catch (e) {
      console.error(e);
      setLoadError(apiErrorMessage(e, 'Could not load the PostHog connection'));
    } finally {
      setRefreshing(false);
      setLoading(false);
    }
  }, [projectId, accessToken]);

  useEffect(() => { loadData(); }, [loadData]);

  const handleConnect = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!accessToken) return;
    const invalid = validatePostHogForm(connectForm);
    if (invalid) { setConnectError(invalid); return; }
    setConnecting(true);
    setConnectError(null);
    try {
      await createConnection(
        projectId,
        'posthog',
        { apiKey: apiKey.trim(), configJson: buildPostHogConfig(connectForm) },
        accessToken
      );
      setApiKey('');
      await loadData(true);
    } catch (err: unknown) {
      setConnectError(apiErrorMessage(err, 'Connection failed'));
    } finally {
      setConnecting(false);
    }
  };

  const openEdit = () => {
    setEditForm(formFromConfig(connection?.config));
    setEditing(true);
  };

  if (loading) {
    return (
      <div className="max-w-6xl mx-auto px-4 sm:px-6 lg:px-8 py-8">
        <div className="animate-pulse space-y-4">
          <div className="h-8 bg-muted rounded w-32" />
          <div className="h-48 bg-muted rounded-lg" />
        </div>
      </div>
    );
  }

  const { status: health, message: healthNote } = effectiveHealth(
    connection, response?.healthStatus, response?.errorMessage);
  const data = (response?.data ?? null) as IntegrationData | null;
  const series = data?.series ?? [];
  const total = data?.total ?? 0;

  if (!connection) {
    return (
      <div className="max-w-6xl mx-auto px-4 sm:px-6 lg:px-8 py-8">
        <div className="mb-6 flex items-start justify-between">
          <div>
            <h1 className="text-2xl font-bold text-foreground">PostHog</h1>
            <p className="text-sm text-muted-foreground mt-1">Analytics · API Key</p>
          </div>
          <a
            href="https://app.posthog.com"
            target="_blank"
            rel="noopener noreferrer"
            className="inline-flex items-center gap-1.5 rounded-md border border-border bg-background px-3 py-1.5 text-xs font-medium text-foreground hover:bg-muted"
          >
            <ExternalLink className="h-3 w-3" />
            Open PostHog
          </a>
        </div>
        <div className="bg-card rounded-lg border border-border p-8 max-w-md">
          <h2 className="text-lg font-semibold text-foreground mb-1">Connect PostHog</h2>
          <p className="text-sm text-muted-foreground mb-6">Enter your PostHog API key to view pageview trends.</p>
          <form onSubmit={handleConnect} className="space-y-4">
            <div>
              <label htmlFor="posthog-api-key" className="block text-sm font-medium text-foreground mb-1">Personal API key</label>
              <input
                id="posthog-api-key"
                type="password"
                value={apiKey}
                onChange={e => setApiKey(e.target.value)}
                placeholder="phx_..."
                required
                autoComplete="off"
                className="w-full rounded-md border border-input bg-background px-3 py-2 text-sm placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring"
              />
              <p className="mt-1 text-xs text-muted-foreground">Needs query:read access to the project.</p>
            </div>
            <PostHogSettingsFields values={connectForm} onChange={setConnectForm} idPrefix="connect" />
            {connectError && <p className="text-sm text-destructive">{connectError}</p>}
            <button
              type="submit"
              disabled={connecting}
              className="w-full rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90 disabled:opacity-50"
            >
              {connecting ? 'Connecting…' : 'Connect'}
            </button>
          </form>
        </div>
      </div>
    );
  }

  return (
    <div className="max-w-6xl mx-auto px-4 sm:px-6 lg:px-8 py-8">
      <ConnectorHeader
        title="PostHog"
        subtitle="Analytics · 30-day web analytics"
        status={health}
        externalUrl="https://app.posthog.com"
        externalLabel="Open PostHog"
        onRefresh={() => loadData(true)}
        refreshing={refreshing}
        onEditSettings={canMutate ? openEdit : undefined}
      />

      {health && health !== 'HEALTHY' && (
        <Alert variant={health === 'UNHEALTHY' ? 'destructive' : 'warning'} className="mb-6 text-sm">
          {healthNote
            || (health === 'SETUP_REQUIRED'
              ? 'PostHog needs attention. Check the settings and the API key.'
              : 'PostHog is not delivering data right now.')}
        </Alert>
      )}
      {loadError && <Alert variant="warning" className="mb-6 text-sm">{loadError}</Alert>}

      {connection && (
        <ConnectionSettingsDialog
          open={editing}
          onOpenChange={setEditing}
          title="PostHog settings"
          projectId={projectId}
          connectorId="posthog"
          connectionId={connection.id}
          apiKeyLabel="Personal API key"
          getConfigPatch={() => {
            const invalid = validatePostHogForm(editForm);
            return invalid ? { error: invalid } : { config: buildPostHogConfigPatch(editForm) };
          }}
          onSaved={() => loadData(true)}
        >
          <PostHogSettingsFields values={editForm} onChange={setEditForm} idPrefix="edit" />
        </ConnectionSettingsDialog>
      )}

      <div className="grid grid-cols-2 sm:grid-cols-5 gap-3 mb-6">
        <StatCard label="Visitors" value={data?.visitors != null ? data.visitors.toLocaleString() : '—'} />
        <StatCard label="Pageviews" value={total.toLocaleString()} />
        <StatCard label="Sessions" value={data?.sessions != null ? data.sessions.toLocaleString() : '—'} />
        <StatCard label="Bounce Rate" value={data?.bounceRate != null ? formatPercent(data.bounceRate) : '—'} />
        <StatCard label="Avg. Duration" value={data?.avgSessionDuration != null ? formatDuration(data.avgSessionDuration) : '—'} />
      </div>

      {data?.queryErrors && data.queryErrors.length > 0 && (
        <Alert variant="warning" className="mb-6 text-xs">
          <p className="font-medium mb-1">Some metrics could not be loaded:</p>
          <ul className="list-disc list-inside space-y-0.5">
            {data.queryErrors.map((e, i) => <li key={i}>{e}</li>)}
          </ul>
        </Alert>
      )}

      <div className="bg-card rounded-lg border border-border p-6 mb-6">
        <div className="mb-4">
          <div className="text-3xl font-bold text-foreground">{total.toLocaleString()}</div>
          <div className="text-sm text-muted-foreground">Total pageviews (last 30 days)</div>
        </div>
        {series.length > 0 ? (
          <div className="h-40">
            <ResponsiveContainer width="100%" height="100%">
              <LineChart data={series} margin={{ top: 4, right: 4, bottom: 0, left: 0 }}>
                <XAxis
                  dataKey="date"
                  tick={{ fontSize: 10 }}
                  tickFormatter={formatChartTick}
                  interval="preserveStartEnd"
                  minTickGap={32}
                  tickLine={false}
                  axisLine={false}
                />
                <YAxis hide />
                <Tooltip
                  formatter={(value) => [Number(value).toLocaleString(), 'Pageviews']}
                  labelFormatter={l => formatChartTick(String(l))}
                />
                <Line
                  type="monotone"
                  dataKey="count"
                  stroke="hsl(var(--primary))"
                  strokeWidth={2}
                  dot={false}
                />
              </LineChart>
            </ResponsiveContainer>
          </div>
        ) : (
          <div className="h-40 flex items-center justify-center text-sm text-muted-foreground">
            No data available
          </div>
        )}
        {response?.isStale && (
          <p className="text-xs text-muted-foreground mt-3">
            Showing cached data{response?.errorMessage ? ' — the latest refresh failed (see below).' : '.'}
          </p>
        )}
        {response?.errorMessage && response.errorMessage !== healthNote && (
          <Alert variant="warning" className="mt-2 text-xs">{response.errorMessage}</Alert>
        )}
      </div>

      {data?.topPages && data.topPages.length > 0 && (
        <div className="bg-card rounded-lg border border-border p-6 mb-6">
          <h2 className="text-sm font-semibold text-foreground mb-4">Top Pages</h2>
          <table className="w-full text-sm">
            <thead>
              <tr className="text-xs text-muted-foreground">
                <th className="text-left pb-2">Page</th>
                <th className="text-right pb-2">Visitors</th>
                <th className="text-right pb-2">Pageviews</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-border">
              {data.topPages.map((p) => (
                <tr key={p.path}>
                  <td className="py-2 font-mono text-xs text-foreground truncate max-w-xs">{p.path}</td>
                  <td className="py-2 text-right text-foreground">{p.visitors.toLocaleString()}</td>
                  <td className="py-2 text-right text-muted-foreground">{p.pageviews.toLocaleString()}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {data?.topSources && data.topSources.length > 0 && (
        <div className="bg-card rounded-lg border border-border p-6 mb-6">
          <h2 className="text-sm font-semibold text-foreground mb-4">Top Sources</h2>
          <table className="w-full text-sm">
            <thead>
              <tr className="text-xs text-muted-foreground">
                <th className="text-left pb-2">Source</th>
                <th className="text-right pb-2">Visitors</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-border">
              {data.topSources.map((s) => (
                <tr key={s.source}>
                  <td className="py-2 text-foreground">{s.source}</td>
                  <td className="py-2 text-right text-foreground">{s.visitors.toLocaleString()}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {response?.fetchedAt && (
        <p className="text-xs text-muted-foreground">
          Last updated: {new Date(response.fetchedAt).toLocaleString()}
        </p>
      )}
    </div>
  );
}
