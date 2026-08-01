import { Config } from '../config.js'
import { apiGet, apiPost, apiPatch, apiPut, apiDelete } from '../api.js'
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
  // Push the kind filter server-side (the endpoint exposes ?lifecycle) instead of fetching all + filtering here.
  const query = new URLSearchParams()
  if (params.kind === 'LIFECYCLE') query.set('lifecycle', 'true')
  else if (params.kind === 'AUTOMATION') query.set('lifecycle', 'false')
  const qs = query.toString()
  const raw = await apiGet<Record<string, unknown>[]>(
    `/api/v1/projects/${config.projectId}/workflows${qs ? `?${qs}` : ''}`,
    config
  )
  const list = Array.isArray(raw) ? raw : []
  return list.map((w) => {
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

export async function deleteWorkflow(
  params: { workflowId: string },
  config: Config
): Promise<Record<string, unknown>> {
  await apiDelete(
    `/api/v1/projects/${config.projectId}/workflows/${params.workflowId}`,
    config
  )
  return { success: true }
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

export async function cancelWorkflowRun(
  params: { workflowId: string; runId: string },
  config: Config
): Promise<Record<string, unknown>> {
  return apiPost<Record<string, unknown>>(
    `/api/v1/projects/${config.projectId}/workflows/${params.workflowId}/runs/${params.runId}/cancel`,
    {},
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

export async function listWorkflowRuns(
  params: { workflowId: string; page?: number; size?: number; status?: string | string[]; state?: string },
  config: Config
): Promise<unknown[]> {
  const query = new URLSearchParams()
  if (params.page !== undefined) query.set('page', String(params.page))
  if (params.size !== undefined) query.set('size', String(params.size))
  // `state` is the correct way to ask "what's queued": a run blocked on an unclaimed self-hosted job
  // is RUNNING at the run level, so `status=PENDING` silently misses exactly the backlog a caller is
  // usually looking for. The backend rejects both params together with a 400.
  if (params.state !== undefined) query.set('state', params.state)
  if (params.status !== undefined) {
    for (const s of Array.isArray(params.status) ? params.status : [params.status]) {
      query.append('status', s)
    }
  }
  const qs = query.toString()
  const raw = await apiGet<unknown[]>(
    `/api/v1/projects/${config.projectId}/workflows/${params.workflowId}/runs${qs ? `?${qs}` : ''}`,
    config
  )
  return Array.isArray(raw) ? raw : []
}

/**
 * Names only — the backend never returns secret values over this endpoint, and this tool must not
 * either. Strips any other field defensively even if the response ever grows one.
 */
export async function listWorkflowSecrets(
  _params: Record<string, never>,
  config: Config
): Promise<{ key: string }[]> {
  const raw = await apiGet<Record<string, unknown>[]>(
    `/api/v1/projects/${config.projectId}/workflow-secrets`,
    config
  )
  const list = Array.isArray(raw) ? raw : []
  return list
    .filter((s): s is Record<string, unknown> => typeof s?.['key'] === 'string')
    .map((s) => ({ key: s['key'] as string }))
}

/**
 * Live workflow-authoring schema: every step type's fields plus the valid `${{ }}`/`if:` interpolation
 * roots and functions, sourced from the backend's StepSchemaRegistry. Thin passthrough — no local
 * shaping — so it always reflects the current engine, never a stale hardcoded copy.
 */
export async function getWorkflowStepSchema(
  _params: Record<string, never>,
  config: Config
): Promise<Record<string, unknown>> {
  return apiGet<Record<string, unknown>>(
    `/api/v1/projects/${config.projectId}/workflows/step-schema`,
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
