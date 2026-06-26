'use client';

import { useEffect, useState } from 'react';
import { useParams, useRouter } from 'next/navigation';
import Link from 'next/link';
import { useAuth } from '@/contexts/AuthContext';
import { apiGet, apiPost } from '@/lib/api';
import { WorkflowDefinitionDto, WorkflowRunDto } from '@/types/workflow';
import { PageContainer } from '@/components/layout/PageContainer';
import { PageHeader } from '@/components/layout/PageHeader';
import { TriggerBadges } from '@/components/workflow/TriggerBadges';

const STATUS_COLORS: Record<string, string> = {
  SUCCESS: 'bg-green-100 text-green-800',
  FAILED: 'bg-red-100 text-red-800',
  RUNNING: 'bg-yellow-100 text-yellow-800',
  PENDING: 'bg-gray-100 text-gray-600',
  CANCELLED: 'bg-gray-100 text-gray-600',
};

const STATUS_ICONS: Record<string, string> = {
  SUCCESS: '✓',
  FAILED: '✗',
  RUNNING: '◎',
  PENDING: '○',
  CANCELLED: '○',
};

function timeAgo(dateStr: string): string {
  const diff = Date.now() - new Date(dateStr).getTime();
  const minutes = Math.floor(diff / 60000);
  if (minutes < 60) return `${minutes}m ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}h ago`;
  return `${Math.floor(hours / 24)}d ago`;
}

export default function WorkflowsPage() {
  const { projectId } = useParams<{ projectId: string }>();
  const { accessToken } = useAuth();
  const router = useRouter();
  const [workflows, setWorkflows] = useState<WorkflowDefinitionDto[]>([]);
  const [lastRuns, setLastRuns] = useState<Record<string, WorkflowRunDto | null>>({});
  const [loading, setLoading] = useState(true);
  const [runningId, setRunningId] = useState<string | null>(null);

  useEffect(() => {
    if (!accessToken) return;
    apiGet<WorkflowDefinitionDto[]>(`/api/v1/projects/${projectId}/workflows`, accessToken)
      .then(async (wfs) => {
        setWorkflows(wfs);
        const runEntries = await Promise.all(
          wfs.map(async (wf) => {
            try {
              const runs = await apiGet<WorkflowRunDto[]>(
                `/api/v1/projects/${projectId}/workflows/${wf.id}/runs?page=0&size=1`,
                accessToken
              );
              return [wf.id, runs[0] ?? null] as const;
            } catch {
              return [wf.id, null] as const;
            }
          })
        );
        setLastRuns(Object.fromEntries(runEntries));
      })
      .finally(() => setLoading(false));
  }, [projectId, accessToken]);

  const handleRun = async (workflow: WorkflowDefinitionDto) => {
    if (!accessToken) return;
    setRunningId(workflow.id);
    try {
      const run = await apiPost<WorkflowRunDto>(
        `/api/v1/projects/${projectId}/workflows/${workflow.id}/dispatch`,
        {},
        accessToken
      );
      router.push(`/app/projects/${projectId}/workflows/${workflow.id}/runs/${run.id}`);
    } finally {
      setRunningId(null);
    }
  };

  if (loading)
    return (
      <PageContainer>
        <PageHeader title="Workflows" description="Run automations and review their history." />
        <div className="text-muted-foreground">Loading...</div>
      </PageContainer>
    );

  return (
    <PageContainer>
      <PageHeader
        title="Workflows"
        description="Run automations and review their history."
        actions={
          <Link
            href={`/app/projects/${projectId}/settings/workflows`}
            className="text-sm text-muted-foreground hover:text-foreground"
          >
            Manage workflows →
          </Link>
        }
      />

      {workflows.length === 0 ? (
        <div className="text-center py-12 text-muted-foreground">
          No workflows yet.{' '}
          <Link
            href={`/app/projects/${projectId}/settings/workflows/new`}
            className="text-primary hover:underline"
          >
            Create one in Settings →
          </Link>
        </div>
      ) : (
        <div className="border rounded-lg overflow-x-auto">
          <table className="w-full min-w-[640px]">
            <thead className="bg-muted/50">
              <tr>
                <th className="text-left p-3 font-medium">Name</th>
                <th className="text-left p-3 font-medium">Last Run</th>
                <th className="text-left p-3 font-medium">Triggers</th>
                <th className="text-left p-3 font-medium">Status</th>
                <th className="p-3 font-medium w-24" />
              </tr>
            </thead>
            <tbody>
              {workflows.map(workflow => {
                const lastRun = lastRuns[workflow.id];
                return (
                  <tr
                    key={workflow.id}
                    className="border-t hover:bg-muted/25 cursor-pointer"
                    onClick={() => router.push(`/app/projects/${projectId}/workflows/${workflow.id}`)}
                  >
                    <td className="p-3 font-medium">{workflow.name}</td>
                    <td className="p-3">
                      {lastRun ? (
                        <div className="flex items-center gap-1.5">
                          <span className={`inline-flex items-center gap-1 px-2 py-0.5 rounded text-xs font-medium ${STATUS_COLORS[lastRun.status] ?? ''}`}>
                            {STATUS_ICONS[lastRun.status]} {lastRun.status}
                          </span>
                          <span className="text-xs text-muted-foreground">{timeAgo(lastRun.startedAt)}</span>
                        </div>
                      ) : (
                        <span className="text-xs text-muted-foreground">—</span>
                      )}
                    </td>
                    <td className="p-3">
                      <TriggerBadges yaml={workflow.yaml} />
                    </td>
                    <td className="p-3">
                      <span className={`inline-flex items-center gap-1 text-xs ${workflow.enabled ? 'text-green-600' : 'text-muted-foreground'}`}>
                        <span className={`inline-block w-1.5 h-1.5 rounded-full ${workflow.enabled ? 'bg-green-500' : 'bg-gray-400'}`} />
                        {workflow.enabled ? 'Enabled' : 'Disabled'}
                      </span>
                    </td>
                    <td className="p-3 text-right" onClick={e => e.stopPropagation()}>
                      <button
                        onClick={() => handleRun(workflow)}
                        disabled={!workflow.enabled || runningId === workflow.id}
                        className="text-sm font-medium text-primary hover:underline disabled:opacity-40 disabled:no-underline disabled:cursor-not-allowed"
                      >
                        {runningId === workflow.id ? 'Starting…' : '▶ Run'}
                      </button>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </PageContainer>
  );
}
