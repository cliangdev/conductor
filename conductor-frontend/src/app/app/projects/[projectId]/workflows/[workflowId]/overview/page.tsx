'use client';

import { useEffect, useState } from 'react';
import { useParams } from 'next/navigation';
import { useAuth } from '@/contexts/AuthContext';
import { apiGet } from '@/lib/api';
import { WorkflowRunDto } from '@/types/workflow';
import WorkflowDiagram from '@/components/workflow/WorkflowDiagram';
import { useWorkflow } from '@/contexts/WorkflowContext';
import { StatusBadge } from '@/components/ui/status-badge';
import { Skeleton } from '@/components/ui/skeleton';
import { timeAgo, formatDuration } from '@/lib/format';

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

  return {
    lastRun: runs[0],
    successRate,
    totalRuns: runs.length,
    avgDuration: avgDurationSeconds !== null ? formatDuration(avgDurationSeconds) : null,
  };
}

export default function WorkflowOverviewPage() {
  const { projectId, workflowId } = useParams<{ projectId: string; workflowId: string }>();
  const { accessToken } = useAuth();
  const { workflow } = useWorkflow();
  const [runs, setRuns] = useState<WorkflowRunDto[]>([]);

  useEffect(() => {
    if (!accessToken) return;
    apiGet<WorkflowRunDto[]>(`/api/v1/projects/${projectId}/workflows/${workflowId}/runs?page=0&size=5`, accessToken)
      .then(setRuns)
      .catch(() => {});
  }, [projectId, workflowId, accessToken]);

  if (!workflow) {
    return (
      <div className="grid grid-cols-1 md:grid-cols-[280px_1fr] gap-4">
        <Skeleton className="h-40 rounded-lg" />
        <Skeleton className="h-[280px] rounded-lg" />
      </div>
    );
  }

  const stats = computeStats(runs);

  return (
    <div className="grid grid-cols-1 md:grid-cols-[280px_1fr] gap-4">
      <div className="border rounded-lg p-4">
        <p className="text-xs font-semibold uppercase tracking-wide text-muted-foreground mb-3">Stats</p>
        {stats ? (
          <div className="space-y-3">
            <div>
              <p className="text-xs text-muted-foreground mb-1">Last run</p>
              <div className="flex items-center gap-1.5">
                <StatusBadge status={stats.lastRun.status} />
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

      <div className="flex flex-col h-[280px] border border-border rounded-lg overflow-hidden">
        <div className="shrink-0 px-4 py-2.5 border-b border-border bg-muted">
          <p className="text-[11.5px] font-semibold uppercase tracking-wide text-muted-foreground">Workflow Diagram</p>
        </div>
        <div className="flex-1 min-h-0">
          <WorkflowDiagram yaml={workflow.yaml ?? ''} />
        </div>
      </div>
    </div>
  );
}
