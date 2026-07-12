'use client';

import { useEffect, useState } from 'react';
import {
  apiErrorMessage,
  deleteProviderCredential,
  getProviderCredentialStatus,
  setProviderCredential,
} from '@/lib/api';
import { useAuth } from '@/contexts/AuthContext';
import { useCan } from '@/contexts/PermissionsContext';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { useToast } from '@/components/ui/toast';

// The Claude Code CLI's subscription OAuth token — a project-level credential consumed by
// `claude-code` workflow steps on every Cloud Run runtime (the built-in `runs-on: cloud-run`
// and named runtime targets). Not a model provider: agent steps can't select it, so it lives
// here rather than under Agents → Providers. Stored via the generic provider-credential
// endpoints under this provider id.
const CLAUDE_CODE_PROVIDER_ID = 'claude-code';

export default function ClaudeCodeCredentialPanel({ projectId }: { projectId: string }) {
  const { accessToken } = useAuth();
  const canMutate = useCan('integration.manage');
  const { showToast } = useToast();

  const [configured, setConfigured] = useState(false);
  const [loading, setLoading] = useState(true);
  const [draft, setDraft] = useState('');
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (!accessToken || !projectId) return;
    let cancelled = false;
    getProviderCredentialStatus(projectId, CLAUDE_CODE_PROVIDER_ID, accessToken)
      .then((s) => { if (!cancelled) setConfigured(s.configured); })
      .catch(() => { if (!cancelled) setConfigured(false); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [projectId, accessToken]);

  async function handleSave() {
    if (!accessToken) return;
    const token = draft.trim();
    if (!token) return;
    setBusy(true);
    try {
      await setProviderCredential(projectId, CLAUDE_CODE_PROVIDER_ID, token, accessToken);
      setConfigured(true);
      setDraft('');
      showToast('Subscription token saved.', 'success');
    } catch (e) {
      showToast(apiErrorMessage(e, 'Failed to save subscription token.'), 'error');
    } finally {
      setBusy(false);
    }
  }

  async function handleRemove() {
    if (!accessToken) return;
    setBusy(true);
    try {
      await deleteProviderCredential(projectId, CLAUDE_CODE_PROVIDER_ID, accessToken);
      setConfigured(false);
      showToast('Subscription token removed.', 'success');
    } catch (e) {
      showToast(apiErrorMessage(e, 'Failed to remove subscription token.'), 'error');
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="space-y-4">
      <div>
        <div className="flex items-center gap-2">
          <h2 className="text-base font-semibold text-foreground">Claude Code (subscription)</h2>
          {!loading && (
            configured ? (
              <Badge variant="status-approved">Configured</Badge>
            ) : (
              <Badge variant="outline">Not configured</Badge>
            )
          )}
        </div>
        <p className="text-sm text-muted-foreground">
          Powers <code className="font-mono text-xs">claude-code</code> steps on every Cloud Run
          runtime — the built-in <code className="font-mono text-xs">runs-on: cloud-run</code> and
          your runtime targets below.
        </p>
      </div>

      <div className="bg-card rounded-lg border border-border p-4 space-y-2">
        <p className="text-xs text-muted-foreground">
          Paste the output of <code className="font-mono">claude setup-token</code> — billed against
          your Claude Pro/Max plan. Stored encrypted, never displayed after saving.
        </p>
        {canMutate ? (
          <div className="flex gap-2">
            <input
              type="password"
              className="w-full rounded-md border border-input bg-background px-3 py-2 text-sm placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring"
              value={draft}
              onChange={(e) => setDraft(e.target.value)}
              placeholder={configured ? '•••••••• (set — enter a new token to replace)' : 'Enter subscription token'}
              autoComplete="off"
            />
            <Button type="button" onClick={handleSave} disabled={busy || !draft.trim()}>
              {configured ? 'Replace' : 'Save'}
            </Button>
            {configured && (
              <Button type="button" variant="outline" onClick={handleRemove} disabled={busy}>
                Remove
              </Button>
            )}
          </div>
        ) : (
          <p className="text-xs text-muted-foreground">
            Only admins and creators can manage this credential.
          </p>
        )}
      </div>
    </div>
  );
}
