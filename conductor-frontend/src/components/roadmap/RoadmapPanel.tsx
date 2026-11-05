'use client'

import { useMemo } from 'react'
import { MapIcon } from 'lucide-react'
import { EmptyState } from '@/components/ui/empty-state'
import { Skeleton } from '@/components/ui/skeleton'
import { MilestoneStrip } from '@/components/roadmap/MilestoneStrip'
import { NowNextLaterBoard } from '@/components/roadmap/NowNextLaterBoard'
import { overviewRowToIssue, useDeliveryOverview, useItemWorkflowLookups } from '@/components/roadmap/useRoadmapData'

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
  // One call: the open items (the server decides what is active), their forecasts and the milestones.
  const overview = useDeliveryOverview(projectId)
  const rows = overview.data?.activeItems ?? null
  const prds = useMemo(() => rows?.map(overviewRowToIssue) ?? null, [rows])
  const milestones = overview.data?.milestones
  const { categoryOf, hrefOf, ready } = useItemWorkflowLookups(projectId, prds)
  const forecastP85ById = useMemo(
    () => Object.fromEntries((rows ?? []).map((a) => [a.item.id, a.forecast?.p85Date ?? null])),
    [rows]
  )

  const error = overview.error
  // Until the Workflow views are in every PRD reads as open, so finished ones would flash onto the board.
  if (overview.loading || (!error && !ready)) return <RoadmapSkeleton />
  if (error || !prds || !milestones) {
    return <EmptyState icon={MapIcon} title="Could not load the roadmap" description={error ?? undefined} />
  }
  const active = prds.filter((i) => categoryOf(i) !== 'terminal')
  if (active.length === 0) {
    return <EmptyState icon={MapIcon} title="No open PRDs" description="PRDs that are not finished show up here, grouped by when they are due." />
  }

  return (
    <>
      <MilestoneStrip items={active} milestones={milestones} forecastP85ById={forecastP85ById} />
      <NowNextLaterBoard items={active} milestones={milestones} categoryOf={categoryOf} hrefOf={hrefOf} />
    </>
  )
}
