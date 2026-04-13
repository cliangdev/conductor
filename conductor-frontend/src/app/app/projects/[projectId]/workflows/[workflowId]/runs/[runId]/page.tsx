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

const STATUS_COLORS: Record<string, string> = {
  SUCCESS: 'bg-green-100 text-green-800',
  FAILED: 'bg-red-100 text-red-800',
  RUNNING: 'bg-yellow-100 text-yellow-800',
  PENDING: 'bg-gray-100 text-gray-600',
  SKIPPED: 'bg-gray-100 text-gray-400',
  CANCELLED: 'bg-gray-100 text-gray-600',
};

const STATUS_ICONS: Record<string, string> = {
  SUCCESS: '✓',
  FAILED: '✗',
  RUNNING: '⟳',
  SKIPPED: '—',
  PENDING: '○',
};

function formatDuration(startedAt?: string, completedAt?: string): string {
  if (!startedAt) return '—';
  const start = new Date(startedAt).getTime();
  const end = completedAt ? new Date(completedAt).getTime() : Date.now();
  const seconds = Math.floor((end - start) / 1000);
  if (seconds < 60) return `${seconds}s`;
  return `${Math.floor(seconds / 60)}m ${seconds % 60}s`;
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

  const jobStatuses = Object.fromEntries(
    run.jobs.map(j => [j.jobId, j.status as 'SUCCESS' | 'FAILED' | 'RUNNING' | 'SKIPPED' | 'PENDING'])
  );

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
        <WorkflowDiagram yaml={run.workflowYaml} jobStatuses={jobStatuses} />
      </div>

      <div className="space-y-2">
        {run.jobs.map(job => (
          <JobRow
            key={job.id}
            job={job}
            expanded={expandedJobs.has(job.jobId)}
            onToggle={() => toggleJob(job.jobId)}
          />
        ))}
      </div>
    </div>
  );
}

function JobRow({ job, expanded, onToggle }: {
  job: WorkflowJobRunDto;
  expanded: boolean;
  onToggle: () => void;
}) {
  return (
    <div className="border rounded-lg overflow-hidden">
      <button
        className="w-full flex items-center gap-3 p-3 hover:bg-muted/25 text-left"
        onClick={onToggle}
      >
        <span className="text-lg">{STATUS_ICONS[job.status] ?? '?'}</span>
        <span className="font-medium flex-1">{job.jobId}</span>
        <span className={`inline-flex items-center px-2 py-0.5 rounded text-xs font-medium ${STATUS_COLORS[job.status] ?? ''}`}>
          {job.status}
        </span>
        <span className="text-sm text-muted-foreground">
          {formatDuration(job.startedAt, job.completedAt)}
        </span>
        <span className="text-muted-foreground">{expanded ? '▲' : '▼'}</span>
      </button>
      {expanded && (
        <div className="border-t divide-y">
          {job.steps.map(step => (
            <StepRow key={step.id} step={step} />
          ))}
          {job.steps.length === 0 && (
            <div className="p-3 text-sm text-muted-foreground">No steps recorded</div>
          )}
        </div>
      )}
    </div>
  );
}
