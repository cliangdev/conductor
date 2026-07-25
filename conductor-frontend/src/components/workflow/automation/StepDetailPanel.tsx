'use client'

import { Sheet } from '@/components/ui/sheet'
import { StatusBadge } from '@/components/ui/status-badge'
import { WorkflowStepTypeIcon } from '@/components/workflow/WorkflowStepTypeIcon'
import { WorkflowLogStream } from '@/components/workflow/WorkflowLogStream'
import { StepOutputsTable } from '@/components/workflow/StepOutputsTable'
import { useWorkflowStepSchema } from '@/lib/workflowStepSchema'
import type { WorkflowStep, StepKind } from '@/lib/workflowAutomation'
import type { WorkflowStepRunDto } from '@/types/workflow'

const KIND_LABEL: Record<StepKind, string> = {
  http: 'HTTP request',
  docker: 'Docker container',
  kestra: 'Kestra flow',
  condition: 'Condition',
  integration: 'Integration',
  action: 'Action',
  agent: 'Agent',
  'claude-code': 'Claude Code',
}

// Structural YAML keys, not step config — excluded from the rendered key/value list.
const STRUCTURAL_KEYS = new Set(['id', 'name', 'type', 'uses', 'with', 'if', 'then', 'else'])

function stepConfigEntries(step: WorkflowStep): [string, unknown][] {
  const withBlock = step.raw['with']
  const source = withBlock && typeof withBlock === 'object' ? withBlock as Record<string, unknown> : step.raw
  return Object.entries(source).filter(([key]) => !STRUCTURAL_KEYS.has(key))
}

interface ConditionRunOutput {
  expression?: string
  result?: boolean
  branch?: string
}

function ConditionDetail({ runData }: { runData: WorkflowStepRunDto }) {
  let conditionData: ConditionRunOutput = {}
  try {
    if (runData.outputJson) conditionData = JSON.parse(runData.outputJson) as ConditionRunOutput
  } catch {}

  const expression = conditionData.expression ?? runData.log ?? '—'
  const result = conditionData.result
  const branch = conditionData.branch

  return (
    <div className="rounded bg-muted/30 p-3 text-xs space-y-1">
      <div>
        <span className="font-medium text-muted-foreground">Expression: </span>
        <code className="font-mono">{expression}</code>
      </div>
      {result !== undefined && (
        <div>
          <span className="font-medium text-muted-foreground">Result: </span>
          <span className={result ? 'text-status-done font-semibold' : 'text-status-failed font-semibold'}>
            {result ? 'true' : 'false'}
          </span>
        </div>
      )}
      {branch && (
        <div>
          <span className="font-medium text-muted-foreground">Branch activated: </span>
          <span className="font-semibold">{branch}</span>
        </div>
      )}
    </div>
  )
}

const MAX_LOG_DISPLAY = 10_000

interface StepDetailPanelProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  step: WorkflowStep | null
  runData?: WorkflowStepRunDto
  runId?: string
  projectId?: string
  token?: string | null
}

export function StepDetailPanel({ open, onOpenChange, step, runData, runId, projectId, token }: StepDetailPanelProps) {
  const schema = useWorkflowStepSchema(projectId, token)
  if (!step) return null

  const fieldSchema = schema?.stepTypes.find(s => s.type === step.kind)
  const configEntries = stepConfigEntries(step)

  const isDockerStep = step.kind === 'docker'
  const isRunningDockerStep = isDockerStep && runData?.status === 'RUNNING'

  const log = runData?.log ?? ''
  const isTruncated = log.length > MAX_LOG_DISPLAY
  const displayLog = isTruncated ? log.slice(-MAX_LOG_DISPLAY) : log

  let outputs: Record<string, unknown> = {}
  try {
    if (runData?.outputJson && step.kind !== 'condition') outputs = JSON.parse(runData.outputJson)
  } catch {}
  const hasOutputs = Object.keys(outputs).length > 0

  return (
    <Sheet
      open={open}
      onOpenChange={onOpenChange}
      title={step.name ?? step.stepId ?? step.kind}
      description={KIND_LABEL[step.kind]}
    >
      <div className="space-y-4">
        <div className="flex items-center gap-2">
          <WorkflowStepTypeIcon kind={step.kind} className="h-4 w-4 text-muted-foreground" />
          {fieldSchema?.description && (
            <span className="text-xs text-muted-foreground">{fieldSchema.description}</span>
          )}
          {runData && <StatusBadge status={runData.status} className="ml-auto" />}
        </div>

        {configEntries.length > 0 && (
          <div>
            <p className="mb-1 text-xs font-medium text-muted-foreground">Configuration</p>
            <dl className="space-y-1.5 rounded border border-border text-xs">
              {configEntries.map(([key, value]) => {
                const field = fieldSchema?.fields.find(f => f.name === key)
                return (
                  <div key={key} className="flex items-start gap-2 border-b border-border px-2 py-1.5 last:border-b-0">
                    <dt className="w-1/3 shrink-0 font-mono text-foreground-subtle" title={field?.description}>
                      {key}
                    </dt>
                    <dd className="min-w-0 flex-1 break-all font-mono">{formatConfigValue(value)}</dd>
                  </div>
                )
              })}
            </dl>
          </div>
        )}

        {runData && step.kind === 'condition' && <ConditionDetail runData={runData} />}

        {runData?.errorReason && (
          <div className="space-y-1">
            <p className="text-xs text-status-failed">{runData.errorReason}</p>
            {runData.explanation && (
              <p className="text-xs text-muted-foreground">{runData.explanation}</p>
            )}
            {runData.remediation && (
              <p className="text-xs text-muted-foreground">→ {runData.remediation}</p>
            )}
          </div>
        )}

        {isRunningDockerStep && runId ? (
          <WorkflowLogStream runId={runId} isRunning />
        ) : (
          displayLog && (
            <div>
              {isTruncated && (
                <p className="mb-1 text-xs text-status-progress">[truncated — showing last 10,000 characters]</p>
              )}
              <pre className="max-h-96 overflow-x-auto overflow-y-auto whitespace-pre-wrap rounded bg-black/90 p-3 text-xs text-green-300">
                {displayLog}
              </pre>
            </div>
          )
        )}

        {hasOutputs && <StepOutputsTable outputs={outputs} />}
      </div>
    </Sheet>
  )
}

function formatConfigValue(value: unknown): string {
  if (typeof value === 'string') return value
  try {
    return JSON.stringify(value)
  } catch {
    return String(value)
  }
}
