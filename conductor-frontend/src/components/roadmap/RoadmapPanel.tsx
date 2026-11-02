'use client'

import { MapIcon } from 'lucide-react'
import { EmptyState } from '@/components/ui/empty-state'
import { Skeleton } from '@/components/ui/skeleton'
import { MilestoneStrip } from '@/components/roadmap/MilestoneStrip'
import { NowNextLaterBoard } from '@/components/roadmap/NowNextLaterBoard'
import { useFetched, useItemWorkflowLookups, useMilestones, workItemsPath } from '@/components/roadmap/useRoadmapData'
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
  const { categoryOf, hrefOf } = useItemWorkflowLookups(projectId, prds.data)

  if (prds.loading || milestones.loading) return <RoadmapSkeleton />
  const error = prds.error ?? milestones.error
  if (error || !prds.data) {
    return <EmptyState icon={MapIcon} title="Could not load the roadmap" description={error ?? undefined} />
  }
  const active = prds.data.filter((i) => categoryOf(i) !== 'terminal')
  if (active.length === 0) {
    return <EmptyState icon={MapIcon} title="No open PRDs" description="PRDs that are not finished show up here, grouped by when they are due." />
  }

  return (
    <>
      <MilestoneStrip items={active} milestones={milestones.data ?? []} />
      <NowNextLaterBoard items={active} milestones={milestones.data ?? []} categoryOf={categoryOf} hrefOf={hrefOf} />
    </>
  )
}
