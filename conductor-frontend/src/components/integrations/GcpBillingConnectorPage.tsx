'use client';

import { useEffect, useState, useCallback } from 'react';
import { useAuth } from '@/contexts/AuthContext';
import { apiPost } from '@/lib/api';

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
        {},
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
        <div className="mb-6">
          <h1 className="text-2xl font-bold text-foreground">GCP Billing</h1>
          <p className="text-sm text-muted-foreground mt-1">Finance · Google Cloud</p>
        </div>
        <div className="bg-card rounded-lg border border-border p-8 max-w-lg">
          <h2 className="text-lg font-semibold text-foreground mb-2">Set up GCP Billing</h2>
          <p className="text-sm text-muted-foreground mb-6">
            Connect your Google Cloud account to view billing costs across services. Conductor queries your BigQuery billing export.
          </p>
          <div className="space-y-3 mb-6">
            <div className="flex items-start gap-3">
              <span className="flex-shrink-0 h-5 w-5 rounded-full bg-muted flex items-center justify-center text-xs font-medium text-muted-foreground">1</span>
              <p className="text-sm text-foreground">Enable BigQuery billing export in your GCP Console.</p>
            </div>
            <div className="flex items-start gap-3">
              <span className="flex-shrink-0 h-5 w-5 rounded-full bg-muted flex items-center justify-center text-xs font-medium text-muted-foreground">2</span>
              <p className="text-sm text-foreground">Authorize Conductor to read your billing data via Google OAuth.</p>
            </div>
            <div className="flex items-start gap-3">
              <span className="flex-shrink-0 h-5 w-5 rounded-full bg-muted flex items-center justify-center text-xs font-medium text-muted-foreground">3</span>
              <p className="text-sm text-foreground">Conductor will query the billing export and show costs by service.</p>
            </div>
          </div>
          {data?.errorMessage && (
            <div className="bg-yellow-50 dark:bg-yellow-900/20 border border-yellow-200 dark:border-yellow-800 rounded-md p-3 mb-4">
              <p className="text-xs text-yellow-700 dark:text-yellow-400">{data.errorMessage}</p>
            </div>
          )}
          <button
            onClick={handleAuthorize}
            disabled={authorizing}
            className="w-full rounded-md bg-primary px-4 py-2.5 text-sm font-medium text-primary-foreground hover:bg-primary/90 disabled:opacity-50"
          >
            {authorizing ? 'Redirecting to Google…' : 'Authorize with Google'}
          </button>
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
