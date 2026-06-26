'use client';

export const dynamic = 'force-dynamic';

import { useEffect, useRef, useState } from 'react';
import { useParams, useRouter } from 'next/navigation';
import { useAuth } from '@/contexts/AuthContext';
import { apiGet, apiPatch, apiDelete, apiErrorMessage } from '@/lib/api';
import { WorkflowDefinitionDto } from '@/types/workflow';
import { Button } from '@/components/ui/button';
import { Modal } from '@/components/ui/modal';
import { useToast } from '@/components/ui/toast';
import { PageContainer } from '@/components/layout/PageContainer';
import { PageHeader } from '@/components/layout/PageHeader';
import { TriggerBadges } from '@/components/workflow/TriggerBadges';

function KebabMenu({
  onEdit,
  onDelete,
}: {
  onEdit: () => void;
  onDelete: () => void;
}) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    function handleClick(e: MouseEvent) {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
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
            className="flex w-full items-center px-3 py-2 text-sm text-destructive hover:bg-muted/50"
            onClick={e => { e.stopPropagation(); setOpen(false); onDelete(); }}
          >
            Delete
          </button>
        </div>
      )}
    </div>
  );
}

export default function ManageWorkflowsPage() {
  const { projectId } = useParams<{ projectId: string }>();
  const { accessToken } = useAuth();
  const router = useRouter();
  const { showToast } = useToast();
  const [workflows, setWorkflows] = useState<WorkflowDefinitionDto[]>([]);
  const [loading, setLoading] = useState(true);
  const [deleteTarget, setDeleteTarget] = useState<WorkflowDefinitionDto | null>(null);
  const [deleting, setDeleting] = useState(false);

  useEffect(() => {
    if (!accessToken) return;
    apiGet<WorkflowDefinitionDto[]>(`/api/v1/projects/${projectId}/workflows`, accessToken)
      .then(setWorkflows)
      .finally(() => setLoading(false));
  }, [projectId, accessToken]);

  const handleToggleEnabled = async (workflow: WorkflowDefinitionDto) => {
    if (!accessToken) return;
    const updated = await apiPatch<WorkflowDefinitionDto>(
      `/api/v1/projects/${projectId}/workflows/${workflow.id}/enabled`,
      { enabled: !workflow.enabled },
      accessToken
    );
    if (!updated) return;
    setWorkflows(prev => prev.map(w => w.id === updated.id ? updated : w));
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

  return (
    <PageContainer>
      <PageHeader
        breadcrumbs={[
          { label: 'Settings', href: `/app/projects/${projectId}/settings/general` },
          { label: 'Workflows' },
        ]}
        title="Workflows"
        description="Create and edit automations. Run them and view history from the Workflows tab."
        actions={
          <Button onClick={() => router.push(`/app/projects/${projectId}/settings/workflows/new`)}>
            New Workflow
          </Button>
        }
      />

      {loading ? (
        <div className="text-muted-foreground">Loading...</div>
      ) : workflows.length === 0 ? (
        <div className="text-center py-12 text-muted-foreground">
          No workflows yet. Create one to automate your project.
        </div>
      ) : (
        <div className="border rounded-lg overflow-x-auto">
          <table className="w-full min-w-[560px]">
            <thead className="bg-muted/50">
              <tr>
                <th className="text-left p-3 font-medium">Name</th>
                <th className="text-left p-3 font-medium">Triggers</th>
                <th className="text-left p-3 font-medium">Enabled</th>
                <th className="p-3 font-medium w-10" />
              </tr>
            </thead>
            <tbody>
              {workflows.map(workflow => (
                <tr
                  key={workflow.id}
                  className="border-t hover:bg-muted/25 cursor-pointer"
                  onClick={() => router.push(`/app/projects/${projectId}/settings/workflows/${workflow.id}/edit`)}
                >
                  <td className="p-3 font-medium">{workflow.name}</td>
                  <td className="p-3">
                    <TriggerBadges yaml={workflow.yaml} />
                  </td>
                  <td className="p-3" onClick={e => e.stopPropagation()}>
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
                  </td>
                  <td className="p-3 text-right" onClick={e => e.stopPropagation()}>
                    <KebabMenu
                      onEdit={() => router.push(`/app/projects/${projectId}/settings/workflows/${workflow.id}/edit`)}
                      onDelete={() => setDeleteTarget(workflow)}
                    />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

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
