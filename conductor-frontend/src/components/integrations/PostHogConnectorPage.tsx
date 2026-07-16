'use client';

import { useEffect, useState, useCallback } from 'react';
import { useAuth } from '@/contexts/AuthContext';
import {
  listConnections,
  createConnection,
  fetchConnectionData,
  apiErrorMessage,
  type ConnectionDataResponse,
} from '@/lib/api';
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

interface ConnectFormState {
  apiKey: string;
  posthogProjectId: string;
}

export default function PostHogConnectorPage({ projectId }: { projectId: string }) {
  const { accessToken } = useAuth();
  const [response, setResponse] = useState<ConnectionDataResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [connectForm, setConnectForm] = useState<ConnectFormState>({ apiKey: '', posthogProjectId: '' });
  const [connecting, setConnecting] = useState(false);
  const [connectError, setConnectError] = useState<string | null>(null);

  const loadData = useCallback(async (isRefresh = false) => {
    if (!accessToken) return;
    if (isRefresh) setRefreshing(true); else setLoading(true);
    try {
      const conns = await listConnections(projectId, 'posthog', accessToken);
      const connId = conns[0]?.id ?? null;
      if (!connId) { setResponse(null); return; }
      const cached = await fetchConnectionData(projectId, 'posthog', connId, accessToken, false);
      setResponse(cached);
      if (isRefresh || cached.isStale || !cached.healthStatus) {
        if (!isRefresh) setRefreshing(true);
        const fresh = await fetchConnectionData(projectId, 'posthog', connId, accessToken, true);
        setResponse(fresh);
      }
    } catch (e) {
      console.error(e);
    } finally {
      setRefreshing(false);
      setLoading(false);
    }
  }, [projectId, accessToken]);

  useEffect(() => { loadData(); }, [loadData]);

  const handleConnect = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!accessToken) return;
    setConnecting(true);
    setConnectError(null);
    try {
      const created = await createConnection(
        projectId,
        'posthog',
        { apiKey: connectForm.apiKey, configJson: { projectId: connectForm.posthogProjectId } },
        accessToken
      );
      const data = await fetchConnectionData(projectId, 'posthog', created.id, accessToken, true);
      setResponse(data);
    } catch (err: unknown) {
      setConnectError(apiErrorMessage(err, 'Connection failed'));
    } finally {
      setConnecting(false);
    }
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

  const health = response?.healthStatus;
  const data = (response?.data ?? null) as IntegrationData | null;
  const series = data?.series ?? [];
  const total = data?.total ?? 0;

  if (health === 'SETUP_REQUIRED' || !response) {
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
              <label className="block text-sm font-medium text-foreground mb-1">API Key</label>
              <input
                type="password"
                value={connectForm.apiKey}
                onChange={e => setConnectForm(f => ({ ...f, apiKey: e.target.value }))}
                placeholder="phx_..."
                required
                className="w-full rounded-md border border-input bg-background px-3 py-2 text-sm placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring"
              />
            </div>
            <div>
              <label className="block text-sm font-medium text-foreground mb-1">Project ID</label>
              <input
                type="text"
                value={connectForm.posthogProjectId}
                onChange={e => setConnectForm(f => ({ ...f, posthogProjectId: e.target.value }))}
                placeholder="12345"
                required
                className="w-full rounded-md border border-input bg-background px-3 py-2 text-sm placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring"
              />
            </div>
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
        status={health ?? null}
        externalUrl="https://app.posthog.com"
        externalLabel="Open PostHog"
        onRefresh={() => loadData(true)}
        refreshing={refreshing}
      />

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
                  tickFormatter={d => d.slice(5)}
                  interval="preserveStartEnd"
                  tickLine={false}
                  axisLine={false}
                />
                <YAxis hide />
                <Tooltip
                  formatter={(value) => [Number(value).toLocaleString(), 'Pageviews']}
                  labelFormatter={l => `Date: ${l}`}
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
        {response.isStale && (
          <p className="text-xs text-muted-foreground mt-3">
            Showing cached data{response.errorMessage ? ' — the latest refresh failed (see below).' : '.'}
          </p>
        )}
        {response.errorMessage && (
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

      {response.fetchedAt && (
        <p className="text-xs text-muted-foreground">
          Last updated: {new Date(response.fetchedAt).toLocaleString()}
        </p>
      )}
    </div>
  );
}
