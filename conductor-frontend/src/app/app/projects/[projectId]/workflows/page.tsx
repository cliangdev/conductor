'use client';

import { Suspense, useEffect, useMemo, useState } from 'react';
import { useParams, usePathname, useRouter, useSearchParams } from 'next/navigation';
import { useAuth } from '@/contexts/AuthContext';
import { apiGet, apiPost, apiPatch, apiDelete, apiErrorMessage } from '@/lib/api';
import { BanIcon, CheckCircleIcon, GitBranchIcon, PlayIcon } from 'lucide-react';
import { WorkflowDefinitionDto, WorkflowRunDto } from '@/types/workflow';
import { isLifecycleWorkflow, disableWorkflow, enableWorkflow, invalidateSidebarCache } from '@/lib/workflows';
import { timeAgo } from '@/lib/format';
import { PageContainer } from '@/components/layout/PageContainer';
import { PageHeader } from '@/components/layout/PageHeader';
import { TriggerBadges } from '@/components/workflow/TriggerBadges';
import { WorkflowStatusBadge } from '@/components/workflow/WorkflowStatusBadge';
import { Button } from '@/components/ui/button';
import { StatusBadge } from '@/components/ui/status-badge';
import { Switch } from '@/components/ui/switch';
import { ConfirmModal } from '@/components/ui/confirm-modal';
import { Tabs, type TabItem } from '@/components/ui/tabs';
import { Skeleton } from '@/components/ui/skeleton';
import { EmptyState } from '@/components/ui/empty-state';
import { Card } from '@/components/ui/card';
import { useToast } from '@/components/ui/toast';
import { RowActionsMenu } from '@/components/ui/RowActionsMenu';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { Can } from '@/components/auth/Can';

type WorkflowTab = 'automation' | 'lifecycle';

const THEAD_CELL = 'text-left px-3 py-2 text-[11.5px] font-semibold uppercase tracking-wide text-muted-foreground';

function EnabledIndicator({ enabled }: { enabled: boolean }) {
  return <StatusBadge status={enabled ? 'done' : 'draft'} label={enabled ? 'Enabled' : 'Disabled'} />;
}

/** Lifecycle vs automation via the authoritative server-derived `kind` (never the `definition` shape). */
const isLifecycle = isLifecycleWorkflow;

function statusCount(wf: WorkflowDefinitionDto): number {
  const statuses = (wf.definition as { statuses?: unknown[] } | null | undefined)?.statuses;
  return Array.isArray(statuses) ? statuses.length : 0;
}

function WorkflowsPageContent() {
  const { projectId } = useParams<{ projectId: string }>();
  const { accessToken } = useAuth();
  const router = useRouter();
  const pathname = usePathname();
  const searchParams = useSearchParams();
  const { showToast } = useToast();
  const [workflows, setWorkflows] = useState<WorkflowDefinitionDto[]>([]);
  const [lastRuns, setLastRuns] = useState<Record<string, WorkflowRunDto | null>>({});
  const [loading, setLoading] = useState(true);
  const [runningId, setRunningId] = useState<string | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<WorkflowDefinitionDto | null>(null);
  const [deleting, setDeleting] = useState(false);
  const [disableTarget, setDisableTarget] = useState<WorkflowDefinitionDto | null>(null);
  const [disabling, setDisabling] = useState(false);

  const tab: WorkflowTab = searchParams.get('tab') === 'lifecycle' ? 'lifecycle' : 'automation';

  function setTab(next: WorkflowTab) {
    if (next === tab) return;
    const sp = new URLSearchParams(searchParams.toString());
    if (next === 'automation') sp.delete('tab');
    else sp.set('tab', next);
    const qs = sp.toString();
    router.replace(qs ? `${pathname}?${qs}` : pathname);
  }

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
    try {
      const updated = await apiPatch<WorkflowDefinitionDto>(
        `/api/v1/projects/${projectId}/workflows/${workflow.id}/enabled`,
        { enabled: !workflow.enabled },
        accessToken
      );
      if (!updated) return;
      setWorkflows(prev => prev.map(w => (w.id === updated.id ? updated : w)));
    } catch (e) {
      showToast(apiErrorMessage(e, `Couldn't ${workflow.enabled ? 'disable' : 'enable'} workflow — try again.`), 'error');
    }
  };

  const handleDisable = async (workflow: WorkflowDefinitionDto) => {
    if (!accessToken) return;
    try {
      const updated = await disableWorkflow(projectId, workflow.id, accessToken);
      setWorkflows(prev => prev.map(w => (w.id === updated.id ? updated : w)));
      // Drop the sidebar cache so the disabled workflow disappears from nav immediately.
      invalidateSidebarCache(projectId);
    } catch (e) {
      showToast(apiErrorMessage(e, 'Failed to disable workflow.'), 'error');
    }
  };

  const handleEnable = async (workflow: WorkflowDefinitionDto) => {
    if (!accessToken) return;
    try {
      const updated = await enableWorkflow(projectId, workflow.id, accessToken);
      setWorkflows(prev => prev.map(w => (w.id === updated.id ? updated : w)));
      // Drop the sidebar cache so the re-enabled workflow re-appears in nav immediately.
      invalidateSidebarCache(projectId);
    } catch (e) {
      showToast(apiErrorMessage(e, 'Failed to enable workflow.'), 'error');
    }
  };

  const confirmDisable = async () => {
    if (!disableTarget) return;
    setDisabling(true);
    try {
      await handleDisable(disableTarget);
      setDisableTarget(null);
    } finally {
      setDisabling(false);
    }
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
        <div className="space-y-2">
          {[0, 1, 2].map((i) => <Skeleton key={i} className="h-10 w-full" />)}
        </div>
      </PageContainer>
    );

  const tabItems: TabItem[] = [
    { value: 'automation', label: 'Automation', count: automation.length },
    { value: 'lifecycle', label: 'Lifecycle', count: lifecycle.length },
  ];

  return (
    <PageContainer>
      <PageHeader title="Workflows" description="Work Item lifecycles and run automations." actions={newWorkflowAction} />

      <Tabs
        items={tabItems}
        value={tab}
        onValueChange={(v) => setTab(v as WorkflowTab)}
        ariaLabel="Workflows view"
        className="mb-4 -mx-1 px-1"
      />

      {tab === 'automation' ? (
        automation.length === 0 ? (
          <Card>
            <EmptyState
              icon={PlayIcon}
              title="No automation workflows yet"
              description="Automations run on a schedule, webhook, or event trigger."
              action={
                <Can do="workflow.manage">
                  <Button size="sm" onClick={() => router.push(`/app/projects/${projectId}/workflows/new`)}>
                    New workflow
                  </Button>
                </Can>
              }
            />
          </Card>
        ) : (
          <Card className="overflow-x-auto">
            <table className="w-full min-w-[680px]">
              <thead className="bg-muted border-b border-border">
                <tr>
                  <th className={THEAD_CELL}>Name</th>
                  <th className={THEAD_CELL}>Last Run</th>
                  <th className={THEAD_CELL}>Triggers</th>
                  <th className={THEAD_CELL}>Enabled</th>
                  <th className={`${THEAD_CELL} w-28`} />
                </tr>
              </thead>
              <tbody className="divide-y divide-border">
                {automation.map(workflow => {
                  const lastRun = lastRuns[workflow.id];
                  return (
                    <tr
                      key={workflow.id}
                      className="h-[38px] hover:bg-muted cursor-pointer transition-colors"
                      onClick={() => router.push(`/app/projects/${projectId}/workflows/${workflow.id}/overview`)}
                    >
                      <td className="px-3 py-2 text-sm font-medium text-foreground">{workflow.name}</td>
                      <td className="px-3 py-2">
                        {lastRun ? (
                          <div className="flex items-center gap-1.5">
                            <StatusBadge status={lastRun.status} />
                            <span className="text-xs text-muted-foreground">{timeAgo(lastRun.startedAt)}</span>
                          </div>
                        ) : (
                          <span className="text-xs text-muted-foreground">—</span>
                        )}
                      </td>
                      <td className="px-3 py-2">
                        <TriggerBadges yaml={workflow.yaml ?? ''} />
                      </td>
                      <td className="px-3 py-2" onClick={e => e.stopPropagation()}>
                        <Can do="workflow.manage" fallback={<EnabledIndicator enabled={workflow.enabled} />}>
                          <Switch
                            checked={workflow.enabled}
                            onCheckedChange={() => handleToggleEnabled(workflow)}
                            aria-label={workflow.enabled ? 'Disable workflow' : 'Enable workflow'}
                          />
                        </Can>
                      </td>
                      <td className="px-3 py-2 text-right" onClick={e => e.stopPropagation()}>
                        <div className="flex items-center justify-end gap-1">
                          <Can do="workflow.run">
                            <button
                              onClick={() => handleRun(workflow)}
                              disabled={!workflow.enabled || runningId === workflow.id}
                              className="inline-flex items-center gap-1 text-sm font-medium text-primary hover:underline disabled:opacity-40 disabled:no-underline disabled:cursor-not-allowed"
                            >
                              {runningId === workflow.id ? 'Starting…' : (<><PlayIcon className="h-3.5 w-3.5" /> Run</>)}
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
          </Card>
        )
      ) : (
        lifecycle.length === 0 ? (
          <Card>
            <EmptyState
              icon={GitBranchIcon}
              title="No lifecycle workflows yet"
              description="Lifecycle workflows define a Work Item type's statuses and allowed transitions."
              action={
                <Can do="workflow.manage">
                  <Button size="sm" onClick={() => router.push(`/app/projects/${projectId}/workflows/lifecycle/new`)}>
                    New workflow
                  </Button>
                </Can>
              }
            />
          </Card>
        ) : (
          <Card className="overflow-x-auto">
            <table className="w-full min-w-[560px]">
              <thead className="bg-muted border-b border-border">
                <tr>
                  <th className={THEAD_CELL}>Name</th>
                  <th className={THEAD_CELL}>Noun</th>
                  <th className={THEAD_CELL}>State</th>
                  <th className={THEAD_CELL}>Statuses</th>
                  <th className={`${THEAD_CELL} w-12`} />
                </tr>
              </thead>
              <tbody className="divide-y divide-border">
                {lifecycle.map(wf => {
                  const noun = (wf.definition as { noun?: string } | null | undefined)?.noun;
                  const hasWorkItems = (wf.workItemCount ?? 0) > 0;
                  const extraItems =
                    wf.state === 'PUBLISHED' && hasWorkItems
                      ? [{
                          label: 'Disable',
                          icon: <BanIcon className="h-4 w-4" />,
                          onSelect: () => setDisableTarget(wf),
                        }]
                      : wf.state === 'DISABLED'
                        ? [{
                            label: 'Enable',
                            icon: <CheckCircleIcon className="h-4 w-4" />,
                            onSelect: () => handleEnable(wf),
                          }]
                        : [];
                  // Delete is hidden whenever Work Items are bound (Disable/Enable govern the lifecycle instead).
                  const canDelete = !hasWorkItems;
                  return (
                    <tr
                      key={wf.id}
                      className="h-[38px] hover:bg-muted cursor-pointer transition-colors"
                      onClick={() => router.push(`/app/projects/${projectId}/workflows/lifecycle/${wf.id}`)}
                    >
                      <td className="px-3 py-2 text-sm font-medium text-foreground">{wf.name}</td>
                      <td className="px-3 py-2 text-sm text-muted-foreground">{noun ?? '—'}</td>
                      <td className="px-3 py-2">
                        <WorkflowStatusBadge workflow={wf} />
                      </td>
                      <td className="px-3 py-2 text-sm text-muted-foreground">{statusCount(wf)}</td>
                      <td className="px-3 py-2 text-right" onClick={e => e.stopPropagation()}>
                        <Can do="workflow.manage">
                          <RowActionsMenu
                            onEdit={() => router.push(`/app/projects/${projectId}/workflows/lifecycle/${wf.id}`)}
                            onDelete={canDelete ? () => setDeleteTarget(wf) : undefined}
                            extraItems={extraItems}
                          />
                        </Can>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </Card>
        )
      )}

      <ConfirmModal
        open={!!deleteTarget}
        title="Delete workflow"
        confirmLabel="Delete workflow"
        busyLabel="Deleting…"
        busy={deleting}
        onConfirm={handleDelete}
        onCancel={() => setDeleteTarget(null)}
      >
        <p className="text-sm text-foreground">
          Permanently delete <strong>{deleteTarget?.name}</strong>? Its run history will be removed and this cannot be undone.
        </p>
      </ConfirmModal>

      <ConfirmModal
        open={!!disableTarget}
        title="Disable workflow"
        confirmLabel="Disable workflow"
        busyLabel="Disabling…"
        destructive={false}
        busy={disabling}
        onConfirm={confirmDisable}
        onCancel={() => setDisableTarget(null)}
      >
        <p className="text-sm text-foreground">
          Work items using <strong>{disableTarget?.name}</strong> will keep their current version.
          No new work items can use this workflow while it is disabled.
        </p>
      </ConfirmModal>
    </PageContainer>
  );
}

export default function WorkflowsPage() {
  return (
    <Suspense
      fallback={
        <PageContainer>
          <PageHeader title="Workflows" description="Work Item lifecycles and run automations." />
          <div className="space-y-2">
            {[0, 1, 2].map((i) => <Skeleton key={i} className="h-10 w-full" />)}
          </div>
        </PageContainer>
      }
    >
      <WorkflowsPageContent />
    </Suspense>
  );
}
