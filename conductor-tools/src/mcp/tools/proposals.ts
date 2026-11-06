import { Config } from '../config.js'
import { apiPost, apiPostWithStatus } from '../api.js'

/**
 * Weekly growth proposals (`/marketing/proposals`) and the workflow that makes them
 * (`/marketing/weekly-growth/install`). A proposal is a PLANNED change on the MARKETING_CHANGES workflow: the server
 * refuses a signal the verification ledger has not verified and a fourth proposal in an ISO week, and a person moves
 * a change to LIVE. This file only checks what can be checked without the server (shape, duplicates, dates) so a
 * malformed call costs no round trip; the evidence rules stay on the server, whose reason is passed through as is.
 */

export const SIGNALS_PER_PROPOSAL = 2

const WINDOW = /^(?:7d|28d|90d|\d{4}-\d{2}-\d{2}\.\.\d{4}-\d{2}-\d{2})$/
const DAY = /^\d{4}-\d{2}-\d{2}$/

export interface ProposalSignal {
  metricRef: string
  window: string
  value: number
  note?: string
}

export interface ProposeMarketingChangeParams {
  title: string
  type: string
  hypothesis: string
  reviewOn: string
  signals: ProposalSignal[]
  runId?: string
}

interface ProposalDto {
  workItemId: string
  displayId: string
  proposalsThisWeek: number
  weeklyLimit: number
}

interface InstallDto {
  workflowId: string
  name: string
  enabled: boolean
  created: boolean
}

function text(name: string, value: unknown): string {
  const trimmed = typeof value === 'string' ? value.trim() : ''
  if (!trimmed) throw new Error(`${name} is required`)
  return trimmed
}

function isRealDay(day: string): boolean {
  if (!DAY.test(day)) return false
  const parsed = new Date(`${day}T00:00:00Z`)
  return !Number.isNaN(parsed.getTime()) && parsed.toISOString().slice(0, 10) === day
}

/** The metric with its channel upper-cased, the way the server compares two metrics. */
function normalizedRef(ref: string): string {
  const dot = ref.indexOf('.')
  return ref.slice(0, dot).toUpperCase() + ref.slice(dot)
}

function checkedSignal(index: number, raw: unknown): Record<string, unknown> {
  const at = `signals[${index}]`
  const s = (raw ?? {}) as Partial<ProposalSignal>
  const metricRef = typeof s.metricRef === 'string' ? s.metricRef.trim() : ''
  const dot = metricRef.indexOf('.')
  if (dot < 1 || dot === metricRef.length - 1) {
    throw new Error(`${at}.metricRef must look like CHANNEL.metric, for example ADS.installs`)
  }
  const window = typeof s.window === 'string' ? s.window.trim() : ''
  if (!WINDOW.test(window)) {
    throw new Error(`${at}.window must be 7d, 28d, 90d or a range YYYY-MM-DD..YYYY-MM-DD`)
  }
  if (typeof s.value !== 'number' || !Number.isFinite(s.value)) {
    throw new Error(`${at}.value must be the ledger's figure for that window, a number`)
  }
  const out: Record<string, unknown> = { metricRef, window, value: s.value }
  const note = typeof s.note === 'string' ? s.note.trim() : ''
  if (note) out['note'] = note
  return out
}

export async function proposeMarketingChange(
  config: Config,
  p: ProposeMarketingChangeParams
): Promise<Record<string, unknown>> {
  const title = text('title', p.title)
  const type = text('type', p.type)
  const hypothesis = text('hypothesis', p.hypothesis)
  const reviewOn = typeof p.reviewOn === 'string' ? p.reviewOn.trim() : ''
  if (!isRealDay(reviewOn)) throw new Error('reviewOn must be the day to review the result, YYYY-MM-DD, after today')
  if (!Array.isArray(p.signals) || p.signals.length !== SIGNALS_PER_PROPOSAL) {
    const got = Array.isArray(p.signals) ? p.signals.length : 0
    throw new Error(`A proposal needs exactly ${SIGNALS_PER_PROPOSAL} signals on different metrics; got ${got}`)
  }
  const signals = p.signals.map((s, i) => checkedSignal(i, s))
  const refs = signals.map((s) => normalizedRef(s['metricRef'] as string))
  if (refs[0] === refs[1]) {
    throw new Error(`The two signals must be on different metrics; ${refs[0]} appears twice`)
  }

  const body: Record<string, unknown> = { title, type, hypothesis, reviewOn, signals }
  const runId = typeof p.runId === 'string' ? p.runId.trim() : ''
  if (runId) body['runId'] = runId
  const made = await apiPost<ProposalDto>(`/api/v2/projects/${config.projectId}/marketing/proposals`, body, config)
  return {
    proposed: true,
    workItemId: made.workItemId,
    displayId: made.displayId,
    status: 'PLANNED',
    proposalsThisWeek: made.proposalsThisWeek,
    weeklyLimit: made.weeklyLimit,
    next: 'A person reviews it and marks it live or abandons it; this tool cannot. Nothing was changed on any ad platform.',
  }
}

export async function installWeeklyGrowthWorkflow(config: Config): Promise<Record<string, unknown>> {
  const { data } = await apiPostWithStatus<InstallDto>(
    `/api/v2/projects/${config.projectId}/marketing/weekly-growth/install`,
    {},
    config
  )
  const out: Record<string, unknown> = {
    installed: data.created,
    workflowId: data.workflowId,
    name: data.name,
    enabled: data.enabled,
  }
  if (data.created) {
    out['next'] =
      'Installed disabled, so nothing runs yet. Set a runtime target for Claude Code steps (Settings > AI Providers > Runtime), then a person enables it under Workflows.'
  } else if (data.enabled) {
    out['next'] = 'A workflow with this name was already there and is enabled; it was left unchanged.'
  } else {
    out['next'] =
      'A workflow with this name was already there and is still disabled; it was left unchanged. A person enables it under Workflows once a runtime target for Claude Code steps is set.'
  }
  return out
}
