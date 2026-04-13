'use client';

import { useEffect, useState } from 'react';
import { useParams, useRouter } from 'next/navigation';
import { useAuth } from '@/contexts/AuthContext';
import { apiGet, apiPut } from '@/lib/api';
import { WorkflowDefinitionDto } from '@/types/workflow';
import WorkflowEditorLayout from '@/components/workflow/WorkflowEditorLayout';

export default function EditWorkflowPage() {
  const { projectId, workflowId } = useParams<{ projectId: string; workflowId: string }>();
  const { accessToken } = useAuth();
  const router = useRouter();
  const [workflow, setWorkflow] = useState<WorkflowDefinitionDto | null>(null);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!accessToken) return;
    apiGet<WorkflowDefinitionDto>(`/api/v1/projects/${projectId}/workflows/${workflowId}`, accessToken)
      .then(setWorkflow);
  }, [projectId, workflowId, accessToken]);

  const handleSave = async (name: string, yaml: string) => {
    if (!accessToken) return;
    setSaving(true);
    setError(null);
    try {
      await apiPut(`/api/v1/projects/${projectId}/workflows/${workflowId}`, { name, yaml }, accessToken);
      router.push(`/app/projects/${projectId}/workflows`);
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : 'Failed to save workflow');
    } finally {
      setSaving(false);
    }
  };

  const handleDiscard = () => {
    router.push(`/app/projects/${projectId}/workflows`);
  };

  if (!workflow) return <div className="p-6">Loading...</div>;

  return (
    <WorkflowEditorLayout
      title={`Edit: ${workflow.name}`}
      initialYaml={workflow.yaml}
      initialName={workflow.name}
      onSave={handleSave}
      onDiscard={handleDiscard}
      saving={saving}
      error={error}
    />
  );
}
