'use client';

import { useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import { useAuth } from '@/contexts/AuthContext';
import { createConnection, deleteConnection, apiPost, apiErrorMessage, listRemoteMcpTools } from '@/lib/api';
import { buildConnectionPayload } from '@/lib/connectorConnectForm';
import { parseServiceAccountKey } from '@/lib/serviceAccountKey';
import { useConnectorCatalogItem } from './ConnectorCatalogContext';
import {
  ConnectorAppCredentialPanel,
  appCredentialOf,
  type ConnectorAppCredentialStatus,
} from './ConnectorAppCredentialPanel';
import { ConnectorConfigFields } from './ConnectorConfigFields';
import { ConnectionRow } from './ConnectionRow';
import { OAuthAccountPicker } from './OAuthAccountPicker';
import { PageHeader } from '@/components/layout/PageHeader';
import { Button } from '@/components/ui/button';
import { ConfirmModal } from '@/components/ui/confirm-modal';
import { useCan } from '@/contexts/PermissionsContext';
import { toastError } from '@/components/ui/toast';

/** Connectors that feed a Marketing overview channel -> where their numbers are shown. */
const OVERVIEW_CHANNELS: Record<string, { slug: string; label: string; retention?: string }> = {
  'app-store-connect': { slug: 'store', label: 'App Store', retention: 'Apple keeps only about 35 days of daily reports.' },
};

/**
 * Fallback overview page for any connector without a bespoke dashboard (e.g. action-only connectors
 * like Discord, which have no analytics to visualize). Renders connection status and a generic
 * connect/disconnect flow driven entirely by the catalog metadata from `GET /integrations` — no
 * connector-specific code needed, so a newly registered connector never lands on "Unknown connector".
 * The catalog entry itself comes from `ConnectorCatalogProvider` (in the surrounding layout), which
 * the breadcrumb also reads — so there's a single fetch per page view, not one per consumer.
 */
export default function GenericConnectorPage({
  projectId,
  connectorId,
}: {
  projectId: string;
  connectorId: string;
}) {
  const { accessToken } = useAuth();
  const canMutate = useCan('integration.manage');
  const { item, refetch } = useConnectorCatalogItem();
  const [formValues, setFormValues] = useState<Record<string, string>>({});
  const [jsonFieldErrors, setJsonFieldErrors] = useState<Record<string, string>>({});
  const [connecting, setConnecting] = useState(false);
  const [connectError, setConnectError] = useState<string | null>(null);
  const [disconnecting, setDisconnecting] = useState<string | null>(null);
  const [confirmingDisconnect, setConfirmingDisconnect] = useState<string | null>(null);
  const [pendingAccountConnectionId, setPendingAccountConnectionId] = useState<string | null>(null);
  // The catalog entry carries readiness, so it's on screen with the first paint; the panel's own
  // responses then take over. Derived during render rather than mirrored into state, so the Connect
  // affordance below is never gated on a stale value for a frame. Keyed by connector because the
  // App Router reuses this component across sibling connector routes — otherwise one connector's
  // edited credential would carry over to the next.
  const [credentialOverride, setCredentialOverride] = useState<
    { connectorId: string; status: ConnectorAppCredentialStatus } | null
  >(null);
  const appCredential =
    credentialOverride?.connectorId === connectorId
      ? credentialOverride.status
      : appCredentialOf(item);

  // Remote MCP connections: say how many tools each one offers, so a person can tell what they
  // connected without opening the Tools tab. A failing server just shows no count.
  const [toolCounts, setToolCounts] = useState<Record<string, number>>({});
  const connectionIds = (item?.connections ?? []).map((c) => c.id).join(',');
  useEffect(() => {
    if (connectorId !== 'remote-mcp' || !accessToken || !connectionIds) return;
    let cancelled = false;
    connectionIds.split(',').forEach((id) => {
      listRemoteMcpTools(projectId, id, accessToken)
        .then((r) => {
          if (!cancelled && !r.error) setToolCounts((prev) => ({ ...prev, [id]: r.toolCount }));
        })
        .catch(() => {});
    });
    return () => {
      cancelled = true;
    };
  }, [connectorId, projectId, accessToken, connectionIds]);

  // The OAuth callback parks a connection here (`?selectAccount=<connectionId>`) when the grant
  // covers several publishable accounts and an admin still has to pick one. Read after mount rather
  // than during render, so the server-rendered pass doesn't touch `window`.
  useEffect(() => {
    const connectionId = new URLSearchParams(window.location.search).get('selectAccount');
    if (connectionId) setPendingAccountConnectionId(connectionId);
  }, []);

  // The OAuth callback sends a refused or cancelled authorization back here as `?oauthError=<code>`.
  // Say so once, then drop the param so a reload doesn't repeat the toast.
  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    const oauthError = params.get('oauthError');
    if (!oauthError) return;
    toastError(
      oauthError === 'access_denied'
        ? "Authorization was cancelled or refused. If the platform's app is in testing mode, your account must be added as a tester."
        : `Authorization failed (${oauthError}). Please try again.`,
    );
    params.delete('oauthError');
    const rest = params.toString();
    window.history.replaceState(null, '', window.location.pathname + (rest ? `?${rest}` : ''));
  }, []);

  // Drop the marker from the URL too, so a reload (or a back-navigation) doesn't reopen a picker
  // for a choice that has already been made.
  const clearPendingAccount = useCallback(() => {
    setPendingAccountConnectionId(null);
    window.history.replaceState(null, '', window.location.pathname);
  }, []);

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
    if (!item || !accessToken) return;

    const payload = buildConnectionPayload(item, formValues);
    if (!payload.ok) {
      setConnectError(payload.error);
      return;
    }

    setConnecting(true);
    setConnectError(null);
    try {
      await createConnection(projectId, item.connectorId, payload.body, accessToken);
      setFormValues({});
      setJsonFieldErrors({});
      await refetch();
    } catch (err: unknown) {
      setConnectError(apiErrorMessage(err, 'Connection failed'));
    } finally {
      setConnecting(false);
    }
  };

  const handleOAuth = async () => {
    if (!accessToken || !item) return;
    try {
      const result = await apiPost<{ authorizationUrl: string }>(
        `/api/v1/projects/${projectId}/integrations/${connectorId}/oauth/authorize`,
        {},
        accessToken
      );
      window.location.href = result.authorizationUrl;
    } catch (e) {
      setConnectError(apiErrorMessage(e, 'Could not start authorization. Please try again.'));
    }
  };

  const handleDisconnect = async (connectionId: string) => {
    if (!accessToken || !item) return;
    setDisconnecting(connectionId);
    try {
      await deleteConnection(projectId, item.connectorId, connectionId, accessToken);
      await refetch();
    } catch (e) {
      setConnectError(apiErrorMessage(e, 'Failed to disconnect. Please try again.'));
    } finally {
      setDisconnecting(null);
      setConfirmingDisconnect(null);
    }
  };

  if (item === undefined) {
    return (
      <div className="max-w-6xl mx-auto px-4 sm:px-6 lg:px-8 py-8">
        <div className="animate-pulse space-y-4">
          <div className="h-8 bg-muted rounded w-32" />
          <div className="h-32 bg-muted rounded-lg" />
        </div>
      </div>
    );
  }

  if (item === null) {
    return (
      <div className="max-w-6xl mx-auto px-4 sm:px-6 lg:px-8 py-8">
        <div className="text-center py-12">
          <p className="text-muted-foreground">Unknown connector: {connectorId}</p>
        </div>
      </div>
    );
  }

  const showsConnectForm = (!item.singleInstance || item.connections.length === 0) && canMutate;
  const overviewChannel = OVERVIEW_CHANNELS[item.connectorId];
  const confirmingConnection = item.connections.find((c) => c.id === confirmingDisconnect);
  const confirmingName = confirmingConnection?.label || item.name;
  const errorShownInForm =showsConnectForm && item.authType !== 'OAUTH2';

  return (
    <div className="max-w-6xl mx-auto px-4 sm:px-6 lg:px-8 py-8">
      <PageHeader
        title={item.name}
        description={item.description}
      />

      {/* A refused connect (bad credentials) is shown inline in the form, next to the fields to fix. */}
      {connectError && !errorShownInForm && <p className="text-sm text-destructive mb-4">{connectError}</p>}

      {pendingAccountConnectionId && canMutate && (
        <OAuthAccountPicker
          projectId={projectId}
          connectorId={item.connectorId}
          connectionId={pendingAccountConnectionId}
          connectorName={item.name}
          onSelected={async () => {
            await refetch();
            clearPendingAccount();
          }}
          onDismiss={clearPendingAccount}
        />
      )}

      {appCredential && (
        <ConnectorAppCredentialPanel
          projectId={projectId}
          connectorId={item.connectorId}
          connectorName={item.name}
          status={appCredential}
          onChange={(status) => setCredentialOverride({ connectorId, status })}
        />
      )}

      {item.connections.length > 0 && (
        <div className="space-y-3 mb-6">
          {item.connections.map((conn) => (
            <ConnectionRow
              key={conn.id}
              connection={conn}
              connectorId={item.connectorId}
              connectorName={item.name}
              iconLabel={item.iconLabel}
              canMutate={canMutate}
              detail={
                toolCounts[conn.id] === undefined
                  ? null
                  : `${toolCounts[conn.id]} ${toolCounts[conn.id] === 1 ? 'tool' : 'tools'}`
              }
              disconnecting={disconnecting === conn.id}
              onDisconnect={setConfirmingDisconnect}
              onChooseAccount={setPendingAccountConnectionId}
            />
          ))}
        </div>
      )}

      {showsConnectForm && (
        <div className="bg-card rounded-lg border border-border p-6 max-w-md">
          <h2 className="text-base font-semibold text-foreground mb-1">
            {item.connections.length > 0 ? 'Add another connection' : `Connect ${item.name}`}
          </h2>
          {item.authType === 'OAUTH2' ? (
            <>
              {/* Without a platform app there is nothing to consent to — starting the flow anyway
                  only produces a server error naming an environment variable. */}
              <Button
                type="button"
                onClick={handleOAuth}
                disabled={appCredential?.credentialSource === 'NONE'}
              >
                Authorize
              </Button>
              {appCredential?.credentialSource === 'NONE' && (
                <p className="text-xs text-muted-foreground mt-2">
                  {appCredential.appOwnership === 'DEPLOYMENT_ONLY'
                    ? "Available once this deployment's platform app is configured."
                    : 'Available once the platform app credentials above are configured.'}
                </p>
              )}
            </>
          ) : (
            <form onSubmit={handleConnect} className="space-y-4">
              <ConnectorConfigFields
                fields={item.configFields}
                formValues={formValues}
                setFormValues={setFormValues}
                jsonFieldErrors={jsonFieldErrors}
                applyJsonField={applyJsonField}
              />
              {connectError && (
                <p role="alert" className="text-sm text-destructive">
                  {connectError}
                </p>
              )}
              <Button type="submit" disabled={connecting}>
                {connecting ? 'Connecting…' : 'Connect'}
              </Button>
            </form>
          )}
        </div>
      )}

      <ConfirmModal
        open={confirmingConnection !== undefined}
        title={`Disconnect ${confirmingName}?`}
        confirmLabel="Disconnect"
        busyLabel="Disconnecting…"
        busy={disconnecting !== null}
        onCancel={() => setConfirmingDisconnect(null)}
        onConfirm={() => confirmingConnection && handleDisconnect(confirmingConnection.id)}
      >
        <div className="space-y-2 text-sm text-muted-foreground">
          <p>
            {confirmingName} stops syncing, and all history stored for this connection is deleted from this
            workspace.
          </p>
          {overviewChannel && (
            <p>
              Its numbers leave the Marketing overview. History older than what the platform still keeps cannot be
              fetched again. {overviewChannel.retention}
            </p>
          )}
        </div>
      </ConfirmModal>

      {overviewChannel ? (
        <p className="text-sm text-muted-foreground mt-6">
          {item.name} numbers appear on the Marketing overview.{' '}
          <Link
            href={`/app/projects/${projectId}/marketing/overview/${overviewChannel.slug}`}
            className="text-primary hover:underline"
          >
            Open the {overviewChannel.label} overview
          </Link>
          . See the Tools tab for what this connector can do in workflows and agents.
        </p>
      ) : (
        <p className="text-xs text-muted-foreground mt-6">
          This connector doesn&apos;t have a dedicated dashboard yet. See the Tools tab for what it can
          do in workflows and agents.
        </p>
      )}
    </div>
  );
}
