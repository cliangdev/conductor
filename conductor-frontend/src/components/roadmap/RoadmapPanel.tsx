'use client'

import { useMemo } from 'react'
import { MapIcon } from 'lucide-react'
import { EmptyState } from '@/components/ui/empty-state'
import { Skeleton } from '@/components/ui/skeleton'
import { MilestoneStrip } from '@/components/roadmap/MilestoneStrip'
import { NowNextLaterBoard } from '@/components/roadmap/NowNextLaterBoard'
import { useDeliveryOverview, useFetched, useItemWorkflowLookups, useMilestones, workItemsPath } from '@/components/roadmap/useRoadmapData'
import type { Issue } from '@/components/workitems/listTypes'

function RoadmapSkeleton() {
  return (
    <div className="grid gap-4 md:grid-cols-3" aria-busy="true">
      {[0, 1, 2].map((i) => (
        <Skeleton key={i} className="h-64" />
      ))}
    </div>
  )
}

export function RoadmapPanel({ projectId }: { projectId: string }) {
  // Top-level PRDs only: the list default hides sub-items.
  const prds = useFetched<Issue[]>(workItemsPath(projectId, 'type=PRD&withProgress=true'))
  const milestones = useMilestones(projectId)
  const { categoryOf, hrefOf, ready } = useItemWorkflowLookups(projectId, prds.data)
  // Forecasts ride along on the one overview call. They only refine bar ends, so a failure is ignored.
  const overview = useDeliveryOverview(projectId)
  const forecastP85ById = useMemo(
    () => Object.fromEntries((overview.data?.activeItems ?? []).map((a) => [a.item.id, a.forecast?.p85Date ?? null])),
    [overview.data]
  )

  const error = prds.error ?? milestones.error
  // Until the Workflow views are in every PRD reads as open, so finished ones would flash onto the board.
  if (prds.loading || milestones.loading || (!error && !ready)) return <RoadmapSkeleton />
  if (error || !prds.data) {
    return <EmptyState icon={MapIcon} title="Could not load the roadmap" description={error ?? undefined} />
  }
  const active = prds.data.filter((i) => categoryOf(i) !== 'terminal')
  if (active.length === 0) {
    return <EmptyState icon={MapIcon} title="No open PRDs" description="PRDs that are not finished show up here, grouped by when they are due." />
  }

  return (
    <>
      <MilestoneStrip items={active} milestones={milestones.data ?? []} forecastP85ById={forecastP85ById} />
      <NowNextLaterBoard items={active} milestones={milestones.data ?? []} categoryOf={categoryOf} hrefOf={hrefOf} />
    </>
  )
}
