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
  ComposedChart,
  Line,
  Bar,
  LineChart,
  XAxis,
  YAxis,
  Tooltip,
  Legend,
  ResponsiveContainer,
} from 'recharts';
import { ExternalLink } from 'lucide-react';
import { StatCard } from './StatCard';
import { ConnectorHeader } from './ConnectorHeader';
import { formatUsd } from '@/lib/format';

interface InstallPoint { date: string; newDownloads: number; installs: number }
interface SpendPoint { date: string; localSpend: number }
interface EffPoint { date: string; avgCPA: number; avgCPT: number; conversionRate: number }
interface KeywordRow { keyword: string; newDownloads: number; localSpend: number; avgCPA: number; taps: number }
interface SearchTermRow { searchTerm: string; newDownloads: number; taps: number; conversionRate: number }

/** Shape of the connector-specific `data` blob inside ConnectionDataResponse. */
interface IntegrationData {
  installsSeries?: InstallPoint[];
  spendSeries?: SpendPoint[];
  effSeries?: EffPoint[];
  topKeywords?: KeywordRow[];
  topSearchTerms?: SearchTermRow[];
}

interface ConnectFormState {
  clientId: string;
  teamId: string;
  keyId: string;
  privateKey: string;
  orgId: string;
  campaignId: string;
}

const CONNECTOR_ID = 'apple-search-ads';
const ASA_URL = 'https://app.searchads.apple.com';
const WINDOW = 28;

const emptyForm: ConnectFormState = {
  clientId: '', teamId: '', keyId: '', privateKey: '', orgId: '', campaignId: '',
};

export default function AppleSearchAdsConnectorPage({ projectId }: { projectId: string }) {
  const { accessToken } = useAuth();
  const [response, setResponse] = useState<ConnectionDataResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [connectForm, setConnectForm] = useState<ConnectFormState>(emptyForm);
  const [connecting, setConnecting] = useState(false);
  const [connectError, setConnectError] = useState<string | null>(null);

  const loadData = useCallback(async (isRefresh = false) => {
    if (!accessToken) return;
    if (isRefresh) setRefreshing(true); else setLoading(true);
    try {
      const conns = await listConnections(projectId, CONNECTOR_ID, accessToken);
      const connId = conns[0]?.id ?? null;
      if (!connId) { setResponse(null); return; }
      const data = await fetchConnectionData(projectId, CONNECTOR_ID, connId, accessToken, isRefresh);
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
      // The .p8 private key is the SECRET → sent as `apiKey` so the framework stores it ENCRYPTED.
      // The remaining identifiers are non-secret → configJson (plaintext config).
      const created = await createConnection(
        projectId,
        CONNECTOR_ID,
        {
          apiKey: connectForm.privateKey,
          configJson: {
            clientId: connectForm.clientId,
            teamId: connectForm.teamId,
            keyId: connectForm.keyId,
            orgId: connectForm.orgId,
            ...(connectForm.campaignId ? { campaignId: connectForm.campaignId } : {}),
          },
        },
        accessToken
      );
      const data = await fetchConnectionData(projectId, CONNECTOR_ID, created.id, accessToken, true);
      setResponse(data);
    } catch (err: unknown) {
      setConnectError(apiErrorMessage(err, 'Connection failed'));
    } finally {
      setConnecting(false);
    }
  };

  if (loading) {
    return (
      <div className="max-w-5xl mx-auto px-4 py-8">
        <div className="animate-pulse space-y-4">
          <div className="h-8 bg-muted rounded w-48" />
          <div className="h-48 bg-muted rounded-lg" />
        </div>
      </div>
    );
  }

  const health = response?.healthStatus;
  const data = (response?.data ?? null) as IntegrationData | null;
  const installsSeries = data?.installsSeries ?? [];
  const spendSeries = data?.spendSeries ?? [];
  const effSeries = data?.effSeries ?? [];
  const topKeywords = data?.topKeywords ?? [];
  const topSearchTerms = data?.topSearchTerms ?? [];

  if (health === 'SETUP_REQUIRED' || !response) {
    return (
      <div className="max-w-5xl mx-auto px-4 py-8">
        <div className="mb-6 flex items-start justify-between">
          <div>
            <h1 className="text-2xl font-bold text-foreground">Apple Search Ads</h1>
            <p className="text-sm text-muted-foreground mt-1">Marketing · API Key</p>
          </div>
          <a
            href={ASA_URL}
            target="_blank"
            rel="noopener noreferrer"
            className="inline-flex items-center gap-1.5 rounded-md border border-border bg-background px-3 py-1.5 text-xs font-medium text-foreground hover:bg-muted"
          >
            <ExternalLink className="h-3 w-3" />
            Open Apple Ads
          </a>
        </div>
        <div className="bg-card rounded-lg border border-border p-8 max-w-lg">
          <h2 className="text-lg font-semibold text-foreground mb-1">Connect Apple Search Ads</h2>
          <p className="text-sm text-muted-foreground mb-6">
            Create an API key in Apple Ads → Account Settings → API, then paste its identifiers and the
            .p8 private key below.
          </p>
          <form onSubmit={handleConnect} className="space-y-4">
            <Field label="Client ID" placeholder="SEARCHADS.xxxx-..." value={connectForm.clientId}
              onChange={v => setConnectForm(f => ({ ...f, clientId: v }))} required />
            <Field label="Team ID" placeholder="SEARCHADS.xxxx-..." value={connectForm.teamId}
              onChange={v => setConnectForm(f => ({ ...f, teamId: v }))} required />
            <Field label="Key ID" placeholder="xxxx-xxxx-..." value={connectForm.keyId}
              onChange={v => setConnectForm(f => ({ ...f, keyId: v }))} required />
            <div>
              <label className="block text-sm font-medium text-foreground mb-1">Private Key (.p8)</label>
              <textarea
                value={connectForm.privateKey}
                onChange={e => setConnectForm(f => ({ ...f, privateKey: e.target.value }))}
                placeholder={'-----BEGIN PRIVATE KEY-----\n...\n-----END PRIVATE KEY-----'}
                required
                rows={5}
                className="w-full rounded-md border border-input bg-background px-3 py-2 text-xs font-mono placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring"
              />
              <p className="text-xs text-muted-foreground mt-1">Stored encrypted; never shown again.</p>
            </div>
            <Field label="Org ID" placeholder="From GET /api/v5/acls" value={connectForm.orgId}
              onChange={v => setConnectForm(f => ({ ...f, orgId: v }))} required />
            <Field label="Campaign ID (optional)" placeholder="For keyword & search-term reports"
              value={connectForm.campaignId} onChange={v => setConnectForm(f => ({ ...f, campaignId: v }))} />
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

  // ── Stat cards over the trailing window ────────────────────────────────────
  const recent = installsSeries.slice(-WINDOW);
  const recentSpend = spendSeries.slice(-WINDOW);
  const recentEff = effSeries.slice(-WINDOW);
  const totalDownloads = recent.reduce((s, p) => s + (p.newDownloads ?? 0), 0);
  const totalSpend = recentSpend.reduce((s, p) => s + (p.localSpend ?? 0), 0);
  const cpa = totalDownloads > 0 ? totalSpend / totalDownloads : 0;
  const avgConversion = recentEff.length
    ? recentEff.reduce((s, p) => s + (p.conversionRate ?? 0), 0) / recentEff.length
    : 0;

  // Merge downloads + spend by date for the dual-axis chart.
  const spendByDate = new Map(spendSeries.map(p => [p.date, p.localSpend]));
  const combined = installsSeries.map(p => ({
    date: p.date,
    newDownloads: p.newDownloads,
    localSpend: spendByDate.get(p.date) ?? 0,
  }));

  return (
    <div className="max-w-5xl mx-auto px-4 py-8">
      <ConnectorHeader
        title="Apple Search Ads"
        subtitle="Marketing · paid acquisition (last 30 days)"
        status={health ?? null}
        externalUrl={ASA_URL}
        externalLabel="Open Apple Ads"
        onRefresh={() => loadData(true)}
        refreshing={refreshing}
      />

      <div className="grid grid-cols-2 md:grid-cols-4 gap-4 mb-6">
        <StatCard label={`New Downloads (${WINDOW}d)`} value={totalDownloads.toLocaleString()} />
        <StatCard label={`Spend (${WINDOW}d)`} value={formatUsd(totalSpend)} />
        <StatCard label="CPA" value={cpa.toLocaleString(undefined, { style: 'currency', currency: 'USD' })} />
        <StatCard label="Conversion Rate" value={`${avgConversion.toFixed(1)}%`} />
      </div>

      <div className="bg-card rounded-lg border border-border p-6 mb-6">
        <h2 className="text-sm font-semibold text-foreground mb-4">New downloads vs. spend</h2>
        {combined.length > 0 ? (
          <div className="h-56">
            <ResponsiveContainer width="100%" height="100%">
              <ComposedChart data={combined} margin={{ top: 4, right: 8, bottom: 0, left: 0 }}>
                <XAxis dataKey="date" tick={{ fontSize: 10 }} tickFormatter={d => d.slice(5)}
                  interval="preserveStartEnd" tickLine={false} axisLine={false} />
                <YAxis yAxisId="left" hide />
                <YAxis yAxisId="right" orientation="right" hide />
                <Tooltip
                  formatter={(value, name) =>
                    name === 'localSpend' ? [formatUsd(Number(value)), 'Spend'] : [Number(value).toLocaleString(), 'New downloads']}
                  labelFormatter={l => `Date: ${l}`}
                />
                <Legend wrapperStyle={{ fontSize: 11 }} />
                <Bar yAxisId="left" dataKey="newDownloads" name="New downloads"
                  fill="hsl(var(--primary))" radius={[2, 2, 0, 0]} maxBarSize={18} />
                <Line yAxisId="right" type="monotone" dataKey="localSpend" name="Spend"
                  stroke="#f59e0b" strokeWidth={2} dot={false} />
              </ComposedChart>
            </ResponsiveContainer>
          </div>
        ) : (
          <div className="h-56 flex items-center justify-center text-sm text-muted-foreground">No data available</div>
        )}
      </div>

      <div className="bg-card rounded-lg border border-border p-6 mb-6">
        <h2 className="text-sm font-semibold text-foreground mb-4">Cost per acquisition (CPA)</h2>
        {effSeries.length > 0 ? (
          <div className="h-40">
            <ResponsiveContainer width="100%" height="100%">
              <LineChart data={effSeries} margin={{ top: 4, right: 8, bottom: 0, left: 0 }}>
                <XAxis dataKey="date" tick={{ fontSize: 10 }} tickFormatter={d => d.slice(5)}
                  interval="preserveStartEnd" tickLine={false} axisLine={false} />
                <YAxis hide />
                <Tooltip
                  formatter={(value) => [formatUsd(Number(value)), 'CPA']}
                  labelFormatter={l => `Date: ${l}`}
                />
                <Line type="monotone" dataKey="avgCPA" stroke="hsl(var(--primary))" strokeWidth={2} dot={false} />
              </LineChart>
            </ResponsiveContainer>
          </div>
        ) : (
          <div className="h-40 flex items-center justify-center text-sm text-muted-foreground">No data available</div>
        )}
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6 mb-6">
        <TableCard title="Top keywords" empty="No keyword data — set a Campaign ID to enable.">
          {topKeywords.length > 0 && (
            <table className="w-full text-sm">
              <thead>
                <tr className="text-left text-xs text-muted-foreground border-b border-border">
                  <th className="py-2 font-medium">Keyword</th>
                  <th className="py-2 font-medium text-right">Downloads</th>
                  <th className="py-2 font-medium text-right">Spend</th>
                  <th className="py-2 font-medium text-right">CPA</th>
                </tr>
              </thead>
              <tbody>
                {topKeywords.map((k, i) => (
                  <tr key={i} className="border-b border-border/50 last:border-0">
                    <td className="py-2 text-foreground">{k.keyword || '—'}</td>
                    <td className="py-2 text-right text-foreground">{(k.newDownloads ?? 0).toLocaleString()}</td>
                    <td className="py-2 text-right text-muted-foreground">{formatUsd(k.localSpend ?? 0)}</td>
                    <td className="py-2 text-right text-muted-foreground">
                      {(k.avgCPA ?? 0).toLocaleString(undefined, { style: 'currency', currency: 'USD' })}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </TableCard>

        <TableCard title="Top search terms" empty="No search-term data — set a Campaign ID to enable.">
          {topSearchTerms.length > 0 && (
            <table className="w-full text-sm">
              <thead>
                <tr className="text-left text-xs text-muted-foreground border-b border-border">
                  <th className="py-2 font-medium">Search term</th>
                  <th className="py-2 font-medium text-right">Downloads</th>
                  <th className="py-2 font-medium text-right">Taps</th>
                  <th className="py-2 font-medium text-right">Conv.</th>
                </tr>
              </thead>
              <tbody>
                {topSearchTerms.map((t, i) => (
                  <tr key={i} className="border-b border-border/50 last:border-0">
                    <td className="py-2 text-foreground">{t.searchTerm || '—'}</td>
                    <td className="py-2 text-right text-foreground">{(t.newDownloads ?? 0).toLocaleString()}</td>
                    <td className="py-2 text-right text-muted-foreground">{(t.taps ?? 0).toLocaleString()}</td>
                    <td className="py-2 text-right text-muted-foreground">{(t.conversionRate ?? 0).toFixed(1)}%</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </TableCard>
      </div>

      {response.isStale && (
        <p className="text-xs text-muted-foreground">
          Showing cached data{response.errorMessage ? ' — the latest refresh failed (see below).' : '.'}
        </p>
      )}
      {response.errorMessage && (
        <p className="text-xs text-yellow-600 dark:text-yellow-400 mt-1">{response.errorMessage}</p>
      )}
      {response.fetchedAt && (
        <p className="text-xs text-muted-foreground mt-2">
          Last updated: {new Date(response.fetchedAt).toLocaleString()}
        </p>
      )}
    </div>
  );
}

function Field({ label, value, onChange, placeholder, required }: {
  label: string; value: string; onChange: (v: string) => void; placeholder?: string; required?: boolean;
}) {
  return (
    <div>
      <label className="block text-sm font-medium text-foreground mb-1">{label}</label>
      <input
        type="text"
        value={value}
        onChange={e => onChange(e.target.value)}
        placeholder={placeholder}
        required={required}
        className="w-full rounded-md border border-input bg-background px-3 py-2 text-sm placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring"
      />
    </div>
  );
}

function TableCard({ title, empty, children }: { title: string; empty: string; children: React.ReactNode }) {
  const hasContent = Array.isArray(children) ? children.some(Boolean) : Boolean(children);
  return (
    <div className="bg-card rounded-lg border border-border p-6">
      <h2 className="text-sm font-semibold text-foreground mb-4">{title}</h2>
      {hasContent ? children : (
        <div className="h-24 flex items-center justify-center text-sm text-muted-foreground text-center">{empty}</div>
      )}
    </div>
  );
}
