'use client';

import { useState } from 'react';
import { Modal } from '@/components/ui/modal';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { useAuth } from '@/contexts/AuthContext';
import { patchConnection, apiErrorMessage } from '@/lib/api';

export type ConfigPatch = { config: Record<string, string> } | { error: string };

/**
 * Edit a connection's settings and, optionally, replace its API key. The caller owns the field state
 * (children) and says how to turn it into a PATCH body; the server re-verifies the merged settings and
 * the new key before saving, and its refusal is shown here so nothing is silently half-saved.
 */
export function ConnectionSettingsDialog({
  open,
  onOpenChange,
  title,
  projectId,
  connectorId,
  connectionId,
  apiKeyLabel = 'API key',
  getConfigPatch,
  onSaved,
  children,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  title: string;
  projectId: string;
  connectorId: string;
  connectionId: string;
  apiKeyLabel?: string;
  getConfigPatch: () => ConfigPatch;
  onSaved: () => void;
  children: React.ReactNode;
}) {
  const { accessToken } = useAuth();
  const [apiKey, setApiKey] = useState('');
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const close = (o: boolean) => {
    if (!o) {
      setApiKey('');
      setError(null);
    }
    onOpenChange(o);
  };

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!accessToken) return;
    const patch = getConfigPatch();
    if ('error' in patch) {
      setError(patch.error);
      return;
    }
    setSaving(true);
    setError(null);
    try {
      await patchConnection(
        projectId,
        connectorId,
        connectionId,
        { config: patch.config, ...(apiKey.trim() ? { apiKey: apiKey.trim() } : {}) },
        accessToken,
      );
      setApiKey('');
      onSaved();
      onOpenChange(false);
    } catch (err: unknown) {
      setError(apiErrorMessage(err, 'Could not save the settings'));
    } finally {
      setSaving(false);
    }
  };

  return (
    <Modal open={open} onOpenChange={close} title={title}>
      <form onSubmit={submit} className="space-y-4">
        {children}
        <div>
          <Label htmlFor="connection-settings-api-key">Replace {apiKeyLabel}</Label>
          <Input
            id="connection-settings-api-key"
            type="password"
            autoComplete="off"
            value={apiKey}
            onChange={(e) => setApiKey(e.target.value)}
            placeholder="Leave empty to keep the current key"
          />
        </div>
        {error && <p className="text-sm text-destructive">{error}</p>}
        <div className="flex gap-3 pt-1">
          <Button type="button" variant="outline" className="flex-1" onClick={() => close(false)}>
            Cancel
          </Button>
          <Button type="submit" className="flex-1" disabled={saving}>
            {saving ? 'Saving…' : 'Save'}
          </Button>
        </div>
      </form>
    </Modal>
  );
}
