'use client';

import { useEffect, useState, useCallback } from 'react';
import { useParams, useRouter } from 'next/navigation';
import { useAuth } from '@/contexts/AuthContext';
import { apiGet, apiPost } from '@/lib/api';
import { WorkflowRunDto } from '@/types/workflow';
import { Button } from '@/components/ui/button';

const STATUS_COLORS: Record<string, string> = {
  SUCCESS: 'bg-green-100 text-green-800',
  FAILED: 'bg-red-100 text-red-800',
  RUNNING: 'bg-yellow-100 text-yellow-800',
  PENDING: 'bg-gray-100 text-gray-600',
  CANCELLED: 'bg-gray-100 text-gray-600',
};

function formatDuration(startedAt: string, completedAt?: string): string {
  const start = new Date(startedAt).getTime();
  const end = completedAt ? new Date(completedAt).getTime() : Date.now();
  const seconds = Math.floor((end - start) / 1000);
  if (seconds < 60) return `${seconds}s`;
  return `${Math.floor(seconds / 60)}m ${seconds % 60}s`;
}

function formatDate(dateStr: string): string {
  return new Date(dateStr).toLocaleString();
}

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

  const handleRunAgain = async () => {
    if (!accessToken) return;
    const run = await apiPost<WorkflowRunDto>(
      `/api/v1/projects/${projectId}/workflows/${workflowId}/dispatch`, {}, accessToken
    );
    router.push(`/app/projects/${projectId}/workflows/${workflowId}/runs/${run.id}`);
  };

  return (
    <div className="p-6">
      <div className="flex items-center justify-between mb-6">
        <div>
          <button
            className="text-sm text-muted-foreground hover:underline"
            onClick={() => router.push(`/app/projects/${projectId}/workflows`)}
          >
            ← Workflows
          </button>
          <h1 className="text-2xl font-semibold mt-1">Run History</h1>
        </div>
        <Button onClick={handleRunAgain}>Run Now</Button>
      </div>

      {loading ? (
        <div className="text-muted-foreground">Loading...</div>
      ) : runs.length === 0 ? (
        <div className="text-center py-12 text-muted-foreground">
          No runs yet. Click &quot;Run Now&quot; to trigger this workflow.
        </div>
      ) : (
        <div className="border rounded-lg overflow-hidden">
          <table className="w-full">
            <thead className="bg-muted/50">
              <tr>
                <th className="text-left p-3 font-medium">Status</th>
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
                    <span className={`inline-flex items-center px-2 py-0.5 rounded text-xs font-medium ${STATUS_COLORS[run.status] ?? ''}`}>
                      {run.status}
                    </span>
                  </td>
                  <td className="p-3 text-sm text-muted-foreground">{run.triggerType}</td>
                  <td className="p-3 text-sm">{formatDate(run.startedAt)}</td>
                  <td className="p-3 text-sm">{formatDuration(run.startedAt, run.completedAt)}</td>
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
    </div>
  );
}
