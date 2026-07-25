// TypeScript mirror of the automation workflow YAML shape (jobs:/on: schema, GitHub-Actions-style).
//
// Authoritative shape: conductor-backend/src/main/java/com/conductor/workflow/model/ (WorkflowSpec,
// JobSpec, StepSpec) and StepSchemaRegistry for the exact set of step types and their config fields.
// This file only normalizes the YAML into a typed shape for rendering — it does not re-implement
// backend validation; the backend re-validates on save/publish regardless of what this parses.

// eslint-disable-next-line @typescript-eslint/no-require-imports
const jsYaml = require('js-yaml') as typeof import('js-yaml')

export type StepKind =
  | 'http'
  | 'docker'
  | 'kestra'
  | 'condition'
  | 'integration'
  | 'action'
  | 'agent'
  | 'claude-code'

export type TriggerKind =
  | 'workflow_dispatch'
  | 'webhook'
  | 'work_item_status_changed'
  | 'github_pull_request'
  | 'schedule'

export interface WorkflowStep {
  stepId?: string
  name?: string
  kind: StepKind
  raw: Record<string, unknown>
  /** condition steps only — the job id branched to when the expression is true/false */
  then?: string
  else?: string
  /** condition steps only */
  expression?: string
}

export interface WorkflowJob {
  jobId: string
  steps: WorkflowStep[]
  needs: string[]
  runsOn?: string
  ifExpr?: string
  loop?: { maxIterations?: number }
}

export interface WorkflowTrigger {
  kind: TriggerKind
  raw: Record<string, unknown>
  /** schedule triggers only */
  cron?: string
  /** workflow_dispatch triggers only — defaults to true when the YAML omits it, matching the
   *  backend's TriggersSpec#allowsManualDispatch default */
  manual?: boolean
}

export interface ParsedWorkflow {
  triggers: WorkflowTrigger[]
  jobs: WorkflowJob[]
}

/**
 * Resolves a step's YAML node to its backend-defined kind, mirroring StepSpec's Java doc exactly:
 * a `uses: docker://...` step resolves to "docker"; any other non-null `uses:` resolves to itself
 * (`integration`/`agent`/`claude-code`/`action`); otherwise the explicit `type:` is used, defaulting
 * to "http".
 */
export function resolveStepKind(step: Record<string, unknown>): StepKind {
  const uses = step['uses']
  if (typeof uses === 'string' && uses.length > 0) {
    if (uses.startsWith('docker://')) return 'docker'
    if (isStepKind(uses)) return uses
  }
  const type = step['type']
  if (typeof type === 'string' && isStepKind(type)) return type
  return 'http'
}

const STEP_KINDS: readonly StepKind[] = [
  'http', 'docker', 'kestra', 'condition', 'integration', 'action', 'agent', 'claude-code',
]

function isStepKind(value: string): value is StepKind {
  return (STEP_KINDS as readonly string[]).includes(value)
}

function parseStep(raw: unknown): WorkflowStep | null {
  if (!raw || typeof raw !== 'object') return null
  const rawStep = raw as Record<string, unknown>
  const kind = resolveStepKind(rawStep)
  const step: WorkflowStep = {
    stepId: typeof rawStep['id'] === 'string' ? rawStep['id'] : undefined,
    name: typeof rawStep['name'] === 'string' ? rawStep['name'] : undefined,
    kind,
    raw: rawStep,
  }
  if (kind === 'condition') {
    step.then = typeof rawStep['then'] === 'string' ? rawStep['then'] : undefined
    step.else = typeof rawStep['else'] === 'string' ? rawStep['else'] : undefined
    step.expression = typeof rawStep['expression'] === 'string' ? rawStep['expression'] : undefined
  }
  return step
}

function parseJob(jobId: string, raw: unknown): WorkflowJob {
  const rawJob = (raw && typeof raw === 'object' ? raw as Record<string, unknown> : {})
  const rawSteps = Array.isArray(rawJob['steps']) ? rawJob['steps'] : []
  const steps = rawSteps.map(parseStep).filter((s): s is WorkflowStep => s !== null)

  const needsValue = rawJob['needs']
  const needs = needsValue
    ? (Array.isArray(needsValue) ? needsValue.map(String) : [String(needsValue)])
    : []

  const loopBlock = rawJob['loop']
  const loop = loopBlock && typeof loopBlock === 'object'
    ? { maxIterations: toNumberOrUndefined((loopBlock as Record<string, unknown>)['max_iterations']) }
    : undefined

  return {
    jobId,
    steps,
    needs,
    runsOn: typeof rawJob['runs-on'] === 'string' ? rawJob['runs-on'] : undefined,
    ifExpr: typeof rawJob['if'] === 'string' ? rawJob['if'] : undefined,
    loop,
  }
}

function toNumberOrUndefined(value: unknown): number | undefined {
  const n = Number(value)
  return Number.isFinite(n) ? n : undefined
}

function parseTriggers(onBlock: unknown): WorkflowTrigger[] {
  if (!onBlock || typeof onBlock !== 'object') return []
  const on = onBlock as Record<string, unknown>
  const triggers: WorkflowTrigger[] = []

  if ('workflow_dispatch' in on) {
    const raw = asRecord(on['workflow_dispatch'])
    triggers.push({ kind: 'workflow_dispatch', raw, manual: raw['manual'] !== false })
  }
  if ('webhook' in on) {
    triggers.push({ kind: 'webhook', raw: asRecord(on['webhook']) })
  }
  if ('conductor.work_item.status_changed' in on) {
    triggers.push({ kind: 'work_item_status_changed', raw: asRecord(on['conductor.work_item.status_changed']) })
  }
  if ('github.pull_request' in on) {
    triggers.push({ kind: 'github_pull_request', raw: asRecord(on['github.pull_request']) })
  }
  if ('schedule' in on) {
    const raw = asRecord(on['schedule'])
    triggers.push({
      kind: 'schedule',
      raw,
      cron: typeof raw['cron'] === 'string' ? raw['cron'] : undefined,
    })
  }
  return triggers
}

function asRecord(value: unknown): Record<string, unknown> {
  return value && typeof value === 'object' ? value as Record<string, unknown> : {}
}

/**
 * True for a `workflow_dispatch` trigger a human can actually fire — false when the workflow opts
 * out with `on.workflow_dispatch.manual: false`, used by system-dispatched workflows like the
 * Knowledge Librarian whose event payload is built by the process that dispatches them, not by a
 * human clicking Run. Always true for other trigger kinds, which have no such ambiguity. Mirrors
 * the backend's `TriggersSpec#allowsManualDispatch`.
 */
export function isManualTrigger(trigger: WorkflowTrigger): boolean {
  return trigger.kind !== 'workflow_dispatch' || trigger.manual !== false
}

/** Throws on invalid YAML, matching the previous inline-parser behavior callers already handle. */
export function parseWorkflowYaml(yamlText: string): ParsedWorkflow {
  if (!yamlText.trim()) return { triggers: [], jobs: [] }

  let parsed: unknown
  try {
    parsed = jsYaml.load(yamlText)
  } catch {
    throw new Error('Invalid YAML')
  }
  if (!parsed || typeof parsed !== 'object') return { triggers: [], jobs: [] }

  const workflow = parsed as Record<string, unknown>
  const triggers = parseTriggers(workflow['on'])

  const jobsBlock = workflow['jobs']
  const jobs = jobsBlock && typeof jobsBlock === 'object'
    ? Object.entries(jobsBlock as Record<string, unknown>).map(([jobId, raw]) => parseJob(jobId, raw))
    : []

  return { triggers, jobs }
}
