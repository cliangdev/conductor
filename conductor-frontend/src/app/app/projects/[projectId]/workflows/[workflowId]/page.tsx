'use client';

import { useEffect, useState } from 'react';
import { useParams, useRouter } from 'next/navigation';
import { useAuth } from '@/contexts/AuthContext';
import { apiGet, apiPost } from '@/lib/api';
import { WorkflowDefinitionDto, WorkflowRunDto } from '@/types/workflow';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import WorkflowDiagram from '@/components/workflow/WorkflowDiagram';

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

function formatDuration(startedAt: string, completedAt?: string): string {
  const start = new Date(startedAt).getTime();
  const end = completedAt ? new Date(completedAt).getTime() : Date.now();
  const seconds = Math.floor((end - start) / 1000);
  if (seconds < 60) return `${seconds}s`;
  return `${Math.floor(seconds / 60)}m ${seconds % 60}s`;
}

function timeAgo(dateStr: string): string {
  const diff = Date.now() - new Date(dateStr).getTime();
  const minutes = Math.floor(diff / 60000);
  if (minutes < 60) return `${minutes}m ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}h ago`;
  return `${Math.floor(hours / 24)}d ago`;
}

function formatDate(dateStr: string): string {
  return new Date(dateStr).toLocaleString('en-US', {
    month: 'short',
    day: 'numeric',
    hour: 'numeric',
    minute: '2-digit',
    hour12: true,
  });
}

function parseTriggers(yaml: string): string[] {
  const triggers: string[] = [];
  if (yaml.includes('conductor.issue.status_changed')) triggers.push('issue');
  if (yaml.includes('webhook:')) triggers.push('webhook');
  if (yaml.includes('workflow_dispatch')) triggers.push('manual');
  return triggers;
}

function computeStats(runs: WorkflowRunDto[]) {
  if (runs.length === 0) return null;

  const successCount = runs.filter(r => r.status === 'SUCCESS').length;
  const successRate = Math.round((successCount / runs.length) * 100);

  const completedRuns = runs.filter(r => r.startedAt && r.completedAt);
  const avgDurationSeconds = completedRuns.length > 0
    ? Math.round(
        completedRuns.reduce((sum, r) => {
          const dur = (new Date(r.completedAt!).getTime() - new Date(r.startedAt).getTime()) / 1000;
          return sum + dur;
        }, 0) / completedRuns.length
      )
    : null;

  const avgDurationStr = avgDurationSeconds !== null
    ? avgDurationSeconds < 60
      ? `${avgDurationSeconds}s`
      : `${Math.floor(avgDurationSeconds / 60)}m ${avgDurationSeconds % 60}s`
    : null;

  return {
    lastRun: runs[0],
    successRate,
    totalRuns: runs.length,
    avgDuration: avgDurationStr,
  };
}

export default function WorkflowDetailPage() {
  const { projectId, workflowId } = useParams<{ projectId: string; workflowId: string }>();
  const { accessToken } = useAuth();
  const router = useRouter();
  const [workflow, setWorkflow] = useState<WorkflowDefinitionDto | null>(null);
  const [runs, setRuns] = useState<WorkflowRunDto[]>([]);
  const [loading, setLoading] = useState(true);
  const [dispatching, setDispatching] = useState(false);

  useEffect(() => {
    if (!accessToken) return;
    Promise.all([
      apiGet<WorkflowDefinitionDto>(`/api/v1/projects/${projectId}/workflows/${workflowId}`, accessToken),
      apiGet<WorkflowRunDto[]>(`/api/v1/projects/${projectId}/workflows/${workflowId}/runs?page=0&size=5`, accessToken),
    ]).then(([wf, wfRuns]) => {
      setWorkflow(wf);
      setRuns(wfRuns);
    }).finally(() => setLoading(false));
  }, [projectId, workflowId, accessToken]);

  const handleRunNow = async () => {
    if (!accessToken || !workflow) return;
    setDispatching(true);
    try {
      const run = await apiPost<WorkflowRunDto>(
        `/api/v1/projects/${projectId}/workflows/${workflowId}/dispatch`,
        {},
        accessToken
      );
      router.push(`/app/projects/${projectId}/workflows/${workflowId}/runs/${run.id}`);
    } finally {
      setDispatching(false);
    }
  };

  if (loading) return <div className="p-6">Loading...</div>;
  if (!workflow) return <div className="p-6 text-muted-foreground">Workflow not found.</div>;

  const triggers = parseTriggers(workflow.yaml);
  const stats = computeStats(runs);

  return (
    <div className="p-6 max-w-6xl">
      <button
        className="text-sm text-muted-foreground hover:underline mb-4 block"
        onClick={() => router.push(`/app/projects/${projectId}/workflows`)}
      >
        ← Workflows
      </button>

      <div className="flex items-start justify-between mb-6">
        <div>
          <h1 className="text-2xl font-semibold">{workflow.name}</h1>
          <div className="flex items-center gap-2 mt-1 text-sm text-muted-foreground">
            <span className={`flex items-center gap-1 ${workflow.enabled ? 'text-green-600' : 'text-gray-400'}`}>
              <span className={`inline-block w-2 h-2 rounded-full ${workflow.enabled ? 'bg-green-500' : 'bg-gray-400'}`} />
              {workflow.enabled ? 'Enabled' : 'Disabled'}
            </span>
            {triggers.length > 0 && (
              <>
                <span>·</span>
                <span>Triggers: {triggers.join(', ')}</span>
              </>
            )}
          </div>
        </div>
        <div className="flex gap-2">
          <Button
            variant="outline"
            onClick={() => router.push(`/app/projects/${projectId}/workflows/${workflowId}/edit`)}
          >
            Edit
          </Button>
          <Button
            onClick={handleRunNow}
            disabled={!workflow.enabled || dispatching}
          >
            {dispatching ? 'Starting...' : '▶ Run Now'}
          </Button>
        </div>
      </div>

      <div className="grid grid-cols-[280px_1fr] gap-4 mb-6">
        <div className="border rounded-lg p-4">
          <p className="text-xs font-semibold uppercase tracking-wide text-muted-foreground mb-3">Stats</p>
          {stats ? (
            <div className="space-y-3">
              <div>
                <p className="text-xs text-muted-foreground mb-1">Last run</p>
                <div className="flex items-center gap-1.5">
                  <span className={`inline-flex items-center gap-1 px-2 py-0.5 rounded text-xs font-medium ${STATUS_COLORS[stats.lastRun.status] ?? ''}`}>
                    {STATUS_ICONS[stats.lastRun.status]} {stats.lastRun.status}
                  </span>
                  <span className="text-xs text-muted-foreground">{timeAgo(stats.lastRun.startedAt)}</span>
                </div>
              </div>

              <div>
                <p className="text-xs text-muted-foreground mb-0.5">Last {runs.length} runs</p>
                <p className="text-lg font-semibold">{stats.successRate}% success rate</p>
                <p className="text-xs text-muted-foreground">out of {stats.totalRuns} total</p>
              </div>

              {stats.avgDuration && (
                <div>
                  <p className="text-xs text-muted-foreground mb-0.5">Avg duration</p>
                  <p className="text-sm font-medium">{stats.avgDuration}</p>
                </div>
              )}
            </div>
          ) : (
            <p className="text-sm text-muted-foreground">No runs yet.</p>
          )}
        </div>

        <div className="border rounded-lg overflow-hidden" style={{ height: 280 }}>
          <div className="px-4 pt-3 pb-2 border-b bg-muted/30">
            <p className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">Workflow Diagram</p>
          </div>
          <div className="h-[calc(100%-36px)]">
            <WorkflowDiagram yaml={workflow.yaml} />
          </div>
        </div>
      </div>

      <div className="flex items-center justify-between mb-3">
        <h2 className="text-base font-semibold">Recent Runs</h2>
        <button
          className="text-sm text-muted-foreground hover:underline"
          onClick={() => router.push(`/app/projects/${projectId}/workflows/${workflowId}/runs`)}
        >
          View all runs →
        </button>
      </div>

      {runs.length === 0 ? (
        <div className="border rounded-lg text-center py-10 text-muted-foreground text-sm">
          No runs yet. Click &quot;Run Now&quot; to trigger this workflow.
        </div>
      ) : (
        <div className="border rounded-lg overflow-hidden">
          <table className="w-full">
            <tbody>
              {runs.map((run, i) => (
                <tr
                  key={run.id}
                  className={`hover:bg-muted/25 cursor-pointer ${i > 0 ? 'border-t' : ''}`}
                  onClick={() => router.push(`/app/projects/${projectId}/workflows/${workflowId}/runs/${run.id}`)}
                >
                  <td className="p-3 w-32">
                    <span className={`inline-flex items-center gap-1 px-2 py-0.5 rounded text-xs font-medium ${STATUS_COLORS[run.status] ?? ''}`}>
                      {STATUS_ICONS[run.status]} {run.status}
                    </span>
                  </td>
                  <td className="p-3 text-sm text-muted-foreground w-24">{run.triggerType}</td>
                  <td className="p-3 text-sm">{formatDate(run.startedAt)}</td>
                  <td className="p-3 text-sm text-muted-foreground text-right">
                    {formatDuration(run.startedAt, run.completedAt)}
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
