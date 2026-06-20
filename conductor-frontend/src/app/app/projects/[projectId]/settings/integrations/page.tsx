'use client';

export const dynamic = 'force-dynamic';

import { useEffect, useState } from 'react';
import { useParams } from 'next/navigation';
import { useAuth } from '@/contexts/AuthContext';
import { apiGet, apiDelete, apiPost } from '@/lib/api';
import Link from 'next/link';
import { PuzzleIcon, CheckCircleIcon } from 'lucide-react';

interface ConnectorConfigField {
  fieldKey: string;
  label: string;
  hint: string | null;
  secret: boolean;
}

interface IntegrationListItem {
  connectorId: string;
  name: string;
  category: string;
  authType: string;
  description: string;
  iconLabel: string;
  connected: boolean;
  healthStatus: string | null;
  fetchedAt: string | null;
  configFields: ConnectorConfigField[];
}

type Tab = 'browse' | 'connected';

export default function SettingsIntegrationsPage() {
  const { projectId } = useParams<{ projectId: string }>();
  const { accessToken } = useAuth();
  const [integrations, setIntegrations] = useState<IntegrationListItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [activeTab, setActiveTab] = useState<Tab>('browse');

  // Modal state for API key connect
  const [connectModal, setConnectModal] = useState<{ connector: IntegrationListItem } | null>(null);
  const [formValues, setFormValues] = useState<Record<string, string>>({});
  const [connecting, setConnecting] = useState(false);
  const [connectError, setConnectError] = useState<string | null>(null);

  // Disconnect state
  const [disconnecting, setDisconnecting] = useState<string | null>(null);

  const loadIntegrations = async () => {
    if (!accessToken || !projectId) return;
    try {
      const data = await apiGet<IntegrationListItem[]>(
        `/api/v1/projects/${projectId}/integrations`,
        accessToken
      );
      setIntegrations(data);
    } catch (e) {
      console.error(e);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadIntegrations();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [projectId, accessToken]);

  const grouped = integrations.reduce<Record<string, IntegrationListItem[]>>((acc, item) => {
    const cat = item.category || 'Other';
    if (!acc[cat]) acc[cat] = [];
    acc[cat].push(item);
    return acc;
  }, {});

  const connected = integrations.filter((i) => i.connected);

  const openConnectModal = (connector: IntegrationListItem) => {
    setConnectModal({ connector });
    setFormValues({});
    setConnectError(null);
  };

  const handleConnect = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!connectModal || !accessToken) return;
    setConnecting(true);
    setConnectError(null);

    const { connector } = connectModal;
    const secretField = connector.configFields.find((f) => f.secret);
    const apiKey = formValues['apiKey'] || formValues[secretField?.fieldKey || ''];
    const configJson: Record<string, string> = {};
    connector.configFields
      .filter((f) => !f.secret)
      .forEach((f) => {
        if (formValues[f.fieldKey]) configJson[f.fieldKey] = formValues[f.fieldKey];
      });

    try {
      await apiPost(
        `/api/v1/projects/${projectId}/integrations/${connector.connectorId}/credentials`,
        { apiKey, configJson },
        accessToken
      );
      setConnectModal(null);
      await loadIntegrations();
    } catch (err: unknown) {
      setConnectError(err instanceof Error ? err.message : 'Connection failed');
    } finally {
      setConnecting(false);
    }
  };

  const handleOAuth = async (connector: IntegrationListItem) => {
    if (!accessToken) return;
    try {
      const result = await apiPost<{ authorizationUrl: string }>(
        `/api/v1/projects/${projectId}/integrations/${connector.connectorId}/oauth/authorize`,
        {},
        accessToken
      );
      window.location.href = result.authorizationUrl;
    } catch (e) {
      console.error('OAuth initiation failed', e);
    }
  };

  const handleDisconnect = async (connectorId: string) => {
    if (!accessToken) return;
    setDisconnecting(connectorId);
    try {
      await apiDelete(
        `/api/v1/projects/${projectId}/integrations/${connectorId}/credentials`,
        accessToken
      );
      await loadIntegrations();
    } catch (e) {
      console.error(e);
    } finally {
      setDisconnecting(null);
    }
  };

  if (loading) {
    return (
      <div className="max-w-4xl mx-auto px-4 py-8">
        <div className="animate-pulse space-y-4">
          <div className="h-8 bg-muted rounded w-48" />
          <div className="grid grid-cols-2 gap-4">
            {[1, 2, 3, 4].map((i) => (
              <div key={i} className="h-28 bg-muted rounded-lg" />
            ))}
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="max-w-4xl mx-auto px-4 py-8">
      <div className="mb-6">
        <h1 className="text-2xl font-bold text-foreground">Integrations</h1>
        <p className="text-sm text-muted-foreground mt-1">
          Connect third-party tools to view live metrics in Conductor.
        </p>
      </div>

      {/* Tabs */}
      <div className="flex gap-1 mb-6 border-b border-border">
        {(['browse', 'connected'] as Tab[]).map((tab) => (
          <button
            key={tab}
            onClick={() => setActiveTab(tab)}
            className={`px-4 py-2 text-sm font-medium border-b-2 -mb-px capitalize transition-colors ${
              activeTab === tab
                ? 'border-primary text-foreground'
                : 'border-transparent text-muted-foreground hover:text-foreground'
            }`}
          >
            {tab}
            {tab === 'connected' && connected.length > 0 && ` (${connected.length})`}
          </button>
        ))}
      </div>

      {/* Browse Tab */}
      {activeTab === 'browse' && (
        <div className="space-y-8">
          {Object.entries(grouped).map(([category, items]) => (
            <div key={category}>
              <h2 className="text-sm font-semibold text-muted-foreground uppercase tracking-wider mb-3">
                {category}
              </h2>
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                {items.map((item) =>
                  item.connected ? (
                    <Link
                      key={item.connectorId}
                      href={`/app/projects/${projectId}/integrations/${item.connectorId}`}
                      className="bg-card rounded-lg border border-border p-4 flex items-center gap-4 hover:border-primary/50 transition-colors"
                    >
                      <div className="h-10 w-10 rounded-md bg-muted flex items-center justify-center text-sm font-bold text-foreground flex-shrink-0">
                        {item.iconLabel}
                      </div>
                      <div className="flex-1 min-w-0">
                        <div className="font-medium text-sm text-foreground">{item.name}</div>
                        <div className="text-xs text-muted-foreground truncate">{item.description}</div>
                      </div>
                      <span className="flex items-center gap-1 text-xs text-green-600 dark:text-green-400 font-medium flex-shrink-0">
                        <CheckCircleIcon className="h-3.5 w-3.5" />
                        Connected
                      </span>
                    </Link>
                  ) : (
                    <button
                      key={item.connectorId}
                      onClick={() =>
                        item.authType === 'OAUTH2' ? handleOAuth(item) : openConnectModal(item)
                      }
                      className="bg-card rounded-lg border border-border p-4 flex items-center gap-4 text-left hover:border-primary/50 transition-colors cursor-pointer w-full"
                    >
                      <div className="h-10 w-10 rounded-md bg-muted flex items-center justify-center text-sm font-bold text-foreground flex-shrink-0">
                        {item.iconLabel}
                      </div>
                      <div className="flex-1 min-w-0">
                        <div className="font-medium text-sm text-foreground">{item.name}</div>
                        <div className="text-xs text-muted-foreground truncate">{item.description}</div>
                      </div>
                      <span className="text-xs font-medium text-primary flex-shrink-0">
                        {item.authType === 'OAUTH2' ? 'Authorize' : 'Add'}
                      </span>
                    </button>
                  )
                )}
              </div>
            </div>
          ))}
          {integrations.length === 0 && (
            <div className="text-center py-12 text-muted-foreground">
              <PuzzleIcon className="h-10 w-10 mx-auto mb-4" />
              <p className="text-sm">No integrations available.</p>
            </div>
          )}
        </div>
      )}

      {/* Connected Tab */}
      {activeTab === 'connected' && (
        <div>
          {connected.length === 0 ? (
            <div className="text-center py-12 text-muted-foreground">
              <p className="text-sm mb-4">No integrations connected yet.</p>
              <button
                onClick={() => setActiveTab('browse')}
                className="text-sm text-primary hover:underline"
              >
                Browse available integrations →
              </button>
            </div>
          ) : (
            <div className="space-y-3">
              {connected.map((item) => (
                <div
                  key={item.connectorId}
                  className="relative bg-card rounded-lg border border-border p-4 flex items-center gap-4 hover:border-primary/50 transition-colors"
                >
                  <Link
                    href={`/app/projects/${projectId}/integrations/${item.connectorId}`}
                    className="absolute inset-0 rounded-lg"
                    aria-label={`View ${item.name} data`}
                  />
                  <div className="h-10 w-10 rounded-md bg-muted flex items-center justify-center text-sm font-bold text-foreground flex-shrink-0">
                    {item.iconLabel}
                  </div>
                  <div className="flex-1 min-w-0">
                    <div className="font-medium text-sm text-foreground">{item.name}</div>
                    <div className="text-xs text-muted-foreground">
                      {item.authType === 'API_KEY' ? '••••••• (API Key)' : 'OAuth2 token'}
                      {item.fetchedAt &&
                        ` · Last synced ${new Date(item.fetchedAt).toLocaleDateString()}`}
                    </div>
                  </div>
                  <button
                    onClick={(e) => { e.stopPropagation(); handleDisconnect(item.connectorId); }}
                    disabled={disconnecting === item.connectorId}
                    className="relative z-10 text-xs font-medium text-destructive hover:underline disabled:opacity-50 flex-shrink-0"
                  >
                    {disconnecting === item.connectorId ? 'Removing…' : 'Remove'}
                  </button>
                </div>
              ))}
            </div>
          )}
        </div>
      )}

      {/* Connect Modal (API Key) */}
      {connectModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50">
          <div className="bg-card rounded-xl border border-border shadow-xl p-6 w-full max-w-md mx-4">
            <h2 className="text-lg font-semibold text-foreground mb-1">
              Connect {connectModal.connector.name}
            </h2>
            <p className="text-sm text-muted-foreground mb-5">{connectModal.connector.description}</p>
            <form onSubmit={handleConnect} className="space-y-4">
              {connectModal.connector.configFields.map((field) => (
                <div key={field.fieldKey}>
                  <label className="block text-sm font-medium text-foreground mb-1">
                    {field.label}
                  </label>
                  <input
                    type={field.secret ? 'password' : 'text'}
                    value={formValues[field.fieldKey] || ''}
                    onChange={(e) =>
                      setFormValues((prev) => ({ ...prev, [field.fieldKey]: e.target.value }))
                    }
                    placeholder={field.hint || ''}
                    required
                    className="w-full rounded-md border border-input bg-background px-3 py-2 text-sm placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                  />
                </div>
              ))}
              {connectError && <p className="text-sm text-destructive">{connectError}</p>}
              <div className="flex gap-3 pt-1">
                <button
                  type="button"
                  onClick={() => setConnectModal(null)}
                  className="flex-1 rounded-md border border-border px-4 py-2 text-sm font-medium text-foreground hover:bg-muted"
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  disabled={connecting}
                  className="flex-1 rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90 disabled:opacity-50"
                >
                  {connecting ? 'Connecting…' : 'Connect'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
}
