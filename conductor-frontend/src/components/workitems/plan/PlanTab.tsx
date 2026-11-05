'use client'

// The detail page's Plan tab: a PRD's work as a tree or as a dependency graph.

import { useState } from 'react'
import { Tabs } from '@/components/ui/tabs'
import { WorkItemTree } from '@/components/workitems/plan/WorkItemTree'
import { DependencyGraph } from '@/components/workitems/plan/DependencyGraph'

type PlanView = 'tree' | 'graph'

export function PlanTab({
  projectId,
  workItemId,
  token,
  canEdit,
}: {
  projectId: string
  workItemId: string
  token: string
  canEdit: boolean
}) {
  const [view, setView] = useState<PlanView>('tree')
  return (
    <div className="space-y-4">
      <Tabs
        ariaLabel="Plan view"
        items={[
          { value: 'tree', label: 'Tree' },
          { value: 'graph', label: 'Graph' },
        ]}
        value={view}
        onValueChange={(v) => setView(v as PlanView)}
        getPanelId={() => 'wi-plan-panel'}
      />
      <div id="wi-plan-panel" role="tabpanel">
        {view === 'tree' ? (
          <WorkItemTree projectId={projectId} rootId={workItemId} token={token} canEdit={canEdit} />
        ) : (
          <DependencyGraph projectId={projectId} rootId={workItemId} token={token} />
        )}
      </div>
    </div>
  )
}
