import type { Config } from '../config.js'
import { apiGet, apiPost, apiPatch, apiPut, apiDelete } from '../api.js'

/**
 * Delivery tools: the work-item hierarchy, dependencies, acceptance criteria, milestones and agent
 * liveness. API errors (409 on a dependency cycle, 404, ...) are left to throw — the MCP dispatcher
 * turns an ApiError into a structured `{error, status, code, title}` response.
 */

const base = (config: Config) => `/api/v2/projects/${config.projectId}`
const item = (config: Config, id: string) => `${base(config)}/work-items/${id}`

/** Drops null/undefined fields and avatar URLs, recursively, to keep a tree small in context. */
export function stripTree(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(stripTree)
  if (value && typeof value === 'object') {
    const out: Record<string, unknown> = {}
    for (const [k, v] of Object.entries(value as Record<string, unknown>)) {
      if (v === null || v === undefined || k === 'avatarUrl') continue
      out[k] = stripTree(v)
    }
    return out
  }
  return value
}

export async function getWorkItemTree(
  params: { issueId: string },
  config: Config
): Promise<unknown> {
  const tree = await apiGet<unknown>(`${item(config, params.issueId)}/tree`, config)
  return stripTree(tree)
}

export async function addDependency(
  params: { issueId: string; kind: string; relatedIssueId: string },
  config: Config
): Promise<Record<string, unknown>> {
  // The backend answers with the item's grouped dependency listing; the new row sits in the group
  // for the direction we just asserted ("issueId BLOCKS related" lands in `blocks`).
  const res = await apiPost<DependencyListing>(
    `${item(config, params.issueId)}/dependencies`,
    { kind: params.kind, relatedWorkItemId: params.relatedIssueId },
    config
  )
  const group = params.kind === 'DISCOVERED_FROM' ? res.discoveredFrom : res.blocks
  const row = group?.find((d) => d.id === params.relatedIssueId)
  return { dependencyId: row?.dependencyId ?? res.dependencyId ?? res.id }
}

interface DependencyEntry {
  dependencyId?: string
  id?: string
}

interface DependencyListing {
  blocks?: DependencyEntry[]
  blockedBy?: DependencyEntry[]
  discoveredFrom?: DependencyEntry[]
  discovered?: DependencyEntry[]
  dependencyId?: string
  id?: string
}

export async function removeDependency(
  params: { issueId: string; dependencyId: string },
  config: Config
): Promise<Record<string, unknown>> {
  await apiDelete(`${item(config, params.issueId)}/dependencies/${params.dependencyId}`, config)
  return { removed: true }
}

interface Criterion {
  criterionId: string
  text?: string
  kind?: string
  externalRef?: string | null
  met?: boolean
}

export async function setCriteria(
  params: { issueId: string; criteria: Array<{ text: string; kind?: string; externalRef?: string }> },
  config: Config
): Promise<Record<string, unknown>[]> {
  const body = params.criteria.map((c) => ({
    text: c.text,
    kind: c.kind ?? 'auto',
    externalRef: c.externalRef,
  }))
  const rows = await apiPut<Criterion[]>(`${item(config, params.issueId)}/criteria`, body, config)
  return rows.map((r) => ({ criterionId: r.criterionId, externalRef: r.externalRef ?? null }))
}

export async function markCriterionMet(
  params: { issueId: string; criterionId?: string; externalRef?: string; met: boolean; evidence?: string },
  config: Config
): Promise<Record<string, unknown>> {
  const criteriaPath = `${item(config, params.issueId)}/criteria`
  let criterionId = params.criterionId
  if (!criterionId) {
    if (!params.externalRef) return { error: 'Pass criterionId or externalRef.' }
    const rows = await apiGet<Criterion[]>(criteriaPath, config)
    const match = rows.find((r) => r.externalRef === params.externalRef)
    if (!match) {
      return { error: `No criterion with externalRef "${params.externalRef}" on this Work Item.` }
    }
    criterionId = match.criterionId
  }

  const body: Record<string, unknown> = { met: params.met }
  if (params.evidence !== undefined) body['evidence'] = params.evidence
  const res = await apiPatch<Criterion & { criteriaMet?: number; criteriaTotal?: number }>(
    `${criteriaPath}/${criterionId}`,
    body,
    config
  )

  let { criteriaMet, criteriaTotal } = res
  if (criteriaMet === undefined || criteriaTotal === undefined) {
    const rows = await apiGet<Criterion[]>(criteriaPath, config)
    criteriaTotal = rows.length
    criteriaMet = rows.filter((r) => r.met).length
  }
  return { criterionId, met: res.met ?? params.met, criteriaMet, criteriaTotal }
}

interface Milestone {
  id: string
  name: string
  targetOn?: string | null
  state?: string
  progress?: unknown
}

export async function listMilestones(
  _params: Record<string, never>,
  config: Config
): Promise<Record<string, unknown>[]> {
  const rows = await apiGet<Milestone[]>(`${base(config)}/milestones`, config)
  return rows.map((m) => ({
    id: m.id,
    name: m.name,
    targetOn: m.targetOn ?? null,
    state: m.state,
    progress: m.progress,
  }))
}

/** Upsert by name: PATCH the milestone with that name when one exists, otherwise POST a new one. */
export async function setMilestone(
  params: { name: string; targetOn?: string; description?: string; state?: string },
  config: Config
): Promise<Record<string, unknown>> {
  const name = params.name.trim()
  const rows = await apiGet<Milestone[]>(`${base(config)}/milestones`, config)
  // The backend refuses names that collide case-insensitively, so match the same way.
  const existing = rows.find((m) => m.name.trim().toLowerCase() === name.toLowerCase())

  const fields: Record<string, unknown> = {}
  if (params.targetOn !== undefined) fields['targetOn'] = params.targetOn
  if (params.description !== undefined) fields['description'] = params.description
  if (params.state !== undefined) fields['state'] = params.state

  if (existing) {
    if (Object.keys(fields).length > 0) {
      await apiPatch<unknown>(`${base(config)}/milestones/${existing.id}`, fields, config)
    }
    return { id: existing.id, name: existing.name }
  }
  const created = await apiPost<Milestone>(`${base(config)}/milestones`, { name, ...fields }, config)
  return { id: created.id, name: created.name ?? name }
}

export async function reportAgentActivity(
  params: {
    issueId: string
    sessionKey: string
    mode?: string
    activity: string
    note?: string
    agentLabel?: string
  },
  config: Config
): Promise<Record<string, unknown>> {
  const body: Record<string, unknown> = {
    agentLabel: params.agentLabel ?? 'claude-code',
    mode: params.mode ?? 'DELEGATE',
    activity: params.activity,
  }
  if (params.note !== undefined) body['note'] = params.note
  await apiPut<unknown>(
    `${item(config, params.issueId)}/agent-sessions/${encodeURIComponent(params.sessionKey)}`,
    body,
    config
  )
  return { sessionKey: params.sessionKey, activity: params.activity }
}

/** Throughput forecast for a Work Item subtree; the backend object is small, so it is returned as-is. */
export async function getForecast(
  params: { issueId: string },
  config: Config
): Promise<unknown> {
  return apiGet<unknown>(`${item(config, params.issueId)}/forecast`, config)
}

interface HealthUpdate {
  id: string
  health: string
}

/** Drafts a health update; the owner confirms it in the web app. */
export async function draftHealthUpdate(
  params: { issueId: string; health: string; note: string },
  config: Config
): Promise<Record<string, unknown>> {
  const res = await apiPost<HealthUpdate>(
    `${item(config, params.issueId)}/health-updates`,
    { health: params.health, note: params.note, draft: true },
    config
  )
  return { updateId: res.id, health: res.health ?? params.health, pendingConfirmation: true }
}
