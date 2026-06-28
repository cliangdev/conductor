'use client';

import { useEffect, useMemo, useState } from 'react';
import { useParams, useRouter } from 'next/navigation';
import { useAuth } from '@/contexts/AuthContext';
import { apiGet, apiPost, apiPatch, apiDelete, apiErrorMessage } from '@/lib/api';
import { WorkflowDefinitionDto, WorkflowRunDto } from '@/types/workflow';
import { DEFAULT_WORKFLOW_SLUG, isLifecycleWorkflow } from '@/lib/workflows';
import { PageContainer } from '@/components/layout/PageContainer';
import { PageHeader } from '@/components/layout/PageHeader';
import { TriggerBadges } from '@/components/workflow/TriggerBadges';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Modal } from '@/components/ui/modal';
import { useToast } from '@/components/ui/toast';
import { RowActionsMenu } from '@/components/ui/RowActionsMenu';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { Can } from '@/components/auth/Can';

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

function EnabledIndicator({ enabled }: { enabled: boolean }) {
  return (
    <span className={`inline-flex items-center gap-1 text-xs ${enabled ? 'text-green-600' : 'text-muted-foreground'}`}>
      <span className={`inline-block w-1.5 h-1.5 rounded-full ${enabled ? 'bg-green-500' : 'bg-gray-400'}`} />
      {enabled ? 'Enabled' : 'Disabled'}
    </span>
  );
}

/** Lifecycle vs automation via the authoritative server-derived `kind` (never the `definition` shape). */
const isLifecycle = isLifecycleWorkflow;

function statusCount(wf: WorkflowDefinitionDto): number {
  const statuses = (wf.definition as { statuses?: unknown[] } | null | undefined)?.statuses;
  return Array.isArray(statuses) ? statuses.length : 0;
}

export default function WorkflowsPage() {
  const { projectId } = useParams<{ projectId: string }>();
  const { accessToken } = useAuth();
  const router = useRouter();
  const { showToast } = useToast();
  const [workflows, setWorkflows] = useState<WorkflowDefinitionDto[]>([]);
  const [lastRuns, setLastRuns] = useState<Record<string, WorkflowRunDto | null>>({});
  const [loading, setLoading] = useState(true);
  const [runningId, setRunningId] = useState<string | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<WorkflowDefinitionDto | null>(null);
  const [deleting, setDeleting] = useState(false);

  useEffect(() => {
    if (!accessToken) return;
    apiGet<WorkflowDefinitionDto[]>(`/api/v1/projects/${projectId}/workflows`, accessToken)
      .then(async (wfs) => {
        setWorkflows(wfs);
        // Last-run badges only apply to automation workflows.
        const automations = wfs.filter(wf => !isLifecycle(wf));
        const runEntries = await Promise.all(
          automations.map(async (wf) => {
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

  const { lifecycle, automation } = useMemo(() => {
    return {
      lifecycle: workflows.filter(isLifecycle),
      automation: workflows.filter(wf => !isLifecycle(wf)),
    };
  }, [workflows]);

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

  const handleToggleEnabled = async (workflow: WorkflowDefinitionDto) => {
    if (!accessToken) return;
    const updated = await apiPatch<WorkflowDefinitionDto>(
      `/api/v1/projects/${projectId}/workflows/${workflow.id}/enabled`,
      { enabled: !workflow.enabled },
      accessToken
    );
    if (!updated) return;
    setWorkflows(prev => prev.map(w => (w.id === updated.id ? updated : w)));
  };

  const handleDelete = async () => {
    if (!accessToken || !deleteTarget) return;
    setDeleting(true);
    try {
      await apiDelete(`/api/v1/projects/${projectId}/workflows/${deleteTarget.id}`, accessToken);
      setWorkflows(prev => prev.filter(w => w.id !== deleteTarget.id));
      setDeleteTarget(null);
    } catch (e) {
      showToast(apiErrorMessage(e, 'Failed to delete workflow.'), 'error');
    } finally {
      setDeleting(false);
    }
  };

  const newWorkflowAction = (
    <Can do="workflow.manage">
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button>New workflow</Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end" className="w-56">
          <DropdownMenuItem onSelect={() => router.push(`/app/projects/${projectId}/workflows/lifecycle/new`)}>
            Lifecycle workflow
          </DropdownMenuItem>
          <DropdownMenuItem onSelect={() => router.push(`/app/projects/${projectId}/workflows/new`)}>
            Automation (YAML)
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
    </Can>
  );

  if (loading)
    return (
      <PageContainer>
        <PageHeader title="Workflows" description="Work Item lifecycles and run automations." actions={newWorkflowAction} />
        <div className="text-muted-foreground">Loading...</div>
      </PageContainer>
    );

  return (
    <PageContainer>
      <PageHeader title="Workflows" description="Work Item lifecycles and run automations." actions={newWorkflowAction} />

      {/* ── Lifecycle (statechart) workflows ── */}
      <section className="mb-8">
        <h2 className="text-sm font-semibold uppercase tracking-wide text-muted-foreground mb-2">Lifecycle</h2>
        <div className="border rounded-lg overflow-x-auto">
          <table className="w-full min-w-[560px]">
            <thead className="bg-muted/50">
              <tr>
                <th className="text-left p-3 font-medium">Name</th>
                <th className="text-left p-3 font-medium">Noun</th>
                <th className="text-left p-3 font-medium">State</th>
                <th className="text-left p-3 font-medium">Statuses</th>
                <th className="p-3 font-medium w-12" />
              </tr>
            </thead>
            <tbody>
              {/* Built-in default Workflow — viewable as a template, not editable in place. */}
              <tr
                className="border-t hover:bg-muted/25 cursor-pointer"
                onClick={() => router.push(`/app/projects/${projectId}/workflows/lifecycle/builtin/${DEFAULT_WORKFLOW_SLUG}`)}
              >
                <td className="p-3 font-medium">{DEFAULT_WORKFLOW_SLUG}</td>
                <td className="p-3 text-muted-foreground">Issue</td>
                <td className="p-3"><Badge variant="secondary">Built-in</Badge></td>
                <td className="p-3 text-muted-foreground">—</td>
                <td className="p-3" />
              </tr>
              {lifecycle.map(wf => {
                const noun = (wf.definition as { noun?: string } | null | undefined)?.noun;
                return (
                  <tr
                    key={wf.id}
                    className="border-t hover:bg-muted/25 cursor-pointer"
                    onClick={() => router.push(`/app/projects/${projectId}/workflows/lifecycle/${wf.id}`)}
                  >
                    <td className="p-3 font-medium">{wf.name}</td>
                    <td className="p-3 text-muted-foreground">{noun ?? '—'}</td>
                    <td className="p-3">
                      <Badge variant={wf.state === 'PUBLISHED' ? 'status-done' : 'status-draft'}>
                        {wf.state ?? 'DRAFT'}
                      </Badge>
                    </td>
                    <td className="p-3 text-muted-foreground">{statusCount(wf)}</td>
                    <td className="p-3 text-right" onClick={e => e.stopPropagation()}>
                      <Can do="workflow.manage">
                        <RowActionsMenu
                          onEdit={() => router.push(`/app/projects/${projectId}/workflows/lifecycle/${wf.id}`)}
                          onDelete={() => setDeleteTarget(wf)}
                        />
                      </Can>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      </section>

      {/* ── Automation (YAML) workflows ── */}
      <section>
        <h2 className="text-sm font-semibold uppercase tracking-wide text-muted-foreground mb-2">Automation</h2>
        {automation.length === 0 ? (
          <div className="bg-card rounded-lg border border-border p-8 text-center">
            <p className="text-muted-foreground text-sm">No automation workflows yet.</p>
          </div>
        ) : (
          <div className="border rounded-lg overflow-x-auto">
            <table className="w-full min-w-[680px]">
              <thead className="bg-muted/50">
                <tr>
                  <th className="text-left p-3 font-medium">Name</th>
                  <th className="text-left p-3 font-medium">Last Run</th>
                  <th className="text-left p-3 font-medium">Triggers</th>
                  <th className="text-left p-3 font-medium">Enabled</th>
                  <th className="p-3 font-medium w-28" />
                </tr>
              </thead>
              <tbody>
                {automation.map(workflow => {
                  const lastRun = lastRuns[workflow.id];
                  return (
                    <tr
                      key={workflow.id}
                      className="border-t hover:bg-muted/25 cursor-pointer"
                      onClick={() => router.push(`/app/projects/${projectId}/workflows/${workflow.id}/overview`)}
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
                        <TriggerBadges yaml={workflow.yaml ?? ''} />
                      </td>
                      <td className="p-3" onClick={e => e.stopPropagation()}>
                        <Can do="workflow.manage" fallback={<EnabledIndicator enabled={workflow.enabled} />}>
                          <button
                            onClick={() => handleToggleEnabled(workflow)}
                            aria-label={workflow.enabled ? 'Disable workflow' : 'Enable workflow'}
                            className={`relative inline-flex h-5 w-10 items-center rounded-full transition-colors ${
                              workflow.enabled ? 'bg-green-500' : 'bg-gray-300'
                            }`}
                          >
                            <span className={`inline-block h-4 w-4 transform rounded-full bg-white transition-transform ${
                              workflow.enabled ? 'translate-x-5' : 'translate-x-1'
                            }`} />
                          </button>
                        </Can>
                      </td>
                      <td className="p-3 text-right" onClick={e => e.stopPropagation()}>
                        <div className="flex items-center justify-end gap-1">
                          <Can do="workflow.run">
                            <button
                              onClick={() => handleRun(workflow)}
                              disabled={!workflow.enabled || runningId === workflow.id}
                              className="text-sm font-medium text-primary hover:underline disabled:opacity-40 disabled:no-underline disabled:cursor-not-allowed"
                            >
                              {runningId === workflow.id ? 'Starting…' : '▶ Run'}
                            </button>
                          </Can>
                          <Can do="workflow.manage">
                            <RowActionsMenu
                              onEdit={() => router.push(`/app/projects/${projectId}/workflows/${workflow.id}/settings`)}
                              onDelete={() => setDeleteTarget(workflow)}
                            />
                          </Can>
                        </div>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </section>

      <Modal
        open={!!deleteTarget}
        onOpenChange={(o) => { if (!o) setDeleteTarget(null); }}
        title="Delete workflow"
      >
        <p className="text-sm text-foreground">
          Permanently delete <strong>{deleteTarget?.name}</strong>? Its run history will be removed and this cannot be undone.
        </p>
        <div className="flex gap-3 mt-4">
          <Button variant="destructive" onClick={handleDelete} disabled={deleting}>
            {deleting ? 'Deleting…' : 'Delete workflow'}
          </Button>
          <Button variant="outline" onClick={() => setDeleteTarget(null)} disabled={deleting}>
            Cancel
          </Button>
        </div>
      </Modal>
    </PageContainer>
  );
}
