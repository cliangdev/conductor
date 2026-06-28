'use client';

import { useEffect, useState } from 'react';
import { useParams } from 'next/navigation';
import { useAuth } from '@/contexts/AuthContext';
import { apiGet } from '@/lib/api';
import { WorkflowRunDto } from '@/types/workflow';
import WorkflowDiagram from '@/components/workflow/WorkflowDiagram';
import { useWorkflow } from '@/contexts/WorkflowContext';

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

  if (!workflow) return <div className="text-muted-foreground">Loading…</div>;

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
  );
}
