'use client';

import { useCallback, useEffect, useState } from 'react';
import { useAuth } from '@/contexts/AuthContext';
import { apiGet, apiPut, apiErrorMessage } from '@/lib/api';
import { Alert } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { Label } from '@/components/ui/label';
import { Select } from '@/components/ui/select';
import { Skeleton } from '@/components/ui/skeleton';

interface OAuthAccount {
  id: string;
  label: string;
}

/**
 * Post-consent account picker. Some providers grant access to several publishable accounts at once
 * — a Meta grant covers every Facebook Page the user administers — so the connection exists and
 * holds the grant, but cannot publish until an admin says which account it maps to.
 *
 * The backend does the resolving: `GET …/oauth/accounts` enumerates the grant's accounts as
 * non-secret identity only (per-account credentials are deliberately withheld), and
 * `PUT …/oauth/account` runs the connector's completion hook for the choice, routing that account's
 * credential to the encrypted token slot and only its identifiers to the connection config.
 *
 * Deliberately connector-agnostic and hung off `GenericConnectorPage`, so a fourth social connector
 * needs no new page.
 */
export function OAuthAccountPicker({
  projectId,
  connectorId,
  connectionId,
  connectorName,
  onSelected,
  onDismiss,
}: {
  projectId: string;
  connectorId: string;
  connectionId: string;
  connectorName: string;
  onSelected: () => void | Promise<void>;
  onDismiss: () => void;
}) {
  const { accessToken } = useAuth();
  const [accounts, setAccounts] = useState<OAuthAccount[] | null>(null);
  const [selectedId, setSelectedId] = useState('');
  const [loadError, setLoadError] = useState<string | null>(null);
  const [saveError, setSaveError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  const basePath = `/api/v1/projects/${projectId}/integrations/${connectorId}/connections/${connectionId}/oauth`;

  const load = useCallback(async () => {
    if (!accessToken) return;
    setLoadError(null);
    try {
      const result = await apiGet<{ accounts?: OAuthAccount[] }>(`${basePath}/accounts`, accessToken);
      const found = result.accounts ?? [];
      setAccounts(found);
      setSelectedId(found[0]?.id ?? '');
    } catch (e) {
      setAccounts([]);
      setLoadError(apiErrorMessage(e, 'Could not list the accounts this authorization covers.'));
    }
  }, [accessToken, basePath]);

  useEffect(() => {
    load();
  }, [load]);

  const handleSelect = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!accessToken || !selectedId) return;
    setSaving(true);
    setSaveError(null);
    try {
      await apiPut(`${basePath}/account`, { accountId: selectedId }, accessToken);
      await onSelected();
    } catch (err) {
      setSaveError(apiErrorMessage(err, 'Could not finish connecting that account.'));
    } finally {
      setSaving(false);
    }
  };

  return (
    <section
      aria-labelledby="oauth-account-picker-heading"
      className="bg-card rounded-lg border border-border p-6 max-w-md mb-6"
    >
      <h2 id="oauth-account-picker-heading" className="text-base font-semibold text-foreground mb-1">
        Choose an account
      </h2>
      <p className="text-sm text-muted-foreground mb-4">
        You authorized {connectorName}. Pick which account this connection publishes to.
      </p>

      {accounts === null ? (
        <div className="space-y-3">
          <Skeleton className="h-8 w-full" />
          <Skeleton className="h-8 w-32" />
        </div>
      ) : loadError ? (
        <div className="space-y-3">
          <Alert variant="destructive">{loadError}</Alert>
          <Button type="button" variant="secondary" onClick={load}>
            Try again
          </Button>
        </div>
      ) : accounts.length === 0 ? (
        <div className="space-y-3">
          <Alert variant="warning">
            This authorization covers no publishable account. Re-authorize with an account that
            administers one, then try again.
          </Alert>
          <Button type="button" variant="secondary" onClick={onDismiss}>
            Dismiss
          </Button>
        </div>
      ) : (
        <form onSubmit={handleSelect} className="space-y-4">
          <div>
            <Label htmlFor="oauth-account">Account</Label>
            <Select
              id="oauth-account"
              value={selectedId}
              onChange={(e) => setSelectedId(e.target.value)}
              required
            >
              {accounts.map((account) => (
                <option key={account.id} value={account.id}>
                  {account.label}
                </option>
              ))}
            </Select>
          </div>
          {saveError && <p className="text-sm text-destructive">{saveError}</p>}
          <div className="flex items-center gap-2">
            <Button type="submit" disabled={saving}>
              {saving ? 'Connecting…' : 'Use this account'}
            </Button>
            <Button type="button" variant="ghost" onClick={onDismiss} disabled={saving}>
              Not now
            </Button>
          </div>
        </form>
      )}
    </section>
  );
}
