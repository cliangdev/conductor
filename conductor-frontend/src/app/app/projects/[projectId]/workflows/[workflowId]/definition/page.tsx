'use client';

import { useState } from 'react';
import { useParams, useRouter } from 'next/navigation';
import { useAuth } from '@/contexts/AuthContext';
import { apiPut, apiErrorMessage } from '@/lib/api';
import { WorkflowDefinitionDto } from '@/types/workflow';
import { useWorkflow } from '@/contexts/WorkflowContext';
import { useCan } from '@/contexts/PermissionsContext';
import WorkflowEditorLayout from '@/components/workflow/WorkflowEditorLayout';
import { WorkflowWebhookInfo } from '@/components/workflow/WorkflowWebhookInfo';
import { WorkflowDangerZone } from '@/components/workflow/WorkflowDangerZone';
import { Skeleton } from '@/components/ui/skeleton';
import { useToast } from '@/components/ui/toast';
import { Can } from '@/components/auth/Can';

/**
 * The canonical view of *what this workflow is* — YAML + live diagram, plus the workflow's
 * webhook URL (when it has a webhook trigger) and its Danger zone (delete). Editable for
 * `workflow.manage`; read-only (but still fully visible — diagram included) for everyone else.
 * Enable/Disable lives in the page header (see layout.tsx's WorkflowEnabledToggle), not here.
 */
export default function WorkflowDefinitionPage() {
  const { projectId, workflowId } = useParams<{ projectId: string; workflowId: string }>();
  const { accessToken } = useAuth();
  const router = useRouter();
  const { showToast } = useToast();
  const { workflow, setWorkflow } = useWorkflow();
  const canManage = useCan('workflow.manage');
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  if (!workflow) {
    return <Skeleton className="h-[calc(100vh-260px)] min-h-[520px] rounded-lg" />;
  }

  const handleSave = async (name: string, tag: string, yaml: string) => {
    if (!accessToken) return;
    setSaving(true);
    setError(null);
    try {
      const updated = await apiPut<WorkflowDefinitionDto>(
        `/api/v1/projects/${projectId}/workflows/${workflowId}`,
        { name, tag, yaml },
        accessToken
      );
      if (updated) setWorkflow(updated);
      showToast('Workflow saved.', 'success');
    } catch (e: unknown) {
      const message = apiErrorMessage(e, 'Failed to save workflow');
      setError(message);
      showToast(message, 'error');
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="space-y-6">
      <WorkflowEditorLayout
        embedded
        readOnly={!canManage}
        initialYaml={workflow.yaml ?? ''}
        initialName={workflow.name}
        initialTag={workflow.tag ?? ''}
        onSave={handleSave}
        onDiscard={() => router.push(`/app/projects/${projectId}/workflows/${workflowId}/runs`)}
        saving={saving}
        error={error}
      />

      <WorkflowWebhookInfo workflow={workflow} />

      <Can do="workflow.manage">
        <WorkflowDangerZone workflow={workflow} />
      </Can>
    </div>
  );
}
