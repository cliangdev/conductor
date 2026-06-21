'use client';

import { useEffect, useState, useCallback } from 'react';
import { useAuth } from '@/contexts/AuthContext';
import { apiPost } from '@/lib/api';
import { ExternalLink } from 'lucide-react';

interface ServiceCost {
  service: string;
  cost: number;
  currency: string;
}

interface GcpBillingData {
  services?: ServiceCost[];
  totalCost?: number;
  currency?: string;
  period?: string;
  momDelta?: number;
  errorMessage?: string;
  datasetConfigured?: boolean;
}

interface IntegrationDataResponse {
  connectorId: string;
  healthStatus: 'HEALTHY' | 'DEGRADED' | 'SETUP_REQUIRED';
  fetchedAt: string | null;
  data: GcpBillingData | null;
  isStale?: boolean;
}

export default function GcpBillingConnectorPage({ projectId }: { projectId: string }) {
  const { accessToken } = useAuth();
  const [response, setResponse] = useState<IntegrationDataResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [authorizing, setAuthorizing] = useState(false);
  const [bqProjectId, setBqProjectId] = useState('');
  const [bqDatasetName, setBqDatasetName] = useState('');

  const fetchData = useCallback(async (isRefresh = false) => {
    if (!accessToken) return;
    if (isRefresh) setRefreshing(true); else setLoading(true);
    try {
      const data = await apiPost<IntegrationDataResponse>(
        `/api/v1/projects/${projectId}/integrations/gcp-billing/data`,
        {},
        accessToken
      );
      setResponse(data);
    } catch (e) {
      console.error(e);
    } finally {
      if (isRefresh) setRefreshing(false); else setLoading(false);
    }
  }, [projectId, accessToken]);

  useEffect(() => { fetchData(); }, [fetchData]);

  const handleAuthorize = async () => {
    if (!accessToken) return;
    setAuthorizing(true);
    try {
      const result = await apiPost<{ authorizationUrl: string }>(
        `/api/v1/projects/${projectId}/integrations/gcp-billing/oauth/authorize`,
        { config: { bqProjectId: bqProjectId.trim(), bqDatasetName: bqDatasetName.trim() } },
        accessToken
      );
      window.location.href = result.authorizationUrl;
    } catch (e) {
      console.error('OAuth initiation failed', e);
      setAuthorizing(false);
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
  const data = response?.data;

  if (health === 'SETUP_REQUIRED' || !response) {
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
        <div className="bg-card rounded-lg border border-border p-8 max-w-lg space-y-6">
          <div>
            <h2 className="text-lg font-semibold text-foreground mb-1">Set up GCP Billing</h2>
            <p className="text-sm text-muted-foreground">
              Connect your Google Cloud billing export to view spend by service.
            </p>
          </div>

          {/* Step 1 */}
          <div className="space-y-2">
            <div className="flex items-center gap-2">
              <span className="flex-shrink-0 h-5 w-5 rounded-full bg-muted flex items-center justify-center text-xs font-medium text-muted-foreground">1</span>
              <p className="text-sm font-medium text-foreground">Enable BigQuery billing export</p>
            </div>
            <p className="text-xs text-muted-foreground ml-7">
              In GCP Console, go to Billing → Billing export → BigQuery export and enable it.{' '}
              <a
                href="https://console.cloud.google.com/billing"
                target="_blank"
                rel="noopener noreferrer"
                className="text-primary hover:underline"
              >
                Open GCP Console →
              </a>
            </p>
          </div>

          {/* Step 2 */}
          <div className="space-y-3">
            <div className="flex items-center gap-2">
              <span className="flex-shrink-0 h-5 w-5 rounded-full bg-muted flex items-center justify-center text-xs font-medium text-muted-foreground">2</span>
              <p className="text-sm font-medium text-foreground">Enter your BigQuery export details</p>
            </div>
            <div className="ml-7 space-y-3">
              <div>
                <label className="block text-xs font-medium text-foreground mb-1">
                  GCP Project ID
                </label>
                <input
                  type="text"
                  value={bqProjectId}
                  onChange={e => setBqProjectId(e.target.value)}
                  placeholder="my-gcp-project"
                  className="w-full rounded-md border border-border bg-background px-3 py-2 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-primary/50"
                />
                <p className="text-xs text-muted-foreground mt-1">The GCP project where your billing export lives.</p>
              </div>
              <div>
                <label className="block text-xs font-medium text-foreground mb-1">
                  BigQuery Dataset Name
                </label>
                <input
                  type="text"
                  value={bqDatasetName}
                  onChange={e => setBqDatasetName(e.target.value)}
                  placeholder="billing_export"
                  className="w-full rounded-md border border-border bg-background px-3 py-2 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-primary/50"
                />
                <p className="text-xs text-muted-foreground mt-1">The dataset name you configured in the billing export.</p>
              </div>
            </div>
          </div>

          {/* Step 3 */}
          <div className="space-y-3">
            <div className="flex items-center gap-2">
              <span className="flex-shrink-0 h-5 w-5 rounded-full bg-muted flex items-center justify-center text-xs font-medium text-muted-foreground">3</span>
              <p className="text-sm font-medium text-foreground">Connect your Google account</p>
            </div>
            {data?.errorMessage && (
              <div className="ml-7 bg-yellow-50 dark:bg-yellow-900/20 border border-yellow-200 dark:border-yellow-800 rounded-md p-3">
                <p className="text-xs text-yellow-700 dark:text-yellow-400">{data.errorMessage}</p>
              </div>
            )}
            <div className="ml-7">
              <button
                onClick={handleAuthorize}
                disabled={authorizing || !bqProjectId.trim() || !bqDatasetName.trim()}
                className="w-full rounded-md bg-primary px-4 py-2.5 text-sm font-medium text-primary-foreground hover:bg-primary/90 disabled:opacity-50 disabled:cursor-not-allowed"
              >
                {authorizing ? 'Redirecting to Google…' : 'Connect with Google'}
              </button>
              <p className="text-xs text-muted-foreground mt-2 text-center">
                Fill in your BigQuery details above to continue.
              </p>
            </div>
          </div>
        </div>
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
            onClick={() => fetchData(true)}
            disabled={refreshing}
            className="rounded-md border border-border bg-background px-3 py-1.5 text-xs font-medium text-foreground hover:bg-muted disabled:opacity-50"
          >
            {refreshing ? 'Refreshing…' : 'Refresh'}
          </button>
        </div>
      </div>

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
        ) : (
          <div className="py-8 text-center text-sm text-muted-foreground">
            No service cost data available.
          </div>
        )}
        {response.isStale && (
          <p className="text-xs text-muted-foreground mt-3">Showing cached data — live fetch failed.</p>
        )}
      </div>

      {data?.errorMessage && health === 'DEGRADED' && (
        <div className="bg-yellow-50 dark:bg-yellow-900/20 border border-yellow-200 dark:border-yellow-800 rounded-md p-3 mb-4">
          <p className="text-xs text-yellow-700 dark:text-yellow-400">{data.errorMessage}</p>
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
