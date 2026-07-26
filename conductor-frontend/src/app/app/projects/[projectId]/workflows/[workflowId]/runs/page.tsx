'use client';

import { Suspense, useCallback, useEffect, useRef, useState } from 'react';
import { useParams, usePathname, useRouter, useSearchParams } from 'next/navigation';
import { useAuth } from '@/contexts/AuthContext';
import { useWorkflow } from '@/contexts/WorkflowContext';
import { apiErrorMessage, type ApiError } from '@/lib/api';
import { WorkflowRunDto, WorkflowScheduleSkipDto } from '@/types/workflow';
import {
  listWorkflowRuns,
  cancelWorkflowRun,
  cancelQueuedWorkflowRuns,
  listScheduleSkips,
  humanizeTriggerType,
} from '@/lib/workflows';
import { WorkflowStatsStrip } from '@/components/workflow/WorkflowStatsStrip';
import { Button } from '@/components/ui/button';
import { StatusBadge } from '@/components/ui/status-badge';
import { CopyableId } from '@/components/ui/copyable-id';
import { Skeleton } from '@/components/ui/skeleton';
import { EmptyState } from '@/components/ui/empty-state';
import { Alert } from '@/components/ui/alert';
import { Card } from '@/components/ui/card';
import { Tabs, type TabItem } from '@/components/ui/tabs';
import { ConfirmModal } from '@/components/ui/confirm-modal';
import { RowActionsMenu } from '@/components/ui/RowActionsMenu';
import { Can } from '@/components/auth/Can';
import { useToast } from '@/components/ui/toast';
import { ListIcon, InboxIcon, XCircleIcon } from 'lucide-react';
import { formatElapsed, formatDate, timeAgo } from '@/lib/format';

const THEAD_CELL = 'text-left px-3 py-2 text-[11.5px] font-semibold uppercase tracking-wide text-muted-foreground';
const PAGE_SIZE = 50;
// Larger than the old Overview tab's 5-run sample so the strip's success rate means something —
// this is a stable recent-history sample, deliberately independent of the active filter tab below
// (a Queued-filtered sample of mostly-pending runs would make "success rate" meaningless).
const HISTORY_SAMPLE_SIZE = 20;
// listScheduleSkips has no time filter server-side — it returns the most recent `limit` skips
// ever, across the workflow's whole history. Fetch a modest page and filter to this window
// client-side so a long-lived `concurrency: single` workflow doesn't show a permanent "skipped"
// alert for something that happened months ago.
const SKIP_FETCH_LIMIT = 50;
const SKIP_LOOKBACK_MS = 24 * 60 * 60 * 1000;

type RunFilter = 'all' | 'queued' | 'running';

const NONTERMINAL_STATUSES = new Set(['PENDING', 'PENDING_LOCAL_PICKUP', 'RUNNING', 'CANCELLING']);
const CANCELABLE_STATUSES = new Set(['PENDING', 'PENDING_LOCAL_PICKUP', 'RUNNING']);

/** Maps the tab to the backend's derived `?state=` value — the raw `status` list is no longer used
 *  for filtering here (see the module comment on `listWorkflowRuns` for why the two are exclusive). */
function stateForFilter(filter: RunFilter): 'queued' | 'running' | undefined {
  if (filter === 'queued') return 'queued';
  if (filter === 'running') return 'running';
  return undefined;
}

function RunListContent() {
  const { projectId, workflowId } = useParams<{ projectId: string; workflowId: string }>();
  const { accessToken } = useAuth();
  const { workflow } = useWorkflow();
  const router = useRouter();
  const pathname = usePathname();
  const searchParams = useSearchParams();
  const { showToast } = useToast();

  // The URL param is named `state` (not `status`) to match what it actually sends the API — see
  // stateForFilter/listWorkflowRuns. Renamed from the original `?status=queued` shipped in the prior
  // phase; nothing outside this branch has linked to it yet, so there's no back-compat cost, and
  // keeping the two names in sync avoids a param that means one thing in the URL and another on the wire.
  const rawFilter = searchParams.get('state');
  const filter: RunFilter = rawFilter === 'queued' || rawFilter === 'running' ? rawFilter : 'all';

  function setFilter(next: string) {
    if (next === filter) return;
    const sp = new URLSearchParams(searchParams.toString());
    if (next === 'all') sp.delete('state');
    else sp.set('state', next);
    const qs = sp.toString();
    router.replace(qs ? `${pathname}?${qs}` : pathname);
    setPage(0);
  }

  const [page, setPage] = useState(0);
  const [tableRuns, setTableRuns] = useState<WorkflowRunDto[]>([]);
  const [historyRuns, setHistoryRuns] = useState<WorkflowRunDto[]>([]);
  // The queued count has to be known regardless of which filter tab is active (for the segment
  // badge and the bulk-cancel button), so it's fetched separately from the table's own query.
  const [queuedCount, setQueuedCount] = useState(0);
  const [queuedAtLeast, setQueuedAtLeast] = useState(false);
  // Already filtered to the last 24h and capped-page-detected at fetch time (see the effect below) —
  // `Date.now()` has to run outside render (an impure call in the component body would violate the
  // Rules of React), so the filtering happens once when the response arrives, not on every render.
  const [skips, setSkips] = useState<WorkflowScheduleSkipDto[]>([]);
  const [skipsAtLeast, setSkipsAtLeast] = useState(false);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  // Set whenever the table/queued probe or the history sample fails, so a failed poll reads as an
  // honest error rather than silently rendering "No runs yet" as if the workflow had no runs.
  const [loadError, setLoadError] = useState<string | null>(null);
  const [cancellingRunId, setCancellingRunId] = useState<string | null>(null);
  const [bulkCancelOpen, setBulkCancelOpen] = useState(false);
  const [bulkCancelling, setBulkCancelling] = useState(false);
  const isFirstLoad = useRef(true);

  // The table + queued-count probe are what the 5s poll needs to keep live (row statuses, the
  // segment badge, the bulk-cancel button). Kept as its own function so the poll doesn't also pay
  // for the history sample below, which doesn't need 5s freshness.
  const fetchTableAndQueued = useCallback(() => {
    if (!accessToken) return Promise.resolve();
    return Promise.all([
      listWorkflowRuns(projectId, workflowId, accessToken, { page, size: PAGE_SIZE, state: stateForFilter(filter) }),
      listWorkflowRuns(projectId, workflowId, accessToken, { page: 0, size: PAGE_SIZE, state: 'queued' }),
    ]).then(([table, queued]) => {
      setTableRuns(table);
      setQueuedCount(queued.length);
      // The list endpoint has no total count — if the queued probe came back full, the real number
      // could be higher. Say "50+" rather than inventing a total the API can't give us.
      setQueuedAtLeast(queued.length === PAGE_SIZE);
      setLoadError(null);
    }).catch((e) => {
      // This also runs every 5s from the poll below — without a catch here, a transient failure
      // becomes an unhandled rejection and the table silently keeps showing whatever it last had
      // (or nothing, on first load) as if that were the truth. Say so instead.
      setLoadError(apiErrorMessage(e, "Couldn't load runs — try again."));
    });
  }, [projectId, workflowId, accessToken, page, filter]);

  // The stats-strip sample: independent of the filter/page above, and — unlike the table — doesn't
  // need 5s freshness, so it's fetched once per mount/filter/page change rather than on every poll tick.
  const fetchHistory = useCallback(() => {
    if (!accessToken) return Promise.resolve();
    return listWorkflowRuns(projectId, workflowId, accessToken, { page: 0, size: HISTORY_SAMPLE_SIZE })
      .then(setHistoryRuns)
      .catch((e) => {
        setLoadError((prev) => prev ?? apiErrorMessage(e, "Couldn't load run history — try again."));
      });
  }, [projectId, workflowId, accessToken]);

  useEffect(() => {
    if (isFirstLoad.current) {
      Promise.all([fetchTableAndQueued(), fetchHistory()]).finally(() => {
        setLoading(false);
        isFirstLoad.current = false;
      });
    } else {
      setRefreshing(true);
      Promise.all([fetchTableAndQueued(), fetchHistory()]).finally(() => setRefreshing(false));
    }
  }, [fetchTableAndQueued, fetchHistory]);

  useEffect(() => {
    if (!accessToken) return;
    listScheduleSkips(projectId, workflowId, accessToken, SKIP_FETCH_LIMIT).then((all) => {
      // Backend returns newest-first with no time filter — restrict to the lookback window here so a
      // long-lived `concurrency: single` workflow doesn't show a permanently "stale-skipped" alert.
      const cutoffMs = Date.now() - SKIP_LOOKBACK_MS;
      const recent = all.filter(s => new Date(s.skippedAt).getTime() >= cutoffMs);
      setSkips(recent);
      // If every returned skip (a full page) is still inside the window, there could be more recent
      // skips beyond SKIP_FETCH_LIMIT we never saw — say "N+" rather than a count the API can't back up.
      setSkipsAtLeast(recent.length === all.length && all.length === SKIP_FETCH_LIMIT);
    }).catch(() => {
      // Non-fatal — the alert simply doesn't render.
    });
  }, [projectId, workflowId, accessToken]);

  // Conditional polling: only while there's something that could still change — a queued run
  // (which may start or be cancelled elsewhere) or a non-terminal run in the current page. Only
  // the table/queued-count probe polls; the history sample doesn't need 5s freshness (see above).
  useEffect(() => {
    const active = queuedCount > 0 || tableRuns.some(r => NONTERMINAL_STATUSES.has(r.status));
    if (!active) return;
    const interval = setInterval(fetchTableAndQueued, 5000);
    return () => clearInterval(interval);
  }, [queuedCount, tableRuns, fetchTableAndQueued]);

  const handleRowCancel = async (runId: string) => {
    if (!accessToken) return;
    setCancellingRunId(runId);
    try {
      await cancelWorkflowRun(projectId, workflowId, runId, accessToken);
      showToast('Cancellation requested.', 'success');
      await Promise.all([fetchTableAndQueued(), fetchHistory()]);
    } catch (e) {
      if ((e as ApiError).status === 409) {
        showToast('That run already finished.', 'success');
        await Promise.all([fetchTableAndQueued(), fetchHistory()]);
      } else {
        showToast(apiErrorMessage(e, "Couldn't cancel this run — try again."), 'error');
      }
    } finally {
      setCancellingRunId(null);
    }
  };

  const handleBulkCancel = async () => {
    if (!accessToken) return;
    setBulkCancelling(true);
    try {
      const { cancelledCount } = await cancelQueuedWorkflowRuns(projectId, workflowId, accessToken);
      showToast(`Cancelled ${cancelledCount} queued run${cancelledCount === 1 ? '' : 's'}.`, 'success');
      setBulkCancelOpen(false);
      await Promise.all([fetchTableAndQueued(), fetchHistory()]);
    } catch (e) {
      showToast(apiErrorMessage(e, "Couldn't cancel queued runs — try again."), 'error');
    } finally {
      setBulkCancelling(false);
    }
  };

  const tabItems: TabItem[] = [
    { value: 'queued', label: 'Queued', count: queuedCount > 0 ? (queuedAtLeast ? '50+' : queuedCount) : undefined },
    { value: 'running', label: 'Running' },
    { value: 'all', label: 'All' },
  ];

  const emptyCopy =
    filter === 'queued'
      ? { icon: InboxIcon, title: 'Nothing queued', description: "New runs will show up here as soon as they're dispatched." }
      : filter === 'running'
        ? { icon: ListIcon, title: 'No runs in progress', description: 'Runs currently executing will appear here.' }
        : { icon: ListIcon, title: 'No runs yet', description: 'Use Run above to trigger this workflow.' };

  if (loading) {
    return (
      <div className="space-y-2">
        {[0, 1, 2].map((i) => <Skeleton key={i} className="h-10 w-full" />)}
      </div>
    );
  }

  return (
    <div className="space-y-4">
      {skips.length > 0 && (
        <Alert variant="info">
          <p>
            {skipsAtLeast ? `${skips.length}+` : skips.length} scheduled run
            {skipsAtLeast || skips.length !== 1 ? 's' : ''} skipped in the last 24 hours —
            a run was already in progress. Most recent {timeAgo(skips[0].skippedAt)}.
          </p>
        </Alert>
      )}

      {/* WorkflowStatsStrip renders its own "No runs yet." for an empty sample — suppress it here
          so a brand-new workflow doesn't show that sentence twice (the EmptyState below says it too). */}
      {historyRuns.length > 0 && <WorkflowStatsStrip runs={historyRuns} />}

      <div className="flex flex-wrap items-center justify-between gap-3">
        <Tabs items={tabItems} value={filter} onValueChange={setFilter} ariaLabel="Filter runs by status" />
        {queuedCount > 0 && (
          <Can do="workflow.run">
            <Button variant="outline" size="sm" onClick={() => setBulkCancelOpen(true)}>
              Cancel queued runs ({queuedAtLeast ? '50+' : queuedCount})
            </Button>
          </Can>
        )}
      </div>

      {filter === 'queued' && queuedCount > 0 && (
        <Alert variant="warning">
          <p>
            New runs will keep joining this queue until <strong>{workflow?.name ?? 'this workflow'}</strong>{' '}
            is disabled.{' '}
            {/* The workflow menu (Disable) lives behind workflow.manage (see layout.tsx) — don't point a
                workflow.run-only user at a control they can't see. */}
            <Can do="workflow.manage">Use the workflow menu above to pause intake.</Can>
          </p>
        </Alert>
      )}

      {loadError && (
        <Alert variant="destructive">
          <p>{loadError}</p>
        </Alert>
      )}

      {refreshing ? (
        <div className="space-y-2">
          {[0, 1, 2].map((i) => <Skeleton key={i} className="h-10 w-full" />)}
        </div>
      ) : tableRuns.length === 0 ? (
        // Don't claim "No runs yet" when the fetch actually failed — the error alert above already
        // says what happened; rendering both would contradict each other.
        loadError ? null : (
          <EmptyState icon={emptyCopy.icon} title={emptyCopy.title} description={emptyCopy.description} />
        )
      ) : (
        <Card className="overflow-x-auto">
          <table className="w-full min-w-[720px]">
            <thead className="bg-muted border-b border-border">
              <tr>
                <th className={THEAD_CELL}>Status</th>
                <th className={THEAD_CELL}>Run ID</th>
                <th className={THEAD_CELL}>Trigger</th>
                <th className={THEAD_CELL}>Started / Queued</th>
                <th className={THEAD_CELL}>Duration / Waiting</th>
                <th className={`${THEAD_CELL} w-12`} />
              </tr>
            </thead>
            <tbody className="divide-y divide-border">
              {tableRuns.map((run) => {
                // A run reads as "Queued" when it hasn't started (PENDING/PENDING_LOCAL_PICKUP) OR it's
                // blocked on an unclaimed self-hosted job — which the run-level status reports as RUNNING
                // (see WorkflowJobOrchestrator.planJobExecution). waitReason is the signal for that second
                // case; checking status alone would render a runner-blocked run as "Running · waiting for
                // runner", contradicting both the Queued tab it's filtered into and the qualifier next to it.
                const queued = run.status === 'PENDING' || run.status === 'PENDING_LOCAL_PICKUP' || !!run.waitReason;
                const cancelable = CANCELABLE_STATUSES.has(run.status);
                return (
                  <tr
                    key={run.id}
                    className="h-[38px] hover:bg-muted cursor-pointer transition-colors"
                    onClick={() => router.push(`/app/projects/${projectId}/workflows/${workflowId}/runs/${run.id}`)}
                  >
                    <td className="px-3 py-2">
                      <div className="flex items-center gap-1.5">
                        <StatusBadge status={run.status} label={queued ? 'Queued' : undefined} />
                        {run.waitReason === 'AWAITING_RUNNER' && (
                          <span className="text-xs text-muted-foreground">· waiting for runner</span>
                        )}
                      </div>
                    </td>
                    <td className="px-3 py-2" onClick={(e) => e.stopPropagation()}>
                      <CopyableId id={run.id} />
                    </td>
                    <td className="px-3 py-2 text-sm text-muted-foreground">{humanizeTriggerType(run.triggerType)}</td>
                    <td className="px-3 py-2 text-sm">{formatDate(run.startedAt)}</td>
                    {/* startedAt is set at run creation and never updated, so for a still-queued run
                        this is genuinely the wait time. For a RUNNING run it also includes the queue
                        wait, folding it into "duration" — a known gap this phase doesn't address. */}
                    <td className="px-3 py-2 text-sm">{formatElapsed(run.startedAt, run.completedAt)}</td>
                    <td className="px-3 py-2 text-right" onClick={(e) => e.stopPropagation()}>
                      {cancelable && (
                        <Can do="workflow.run">
                          <RowActionsMenu
                            extraItems={[
                              {
                                label: cancellingRunId === run.id ? 'Cancelling…' : 'Cancel run',
                                icon: <XCircleIcon className="h-4 w-4" />,
                                onSelect: () => handleRowCancel(run.id),
                                destructive: true,
                              },
                            ]}
                          />
                        </Can>
                      )}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
          {tableRuns.length === PAGE_SIZE && (
            <div className="flex justify-center gap-2 p-3 border-t border-border">
              <Button variant="outline" size="sm" disabled={page === 0} onClick={() => setPage(p => p - 1)}>Previous</Button>
              <Button variant="outline" size="sm" onClick={() => setPage(p => p + 1)}>Next</Button>
            </div>
          )}
        </Card>
      )}

      <ConfirmModal
        open={bulkCancelOpen}
        title="Cancel queued runs"
        confirmLabel="Cancel queued runs"
        busyLabel="Cancelling…"
        destructive={false}
        busy={bulkCancelling}
        onConfirm={handleBulkCancel}
        onCancel={() => setBulkCancelOpen(false)}
      >
        <p className="text-sm text-foreground">
          Cancel {queuedAtLeast ? 'all 50+' : `all ${queuedCount}`} queued run{queuedCount === 1 ? '' : 's'}?{' '}
          Runs already in progress will not be affected.
        </p>
      </ConfirmModal>
    </div>
  );
}

export default function RunListPage() {
  return (
    <Suspense
      fallback={
        <div className="space-y-2">
          {[0, 1, 2].map((i) => <Skeleton key={i} className="h-10 w-full" />)}
        </div>
      }
    >
      <RunListContent />
    </Suspense>
  );
}
