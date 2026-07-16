'use client';

import { useState } from 'react';
import { useParams, useRouter } from 'next/navigation';
import { useAuth } from '@/contexts/AuthContext';
import { apiPut, apiPatch, apiDelete, apiErrorMessage } from '@/lib/api';
import { WorkflowDefinitionDto } from '@/types/workflow';
import { useWorkflow } from '@/contexts/WorkflowContext';
import { usePermissions } from '@/contexts/PermissionsContext';
import WorkflowEditorLayout from '@/components/workflow/WorkflowEditorLayout';
import { Button } from '@/components/ui/button';
import { Modal } from '@/components/ui/modal';
import { Switch } from '@/components/ui/switch';
import { useToast } from '@/components/ui/toast';

export default function WorkflowSettingsPage() {
  const { projectId, workflowId } = useParams<{ projectId: string; workflowId: string }>();
  const { accessToken } = useAuth();
  const router = useRouter();
  const { showToast } = useToast();
  const { workflow, setWorkflow } = useWorkflow();
  const { can, loading: roleLoading } = usePermissions();
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [showDelete, setShowDelete] = useState(false);
  const [deleting, setDeleting] = useState(false);

  if (!workflow) return <div className="text-muted-foreground">Loading…</div>;

  if (!roleLoading && !can('workflow.manage')) {
    return (
      <p className="text-sm text-muted-foreground">
        You don&apos;t have permission to edit this workflow.
      </p>
    );
  }

  const handleSave = async (name: string, yaml: string) => {
    if (!accessToken) return;
    setSaving(true);
    setError(null);
    try {
      const updated = await apiPut<WorkflowDefinitionDto>(
        `/api/v1/projects/${projectId}/workflows/${workflowId}`,
        { name, yaml },
        accessToken
      );
      if (updated) setWorkflow(updated);
      showToast('Workflow saved.', 'success');
    } catch (e: unknown) {
      setError(apiErrorMessage(e, 'Failed to save workflow'));
    } finally {
      setSaving(false);
    }
  };

  const handleToggleEnabled = async () => {
    if (!accessToken) return;
    try {
      const updated = await apiPatch<WorkflowDefinitionDto>(
        `/api/v1/projects/${projectId}/workflows/${workflowId}/enabled`,
        { enabled: !workflow.enabled },
        accessToken
      );
      if (updated) setWorkflow(updated);
    } catch (e) {
      showToast(apiErrorMessage(e, `Couldn't ${workflow.enabled ? 'disable' : 'enable'} workflow — try again.`), 'error');
    }
  };

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
    <div className="space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-3 border rounded-lg p-4">
        <div className="flex items-center gap-3">
          <Switch
            checked={workflow.enabled}
            onCheckedChange={handleToggleEnabled}
            aria-label={workflow.enabled ? 'Disable workflow' : 'Enable workflow'}
          />
          <span className="text-sm font-medium">{workflow.enabled ? 'Enabled' : 'Disabled'}</span>
        </div>
        <Button
          variant="outline"
          className="text-destructive hover:text-destructive"
          onClick={() => setShowDelete(true)}
        >
          Delete workflow
        </Button>
      </div>

      <WorkflowEditorLayout
        embedded
        initialYaml={workflow.yaml ?? ''}
        initialName={workflow.name}
        onSave={handleSave}
        onDiscard={() => router.push(`/app/projects/${projectId}/workflows/${workflowId}/overview`)}
        saving={saving}
        error={error}
      />

      <Modal
        open={showDelete}
        onOpenChange={setShowDelete}
        title="Delete workflow"
      >
        <p className="text-sm text-foreground">
          Permanently delete <strong>{workflow.name}</strong>? Its run history will be removed and this cannot be undone.
        </p>
        <div className="flex gap-3 mt-4">
          <Button variant="destructive" onClick={handleDelete} disabled={deleting}>
            {deleting ? 'Deleting…' : 'Delete workflow'}
          </Button>
          <Button variant="outline" onClick={() => setShowDelete(false)} disabled={deleting}>
            Cancel
          </Button>
        </div>
      </Modal>
    </div>
  );
}
