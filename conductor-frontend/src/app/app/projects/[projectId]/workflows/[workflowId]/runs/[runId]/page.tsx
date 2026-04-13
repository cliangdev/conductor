'use client';

import { useEffect, useState, useCallback } from 'react';
import { useParams, useRouter } from 'next/navigation';
import { useAuth } from '@/contexts/AuthContext';
import { apiGet, apiPost } from '@/lib/api';
import { WorkflowRunDetailDto, WorkflowJobRunDto } from '@/types/workflow';
import dynamic from 'next/dynamic';
import { Button } from '@/components/ui/button';
import { StepRow } from '@/components/workflow/StepRow';

const WorkflowDiagram = dynamic(() => import('@/components/workflow/WorkflowDiagram'), { ssr: false });

type JobStatus = 'SUCCESS' | 'FAILED' | 'RUNNING' | 'SKIPPED' | 'PENDING' | 'LOOP_EXHAUSTED';

interface JobRunStatus {
  status: JobStatus;
  iteration?: number;
  maxIterations?: number;
}

const STATUS_COLORS: Record<string, string> = {
  SUCCESS:       'bg-green-100 text-green-800',
  FAILED:        'bg-red-100 text-red-800',
  RUNNING:       'bg-yellow-100 text-yellow-800',
  PENDING:       'bg-gray-100 text-gray-600',
  SKIPPED:       'bg-gray-100 text-gray-400',
  CANCELLED:     'bg-gray-100 text-gray-600',
  LOOP_EXHAUSTED:'bg-orange-100 text-orange-700',
};

const STATUS_ICONS: Record<string, string> = {
  SUCCESS: '✓',
  FAILED: '✗',
  RUNNING: '⟳',
  SKIPPED: '—',
  PENDING: '○',
  LOOP_EXHAUSTED: '↺',
};

function formatDuration(startedAt?: string, completedAt?: string): string {
  if (!startedAt) return '—';
  const start = new Date(startedAt).getTime();
  const end = completedAt ? new Date(completedAt).getTime() : Date.now();
  const seconds = Math.floor((end - start) / 1000);
  if (seconds < 60) return `${seconds}s`;
  return `${Math.floor(seconds / 60)}m ${seconds % 60}s`;
}

function parseMaxIterations(workflowYaml: string, jobId: string): number | undefined {
  try {
    // eslint-disable-next-line @typescript-eslint/no-require-imports
    const jsYaml = require('js-yaml') as typeof import('js-yaml');
    const parsed = jsYaml.load(workflowYaml) as Record<string, unknown> | null;
    if (!parsed) return undefined;
    const jobs = parsed['jobs'] as Record<string, unknown> | undefined;
    if (!jobs) return undefined;
    const job = jobs[jobId] as Record<string, unknown> | undefined;
    if (!job) return undefined;
    const loop = job['loop'] as Record<string, unknown> | undefined;
    if (!loop) return undefined;
    return Number(loop['max_iterations']) || undefined;
  } catch {
    return undefined;
  }
}

export default function RunDetailPage() {
  const { projectId, workflowId, runId } = useParams<{
    projectId: string; workflowId: string; runId: string;
  }>();
  const { accessToken } = useAuth();
  const router = useRouter();
  const [run, setRun] = useState<WorkflowRunDetailDto | null>(null);
  const [expandedJobs, setExpandedJobs] = useState<Set<string>>(new Set());

  const fetchRun = useCallback(() => {
    if (!accessToken) return;
    apiGet<WorkflowRunDetailDto>(
      `/api/v1/projects/${projectId}/workflows/${workflowId}/runs/${runId}`,
      accessToken
    ).then(setRun);
  }, [projectId, workflowId, runId, accessToken]);

  useEffect(() => { fetchRun(); }, [fetchRun]);

  useEffect(() => {
    if (!run || (run.status !== 'RUNNING' && run.status !== 'PENDING')) return;
    const interval = setInterval(fetchRun, 5000);
    return () => clearInterval(interval);
  }, [run, fetchRun]);

  const handleRunAgain = async () => {
    if (!accessToken) return;
    const newRun = await apiPost<{ id: string }>(
      `/api/v1/projects/${projectId}/workflows/${workflowId}/dispatch`, {}, accessToken!
    );
    router.push(`/app/projects/${projectId}/workflows/${workflowId}/runs/${newRun.id}`);
  };

  const toggleJob = (jobId: string) => {
    setExpandedJobs(prev => {
      const next = new Set(prev);
      if (next.has(jobId)) next.delete(jobId); else next.add(jobId);
      return next;
    });
  };

  if (!run) return <div className="p-6 text-muted-foreground">Loading...</div>;

  // Build jobRunData from run.jobs (use latest iteration per jobId)
  const jobRunData: Record<string, JobRunStatus> = {};
  run.jobs.forEach(j => {
    const existing = jobRunData[j.jobId];
    const current = j.iteration ?? 0;
    const existingIter = existing?.iteration ?? -1;
    if (!existing || current > existingIter) {
      const maxIterations = parseMaxIterations(run.workflowYaml, j.jobId);
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

  return (
    <div className="p-6 space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <button
            className="text-sm text-muted-foreground hover:underline"
            onClick={() => router.push(`/app/projects/${projectId}/workflows/${workflowId}/runs`)}
          >
            ← Run History
          </button>
          <div className="flex items-center gap-3 mt-1">
            <h1 className="text-2xl font-semibold">Run Detail</h1>
            <span className={`inline-flex items-center px-2 py-0.5 rounded text-sm font-medium ${STATUS_COLORS[run.status] ?? ''}`}>
              {run.status}
            </span>
          </div>
          <p className="text-sm text-muted-foreground mt-1">
            Trigger: {run.triggerType} · Duration: {formatDuration(run.startedAt, run.completedAt)}
          </p>
        </div>
        <Button onClick={handleRunAgain} variant="outline">Run Again</Button>
      </div>

      <div className="border rounded-lg bg-muted/20 h-64">
        <WorkflowDiagram
          yaml={run.workflowYaml}
          jobStatuses={jobStatuses}
          jobRunData={jobRunData}
        />
      </div>

      <div className="space-y-2">
        {uniqueJobIds.map(jobId => (
          <JobGroupRow
            key={jobId}
            jobId={jobId}
            iterations={jobGroups[jobId]}
            expanded={expandedJobs.has(jobId)}
            onToggle={() => toggleJob(jobId)}
            workflowYaml={run.workflowYaml}
          />
        ))}
      </div>
    </div>
  );
}

function JobGroupRow({
  jobId,
  iterations,
  expanded,
  onToggle,
  workflowYaml,
}: {
  jobId: string;
  iterations: WorkflowJobRunDto[];
  expanded: boolean;
  onToggle: () => void;
  workflowYaml: string;
}) {
  const latest = iterations[0];
  const isLoop = iterations.length > 1 || (iterations[0]?.iteration ?? 0) > 0;
  const maxIterations = isLoop ? parseMaxIterations(workflowYaml, jobId) : undefined;

  return (
    <div className="border rounded-lg overflow-hidden">
      <button
        className="w-full flex items-center gap-3 p-3 hover:bg-muted/25 text-left"
        onClick={onToggle}
      >
        <span className="text-lg">{STATUS_ICONS[latest.status] ?? '?'}</span>
        <span className="font-medium flex-1">{jobId}</span>
        {isLoop && (
          <span className="inline-flex items-center px-2 py-0.5 rounded text-xs bg-orange-100 text-orange-700">
            {(latest.iteration ?? 0) + 1}/{maxIterations ?? '?'}
          </span>
        )}
        <span className={`inline-flex items-center px-2 py-0.5 rounded text-xs font-medium ${STATUS_COLORS[latest.status] ?? ''}`}>
          {latest.status}
        </span>
        <span className="text-sm text-muted-foreground">
          {formatDuration(latest.startedAt, latest.completedAt)}
        </span>
        <span className="text-muted-foreground">{expanded ? '▲' : '▼'}</span>
      </button>
      {expanded && (
        <div className="border-t divide-y">
          {isLoop ? (
            iterations.map(iter => (
              <div key={iter.id} className="p-2 pl-6">
                <div className="text-xs font-medium text-muted-foreground mb-1">
                  Iteration {(iter.iteration ?? 0) + 1}
                </div>
                {iter.steps.map(step => (
                  <StepRow key={step.id} step={step} />
                ))}
                {iter.steps.length === 0 && (
                  <div className="p-3 text-sm text-muted-foreground">No steps recorded</div>
                )}
              </div>
            ))
          ) : (
            <>
              {latest.steps.map(step => (
                <StepRow key={step.id} step={step} />
              ))}
              {latest.steps.length === 0 && (
                <div className="p-3 text-sm text-muted-foreground">No steps recorded</div>
              )}
            </>
          )}
        </div>
      )}
    </div>
  );
}
