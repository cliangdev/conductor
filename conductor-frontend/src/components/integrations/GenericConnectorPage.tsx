'use client';

import { useState } from 'react';
import { useAuth } from '@/contexts/AuthContext';
import { createConnection, deleteConnection, apiPost, apiErrorMessage } from '@/lib/api';
import { buildConnectionPayload } from '@/lib/connectorConnectForm';
import { parseServiceAccountKey } from '@/lib/serviceAccountKey';
import { useConnectorCatalogItem } from './ConnectorCatalogContext';
import { ConnectorConfigFields } from './ConnectorConfigFields';
import { ConnectionRow } from './ConnectionRow';
import { PageHeader } from '@/components/layout/PageHeader';
import { Button } from '@/components/ui/button';
import { useCan } from '@/contexts/PermissionsContext';

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

  return (
    <div className="max-w-6xl mx-auto px-4 sm:px-6 lg:px-8 py-8">
      <PageHeader
        title={item.name}
        description={item.description}
      />

      {connectError && <p className="text-sm text-destructive mb-4">{connectError}</p>}

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
              disconnecting={disconnecting === conn.id}
              onDisconnect={handleDisconnect}
            />
          ))}
        </div>
      )}

      {(!item.singleInstance || item.connections.length === 0) && canMutate && (
        <div className="bg-card rounded-lg border border-border p-6 max-w-md">
          <h2 className="text-base font-semibold text-foreground mb-1">
            {item.connections.length > 0 ? 'Add another connection' : `Connect ${item.name}`}
          </h2>
          {item.authType === 'OAUTH2' ? (
            <Button type="button" onClick={handleOAuth}>Authorize</Button>
          ) : (
            <form onSubmit={handleConnect} className="space-y-4">
              <ConnectorConfigFields
                fields={item.configFields}
                formValues={formValues}
                setFormValues={setFormValues}
                jsonFieldErrors={jsonFieldErrors}
                applyJsonField={applyJsonField}
              />
              <Button type="submit" disabled={connecting}>
                {connecting ? 'Connecting…' : 'Connect'}
              </Button>
            </form>
          )}
        </div>
      )}

      <p className="text-xs text-muted-foreground mt-6">
        This connector doesn&apos;t have a dedicated dashboard yet. See the Tools tab for what it can
        do in workflows and agents.
      </p>
    </div>
  );
}
