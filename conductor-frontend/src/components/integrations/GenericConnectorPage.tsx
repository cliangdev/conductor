'use client';

import { useEffect, useState, useCallback } from 'react';
import { useAuth } from '@/contexts/AuthContext';
import {
  listIntegrations,
  createConnection,
  deleteConnection,
  apiPost,
  apiErrorMessage,
  type IntegrationListItem,
} from '@/lib/api';
import { parseServiceAccountKey } from '@/lib/serviceAccountKey';
import { ServiceAccountKeyField } from './ServiceAccountKeyField';
import { ConnectorIcon } from './ConnectorIcon';
import { PageHeader } from '@/components/layout/PageHeader';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Select } from '@/components/ui/select';
import { useCan } from '@/contexts/PermissionsContext';
import { CheckCircleIcon } from 'lucide-react';

/**
 * Fallback overview page for any connector without a bespoke dashboard (e.g. action-only connectors
 * like Discord, which have no analytics to visualize). Renders connection status and a generic
 * connect/disconnect flow driven entirely by the catalog metadata from `GET /integrations` — no
 * connector-specific code needed, so a newly registered connector never lands on "Unknown connector".
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
  const [item, setItem] = useState<IntegrationListItem | null | undefined>(undefined);
  const [formValues, setFormValues] = useState<Record<string, string>>({});
  const [jsonFieldErrors, setJsonFieldErrors] = useState<Record<string, string>>({});
  const [connecting, setConnecting] = useState(false);
  const [connectError, setConnectError] = useState<string | null>(null);
  const [disconnecting, setDisconnecting] = useState<string | null>(null);

  const load = useCallback(async () => {
    if (!accessToken) return;
    try {
      const all = await listIntegrations(projectId, accessToken);
      setItem(all.find((i) => i.connectorId === connectorId) ?? null);
    } catch (e) {
      console.error(e);
      setItem(null);
    }
  }, [projectId, connectorId, accessToken]);

  // Reset to the loading state on connectorId change — App Router reuses this component across
  // sibling connector routes, so without this the previous connector's data would flash first.
  useEffect(() => { setItem(undefined); load(); }, [connectorId, load]);

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

    const inputFields = item.configFields.filter((f) => f.source === 'USER_INPUT');
    const secretField = inputFields.find((f) => f.secret);
    const secretValue = formValues[secretField?.key || 'apiKey'] || formValues['apiKey'];
    const configJson: Record<string, string> = {};
    inputFields
      .filter((f) => !f.secret)
      .forEach((f) => {
        if (formValues[f.key]) configJson[f.key] = formValues[f.key];
      });

    if (secretField?.type === 'JSON') {
      const parsed = parseServiceAccountKey(secretValue || '');
      if (!parsed.valid) {
        setConnectError(parsed.error ?? 'Invalid key');
        return;
      }
    }

    setConnecting(true);
    setConnectError(null);
    try {
      await createConnection(
        projectId,
        item.connectorId,
        item.authType === 'SERVICE_ACCOUNT'
          ? { serviceAccountKey: secretValue, configJson }
          : { apiKey: secretValue, configJson },
        accessToken
      );
      setFormValues({});
      setJsonFieldErrors({});
      await load();
    } catch (err: unknown) {
      setConnectError(apiErrorMessage(err, 'Connection failed'));
    } finally {
      setConnecting(false);
    }
  };

  const handleOAuth = async () => {
    if (!accessToken) return;
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
      await load();
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

  const connectForm = (
    <form onSubmit={handleConnect} className="space-y-4">
      {item.configFields
        .filter((field) => field.source === 'USER_INPUT')
        .map((field) => (
          <div key={field.key}>
            {field.type === 'JSON' ? (
              <ServiceAccountKeyField
                label={field.label}
                hint={field.hint}
                required={field.required}
                value={formValues[field.key] || ''}
                error={jsonFieldErrors[field.key] || null}
                onChange={(text) => applyJsonField(field.key, text)}
              />
            ) : field.type === 'SELECT' ? (
              <>
                <Label>{field.label}</Label>
                <Select
                  value={formValues[field.key] || ''}
                  onChange={(e) => setFormValues((prev) => ({ ...prev, [field.key]: e.target.value }))}
                  required={field.required}
                >
                  <option value="" disabled>{field.hint || 'Select…'}</option>
                </Select>
              </>
            ) : (
              <>
                <Label>{field.label}</Label>
                <Input
                  type={field.secret ? 'password' : 'text'}
                  value={formValues[field.key] || ''}
                  onChange={(e) => setFormValues((prev) => ({ ...prev, [field.key]: e.target.value }))}
                  placeholder={field.hint || ''}
                  required={field.required}
                />
              </>
            )}
          </div>
        ))}
      <Button type="submit" disabled={connecting}>
        {connecting ? 'Connecting…' : 'Connect'}
      </Button>
    </form>
  );

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
            <div
              key={conn.id}
              className="bg-card rounded-lg border border-border p-4 flex items-center gap-4"
            >
              <ConnectorIcon connectorId={item.connectorId} iconLabel={item.iconLabel} className="h-8 w-8" />
              <div className="flex-1 min-w-0">
                <div className="text-sm font-medium text-foreground truncate">
                  {conn.label || item.name}
                </div>
                <div className="text-xs text-muted-foreground flex items-center gap-1">
                  {conn.status === 'ACTIVE' ? (
                    <>
                      <CheckCircleIcon className="h-3.5 w-3.5 text-status-done" />
                      Connected
                    </>
                  ) : (
                    conn.status
                  )}
                </div>
              </div>
              {canMutate && (
                <Button
                  type="button"
                  size="sm"
                  variant="ghost"
                  onClick={() => handleDisconnect(conn.id)}
                  disabled={disconnecting === conn.id}
                  className="text-destructive hover:text-destructive hover:bg-destructive/10"
                >
                  {disconnecting === conn.id ? 'Disconnecting…' : 'Disconnect'}
                </Button>
              )}
            </div>
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
            connectForm
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
