'use client';

// Delete moved here from the header's overflow menu (now removed — see layout.tsx's
// WorkflowEnabledToggle) since it's rare/destructive/config, not a daily control. Follows the same
// "Danger zone" visual idiom as settings/general and agent settings, but keeps the shared
// ConfirmModal (not a hand-rolled Modal) — PR #339 deliberately upgraded workflow delete to it.

import { useState } from 'react';
import { useParams, useRouter } from 'next/navigation';
import { useAuth } from '@/contexts/AuthContext';
import { apiDelete, apiErrorMessage } from '@/lib/api';
import { Button } from '@/components/ui/button';
import { ConfirmModal } from '@/components/ui/confirm-modal';
import { useToast } from '@/components/ui/toast';
import type { WorkflowDefinitionDto } from '@/types/workflow';

export function WorkflowDangerZone({ workflow }: { workflow: WorkflowDefinitionDto }) {
  const { projectId, workflowId } = useParams<{ projectId: string; workflowId: string }>();
  const { accessToken } = useAuth();
  const router = useRouter();
  const { showToast } = useToast();
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [deleting, setDeleting] = useState(false);

  const handleDelete = async () => {
    if (!accessToken) return;
    setDeleting(true);
    try {
      await apiDelete(`/api/v1/projects/${projectId}/workflows/${workflowId}`, accessToken);
      router.push(`/app/projects/${projectId}/workflows`);
    } catch (e) {
      showToast(apiErrorMessage(e, 'Failed to delete workflow.'), 'error');
      setDeleting(false);
    }
  };

  return (
    <div>
      <h2 className="text-sm font-semibold text-destructive mb-3">Danger zone</h2>
      <div className="rounded-lg border border-destructive/30 divide-y divide-destructive/20">
        <div className="flex items-center justify-between p-4">
          <div>
            <p className="text-sm font-medium text-foreground">Delete workflow</p>
            <p className="text-xs text-muted-foreground">Permanently delete this workflow and its run history.</p>
          </div>
          <Button variant="destructive" size="sm" onClick={() => setConfirmDelete(true)}>
            Delete
          </Button>
        </div>
      </div>

      <ConfirmModal
        open={confirmDelete}
        title="Delete workflow"
        confirmLabel="Delete workflow"
        busyLabel="Deleting…"
        busy={deleting}
        onConfirm={handleDelete}
        onCancel={() => setConfirmDelete(false)}
      >
        <p className="text-sm text-foreground">
          Permanently delete <strong>{workflow.name}</strong>? Its run history will be removed and this cannot be undone.
        </p>
      </ConfirmModal>
    </div>
  );
}
