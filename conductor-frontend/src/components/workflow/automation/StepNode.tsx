'use client'

import { Handle, Position } from '@xyflow/react'
import { cn } from '@/lib/utils'
import { statusHueClasses } from '@/components/ui/status-badge'
import { statusHue } from '@/lib/workflows'
import { WorkflowStepTypeIcon, stepTypeAvatarColor } from '@/components/workflow/WorkflowStepTypeIcon'
import { AVATAR_COLOR_CLASSES } from '@/components/agents/AgentAvatar'
import type { WorkflowStep } from '@/lib/workflowAutomation'
import type { WorkflowStepRunDto } from '@/types/workflow'
import { STEP_W, STEP_H, CONDITION_W, CONDITION_H } from './dimensions'

export type StepRunStatus = WorkflowStepRunDto['status']

export interface StepNodeData {
  step: WorkflowStep
  /** Full run data when this diagram is rendering a run's live/historical state — carried on the
   * node so a click can open the detail panel with everything (status/log/outputs) already in hand,
   * no separate lookup needed. */
  runData?: WorkflowStepRunDto
  [key: string]: unknown
}

/** A short kind-specific detail read straight off the step's raw config — no network call, so this
 * stays synchronous for the live YAML-editor preview. */
function stepSubtitle(step: WorkflowStep): string | undefined {
  const raw = step.raw
  const withBlock = (raw['with'] && typeof raw['with'] === 'object' ? raw['with'] as Record<string, unknown> : raw)
  switch (step.kind) {
    case 'http':
      return typeof raw['method'] === 'string' ? `${raw['method']} ${typeof raw['url'] === 'string' ? raw['url'] : ''}`.trim() : (typeof raw['url'] === 'string' ? raw['url'] : undefined)
    case 'docker': {
      const uses = raw['uses']
      return typeof uses === 'string' ? uses.replace(/^docker:\/\//, '') : undefined
    }
    case 'kestra':
      return typeof withBlock['flow_id'] === 'string' ? String(withBlock['flow_id']) : undefined
    case 'integration':
    case 'action':
      return typeof withBlock['connector'] === 'string' ? String(withBlock['connector']) : undefined
    case 'agent':
      return typeof withBlock['agent'] === 'string' ? String(withBlock['agent']) : undefined
    case 'claude-code':
      return typeof withBlock['prompt'] === 'string' ? String(withBlock['prompt']).slice(0, 40) : undefined
    case 'condition':
      return typeof step.expression === 'string' ? step.expression.replace(/\$\{\{|\}\}/g, '').trim() : undefined
  }
}

export function StepNode({ data }: { data: StepNodeData }) {
  const { step, runData } = data
  const title = step.name ?? step.stepId ?? step.kind
  const subtitle = stepSubtitle(step)
  const iconColor = AVATAR_COLOR_CLASSES[stepTypeAvatarColor(step.kind)]
  const ring = runData ? statusHueClasses(statusHue(runData.status)) : undefined

  return (
    <div
      className={cn(
        'flex items-center gap-2.5 rounded-lg border border-border bg-surface px-3 py-2 shadow-sm',
        ring && `ring-2 ${ring.ring}`,
      )}
      style={{ width: STEP_W, height: STEP_H }}
    >
      <Handle type="target" position={Position.Left} className="!bg-foreground-subtle" />
      <div className={cn('flex h-8 w-8 shrink-0 items-center justify-center rounded-md', iconColor)}>
        <WorkflowStepTypeIcon kind={step.kind} className="h-4 w-4" />
      </div>
      <div className="min-w-0 flex-1">
        <div className="truncate text-xs font-semibold text-foreground">{title}</div>
        {subtitle && <div className="truncate text-[11px] text-muted-foreground">{subtitle}</div>}
      </div>
      <Handle type="source" position={Position.Right} className="!bg-foreground-subtle" />
    </div>
  )
}

export function ConditionStepNode({ data }: { data: StepNodeData }) {
  const { step, runData } = data
  const title = step.name ?? step.stepId ?? 'condition'
  const style = statusHueClasses(runData ? statusHue(runData.status) : 'gray')

  return (
    <div className="relative flex items-center justify-center" style={{ width: CONDITION_W, height: CONDITION_H }}>
      <Handle type="target" position={Position.Left} className="!bg-foreground-subtle" />
      <div
        className={cn('absolute inset-0 rounded border-2', style.border, style.bg)}
        style={{ transform: 'rotate(45deg)', transformOrigin: 'center' }}
      />
      <div className="relative z-10 flex flex-col items-center gap-0.5 px-2 text-center">
        <WorkflowStepTypeIcon kind="condition" className="h-3.5 w-3.5" />
        <span className="max-w-full truncate text-[10px] font-semibold">{title}</span>
      </div>
      <Handle type="source" position={Position.Right} id="true" className="!bg-status-done" style={{ top: '30%' }} />
      <Handle type="source" position={Position.Right} id="false" className="!bg-status-failed" style={{ top: '70%' }} />
    </div>
  )
}
