'use client';

import { useEffect, useState } from 'react';
import { useParams, useRouter } from 'next/navigation';
import { useAuth } from '@/contexts/AuthContext';
import { apiGet, apiPatch, apiPost } from '@/lib/api';
import { WorkflowDefinitionDto } from '@/types/workflow';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';

export default function WorkflowsPage() {
  const { projectId } = useParams<{ projectId: string }>();
  const { accessToken } = useAuth();
  const router = useRouter();
  const [workflows, setWorkflows] = useState<WorkflowDefinitionDto[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (!accessToken) return;
    apiGet<WorkflowDefinitionDto[]>(`/projects/${projectId}/workflows`, accessToken)
      .then(setWorkflows)
      .finally(() => setLoading(false));
  }, [projectId, accessToken]);

  const handleToggleEnabled = async (workflow: WorkflowDefinitionDto) => {
    if (!accessToken) return;
    const updated = await apiPatch<WorkflowDefinitionDto>(
      `/projects/${projectId}/workflows/${workflow.id}/enabled`,
      { enabled: !workflow.enabled },
      accessToken
    );
    setWorkflows(prev => prev.map(w => w.id === updated.id ? updated : w));
  };

  const handleDispatch = async (workflow: WorkflowDefinitionDto) => {
    if (!accessToken) return;
    await apiPost(`/projects/${projectId}/workflows/${workflow.id}/dispatch`, {}, accessToken);
    router.push(`/app/projects/${projectId}/workflows/${workflow.id}/runs`);
  };

  if (loading) return <div className="p-6">Loading...</div>;

  return (
    <div className="p-6">
      <div className="flex items-center justify-between mb-6">
        <h1 className="text-2xl font-semibold">Workflows</h1>
        <Button onClick={() => router.push(`/app/projects/${projectId}/workflows/new`)}>
          New Workflow
        </Button>
      </div>

      {workflows.length === 0 ? (
        <div className="text-center py-12 text-muted-foreground">
          No workflows yet. Create one to automate your project.
        </div>
      ) : (
        <div className="border rounded-lg overflow-hidden">
          <table className="w-full">
            <thead className="bg-muted/50">
              <tr>
                <th className="text-left p-3 font-medium">Name</th>
                <th className="text-left p-3 font-medium">Triggers</th>
                <th className="text-left p-3 font-medium">Enabled</th>
                <th className="text-left p-3 font-medium">Actions</th>
              </tr>
            </thead>
            <tbody>
              {workflows.map(workflow => (
                <tr key={workflow.id} className="border-t hover:bg-muted/25">
                  <td className="p-3">
                    <button
                      className="font-medium hover:underline text-left"
                      onClick={() => router.push(`/app/projects/${projectId}/workflows/${workflow.id}/runs`)}
                    >
                      {workflow.name}
                    </button>
                  </td>
                  <td className="p-3">
                    <TriggerBadges yaml={workflow.yaml} />
                  </td>
                  <td className="p-3">
                    <button
                      onClick={() => handleToggleEnabled(workflow)}
                      className={`relative inline-flex h-5 w-10 items-center rounded-full transition-colors ${
                        workflow.enabled ? 'bg-green-500' : 'bg-gray-300'
                      }`}
                    >
                      <span className={`inline-block h-4 w-4 transform rounded-full bg-white transition-transform ${
                        workflow.enabled ? 'translate-x-5' : 'translate-x-1'
                      }`} />
                    </button>
                  </td>
                  <td className="p-3 flex gap-2">
                    <Button
                      size="sm"
                      variant="outline"
                      onClick={() => router.push(`/app/projects/${projectId}/workflows/${workflow.id}/edit`)}
                    >
                      Edit
                    </Button>
                    <Button
                      size="sm"
                      onClick={() => handleDispatch(workflow)}
                      disabled={!workflow.enabled}
                    >
                      Run
                    </Button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

function TriggerBadges({ yaml }: { yaml: string }) {
  const triggers: string[] = [];
  if (yaml.includes('conductor.issue.status_changed')) triggers.push('issue');
  if (yaml.includes('webhook:')) triggers.push('webhook');
  if (yaml.includes('workflow_dispatch')) triggers.push('manual');
  return (
    <div className="flex gap-1 flex-wrap">
      {triggers.map(t => (
        <Badge key={t} variant="secondary" className="text-xs">{t}</Badge>
      ))}
    </div>
  );
}
