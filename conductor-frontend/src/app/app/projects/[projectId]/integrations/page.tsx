'use client';

export const dynamic = 'force-dynamic';

import { useEffect, useState } from 'react';
import { useParams } from 'next/navigation';
import { useAuth } from '@/contexts/AuthContext';
import { apiPost, createConnection, deleteConnection, apiErrorMessage, listIntegrations } from '@/lib/api';
import type { IntegrationListItem } from '@/lib/api';
import { parseServiceAccountKey } from '@/lib/serviceAccountKey';
import { buildConnectionPayload } from '@/lib/connectorConnectForm';
import { ConnectorConfigFields } from '@/components/integrations/ConnectorConfigFields';
import { usePermissions } from '@/contexts/PermissionsContext';
import { useToast } from '@/components/ui/toast';
import Link from 'next/link';
import { PuzzleIcon, CheckCircleIcon, ChevronRightIcon } from 'lucide-react';
import { ConnectorIcon } from '@/components/integrations/ConnectorIcon';
import { ConnectorCard } from '@/components/integrations/ConnectorCard';
import { PageContainer } from '@/components/layout/PageContainer';
import { PageHeader } from '@/components/layout/PageHeader';
import { Button } from '@/components/ui/button';
import { Modal } from '@/components/ui/modal';
import { Tabs } from '@/components/ui/tabs';
import { Can } from '@/components/auth/Can';

type Tab = 'connected' | 'browse';

export default function IntegrationsPage() {
  const { projectId } = useParams<{ projectId: string }>();
  const { accessToken } = useAuth();
  const { showToast } = useToast();
  const { can } = usePermissions();
  const [integrations, setIntegrations] = useState<IntegrationListItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [activeTab, setActiveTab] = useState<Tab>('connected');

  // Modal state for API key connect (single-instance API_KEY/BASIC connectors)
  const [connectModal, setConnectModal] = useState<{ connector: IntegrationListItem } | null>(null);
  const [formValues, setFormValues] = useState<Record<string, string>>({});
  const [connecting, setConnecting] = useState(false);
  const [connectError, setConnectError] = useState<string | null>(null);
  // Inline validation errors for JSON-typed fields (e.g. a pasted GCP service-account key).
  const [jsonFieldErrors, setJsonFieldErrors] = useState<Record<string, string>>({});

  // Disconnect state
  const [disconnecting, setDisconnecting] = useState<string | null>(null);

  const canMutate = can('integration.manage');

  const loadIntegrations = async () => {
    if (!accessToken || !projectId) return;
    try {
      const data = await listIntegrations(projectId, accessToken);
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

  // A connector is managed on its own detail page when it holds multiple instances
  // (e.g. GitHub repos) or is already connected. Single-instance connectors connect inline.
  const usesDetailPage = (item: IntegrationListItem) => item.connected || !item.singleInstance;

  // An OAuth2 connector with no platform app behind it has nothing to consent to — starting the
  // flow from here would only redirect the user into a server error naming an environment
  // variable. Non-OAuth2 connectors carry no app credential at all, so they are never blocked.
  const blockedOnAppCredential = (item: IntegrationListItem) =>
    item.authType === 'OAUTH2' && item.appCredential?.credentialSource === 'NONE';

  const openConnectModal = (connector: IntegrationListItem) => {
    setConnectModal({ connector });
    setFormValues({});
    setConnectError(null);
    setJsonFieldErrors({});
  };

  const applyJsonField = (key: string, value: string) => {
    setFormValues((prev) => ({ ...prev, [key]: value }));
    const parsed = parseServiceAccountKey(value);
    setJsonFieldErrors((prev) => ({
      ...prev,
      [key]: value.trim() && !parsed.valid ? (parsed.error ?? 'Invalid key') : '',
    }));
  };

  const handleConnect = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!connectModal || !accessToken) return;

    const { connector } = connectModal;
    const payload = buildConnectionPayload(connector, formValues);
    if (!payload.ok) {
      setConnectError(payload.error);
      return;
    }

    setConnecting(true);
    setConnectError(null);
    try {
      await createConnection(projectId, connector.connectorId, payload.body, accessToken);
      setConnectModal(null);
      // Don't keep the pasted secret in component state past a successful connect.
      setFormValues({});
      setJsonFieldErrors({});
      await loadIntegrations();
    } catch (err: unknown) {
      setConnectError(apiErrorMessage(err, 'Connection failed'));
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
      showToast(apiErrorMessage(e, 'Could not start authorization. Please try again.'), 'error');
    }
  };

  const handleDisconnect = async (item: IntegrationListItem) => {
    if (!accessToken) return;
    const connectionId = item.connections[0]?.id;
    if (!connectionId) return;
    setDisconnecting(item.connectorId);
    try {
      await deleteConnection(projectId, item.connectorId, connectionId, accessToken);
      await loadIntegrations();
    } catch (e) {
      showToast(apiErrorMessage(e, 'Failed to disconnect. Please try again.'), 'error');
    } finally {
      setDisconnecting(null);
    }
  };

  const Icon = ({ item }: { item: IntegrationListItem }) => (
    <ConnectorIcon connectorId={item.connectorId} iconLabel={item.iconLabel} className="h-10 w-10" />
  );

  const header = (
    <PageHeader
      title="Integrations"
      description="Connect third-party tools — reused across your workflows and agents."
      actions={
        <Can do="integration.manage">
          <Button onClick={() => setActiveTab('browse')}>Connect app</Button>
        </Can>
      }
    />
  );

  if (loading) {
    return (
      <PageContainer>
        {header}
        <div className="animate-pulse grid grid-cols-2 gap-4">
          {[1, 2, 3, 4].map((i) => (
            <div key={i} className="h-28 bg-muted rounded-lg" />
          ))}
        </div>
      </PageContainer>
    );
  }

  return (
    <PageContainer>
      {header}

      <Tabs
        className="mb-6"
        value={activeTab}
        onValueChange={(v) => setActiveTab(v as Tab)}
        items={[
          { value: 'connected', label: `Connected${connected.length > 0 ? ` (${connected.length})` : ''}` },
          { value: 'browse', label: 'Browse' },
        ]}
      />

      {/* Connected Tab */}
      {activeTab === 'connected' && (
        <div>
          {connected.length === 0 ? (
            <div className="bg-card rounded-lg border border-border p-12 text-center">
              <PuzzleIcon className="h-10 w-10 text-muted-foreground mx-auto mb-4" />
              <h2 className="text-lg font-medium text-foreground mb-2">No integrations connected yet</h2>
              <p className="text-sm text-muted-foreground mb-6">
                Connect your tools — billing, analytics, marketing — to see live metrics and power your workflows and agents.
              </p>
              <button
                onClick={() => setActiveTab('browse')}
                className="inline-flex items-center gap-1 text-sm font-medium text-primary hover:underline"
              >
                Browse available integrations
                <ChevronRightIcon className="h-3.5 w-3.5" />
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
                    aria-label={`View ${item.name}`}
                  />
                  <Icon item={item} />
                  <div className="flex-1 min-w-0">
                    <div className="font-medium text-sm text-foreground">{item.name}</div>
                    <div className="text-xs text-muted-foreground">
                      {!item.singleInstance
                        ? `${item.connections.length} ${item.connections.length === 1 ? 'connection' : 'connections'}`
                        : item.authType === 'API_KEY'
                          ? '••••••• (API Key)'
                          : item.authType === 'WEBHOOK'
                            ? 'Webhook'
                            : item.authType === 'SERVICE_ACCOUNT'
                              ? 'Service account'
                              : 'OAuth2'}
                    </div>
                  </div>
                  {/* Single-instance connectors can be removed inline; multi-instance are managed on the detail page. */}
                  {canMutate && item.singleInstance && (
                    <button
                      onClick={(e) => { e.stopPropagation(); handleDisconnect(item); }}
                      disabled={disconnecting === item.connectorId}
                      className="relative z-10 text-xs font-medium text-destructive hover:underline disabled:opacity-50 flex-shrink-0"
                    >
                      {disconnecting === item.connectorId ? 'Removing…' : 'Remove'}
                    </button>
                  )}
                </div>
              ))}
            </div>
          )}
        </div>
      )}

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
                  usesDetailPage(item) ? (
                    <ConnectorCard
                      key={item.connectorId}
                      icon={<Icon item={item} />}
                      name={item.name}
                      description={item.description}
                      href={`/app/projects/${projectId}/integrations/${item.connectorId}`}
                      trailing={
                        item.connected ? (
                          <span className="flex items-center gap-1 text-xs text-status-done font-medium flex-shrink-0">
                            <CheckCircleIcon className="h-3.5 w-3.5" />
                            {!item.singleInstance && item.connections.length > 0
                              ? `${item.connections.length} connected`
                              : 'Connected'}
                          </span>
                        ) : (
                          <span className="text-xs font-medium text-primary flex-shrink-0">Manage</span>
                        )
                      }
                    />
                  ) : canMutate ? (
                    <ConnectorCard
                      key={item.connectorId}
                      icon={<Icon item={item} />}
                      name={item.name}
                      description={item.description}
                      onClick={() => (item.authType === 'OAUTH2' ? handleOAuth(item) : openConnectModal(item))}
                      disabled={blockedOnAppCredential(item)}
                      disabledReason="No platform app is configured, so nobody can connect this yet."
                      trailing={
                        <span
                          className={
                            blockedOnAppCredential(item)
                              ? 'text-xs font-medium text-muted-foreground flex-shrink-0'
                              : 'text-xs font-medium text-primary flex-shrink-0'
                          }
                        >
                          {blockedOnAppCredential(item)
                            ? 'Unavailable'
                            : item.authType === 'OAUTH2'
                              ? 'Authorize'
                              : 'Add'}
                        </span>
                      }
                    />
                  ) : (
                    <ConnectorCard
                      key={item.connectorId}
                      icon={<Icon item={item} />}
                      name={item.name}
                      description={item.description}
                    />
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

      {/* Connect Modal (API Key / Basic) */}
      <Modal
        open={!!connectModal}
        onOpenChange={(o) => { if (!o) setConnectModal(null); }}
        title={connectModal ? `Connect ${connectModal.connector.name}` : ''}
        description={connectModal?.connector.description}
      >
        {connectModal && (
          <form onSubmit={handleConnect} className="space-y-4">
            <ConnectorConfigFields
              fields={connectModal.connector.configFields}
              formValues={formValues}
              setFormValues={setFormValues}
              jsonFieldErrors={jsonFieldErrors}
              applyJsonField={applyJsonField}
            />
            {connectError && <p className="text-sm text-destructive">{connectError}</p>}
            <div className="flex gap-3 pt-1">
              <Button type="button" variant="outline" className="flex-1" onClick={() => setConnectModal(null)}>
                Cancel
              </Button>
              <Button type="submit" className="flex-1" disabled={connecting}>
                {connecting ? 'Connecting…' : 'Connect'}
              </Button>
            </div>
          </form>
        )}
      </Modal>
    </PageContainer>
  );
}
