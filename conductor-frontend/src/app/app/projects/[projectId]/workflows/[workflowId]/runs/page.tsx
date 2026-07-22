'use client';

import { useEffect, useState, useCallback } from 'react';
import { useParams, useRouter } from 'next/navigation';
import { useAuth } from '@/contexts/AuthContext';
import { apiGet } from '@/lib/api';
import { WorkflowRunDto } from '@/types/workflow';
import { Button } from '@/components/ui/button';
import { StatusBadge } from '@/components/ui/status-badge';
import { CopyableId } from '@/components/ui/copyable-id';
import { Skeleton } from '@/components/ui/skeleton';
import { EmptyState } from '@/components/ui/empty-state';
import { ListIcon } from 'lucide-react';
import { formatElapsed, formatDate } from '@/lib/format';

export default function RunListPage() {
  const { projectId, workflowId } = useParams<{ projectId: string; workflowId: string }>();
  const { accessToken } = useAuth();
  const router = useRouter();
  const [runs, setRuns] = useState<WorkflowRunDto[]>([]);
  const [loading, setLoading] = useState(true);
  const [page, setPage] = useState(0);

  const fetchRuns = useCallback(() => {
    if (!accessToken) return;
    apiGet<WorkflowRunDto[]>(
      `/api/v1/projects/${projectId}/workflows/${workflowId}/runs?page=${page}&size=50`,
      accessToken
    ).then(setRuns).finally(() => setLoading(false));
  }, [projectId, workflowId, accessToken, page]);

  useEffect(() => { fetchRuns(); }, [fetchRuns]);

  useEffect(() => {
    const hasRunning = runs.some(r => r.status === 'RUNNING' || r.status === 'PENDING');
    if (!hasRunning) return;
    const interval = setInterval(fetchRuns, 5000);
    return () => clearInterval(interval);
  }, [runs, fetchRuns]);

  return (
    <>
      {loading ? (
        <div className="space-y-2">
          {[0, 1, 2].map((i) => <Skeleton key={i} className="h-10 w-full" />)}
        </div>
      ) : runs.length === 0 ? (
        <EmptyState icon={ListIcon} title="No runs yet" description="Use Run above to trigger this workflow." />
      ) : (
        <div className="border rounded-lg overflow-x-auto">
          <table className="w-full min-w-[640px]">
            <thead className="bg-muted/50">
              <tr>
                <th className="text-left p-3 font-medium">Status</th>
                <th className="text-left p-3 font-medium">Run ID</th>
                <th className="text-left p-3 font-medium">Trigger</th>
                <th className="text-left p-3 font-medium">Started</th>
                <th className="text-left p-3 font-medium">Duration</th>
              </tr>
            </thead>
            <tbody>
              {runs.map(run => (
                <tr
                  key={run.id}
                  className="border-t hover:bg-muted/25 cursor-pointer"
                  onClick={() => router.push(`/app/projects/${projectId}/workflows/${workflowId}/runs/${run.id}`)}
                >
                  <td className="p-3">
                    <StatusBadge status={run.status} />
                  </td>
                  <td className="p-3">
                    <CopyableId id={run.id} />
                  </td>
                  <td className="p-3 text-sm text-muted-foreground">{run.triggerType}</td>
                  <td className="p-3 text-sm">{formatDate(run.startedAt)}</td>
                  <td className="p-3 text-sm">{formatElapsed(run.startedAt, run.completedAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
          {runs.length === 50 && (
            <div className="flex justify-center gap-2 p-3 border-t">
              <Button variant="outline" size="sm" disabled={page === 0} onClick={() => setPage(p => p - 1)}>Previous</Button>
              <Button variant="outline" size="sm" onClick={() => setPage(p => p + 1)}>Next</Button>
            </div>
          )}
        </div>
      )}
    </>
  );
}
