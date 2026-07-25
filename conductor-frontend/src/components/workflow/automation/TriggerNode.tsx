'use client'

import { Handle, Position } from '@xyflow/react'
import cronstrue from 'cronstrue'
import { cn } from '@/lib/utils'
import { WorkflowTriggerTypeIcon, triggerTypeAvatarColor } from '@/components/workflow/WorkflowStepTypeIcon'
import { AVATAR_COLOR_CLASSES } from '@/components/agents/AgentAvatar'
import { isManualTrigger, type WorkflowTrigger, type TriggerKind } from '@/lib/workflowAutomation'
import { TRIGGER_W, TRIGGER_H } from './dimensions'

const TRIGGER_LABEL: Record<TriggerKind, string> = {
  workflow_dispatch: 'Manual',
  webhook: 'Webhook',
  work_item_status_changed: 'Work item status',
  github_pull_request: 'GitHub PR',
  schedule: 'Schedule',
}

function triggerLabel(trigger: WorkflowTrigger): string {
  if (trigger.kind === 'workflow_dispatch' && !isManualTrigger(trigger)) return 'System-triggered'
  return TRIGGER_LABEL[trigger.kind]
}

function triggerSubtitle(trigger: WorkflowTrigger): string | undefined {
  if (trigger.kind === 'schedule' && trigger.cron) {
    try {
      return cronstrue.toString(trigger.cron, { throwExceptionOnParseError: false })
    } catch {
      return trigger.cron
    }
  }
  return undefined
}

export interface TriggerNodeData {
  trigger: WorkflowTrigger
  [key: string]: unknown
}

export function TriggerNode({ data }: { data: TriggerNodeData }) {
  const { trigger } = data
  const subtitle = triggerSubtitle(trigger)
  const iconColor = AVATAR_COLOR_CLASSES[triggerTypeAvatarColor(trigger.kind)]

  return (
    <div
      className="flex items-center gap-2 rounded-full border border-primary/30 bg-accent-soft px-3 py-2 shadow-sm"
      style={{ width: TRIGGER_W, height: TRIGGER_H }}
    >
      <div className={cn('flex h-6 w-6 shrink-0 items-center justify-center rounded-full', iconColor)}>
        <WorkflowTriggerTypeIcon kind={trigger.kind} className="h-3.5 w-3.5" />
      </div>
      <div className="min-w-0 flex-1">
        <div className="truncate text-[11px] font-semibold text-primary">{triggerLabel(trigger)}</div>
        {subtitle && <div className="truncate text-[10px] text-primary/70">{subtitle}</div>}
      </div>
      <Handle type="source" position={Position.Right} className="!bg-primary" />
    </div>
  )
}
