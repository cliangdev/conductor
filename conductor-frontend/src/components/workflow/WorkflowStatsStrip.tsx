'use client';

// Extracted from the old Overview tab (removed in the Runs/Definition IA — see
// workflows/[workflowId]/layout.tsx). A later phase drops this into the Runs tab.
//
// Fixed a labeling bug from the original: the success rate is computed over whatever sample of
// runs the caller passes in (the overview page fetched page=0&size=5), but the old copy read
// "68% success rate — out of 5 total", implying 5 was the workflow's entire run history rather
// than just the sample size. This version only ever says "last N runs" — honest about the sample,
// never implying a total.

import { WorkflowRunDto } from '@/types/workflow';
import { StatusBadge } from '@/components/ui/status-badge';
import { timeAgo, formatDuration } from '@/lib/format';

export interface WorkflowStats {
  lastRun: WorkflowRunDto;
  /** Success rate over `sampleSize` runs — NOT a lifetime total. */
  successRate: number;
  /** How many runs the rate above was computed over (the length of whatever was passed in). */
  sampleSize: number;
  avgDuration: string | null;
}

/** Pure computation, exported so a caller that wants custom layout doesn't have to re-derive it. */
export function computeWorkflowStats(runs: WorkflowRunDto[]): WorkflowStats | null {
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
    sampleSize: runs.length,
    avgDuration: avgDurationSeconds !== null ? formatDuration(avgDurationSeconds) : null,
  };
}

/**
 * Compact horizontal stats strip — last run status, success rate over the given sample (honestly
 * labeled "last N runs"), and average duration. Takes whatever runs the caller already fetched;
 * doesn't fetch its own data, so it fits wherever a run list is already loaded (e.g. the Runs tab).
 */
export function WorkflowStatsStrip({ runs }: { runs: WorkflowRunDto[] }) {
  const stats = computeWorkflowStats(runs);

  if (!stats) {
    return <p className="text-sm text-muted-foreground">No runs yet.</p>;
  }

  return (
    <div className="flex flex-wrap items-center gap-6">
      <div>
        <p className="text-xs text-muted-foreground mb-1">Last run</p>
        <div className="flex items-center gap-1.5">
          <StatusBadge status={stats.lastRun.status} />
          <span className="text-xs text-muted-foreground">{timeAgo(stats.lastRun.startedAt)}</span>
        </div>
      </div>

      <div>
        <p className="text-xs text-muted-foreground mb-0.5">Last {stats.sampleSize} runs</p>
        <p className="text-lg font-semibold">{stats.successRate}% success rate</p>
      </div>

      {stats.avgDuration && (
        <div>
          <p className="text-xs text-muted-foreground mb-0.5">Avg duration</p>
          <p className="text-sm font-medium">{stats.avgDuration}</p>
        </div>
      )}
    </div>
  );
}
