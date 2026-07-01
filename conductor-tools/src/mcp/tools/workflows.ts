import { Config } from '../config.js'
import { apiGet, apiPost, apiPatch, apiPut } from '../api.js'
import { queueChange } from '../queue.js'

/**
 * COND-18 workflow-aware MCP tools. The surface stays GENERIC + discovery-based: the agent calls
 * list_workflows to learn a project's Workflows (slug + vocabulary), then walks a Work Item by asking
 * get_available_transitions and applying transition_work_item — the Workflow definition drives the walk,
 * so the same tools work for any Workflow. Never generate per-workflow tools.
 */

/**
 * Discovery entry point. Returns each Workflow flattened to the vocabulary an agent needs to pick one:
 * { slug, name, area, noun, kind, state, version, workflowId, types, statuses } — lifting `types`/`statuses`
 * out of the nested `definition` JSON so the agent never parses a statechart to answer "which Workflow?".
 * Filter by `kind` (LIFECYCLE = statechart that governs Work Items; AUTOMATION = YAML run-automation).
 */
export async function listWorkflows(
  params: { kind?: 'LIFECYCLE' | 'AUTOMATION' },
  config: Config
): Promise<unknown[]> {
  const raw = await apiGet<Record<string, unknown>[]>(
    `/api/v1/projects/${config.projectId}/workflows`,
    config
  )
  const list = Array.isArray(raw) ? raw : []
  const filtered = params.kind ? list.filter((w) => w['kind'] === params.kind) : list
  return filtered.map((w) => {
    const def = (w['definition'] as Record<string, unknown> | undefined) ?? undefined
    return {
      slug: w['slug'],
      name: w['name'],
      area: w['area'],
      noun: w['noun'],
      kind: w['kind'],
      state: w['state'],
      version: w['version'],
      workflowId: w['id'],
      types: def?.['types'] ?? [],
      statuses: def?.['statuses'] ?? [],
    }
  })
}

export async function getAvailableTransitions(
  params: { issueId: string },
  config: Config
): Promise<Record<string, unknown>> {
  return apiGet<Record<string, unknown>>(
    `/api/v2/projects/${config.projectId}/work-items/${params.issueId}/available-transitions`,
    config
  )
}

export async function transitionWorkItem(
  params: { issueId: string; toStatus: string },
  config: Config
): Promise<Record<string, unknown>> {
  const path = `/api/v2/projects/${config.projectId}/work-items/${params.issueId}`
  const body = { status: params.toStatus }
  try {
    return await apiPatch<Record<string, unknown>>(path, body, config)
  } catch (err) {
    const size = queueChange({ method: 'PATCH', path, body, timestamp: new Date().toISOString() })
    return {
      issueId: params.issueId,
      toStatus: params.toStatus,
      warning: `Sync failed — change queued: ${err instanceof Error ? err.message : String(err)}`,
      queueSize: size,
    }
  }
}

export async function recordAsset(
  params: { issueId: string; type: string; kind: string; ref: string; label?: string; done?: boolean },
  config: Config
): Promise<Record<string, unknown>> {
  const path = `/api/v2/projects/${config.projectId}/work-items/${params.issueId}/assets`
  const body = {
    type: params.type,
    kind: params.kind,
    ref: params.ref,
    label: params.label,
    done: params.done,
  }
  try {
    return await apiPost<Record<string, unknown>>(path, body, config)
  } catch (err) {
    const size = queueChange({ method: 'POST', path, body, timestamp: new Date().toISOString() })
    return {
      warning: `Sync failed — change queued: ${err instanceof Error ? err.message : String(err)}`,
      queueSize: size,
    }
  }
}

// --- Workflow authoring tools (create / read / update / publish / dispatch) ---

export async function createWorkflow(
  params: { name: string; area: string; yaml?: string; definition?: Record<string, unknown> },
  config: Config
): Promise<Record<string, unknown>> {
  const body: Record<string, unknown> = { name: params.name, area: params.area }
  if (params.yaml !== undefined) body['yaml'] = params.yaml
  if (params.definition !== undefined) body['definition'] = params.definition
  return apiPost<Record<string, unknown>>(
    `/api/v1/projects/${config.projectId}/workflows`,
    body,
    config
  )
}

export async function getWorkflow(
  params: { workflowId: string },
  config: Config
): Promise<Record<string, unknown>> {
  return apiGet<Record<string, unknown>>(
    `/api/v1/projects/${config.projectId}/workflows/${params.workflowId}`,
    config
  )
}

export async function updateWorkflow(
  params: {
    workflowId: string
    name?: string
    area?: string
    yaml?: string
    definition?: Record<string, unknown>
  },
  config: Config
): Promise<Record<string, unknown>> {
  const { workflowId, ...rest } = params
  const body: Record<string, unknown> = {}
  if (rest.name !== undefined) body['name'] = rest.name
  if (rest.area !== undefined) body['area'] = rest.area
  if (rest.yaml !== undefined) body['yaml'] = rest.yaml
  if (rest.definition !== undefined) body['definition'] = rest.definition
  return apiPut<Record<string, unknown>>(
    `/api/v1/projects/${config.projectId}/workflows/${workflowId}`,
    body,
    config
  )
}

export async function publishWorkflow(
  params: { workflowId: string },
  config: Config
): Promise<Record<string, unknown>> {
  return apiPost<Record<string, unknown>>(
    `/api/v1/projects/${config.projectId}/workflows/${params.workflowId}/publish`,
    {},
    config
  )
}

export async function dispatchWorkflow(
  params: { workflowId: string; inputs?: Record<string, unknown> },
  config: Config
): Promise<Record<string, unknown>> {
  return apiPost<Record<string, unknown>>(
    `/api/v1/projects/${config.projectId}/workflows/${params.workflowId}/dispatch`,
    { inputs: params.inputs ?? {} },
    config
  )
}

export async function getWorkflowRun(
  params: { workflowId: string; runId: string },
  config: Config
): Promise<Record<string, unknown>> {
  return apiGet<Record<string, unknown>>(
    `/api/v1/projects/${config.projectId}/workflows/${params.workflowId}/runs/${params.runId}`,
    config
  )
}

export async function reportStepRun(
  params: {
    issueId: string
    stepKind: string
    status: string
    inputBrief: string
    reportedBy: string
    workflow?: string
    fromStatus?: string
    toStatus?: string
    skill?: string
    startedAt?: string
    finishedAt?: string
    produced?: unknown[]
    beforeAfter?: unknown
    flags?: unknown[]
  },
  config: Config
): Promise<Record<string, unknown>> {
  const { issueId, ...rest } = params
  const path = `/api/v2/projects/${config.projectId}/work-items/${issueId}/step-runs`
  try {
    return await apiPost<Record<string, unknown>>(path, rest, config)
  } catch (err) {
    const size = queueChange({ method: 'POST', path, body: rest, timestamp: new Date().toISOString() })
    return {
      warning: `Sync failed — change queued: ${err instanceof Error ? err.message : String(err)}`,
      queueSize: size,
    }
  }
}
