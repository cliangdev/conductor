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

interface DataPoint {
  date: string;
  value: number;
}

/** Shape of the connector-specific `data` blob inside ConnectionDataResponse. */
interface IntegrationData {
  series?: DataPoint[];
  total?: number;
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
      const data = await fetchConnectionData(projectId, 'posthog', connId, accessToken, isRefresh);
      setResponse(data);
    } catch (e) {
      console.error(e);
    } finally {
      if (isRefresh) setRefreshing(false); else setLoading(false);
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
      <div className="mb-6 flex items-start justify-between">
        <div>
          <h1 className="text-2xl font-bold text-foreground">PostHog</h1>
          <p className="text-sm text-muted-foreground mt-1">Analytics · 30-day pageview trend</p>
        </div>
        <div className="flex items-center gap-2">
          {health === 'DEGRADED' && (
            <span className="inline-flex items-center gap-1 text-xs text-yellow-600 dark:text-yellow-400 font-medium">
              <span className="h-1.5 w-1.5 rounded-full bg-yellow-500 inline-block" />
              Degraded
            </span>
          )}
          {health === 'HEALTHY' && (
            <span className="inline-flex items-center gap-1 text-xs text-green-600 dark:text-green-400 font-medium">
              <span className="h-1.5 w-1.5 rounded-full bg-green-500 inline-block" />
              Connected
            </span>
          )}
          <a
            href="https://app.posthog.com"
            target="_blank"
            rel="noopener noreferrer"
            className="inline-flex items-center gap-1.5 rounded-md border border-border bg-background px-3 py-1.5 text-xs font-medium text-foreground hover:bg-muted"
          >
            <ExternalLink className="h-3 w-3" />
            Open PostHog
          </a>
          <button
            onClick={() => loadData(true)}
            disabled={refreshing}
            className="rounded-md border border-border bg-background px-3 py-1.5 text-xs font-medium text-foreground hover:bg-muted disabled:opacity-50"
          >
            {refreshing ? 'Refreshing…' : 'Refresh'}
          </button>
        </div>
      </div>

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
                  dataKey="value"
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
          <p className="text-xs text-yellow-600 dark:text-yellow-400 mt-2">{response.errorMessage}</p>
        )}
      </div>

      {response.fetchedAt && (
        <p className="text-xs text-muted-foreground">
          Last updated: {new Date(response.fetchedAt).toLocaleString()}
        </p>
      )}
    </div>
  );
}
