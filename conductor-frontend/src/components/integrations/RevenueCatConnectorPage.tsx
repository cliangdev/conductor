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
import {
  LineChart, Line, BarChart, Bar, XAxis, YAxis, Tooltip, ResponsiveContainer,
} from 'recharts';
import { ExternalLink } from 'lucide-react';
import { StatCard } from './StatCard';
import { ConnectorHeader } from './ConnectorHeader';
import { formatUsd } from '@/lib/format';

interface SeriesPoint {
  date: string;
  value: number;
}

interface ConversionPoint {
  period: string;
  startRate?: number;
  conversionRate?: number;
}

interface Overview {
  activeTrials?: number;
  activeSubscriptions?: number;
  mrr?: number;
  revenueLast28Days?: number;
  newCustomersLast28Days?: number;
}

/** Shape of the connector-specific `data` blob inside ConnectionDataResponse. */
interface IntegrationData {
  overview?: Overview;
  newCustomersSeries?: SeriesPoint[];
  newTrialsSeries?: SeriesPoint[];
  trialConversion?: ConversionPoint[];
  revenueSeries?: SeriesPoint[];
}

interface ConnectFormState {
  apiKey: string;
  revenueCatProjectId: string;
  currency: string;
}

export default function RevenueCatConnectorPage({ projectId }: { projectId: string }) {
  const { accessToken } = useAuth();
  const [response, setResponse] = useState<ConnectionDataResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [connectForm, setConnectForm] = useState<ConnectFormState>({
    apiKey: '', revenueCatProjectId: '', currency: '',
  });
  const [connecting, setConnecting] = useState(false);
  const [connectError, setConnectError] = useState<string | null>(null);

  const loadData = useCallback(async (isRefresh = false) => {
    if (!accessToken) return;
    if (isRefresh) setRefreshing(true); else setLoading(true);
    try {
      const conns = await listConnections(projectId, 'revenuecat', accessToken);
      const connId = conns[0]?.id ?? null;
      if (!connId) { setResponse(null); return; }
      const data = await fetchConnectionData(projectId, 'revenuecat', connId, accessToken, isRefresh);
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
        'revenuecat',
        {
          apiKey: connectForm.apiKey,
          configJson: {
            projectId: connectForm.revenueCatProjectId,
            ...(connectForm.currency ? { currency: connectForm.currency } : {}),
          },
        },
        accessToken
      );
      const data = await fetchConnectionData(projectId, 'revenuecat', created.id, accessToken, true);
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
  const overview = data?.overview ?? {};
  const newCustomers = data?.newCustomersSeries ?? [];
  const newTrials = data?.newTrialsSeries ?? [];
  const trialConversion = data?.trialConversion ?? [];
  const revenueSeries = data?.revenueSeries ?? [];

  if (health === 'SETUP_REQUIRED' || !response) {
    return (
      <div className="max-w-6xl mx-auto px-4 sm:px-6 lg:px-8 py-8">
        <div className="mb-6 flex items-start justify-between">
          <div>
            <h1 className="text-2xl font-bold text-foreground">RevenueCat</h1>
            <p className="text-sm text-muted-foreground mt-1">Finance · API Key</p>
          </div>
          <a
            href="https://app.revenuecat.com"
            target="_blank"
            rel="noopener noreferrer"
            className="inline-flex items-center gap-1.5 rounded-md border border-border bg-background px-3 py-1.5 text-xs font-medium text-foreground hover:bg-muted"
          >
            <ExternalLink className="h-3 w-3" />
            Open RevenueCat
          </a>
        </div>
        <div className="bg-card rounded-lg border border-border p-8 max-w-md">
          <h2 className="text-lg font-semibold text-foreground mb-1">Connect RevenueCat</h2>
          <p className="text-sm text-muted-foreground mb-6">Enter your RevenueCat V2 secret key to view subscription metrics.</p>
          <form onSubmit={handleConnect} className="space-y-4">
            <div>
              <label className="block text-sm font-medium text-foreground mb-1">API Key</label>
              <input
                type="password"
                value={connectForm.apiKey}
                onChange={e => setConnectForm(f => ({ ...f, apiKey: e.target.value }))}
                placeholder="sk_..."
                required
                className="w-full rounded-md border border-input bg-background px-3 py-2 text-sm placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring"
              />
            </div>
            <div>
              <label className="block text-sm font-medium text-foreground mb-1">Project ID</label>
              <input
                type="text"
                value={connectForm.revenueCatProjectId}
                onChange={e => setConnectForm(f => ({ ...f, revenueCatProjectId: e.target.value }))}
                placeholder="proj1ab2c3d4"
                required
                className="w-full rounded-md border border-input bg-background px-3 py-2 text-sm placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring"
              />
            </div>
            <div>
              <label className="block text-sm font-medium text-foreground mb-1">Currency <span className="text-muted-foreground">(optional)</span></label>
              <input
                type="text"
                value={connectForm.currency}
                onChange={e => setConnectForm(f => ({ ...f, currency: e.target.value }))}
                placeholder="USD"
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

  const stats = [
    { label: 'Active Trials', value: (overview.activeTrials ?? 0).toLocaleString() },
    { label: 'Active Subscriptions', value: (overview.activeSubscriptions ?? 0).toLocaleString() },
    { label: 'New Customers (28d)', value: (overview.newCustomersLast28Days ?? 0).toLocaleString() },
    { label: 'MRR', value: formatUsd(overview.mrr ?? 0) },
    { label: 'Revenue (28d)', value: formatUsd(overview.revenueLast28Days ?? 0) },
  ];

  return (
    <div className="max-w-6xl mx-auto px-4 sm:px-6 lg:px-8 py-8">
      <ConnectorHeader
        title="RevenueCat"
        subtitle="Finance · Subscription metrics"
        status={health ?? null}
        externalUrl="https://app.revenuecat.com"
        externalLabel="Open RevenueCat"
        onRefresh={() => loadData(true)}
        refreshing={refreshing}
      />

      <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-5 gap-4 mb-6">
        {stats.map(s => (
          <StatCard key={s.label} label={s.label} value={s.value} />
        ))}
      </div>

      <div className="bg-card rounded-lg border border-border p-6 mb-6">
        <div className="text-sm font-medium text-foreground mb-4">New customers & trials (last 30 days)</div>
        {newCustomers.length > 0 || newTrials.length > 0 ? (
          <div className="h-48">
            <ResponsiveContainer width="100%" height="100%">
              <BarChart
                data={newCustomers.map((c, i) => ({
                  date: c.date,
                  customers: c.value,
                  trials: newTrials[i]?.value ?? 0,
                }))}
                margin={{ top: 4, right: 4, bottom: 0, left: 0 }}
              >
                <XAxis
                  dataKey="date"
                  tick={{ fontSize: 10 }}
                  tickFormatter={d => (typeof d === 'string' ? d.slice(5) : d)}
                  interval="preserveStartEnd"
                  tickLine={false}
                  axisLine={false}
                />
                <YAxis hide />
                <Tooltip labelFormatter={l => `Date: ${l}`} />
                <Bar dataKey="customers" fill="hsl(var(--primary))" name="New customers" radius={[2, 2, 0, 0]} />
                <Bar dataKey="trials" fill="hsl(var(--muted-foreground))" name="New trials" radius={[2, 2, 0, 0]} />
              </BarChart>
            </ResponsiveContainer>
          </div>
        ) : (
          <div className="h-48 flex items-center justify-center text-sm text-muted-foreground">
            No data available
          </div>
        )}
      </div>

      <div className="bg-card rounded-lg border border-border p-6 mb-6">
        <div className="text-sm font-medium text-foreground mb-4">Trial conversion rate (weekly)</div>
        {trialConversion.length > 0 ? (
          <div className="h-40">
            <ResponsiveContainer width="100%" height="100%">
              <LineChart data={trialConversion} margin={{ top: 4, right: 4, bottom: 0, left: 0 }}>
                <XAxis
                  dataKey="period"
                  tick={{ fontSize: 10 }}
                  tickFormatter={d => (typeof d === 'string' ? d.slice(5) : d)}
                  interval="preserveStartEnd"
                  tickLine={false}
                  axisLine={false}
                />
                <YAxis hide domain={[0, 1]} />
                <Tooltip
                  formatter={(value) => [`${(Number(value) * 100).toFixed(1)}%`, 'Conversion']}
                  labelFormatter={l => `Week of: ${l}`}
                />
                <Line
                  type="monotone"
                  dataKey="conversionRate"
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

      <div className="bg-card rounded-lg border border-border p-6 mb-6">
        <div className="text-sm font-medium text-foreground mb-4">Revenue (last 30 days)</div>
        {revenueSeries.length > 0 ? (
          <div className="h-48">
            <ResponsiveContainer width="100%" height="100%">
              <LineChart data={revenueSeries} margin={{ top: 4, right: 4, bottom: 0, left: 0 }}>
                <XAxis
                  dataKey="date"
                  tick={{ fontSize: 10 }}
                  tickFormatter={d => (typeof d === 'string' ? d.slice(5) : d)}
                  interval="preserveStartEnd"
                  tickLine={false}
                  axisLine={false}
                />
                <YAxis hide />
                <Tooltip
                  formatter={(value) => [formatUsd(Number(value)), 'Revenue']}
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
          <div className="h-48 flex items-center justify-center text-sm text-muted-foreground">
            No data available
          </div>
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
