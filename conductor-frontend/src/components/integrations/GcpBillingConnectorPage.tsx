'use client';

import { useEffect, useState, useCallback } from 'react';
import { useAuth } from '@/contexts/AuthContext';
import {
  apiGet,
  apiPost,
  apiErrorMessage,
  listConnections,
  fetchConnectionData,
  patchConnection,
  type ConnectionDataResponse,
} from '@/lib/api';
import { ExternalLink } from 'lucide-react';

interface ServiceCost {
  service: string;
  cost: number;
  currency: string;
}

/** Shape of the connector-specific `data` blob inside ConnectionDataResponse. */
interface GcpBillingData {
  services?: ServiceCost[];
  totalCost?: number;
  currency?: string;
  period?: string;
  momDelta?: number;
  datasetConfigured?: boolean;
  oauthConnected?: boolean;
}

export default function GcpBillingConnectorPage({ projectId }: { projectId: string }) {
  const { accessToken } = useAuth();
  const [response, setResponse] = useState<ConnectionDataResponse | null>(null);
  const [fetchError, setFetchError] = useState<string | null>(null);
  const [connectionId, setConnectionId] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [authorizing, setAuthorizing] = useState(false);
  const [gcpProjects, setGcpProjects] = useState<{ projectId: string; name: string }[]>([]);
  const [bqDatasets, setBqDatasets] = useState<{ datasetId: string; location: string }[]>([]);
  const [selectedGcpProject, setSelectedGcpProject] = useState('');
  const [selectedDataset, setSelectedDataset] = useState('');
  const [loadingProjects, setLoadingProjects] = useState(false);
  const [loadingDatasets, setLoadingDatasets] = useState(false);
  const [saving, setSaving] = useState(false);

  const loadData = useCallback(async (isRefresh = false) => {
    if (!accessToken) return;
    if (isRefresh) setRefreshing(true); else setLoading(true);
    setFetchError(null);
    try {
      const conns = await listConnections(projectId, 'gcp-billing', accessToken);
      const connId = conns[0]?.id ?? null;
      setConnectionId(connId);
      if (!connId) { setResponse(null); return; }
      const data = await fetchConnectionData(projectId, 'gcp-billing', connId, accessToken, isRefresh);
      setResponse(data);
    } catch (e) {
      console.error(e);
      setFetchError(apiErrorMessage(e, 'Failed to load billing data'));
    } finally {
      if (isRefresh) setRefreshing(false); else setLoading(false);
    }
  }, [projectId, accessToken]);

  useEffect(() => { loadData(); }, [loadData]);

  useEffect(() => {
    // Once a connection exists (OAuth complete), load the GCP projects for the dataset picker.
    if (!accessToken || !connectionId || response?.healthStatus !== 'SETUP_REQUIRED') return;
    setLoadingProjects(true);
    apiGet<{ projects: { projectId: string; name: string }[] }>(
      `/api/v1/projects/${projectId}/integrations/gcp-billing/gcp-projects`,
      accessToken
    )
      .then(d => setGcpProjects(d.projects ?? []))
      .catch(console.error)
      .finally(() => setLoadingProjects(false));
  }, [projectId, accessToken, connectionId, response?.healthStatus]);

  useEffect(() => {
    if (!accessToken || !selectedGcpProject) return;
    setLoadingDatasets(true);
    setBqDatasets([]);
    setSelectedDataset('');
    apiGet<{ datasets: { datasetId: string; location: string }[] }>(
      `/api/v1/projects/${projectId}/integrations/gcp-billing/bq-datasets?gcpProjectId=${selectedGcpProject}`,
      accessToken
    )
      .then(d => setBqDatasets(d.datasets ?? []))
      .catch(console.error)
      .finally(() => setLoadingDatasets(false));
  }, [projectId, accessToken, selectedGcpProject]);

  const handleAuthorize = async () => {
    if (!accessToken) return;
    setAuthorizing(true);
    try {
      const result = await apiPost<{ authorizationUrl: string }>(
        `/api/v1/projects/${projectId}/integrations/gcp-billing/oauth/authorize`,
        {},
        accessToken
      );
      window.location.href = result.authorizationUrl;
    } catch (e) {
      console.error('OAuth initiation failed', e);
      setAuthorizing(false);
    }
  };

  const handleSaveConfig = async () => {
    if (!accessToken || !connectionId || !selectedGcpProject || !selectedDataset) return;
    setSaving(true);
    try {
      await patchConnection(
        projectId,
        'gcp-billing',
        connectionId,
        { config: { bqProjectId: selectedGcpProject, bqDatasetName: selectedDataset } },
        accessToken
      );
      await loadData();
    } catch (e) {
      console.error('Config save failed', e);
    } finally {
      setSaving(false);
    }
  };

  if (loading) {
    return (
      <div className="max-w-4xl mx-auto px-4 py-8">
        <div className="animate-pulse space-y-4">
          <div className="h-8 bg-muted rounded w-40" />
          <div className="h-48 bg-muted rounded-lg" />
        </div>
      </div>
    );
  }

  const health = response?.healthStatus;
  const data = (response?.data ?? null) as GcpBillingData | null;
  // The actual reason a live fetch failed: a thrown request error, or the soft error the backend
  // returns in a 200 body (cached data served, live refresh failed).
  const errorBanner = fetchError ?? response?.errorMessage ?? null;

  if (health === 'SETUP_REQUIRED' || !response) {
    const oauthConnected = connectionId != null;

    return (
      <div className="max-w-4xl mx-auto px-4 py-8">
        <div className="mb-6 flex items-start justify-between">
          <div>
            <h1 className="text-2xl font-bold text-foreground">GCP Billing</h1>
            <p className="text-sm text-muted-foreground mt-1">Finance · Google Cloud</p>
          </div>
          <a
            href="https://console.cloud.google.com/billing"
            target="_blank"
            rel="noopener noreferrer"
            className="inline-flex items-center gap-1.5 rounded-md border border-border bg-background px-3 py-1.5 text-xs font-medium text-foreground hover:bg-muted"
          >
            <ExternalLink className="h-3 w-3" />
            Open GCP Console
          </a>
        </div>

        {!oauthConnected ? (
          /* Step 1: OAuth */
          <div className="bg-card rounded-lg border border-border p-8 max-w-lg space-y-6">
            <div>
              <h2 className="text-lg font-semibold text-foreground mb-1">Set up GCP Billing</h2>
              <p className="text-sm text-muted-foreground">
                Connect your Google Cloud account to view spend by service.
              </p>
            </div>
            <div className="space-y-2">
              <div className="flex items-center gap-2">
                <span className="flex-shrink-0 h-5 w-5 rounded-full bg-muted flex items-center justify-center text-xs font-medium text-muted-foreground">1</span>
                <p className="text-sm font-medium text-foreground">Enable BigQuery billing export</p>
              </div>
              <p className="text-xs text-muted-foreground ml-7">
                In GCP Console, go to Billing → Billing export → BigQuery export and enable Standard export.{' '}
                <a href="https://console.cloud.google.com/billing" target="_blank" rel="noopener noreferrer" className="text-primary hover:underline">
                  Open GCP Console →
                </a>
              </p>
            </div>
            <div className="space-y-2">
              <div className="flex items-center gap-2">
                <span className="flex-shrink-0 h-5 w-5 rounded-full bg-muted flex items-center justify-center text-xs font-medium text-muted-foreground">2</span>
                <p className="text-sm font-medium text-foreground">Connect your Google account</p>
              </div>
              <div className="ml-7">
                <button
                  onClick={handleAuthorize}
                  disabled={authorizing}
                  className="w-full rounded-md bg-primary px-4 py-2.5 text-sm font-medium text-primary-foreground hover:bg-primary/90 disabled:opacity-50"
                >
                  {authorizing ? 'Redirecting to Google…' : 'Connect with Google'}
                </button>
              </div>
            </div>
          </div>
        ) : (
          /* Step 2: Select project + dataset */
          <div className="bg-card rounded-lg border border-border p-8 max-w-lg space-y-6">
            <div>
              <h2 className="text-lg font-semibold text-foreground mb-1">Almost there!</h2>
              <p className="text-sm text-muted-foreground">
                Select the BigQuery dataset where your billing export is stored.
              </p>
            </div>
            <div className="space-y-4">
              <div>
                <label className="block text-xs font-medium text-foreground mb-1">GCP Project</label>
                <select
                  value={selectedGcpProject}
                  onChange={e => setSelectedGcpProject(e.target.value)}
                  disabled={loadingProjects}
                  className="w-full rounded-md border border-border bg-background px-3 py-2 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-primary/50 disabled:opacity-50"
                >
                  <option value="">{loadingProjects ? 'Loading projects…' : 'Select a project'}</option>
                  {gcpProjects.map(p => (
                    <option key={p.projectId} value={p.projectId}>{p.name} ({p.projectId})</option>
                  ))}
                </select>
              </div>
              <div>
                <label className="block text-xs font-medium text-foreground mb-1">BigQuery Dataset</label>
                <select
                  value={selectedDataset}
                  onChange={e => setSelectedDataset(e.target.value)}
                  disabled={!selectedGcpProject || loadingDatasets || bqDatasets.length === 0}
                  className="w-full rounded-md border border-border bg-background px-3 py-2 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-primary/50 disabled:opacity-50"
                >
                  <option value="">
                    {!selectedGcpProject ? 'Select a project first' : loadingDatasets ? 'Loading datasets…' : bqDatasets.length === 0 ? 'No datasets found' : 'Select a dataset'}
                  </option>
                  {bqDatasets.map(d => (
                    <option key={d.datasetId} value={d.datasetId}>{d.datasetId}{d.location ? ` (${d.location})` : ''}</option>
                  ))}
                </select>
                {selectedGcpProject && !loadingDatasets && bqDatasets.length === 0 && (
                  <div className="mt-2 rounded-md bg-muted/50 border border-border p-3 text-xs text-muted-foreground space-y-1">
                    <p className="font-medium text-foreground">No BigQuery datasets found in this project.</p>
                    <p>You need to enable billing export first:</p>
                    <ol className="list-decimal list-inside space-y-0.5 pl-1">
                      <li>Go to <a href="https://console.cloud.google.com/billing" target="_blank" rel="noopener noreferrer" className="text-primary hover:underline">GCP Console → Billing</a></li>
                      <li>Click <strong>Billing export</strong> → <strong>BigQuery export</strong></li>
                      <li>Enable <strong>Standard usage cost</strong> and select a dataset (or create one)</li>
                      <li>Come back here and refresh</li>
                    </ol>
                  </div>
                )}
              </div>
            </div>
            {errorBanner && (
              <div className="bg-yellow-50 dark:bg-yellow-900/20 border border-yellow-200 dark:border-yellow-800 rounded-md p-3">
                <p className="text-xs text-yellow-700 dark:text-yellow-400">{errorBanner}</p>
              </div>
            )}
            <button
              onClick={handleSaveConfig}
              disabled={saving || !selectedGcpProject || !selectedDataset}
              className="w-full rounded-md bg-primary px-4 py-2.5 text-sm font-medium text-primary-foreground hover:bg-primary/90 disabled:opacity-50 disabled:cursor-not-allowed"
            >
              {saving ? 'Saving…' : 'Save & Connect'}
            </button>
          </div>
        )}
      </div>
    );
  }

  const services = data?.services ?? [];
  const totalCost = data?.totalCost ?? 0;
  const currency = data?.currency ?? 'USD';
  const momDelta = data?.momDelta;
  const period = data?.period;

  return (
    <div className="max-w-4xl mx-auto px-4 py-8">
      <div className="mb-6 flex items-start justify-between">
        <div>
          <h1 className="text-2xl font-bold text-foreground">GCP Billing</h1>
          <p className="text-sm text-muted-foreground mt-1">
            Finance · {period ?? 'Current month'}
          </p>
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
            href="https://console.cloud.google.com/billing"
            target="_blank"
            rel="noopener noreferrer"
            className="inline-flex items-center gap-1.5 rounded-md border border-border bg-background px-3 py-1.5 text-xs font-medium text-foreground hover:bg-muted"
          >
            <ExternalLink className="h-3 w-3" />
            Open GCP Console
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

      {errorBanner && (
        <div className="bg-yellow-50 dark:bg-yellow-900/20 border border-yellow-200 dark:border-yellow-800 rounded-md p-3 mb-4">
          <p className="text-xs font-medium text-yellow-800 dark:text-yellow-300">Live fetch failed</p>
          <p className="text-xs text-yellow-700 dark:text-yellow-400 mt-0.5 break-words">{errorBanner}</p>
        </div>
      )}

      {/* Summary */}
      <div className="bg-card rounded-lg border border-border p-6 mb-6">
        <div className="flex items-end gap-4 mb-4">
          <div>
            <div className="text-3xl font-bold text-foreground">
              {currency === 'USD' ? '$' : ''}{totalCost.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}
              {currency !== 'USD' && <span className="text-base font-normal ml-1 text-muted-foreground">{currency}</span>}
            </div>
            <div className="text-sm text-muted-foreground">Total this month</div>
          </div>
          {momDelta !== undefined && momDelta !== null && (
            <div className={`text-sm font-medium pb-0.5 ${momDelta >= 0 ? 'text-red-500' : 'text-green-500'}`}>
              {momDelta >= 0 ? '+' : ''}{momDelta.toFixed(1)}% MoM
            </div>
          )}
        </div>

        {services.length > 0 ? (
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-border">
                <th className="text-left font-medium text-muted-foreground pb-2">Service</th>
                <th className="text-right font-medium text-muted-foreground pb-2">Cost</th>
                <th className="text-right font-medium text-muted-foreground pb-2">Share</th>
              </tr>
            </thead>
            <tbody>
              {services.map((svc, i) => (
                <tr key={i} className="border-b border-border/50 last:border-0">
                  <td className="py-2 text-foreground">{svc.service}</td>
                  <td className="py-2 text-right text-foreground font-mono text-xs">
                    {svc.currency === 'USD' ? '$' : ''}{svc.cost.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}
                  </td>
                  <td className="py-2 text-right text-muted-foreground text-xs">
                    {totalCost > 0 ? ((svc.cost / totalCost) * 100).toFixed(1) : '0.0'}%
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        ) : health === 'HEALTHY' && !errorBanner ? (
          /* Fetch succeeded but returned no rows — almost always the BigQuery export lag, not an error. */
          <div className="py-6 text-center text-sm text-muted-foreground space-y-2">
            <p className="font-medium text-foreground">No cost data yet.</p>
            <p>
              GCP billing export typically takes 24–48h to populate after you enable it. If you just
              connected, check back later.
            </p>
            <p>
              Otherwise, confirm rows exist in your export dataset under{' '}
              <a
                href="https://console.cloud.google.com/billing"
                target="_blank"
                rel="noopener noreferrer"
                className="text-primary hover:underline"
              >
                GCP Console → Billing → Billing export
              </a>
              .
            </p>
          </div>
        ) : (
          <div className="py-8 text-center text-sm text-muted-foreground">
            No service cost data available.
          </div>
        )}
        {response.isStale && (
          <p className="text-xs text-muted-foreground mt-3">
            Showing cached data{response.fetchedAt ? ` from ${new Date(response.fetchedAt).toLocaleString()}` : ''}
            {errorBanner ? ' — the latest refresh failed (see above).' : '.'}
          </p>
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
