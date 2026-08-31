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
import { LineChart, Line, XAxis, YAxis, Tooltip, Legend, ResponsiveContainer } from 'recharts';
import { ExternalLink } from 'lucide-react';
import { StatCard } from './StatCard';
import { ConnectorHeader } from './ConnectorHeader';
import { useConnectorCatalogItem } from './ConnectorCatalogContext';
import {
  ConnectorAppCredentialPanel,
  appCredentialOf,
  type ConnectorAppCredentialStatus,
} from './ConnectorAppCredentialPanel';
import { Alert } from '@/components/ui/alert';
import { statusHueClasses } from '@/components/ui/status-badge';
import { toastError } from '@/components/ui/toast';

interface TrendPoint {
  date: string;
  clicks: number;
  impressions: number;
  ctr: number;
  position: number;
}

interface QueryRow {
  query: string;
  clicks: number;
  impressions: number;
  ctr: number;
  position: number;
}

interface PageRow {
  page: string;
  clicks: number;
  impressions: number;
}

interface DimensionClicks {
  clicks: number;
}

interface CountryRow extends DimensionClicks { country: string }
interface DeviceRow extends DimensionClicks { device: string }

/** Shape of the connector-specific `data` blob inside ConnectionDataResponse. */
interface GscData {
  siteUrl?: string;
  /** Only present on SETUP_REQUIRED responses — distinguishes "never connected" (false, show the
   * OAuth button again) from "connected, just needs a property" (true, show the property picker). */
  oauthConnected?: boolean;
  trend?: TrendPoint[];
  topQueries?: QueryRow[];
  brandedClickShare?: number;
  topPages?: PageRow[];
  countries?: CountryRow[];
  devices?: DeviceRow[];
}

const SEARCH_CONSOLE_URL = 'https://search.google.com/search-console';

export default function GscConnectorPage({ projectId }: { projectId: string }) {
  const { accessToken } = useAuth();
  const { item } = useConnectorCatalogItem();
  const [response, setResponse] = useState<ConnectionDataResponse | null>(null);
  const [fetchError, setFetchError] = useState<string | null>(null);
  const [connectionId, setConnectionId] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [authorizing, setAuthorizing] = useState(false);
  const [siteUrl, setSiteUrl] = useState('');
  const [brandTerm, setBrandTerm] = useState('');
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);
  const [sites, setSites] = useState<{ siteUrl: string; permissionLevel: string }[]>([]);
  const [loadingSites, setLoadingSites] = useState(false);
  const [manualEntry, setManualEntry] = useState(false);
  // Same shape as GenericConnectorPage: the catalog entry carries readiness on the first paint and
  // the panel's own responses take over once an admin edits the credential. Derived during render
  // so the authorize button below is never gated on a stale value for a frame.
  const [credentialOverride, setCredentialOverride] = useState<ConnectorAppCredentialStatus | null>(null);
  const appCredential = credentialOverride ?? appCredentialOf(item);
  // Without a platform app there is nothing to consent to — starting the flow anyway only produces
  // a server error naming an environment variable.
  const credentialBlocked = appCredential?.credentialSource === 'NONE';
  const credentialPanel = appCredential ? (
    <ConnectorAppCredentialPanel
      projectId={projectId}
      connectorId="gsc"
      connectorName="Google Search Console"
      status={appCredential}
      onChange={setCredentialOverride}
    />
  ) : null;

  const loadData = useCallback(async (isRefresh = false) => {
    if (!accessToken) return;
    if (isRefresh) setRefreshing(true); else setLoading(true);
    setFetchError(null);
    try {
      const conns = await listConnections(projectId, 'gsc', accessToken);
      const connId = conns[0]?.id ?? null;
      setConnectionId(connId);
      if (!connId) { setResponse(null); return; }
      // Fast cache read for immediate render
      const cached = await fetchConnectionData(projectId, 'gsc', connId, accessToken, false);
      setResponse(cached);
      // Auto-refresh when explicitly requested, cache is stale, or no data has been fetched yet
      if (isRefresh || cached.isStale || !cached.healthStatus) {
        if (!isRefresh) setRefreshing(true);
        const fresh = await fetchConnectionData(projectId, 'gsc', connId, accessToken, true);
        setResponse(fresh);
      }
    } catch (e) {
      console.error(e);
      setFetchError(apiErrorMessage(e, 'Failed to load Search Console data'));
    } finally {
      setRefreshing(false);
      setLoading(false);
    }
  }, [projectId, accessToken]);

  useEffect(() => { loadData(); }, [loadData]);

  // Once OAuth is complete but the property isn't configured yet, load the verified properties for
  // the picker. Falls back to manual entry if the list can't be fetched or is empty.
  useEffect(() => {
    if (!accessToken || !connectionId || response?.healthStatus !== 'SETUP_REQUIRED') return;
    setLoadingSites(true);
    apiGet<{ sites: { siteUrl: string; permissionLevel: string }[] }>(
      `/api/v1/projects/${projectId}/integrations/gsc/sites`,
      accessToken
    )
      .then(d => {
        const list = d.sites ?? [];
        setSites(list);
        if (list.length === 0) setManualEntry(true);
      })
      .catch(() => setManualEntry(true))
      .finally(() => setLoadingSites(false));
  }, [projectId, accessToken, connectionId, response?.healthStatus]);

  const handleAuthorize = async () => {
    if (!accessToken) return;
    setAuthorizing(true);
    try {
      const result = await apiPost<{ authorizationUrl: string }>(
        `/api/v1/projects/${projectId}/integrations/gsc/oauth/authorize`,
        {},
        accessToken
      );
      window.location.href = result.authorizationUrl;
    } catch (e) {
      toastError(apiErrorMessage(e, 'Could not start the Google authorization. Please try again.'));
      setAuthorizing(false);
    }
  };

  const handleSaveConfig = async () => {
    // In dropdown mode, only an explicit selection counts — never fall back to the saved (broken)
    // value, which would just re-submit the same property and loop. In manual mode, the pre-filled
    // text is intentional and usable as-is.
    const inDropdownMode = !manualEntry && sites.length > 0;
    const urlToSave = inDropdownMode
      ? siteUrl.trim()
      : (siteUrl.trim() || ((response?.data as GscData | null)?.siteUrl ?? ''));
    if (!accessToken || !connectionId || !urlToSave) return;
    setSaving(true);
    setSaveError(null);
    try {
      await patchConnection(
        projectId,
        'gsc',
        connectionId,
        { config: { siteUrl: urlToSave, brandTerm: brandTerm.trim() } },
        accessToken
      );
      setSiteUrl('');
      await loadData(true);
    } catch (e) {
      console.error('Config save failed', e);
      setSaveError(apiErrorMessage(e, 'Failed to save configuration'));
    } finally {
      setSaving(false);
    }
  };

  if (loading) {
    return (
      <div className="max-w-6xl mx-auto px-4 sm:px-6 lg:px-8 py-8">
        <div className="animate-pulse space-y-4">
          <div className="h-8 bg-muted rounded w-48" />
          <div className="h-48 bg-muted rounded-lg" />
        </div>
      </div>
    );
  }

  const health = response?.healthStatus;
  const data = (response?.data ?? null) as GscData | null;
  // The actual reason a live fetch failed: a thrown request error, or the soft error the backend
  // returns in a 200 body (cached data served, live refresh failed).
  const errorBanner = saveError ?? fetchError ?? response?.errorMessage ?? null;
  // Pre-populate the property picker with a previously saved (but broken) siteUrl so the user
  // can see and correct it without retyping. Derived rather than stored to avoid setState-in-effect.
  const savedSiteUrl = health === 'SETUP_REQUIRED' ? (data?.siteUrl ?? '') : '';
  const effectiveSiteUrl = siteUrl || savedSiteUrl;

  if (health === 'SETUP_REQUIRED' || !response) {
    // The backend tells us explicitly when OAuth itself needs to be redone (e.g. a dead refresh
    // token) via data.oauthConnected=false — a stored connection row alone doesn't mean the grant
    // still works, so don't infer "connected" purely from connectionId existing.
    const oauthConnected = data?.oauthConnected ?? (connectionId != null);

    return (
      <div className="max-w-6xl mx-auto px-4 sm:px-6 lg:px-8 py-8">
        <div className="mb-6 flex items-start justify-between">
          <div>
            <h1 className="text-2xl font-bold text-foreground">Google Search Console</h1>
            <p className="text-sm text-muted-foreground mt-1">Marketing · Google</p>
          </div>
          <a
            href={SEARCH_CONSOLE_URL}
            target="_blank"
            rel="noopener noreferrer"
            className="inline-flex items-center gap-1.5 rounded-md border border-border bg-background px-3 py-1.5 text-xs font-medium text-foreground hover:bg-muted"
          >
            <ExternalLink className="h-3 w-3" />
            Open Search Console
          </a>
        </div>

        {credentialPanel}

        {!oauthConnected ? (
          /* Step 1: OAuth */
          <div className="bg-card rounded-lg border border-border p-8 max-w-lg space-y-6">
            <div>
              <h2 className="text-lg font-semibold text-foreground mb-1">Set up Google Search Console</h2>
              <p className="text-sm text-muted-foreground">
                Connect your Google account to view organic search performance.
              </p>
            </div>
            <div className="space-y-2">
              <button
                onClick={handleAuthorize}
                disabled={authorizing || credentialBlocked}
                className="w-full rounded-md bg-primary px-4 py-2.5 text-sm font-medium text-primary-foreground hover:bg-primary/90 disabled:opacity-50 disabled:cursor-not-allowed"
              >
                {authorizing ? 'Redirecting to Google…' : 'Connect Google Search Console'}
              </button>
              {credentialBlocked && (
                <p className="text-xs text-muted-foreground">
                  Available once the platform app credentials above are configured.
                </p>
              )}
            </div>
          </div>
        ) : (
          /* Step 2: Pick the property */
          <div className="bg-card rounded-lg border border-border p-8 max-w-lg space-y-6">
            <div>
              <h2 className="text-lg font-semibold text-foreground mb-1">Almost there!</h2>
              <p className="text-sm text-muted-foreground">
                Enter the verified Search Console property you want to track.
              </p>
            </div>
            <div className="space-y-4">
              <div>
                <label className="block text-xs font-medium text-foreground mb-1">Property</label>
                {!manualEntry && sites.length > 0 ? (
                  <>
                    <select
                      value={siteUrl}
                      onChange={e => setSiteUrl(e.target.value)}
                      disabled={loadingSites}
                      className="w-full rounded-md border border-border bg-background px-3 py-2 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-primary/50"
                    >
                      <option value="">{loadingSites ? 'Loading properties…' : 'Select a property'}</option>
                      {sites.map(s => (
                        <option key={s.siteUrl} value={s.siteUrl}>{s.siteUrl}</option>
                      ))}
                    </select>
                    <p className="text-xs text-muted-foreground mt-1">
                      Verified properties from your Google account.{' '}
                      <button
                        type="button"
                        onClick={() => { setManualEntry(true); setSiteUrl(''); }}
                        className="text-primary hover:underline"
                      >
                        Enter manually
                      </button>
                    </p>
                  </>
                ) : (
                  <>
                    <input
                      type="text"
                      value={effectiveSiteUrl}
                      onChange={e => setSiteUrl(e.target.value)}
                      placeholder="sc-domain:example.com or https://example.com/"
                      className="w-full rounded-md border border-border bg-background px-3 py-2 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-primary/50"
                    />
                    <p className="text-xs text-muted-foreground mt-1">
                      {loadingSites
                        ? 'Loading your properties…'
                        : 'Use the exact property string from Search Console (domain or URL-prefix).'}
                      {sites.length > 0 && (
                        <>
                          {' '}
                          <button
                            type="button"
                            onClick={() => { setManualEntry(false); setSiteUrl(''); }}
                            className="text-primary hover:underline"
                          >
                            Choose from my properties
                          </button>
                        </>
                      )}
                    </p>
                  </>
                )}
              </div>
              <div>
                <label className="block text-xs font-medium text-foreground mb-1">
                  Brand term <span className="text-muted-foreground font-normal">(optional)</span>
                </label>
                <input
                  type="text"
                  value={brandTerm}
                  onChange={e => setBrandTerm(e.target.value)}
                  placeholder="acme"
                  className="w-full rounded-md border border-border bg-background px-3 py-2 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-primary/50"
                />
                <p className="text-xs text-muted-foreground mt-1">
                  Used for the branded vs non-branded click split.
                </p>
              </div>
            </div>
            {(saveError ?? fetchError ?? (response?.data as GscData | null)?.siteUrl) ? (
              /* A save/fetch failure, or a previously configured property that failed — show as amber warning. */
              <Alert variant="warning" className="text-xs">
                {saveError ?? fetchError ?? response?.errorMessage}
              </Alert>
            ) : response?.errorMessage ? (
              /* Fresh setup prompt — informational, not an error. */
              <div className="bg-muted/50 border border-border rounded-md p-3">
                <p className="text-xs text-muted-foreground">{response.errorMessage}</p>
              </div>
            ) : null}
            <button
              onClick={handleSaveConfig}
              disabled={saving || (!manualEntry && sites.length > 0 ? !siteUrl.trim() : !effectiveSiteUrl.trim())}
              className="w-full rounded-md bg-primary px-4 py-2.5 text-sm font-medium text-primary-foreground hover:bg-primary/90 disabled:opacity-50 disabled:cursor-not-allowed"
            >
              {saving ? 'Saving…' : 'Save & Connect'}
            </button>
          </div>
        )}
      </div>
    );
  }

  const trend = data?.trend ?? [];
  const topQueries = data?.topQueries ?? [];
  const topPages = data?.topPages ?? [];
  const countries = data?.countries ?? [];
  const devices = data?.devices ?? [];
  const brandedClickShare = data?.brandedClickShare ?? 0;

  const totalClicks = trend.reduce((sum, p) => sum + (p.clicks ?? 0), 0);
  const totalImpressions = trend.reduce((sum, p) => sum + (p.impressions ?? 0), 0);
  const avgCtr = totalImpressions > 0 ? (totalClicks / totalImpressions) * 100 : 0;
  const avgPosition = trend.length > 0
    ? trend.reduce((sum, p) => sum + (p.position ?? 0), 0) / trend.length
    : 0;

  return (
    <div className="max-w-6xl mx-auto px-4 sm:px-6 lg:px-8 py-8">
      <ConnectorHeader
        title="Google Search Console"
        subtitle="Marketing · Organic search (last 28 days)"
        status={health ?? null}
        externalUrl={SEARCH_CONSOLE_URL}
        externalLabel="Open Search Console"
        onRefresh={() => loadData(true)}
        refreshing={refreshing}
      />

      {credentialPanel}

      {/* Always-visible connection health — shown without any user action */}
      <div className="flex items-center gap-4 mb-4 text-xs">
        <HealthDot ok={true} label="Google connected" />
        <HealthDot
          ok={health === 'HEALTHY'}
          // health is HEALTHY or DEGRADED here (SETUP_REQUIRED returns above) — an empty siteUrl on
          // a DEGRADED response just means the last attempt didn't get far enough to know, not that
          // the property specifically is inaccessible (e.g. it could be a dead OAuth token instead).
          label={data?.siteUrl ? `Property: ${data.siteUrl}` : 'Property status unknown — see below'}
        />
      </div>

      {errorBanner && (
        <Alert variant="warning" className="mb-4 text-xs">
          <div className="flex items-start justify-between gap-3">
            <div>
              <p className="font-medium">Fetch issue</p>
              <p className="mt-0.5 break-words">{errorBanner}</p>
            </div>
            <button
              type="button"
              onClick={handleAuthorize}
              disabled={authorizing || credentialBlocked}
              className="shrink-0 rounded-md border border-border bg-background px-2.5 py-1 text-xs font-medium text-foreground hover:bg-muted disabled:opacity-50"
            >
              {authorizing ? 'Redirecting…' : 'Reconnect Google'}
            </button>
          </div>
        </Alert>
      )}

      {/* Stat cards */}
      <div className="grid grid-cols-2 sm:grid-cols-4 gap-4 mb-6">
        <StatCard label="Clicks" value={totalClicks.toLocaleString()} />
        <StatCard label="Impressions" value={totalImpressions.toLocaleString()} />
        <StatCard label="Avg CTR" value={`${avgCtr.toFixed(1)}%`} />
        <StatCard label="Avg Position" value={avgPosition.toFixed(1)} />
      </div>

      {/* Trend chart */}
      <div className="bg-card rounded-lg border border-border p-6 mb-6">
        <h2 className="text-sm font-medium text-foreground mb-4">Clicks &amp; impressions</h2>
        {trend.length > 0 ? (
          <div className="h-56">
            <ResponsiveContainer width="100%" height="100%">
              <LineChart data={trend} margin={{ top: 4, right: 8, bottom: 0, left: 0 }}>
                <XAxis
                  dataKey="date"
                  tick={{ fontSize: 10 }}
                  tickFormatter={d => d.slice(5)}
                  interval="preserveStartEnd"
                  tickLine={false}
                  axisLine={false}
                />
                <YAxis yAxisId="left" hide />
                <YAxis yAxisId="right" orientation="right" hide />
                <Tooltip
                  formatter={(value, name) => [Number(value).toLocaleString(), name]}
                  labelFormatter={l => `Date: ${l}`}
                />
                <Legend wrapperStyle={{ fontSize: 11 }} />
                <Line
                  yAxisId="left"
                  type="monotone"
                  dataKey="clicks"
                  name="Clicks"
                  stroke="hsl(var(--primary))"
                  strokeWidth={2}
                  dot={false}
                />
                <Line
                  yAxisId="right"
                  type="monotone"
                  dataKey="impressions"
                  name="Impressions"
                  stroke="hsl(var(--muted-foreground))"
                  strokeWidth={1.5}
                  strokeDasharray="4 3"
                  dot={false}
                />
              </LineChart>
            </ResponsiveContainer>
          </div>
        ) : (
          <div className="h-56 flex items-center justify-center text-sm text-muted-foreground">
            No trend data available
          </div>
        )}
      </div>

      {/* Branded share */}
      <div className="bg-card rounded-lg border border-border p-6 mb-6">
        <div className="flex items-center justify-between mb-2">
          <h2 className="text-sm font-medium text-foreground">Branded click share</h2>
          <span className="text-sm font-semibold text-foreground">
            {(brandedClickShare * 100).toFixed(0)}%
          </span>
        </div>
        <div className="h-2 w-full rounded-full bg-muted overflow-hidden">
          <div
            className="h-full rounded-full bg-primary"
            style={{ width: `${Math.min(100, Math.max(0, brandedClickShare * 100))}%` }}
          />
        </div>
        <p className="text-xs text-muted-foreground mt-2">
          Share of clicks from queries containing your brand term.
        </p>
      </div>

      {/* Top queries */}
      <div className="bg-card rounded-lg border border-border p-6 mb-6">
        <h2 className="text-sm font-medium text-foreground mb-4">Top queries</h2>
        {topQueries.length > 0 ? (
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-border">
                <th className="text-left font-medium text-muted-foreground pb-2">Query</th>
                <th className="text-right font-medium text-muted-foreground pb-2">Clicks</th>
                <th className="text-right font-medium text-muted-foreground pb-2">Impr.</th>
                <th className="text-right font-medium text-muted-foreground pb-2">CTR</th>
                <th className="text-right font-medium text-muted-foreground pb-2">Pos.</th>
              </tr>
            </thead>
            <tbody>
              {topQueries.slice(0, 25).map((q, i) => (
                <tr key={i} className="border-b border-border/50 last:border-0">
                  <td className="py-2 text-foreground truncate max-w-[16rem]">{q.query}</td>
                  <td className="py-2 text-right text-foreground font-mono text-xs">{q.clicks.toLocaleString()}</td>
                  <td className="py-2 text-right text-muted-foreground font-mono text-xs">{q.impressions.toLocaleString()}</td>
                  <td className="py-2 text-right text-muted-foreground text-xs">{(q.ctr * 100).toFixed(1)}%</td>
                  <td className="py-2 text-right text-muted-foreground text-xs">{q.position.toFixed(1)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        ) : (
          <div className="py-6 text-center text-sm text-muted-foreground">No query data available.</div>
        )}
      </div>

      {/* Pages */}
      {topPages.length > 0 && (
        <div className="bg-card rounded-lg border border-border p-6 mb-6">
          <h2 className="text-sm font-medium text-foreground mb-4">Top pages</h2>
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-border">
                <th className="text-left font-medium text-muted-foreground pb-2">Page</th>
                <th className="text-right font-medium text-muted-foreground pb-2">Clicks</th>
                <th className="text-right font-medium text-muted-foreground pb-2">Impr.</th>
              </tr>
            </thead>
            <tbody>
              {topPages.map((p, i) => (
                <tr key={i} className="border-b border-border/50 last:border-0">
                  <td className="py-2 text-foreground truncate max-w-[20rem]">{p.page}</td>
                  <td className="py-2 text-right text-foreground font-mono text-xs">{p.clicks.toLocaleString()}</td>
                  <td className="py-2 text-right text-muted-foreground font-mono text-xs">{p.impressions.toLocaleString()}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {/* Countries + devices */}
      <div className="grid grid-cols-1 sm:grid-cols-2 gap-6 mb-6">
        <DimensionList title="Top countries" rows={countries.map(c => ({ label: c.country.toUpperCase(), clicks: c.clicks }))} />
        <DimensionList title="Devices" rows={devices.map(d => ({ label: d.device, clicks: d.clicks }))} />
      </div>

      {response.isStale && (
        <p className="text-xs text-muted-foreground">
          Showing cached data{response.fetchedAt ? ` from ${new Date(response.fetchedAt).toLocaleString()}` : ''}
          {errorBanner ? ' — the latest refresh failed (see above).' : '.'}
        </p>
      )}
      {response.fetchedAt && !response.isStale && (
        <p className="text-xs text-muted-foreground">
          Last updated: {new Date(response.fetchedAt).toLocaleString()}
        </p>
      )}
    </div>
  );
}

function HealthDot({ ok, label }: { ok: boolean; label: string }) {
  const hue = statusHueClasses(ok ? 'green' : 'amber');
  return (
    <span className={`inline-flex items-center gap-1.5 ${hue.text}`}>
      <span className={`h-1.5 w-1.5 rounded-full ${hue.dot}`} />
      {label}
    </span>
  );
}

function DimensionList({ title, rows }: { title: string; rows: { label: string; clicks: number }[] }) {
  const max = rows.reduce((m, r) => Math.max(m, r.clicks), 0);
  return (
    <div className="bg-card rounded-lg border border-border p-6">
      <h2 className="text-sm font-medium text-foreground mb-4">{title}</h2>
      {rows.length > 0 ? (
        <ul className="space-y-2">
          {rows.map((r, i) => (
            <li key={i} className="text-sm">
              <div className="flex items-center justify-between mb-1">
                <span className="text-foreground truncate max-w-[12rem]">{r.label}</span>
                <span className="text-muted-foreground font-mono text-xs">{r.clicks.toLocaleString()}</span>
              </div>
              <div className="h-1.5 w-full rounded-full bg-muted overflow-hidden">
                <div
                  className="h-full rounded-full bg-primary/70"
                  style={{ width: max > 0 ? `${(r.clicks / max) * 100}%` : '0%' }}
                />
              </div>
            </li>
          ))}
        </ul>
      ) : (
        <p className="text-sm text-muted-foreground">No data available.</p>
      )}
    </div>
  );
}
