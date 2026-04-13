'use client';

import { useEffect, useRef, useState } from 'react';
import { useParams, useRouter } from 'next/navigation';
import { useAuth } from '@/contexts/AuthContext';
import { apiGet, apiPatch, apiPost } from '@/lib/api';
import { WorkflowDefinitionDto, WorkflowRunDto } from '@/types/workflow';
import { Button } from '@/components/ui/button';
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

function KebabMenu({
  workflow,
  onEdit,
  onRun,
}: {
  workflow: WorkflowDefinitionDto;
  onEdit: () => void;
  onRun: () => void;
}) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    function handleClick(e: MouseEvent) {
      if (ref.current && !ref.current.contains(e.target as Node)) {
        setOpen(false);
      }
    }
    document.addEventListener('mousedown', handleClick);
    return () => document.removeEventListener('mousedown', handleClick);
  }, [open]);

  return (
    <div className="relative" ref={ref}>
      <button
        className="px-2 py-1 rounded text-muted-foreground hover:bg-muted/50 text-base leading-none"
        onClick={e => { e.stopPropagation(); setOpen(v => !v); }}
        aria-label="More actions"
      >
        ···
      </button>
      {open && (
        <div className="absolute right-0 z-20 mt-1 w-36 rounded-md border bg-background shadow-md">
          <button
            className="flex w-full items-center px-3 py-2 text-sm hover:bg-muted/50"
            onClick={e => { e.stopPropagation(); setOpen(false); onEdit(); }}
          >
            Edit
          </button>
          <button
            className={`flex w-full items-center px-3 py-2 text-sm hover:bg-muted/50 ${!workflow.enabled ? 'opacity-40 cursor-not-allowed' : ''}`}
            disabled={!workflow.enabled}
            onClick={e => { e.stopPropagation(); setOpen(false); onRun(); }}
          >
            Run Now
          </button>
        </div>
      )}
    </div>
  );
}

export default function WorkflowsPage() {
  const { projectId } = useParams<{ projectId: string }>();
  const { accessToken } = useAuth();
  const router = useRouter();
  const [workflows, setWorkflows] = useState<WorkflowDefinitionDto[]>([]);
  const [lastRuns, setLastRuns] = useState<Record<string, WorkflowRunDto | null>>({});
  const [loading, setLoading] = useState(true);

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

  const handleToggleEnabled = async (workflow: WorkflowDefinitionDto) => {
    if (!accessToken) return;
    const updated = await apiPatch<WorkflowDefinitionDto>(
      `/api/v1/projects/${projectId}/workflows/${workflow.id}/enabled`,
      { enabled: !workflow.enabled },
      accessToken
    );
    setWorkflows(prev => prev.map(w => w.id === updated.id ? updated : w));
  };

  const handleDispatch = async (workflow: WorkflowDefinitionDto) => {
    if (!accessToken) return;
    const run = await apiPost<WorkflowRunDto>(
      `/api/v1/projects/${projectId}/workflows/${workflow.id}/dispatch`,
      {},
      accessToken
    );
    router.push(`/app/projects/${projectId}/workflows/${workflow.id}/runs/${run.id}`);
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
        <div className="border rounded-lg">
          <table className="w-full">
            <thead className="bg-muted/50">
              <tr>
                <th className="text-left p-3 font-medium">Name</th>
                <th className="text-left p-3 font-medium">Last Run</th>
                <th className="text-left p-3 font-medium">Triggers</th>
                <th className="text-left p-3 font-medium">Enabled</th>
                <th className="p-3 font-medium w-10" />
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
                    <td className="p-3" onClick={e => e.stopPropagation()}>
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
                    <td className="p-3 text-right" onClick={e => e.stopPropagation()}>
                      <KebabMenu
                        workflow={workflow}
                        onEdit={() => router.push(`/app/projects/${projectId}/workflows/${workflow.id}/edit`)}
                        onRun={() => handleDispatch(workflow)}
                      />
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
