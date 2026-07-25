'use client';

import { useEffect, useState, useCallback } from 'react';
import { useParams } from 'next/navigation';
import { useAuth } from '@/contexts/AuthContext';
import { apiGet, apiErrorMessage } from '@/lib/api';
import { WorkflowRunDetailDto, WorkflowJobRunDto, WorkflowStepRunDto } from '@/types/workflow';
import dynamic from 'next/dynamic';
import { PageHeader } from '@/components/layout/PageHeader';
import { StatusBadge } from '@/components/ui/status-badge';
import { CopyableId } from '@/components/ui/copyable-id';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import { Can } from '@/components/auth/Can';
import { useToast } from '@/components/ui/toast';
import { formatElapsed } from '@/lib/format';
import { parseWorkflowYaml } from '@/lib/workflowAutomation';
import { cancelWorkflowRun } from '@/lib/workflows';
import { stepNodeId } from '@/components/workflow/automation/graphBuilder';

const WorkflowDiagram = dynamic(() => import('@/components/workflow/WorkflowDiagram'), { ssr: false });

type JobStatus = 'SUCCESS' | 'FAILED' | 'RUNNING' | 'SKIPPED' | 'PENDING' | 'LOOP_EXHAUSTED' | 'CANCELLED';

interface JobRunStatus {
  status: JobStatus;
  iteration?: number;
  maxIterations?: number;
}

function maxIterationsForJob(workflowYaml: string, jobId: string): number | undefined {
  try {
    return parseWorkflowYaml(workflowYaml).jobs.find(j => j.jobId === jobId)?.loop?.maxIterations;
  } catch {
    return undefined;
  }
}

export default function RunDetailPage() {
  const { projectId, workflowId, runId } = useParams<{
    projectId: string; workflowId: string; runId: string;
  }>();
  const { accessToken } = useAuth();
  const { showToast } = useToast();
  const [run, setRun] = useState<WorkflowRunDetailDto | null>(null);
  const [cancelling, setCancelling] = useState(false);

  const fetchRun = useCallback(() => {
    if (!accessToken) return;
    apiGet<WorkflowRunDetailDto>(
      `/api/v1/projects/${projectId}/workflows/${workflowId}/runs/${runId}`,
      accessToken
    ).then(setRun);
  }, [projectId, workflowId, runId, accessToken]);

  useEffect(() => { fetchRun(); }, [fetchRun]);

  useEffect(() => {
    if (!run || (run.status !== 'RUNNING' && run.status !== 'PENDING' && run.status !== 'CANCELLING')) return;
    const interval = setInterval(fetchRun, 5000);
    return () => clearInterval(interval);
  }, [run, fetchRun]);

  const handleCancel = async () => {
    if (!accessToken) return;
    setCancelling(true);
    try {
      await cancelWorkflowRun(projectId, workflowId, runId, accessToken);
      fetchRun();
      showToast('Cancellation requested.', 'success');
    } catch (e) {
      showToast(apiErrorMessage(e, "Couldn't cancel this run — try again."), 'error');
    } finally {
      setCancelling(false);
    }
  };

  if (!run) {
    return (
      <div className="space-y-6">
        <PageHeader title="Run Detail" />
        <Skeleton className="h-64 rounded-lg" />
        <div className="space-y-2">
          {[0, 1, 2].map((i) => <Skeleton key={i} className="h-14 rounded-lg" />)}
        </div>
      </div>
    );
  }

  // Build jobRunData from run.jobs (use latest iteration per jobId)
  const jobRunData: Record<string, JobRunStatus> = {};
  run.jobs.forEach(j => {
    const existing = jobRunData[j.jobId];
    const current = j.iteration ?? 0;
    const existingIter = existing?.iteration ?? -1;
    if (!existing || current > existingIter) {
      const maxIterations = maxIterationsForJob(run.workflowYaml, j.jobId);
      jobRunData[j.jobId] = {
        status: j.status as JobStatus,
        iteration: j.iteration,
        maxIterations,
      };
    }
  });

  // Backward-compat jobStatuses
  const jobStatuses = Object.fromEntries(
    Object.entries(jobRunData).map(([id, d]) => [id, d.status])
  ) as Record<string, JobStatus>;

  // Group jobs by jobId, each group sorted latest iteration first
  const jobGroups: Record<string, WorkflowJobRunDto[]> = {};
  run.jobs.forEach(job => {
    if (!jobGroups[job.jobId]) jobGroups[job.jobId] = [];
    jobGroups[job.jobId].push(job);
  });
  Object.values(jobGroups).forEach(group =>
    group.sort((a, b) => (b.iteration ?? 0) - (a.iteration ?? 0))
  );
  const uniqueJobIds = [...new Set(run.jobs.map(j => j.jobId))];

  // Step-level run data for the diagram's status rings and detail panel, keyed the same way
  // graphBuilder ids step nodes — built from each job's latest iteration (group[0], since groups
  // are sorted latest-first above).
  const stepRunData: Record<string, WorkflowStepRunDto> = {};
  Object.entries(jobGroups).forEach(([jobId, group]) => {
    group[0]?.steps.forEach((step, index) => {
      stepRunData[stepNodeId(jobId, index)] = step;
    });
  });

  return (
    <div className="space-y-6">
      <PageHeader
        className="mb-0"
        title="Run Detail"
        status={<StatusBadge status={run.status} />}
        description={
          <span className="inline-flex items-center gap-1.5 flex-wrap">
            <span>Run <CopyableId id={run.id} /></span>
            <span>· Trigger: {run.triggerType}</span>
            <span>· Duration: {formatElapsed(run.startedAt, run.completedAt)}</span>
          </span>
        }
        actions={
          (run.status === 'RUNNING' || run.status === 'PENDING' || run.status === 'CANCELLING') && (
            <Can do="workflow.run">
              <Button
                variant="outline"
                onClick={handleCancel}
                disabled={run.status === 'CANCELLING' || cancelling}
              >
                {run.status === 'CANCELLING' || cancelling ? 'Cancelling…' : 'Cancel run'}
              </Button>
            </Can>
          )
        }
      />

      <div className="border rounded-lg bg-muted/20 h-64">
        <WorkflowDiagram
          yaml={run.workflowYaml}
          jobStatuses={jobStatuses}
          jobRunData={jobRunData}
          stepRunData={stepRunData}
          runId={run.id}
          projectId={projectId}
          token={accessToken}
        />
      </div>

      <div className="space-y-2">
        {uniqueJobIds.map(jobId => (
          <JobSummaryRow
            key={jobId}
            jobId={jobId}
            iterations={jobGroups[jobId]}
            workflowYaml={run.workflowYaml}
          />
        ))}
      </div>
    </div>
  );
}

// A scannable, non-expandable overview row per job — step-level detail lives in the diagram above
// (click a step node to open its detail panel), so this stays a summary, not a second copy of it.
function JobSummaryRow({
  jobId,
  iterations,
  workflowYaml,
}: {
  jobId: string;
  iterations: WorkflowJobRunDto[];
  workflowYaml: string;
}) {
  const latest = iterations[0];
  const isLoop = iterations.length > 1 || (iterations[0]?.iteration ?? 0) > 0;
  const maxIterations = isLoop ? maxIterationsForJob(workflowYaml, jobId) : undefined;

  return (
    <div className="flex items-center gap-3 border rounded-lg p-3">
      <span className="font-medium flex-1">{jobId}</span>
      {isLoop && (
        <Badge variant="status-progress">
          {(latest.iteration ?? 0) + 1}/{maxIterations ?? '?'}
        </Badge>
      )}
      <StatusBadge status={latest.status} />
      <span className="text-sm text-muted-foreground">
        {formatElapsed(latest.startedAt, latest.completedAt)}
      </span>
    </div>
  );
}
