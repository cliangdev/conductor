'use client';

import { useState } from 'react';
import { useParams, useRouter } from 'next/navigation';
import { useAuth } from '@/contexts/AuthContext';
import { apiPost, apiErrorMessage } from '@/lib/api';
import WorkflowEditorLayout from '@/components/workflow/WorkflowEditorLayout';

const DEFAULT_YAML = `name: my-workflow
on:
  workflow_dispatch: {}

jobs:
  example:
    steps:
      - name: Example step
        type: http
        method: GET
        url: https://httpbin.org/get
`;

export default function NewWorkflowPage() {
  const { projectId } = useParams<{ projectId: string }>();
  const { accessToken } = useAuth();
  const router = useRouter();
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const handleSave = async (name: string, yaml: string) => {
    if (!accessToken) return;
    setSaving(true);
    setError(null);
    try {
      await apiPost(`/api/v1/projects/${projectId}/workflows`, { name, yaml }, accessToken);
      router.push(`/app/projects/${projectId}/settings/workflows`);
    } catch (e: unknown) {
      setError(apiErrorMessage(e, 'Failed to save workflow'));
    } finally {
      setSaving(false);
    }
  };

  const handleDiscard = () => {
    router.push(`/app/projects/${projectId}/settings/workflows`);
  };

  return (
    <WorkflowEditorLayout
      title="New Workflow"
      breadcrumbs={[
        { label: 'Settings', href: `/app/projects/${projectId}/settings/general` },
        { label: 'Workflows', href: `/app/projects/${projectId}/settings/workflows` },
        { label: 'New' },
      ]}
      initialYaml={DEFAULT_YAML}
      onSave={handleSave}
      onDiscard={handleDiscard}
      saving={saving}
      error={error}
    />
  );
}
