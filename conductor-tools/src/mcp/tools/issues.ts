import * as path from 'path'
import { Config } from '../config.js'
import { apiGet, apiPost, apiPatch, isClientError } from '../api.js'
import { writeIssueFile, readIssueFile, resolveLocalPath } from '../files.js'
import { queueChange } from '../queue.js'

interface IssueResponse {
  id: string
  displayId: string
  type: string
  title: string
  status: string
  description?: string
}

/**
 * Core Work Item resource targeting. The canonical `*_work_item` tools hit the
 * v2 `work-items` surface. The endpoint is factored out so the shared handlers
 * below stay decoupled from the concrete paths.
 */
interface WorkItemEndpoint {
  collection: (projectId: string) => string
  item: (projectId: string, id: string) => string
}

const V2: WorkItemEndpoint = {
  collection: (projectId) => `/api/v2/projects/${projectId}/work-items`,
  item: (projectId, id) => `/api/v2/projects/${projectId}/work-items/${id}`,
}

function buildIssueFrontmatter(
  issueId: string,
  type: string,
  title: string,
  status: string,
  description?: string
): string {
  const body = description ?? ''
  const createdAt = new Date().toISOString()
  return `---\nid: ${issueId}\ntype: ${type}\ntitle: ${title}\nstatus: ${status}\ncreatedAt: ${createdAt}\n---\n\n${body}`
}

function updateFrontmatterField(content: string, field: string, value: string): string {
  const pattern = new RegExp(`^(${field}:\\s*)(.*)$`, 'm')
  if (pattern.test(content)) {
    return content.replace(pattern, `$1${value}`)
  }
  return content
}

async function createWorkItemImpl(
  params: { type: string; title: string; description?: string; workflow?: string },
  config: Config,
  endpoint: WorkItemEndpoint
): Promise<Record<string, unknown>> {
  if (!config.localPath) {
    return { error: 'Run conductor init to set up local project directory' }
  }

  // COND-18: bind the Work Item to a Workflow (slug). Omitted → backend defaults to ENGINEERING.
  const body = {
    type: params.type,
    title: params.title,
    description: params.description,
    workflow: params.workflow,
  }

  const collectionPath = endpoint.collection(config.projectId)

  let issueId: string
  let backendResult: IssueResponse | null = null
  let warning: string | undefined
  let queueSize: number | undefined

  try {
    backendResult = await apiPost<IssueResponse>(collectionPath, body, config)
    issueId = backendResult.id
  } catch (err) {
    // A 4xx (bad type, unknown workflow, forbidden) is permanent — surface it instead of queuing a retry that
    // would never succeed and writing a phantom local_… item.
    if (isClientError(err)) {
      return { error: `Work Item not created: ${err instanceof Error ? err.message : String(err)}` }
    }
    issueId = `local_${Date.now()}`
    const size = queueChange({
      method: 'POST',
      path: collectionPath,
      body,
      timestamp: new Date().toISOString(),
    })
    warning = 'Sync failed — change queued'
    queueSize = size
  }

  // Initial status comes from the bound Workflow (statechart initial status), returned by the backend.
  // Offline (queued) we can't know it, so fall back to the Engineering initial status.
  const initialStatus = backendResult?.status ?? 'DRAFT'

  const content = buildIssueFrontmatter(
    issueId,
    params.type,
    params.title,
    initialStatus,
    params.description
  )
  writeIssueFile(config, issueId, content)

  const localPath = `.conductor/issues/${issueId}/`
  const absolutePath = path.join(resolveLocalPath(config), '.conductor', 'issues', issueId) + path.sep

  const result: Record<string, unknown> = {
    issueId,
    displayId: backendResult?.displayId,
    type: params.type,
    title: params.title,
    status: initialStatus,
    localPath,
    absolutePath,
  }

  if (warning !== undefined) {
    result['warning'] = warning
    result['queueSize'] = queueSize
  }

  return result
}

async function updateWorkItemImpl(
  params: { issueId: string; title?: string; description?: string },
  config: Config,
  endpoint: WorkItemEndpoint
): Promise<Record<string, unknown>> {
  const body: Record<string, string> = {}
  if (params.title !== undefined) body['title'] = params.title
  if (params.description !== undefined) body['description'] = params.description

  const itemPath = endpoint.item(config.projectId, params.issueId)

  let warning: string | undefined
  let queueSize: number | undefined

  try {
    await apiPatch<IssueResponse>(itemPath, body, config)
  } catch {
    const size = queueChange({
      method: 'PATCH',
      path: itemPath,
      body,
      timestamp: new Date().toISOString(),
    })
    warning = 'Sync failed — change queued'
    queueSize = size
  }

  const existing = readIssueFile(config, params.issueId)
  if (existing !== null) {
    let updated = existing
    if (params.title !== undefined) {
      updated = updateFrontmatterField(updated, 'title', params.title)
    }
    writeIssueFile(config, params.issueId, updated)
  }

  const result: Record<string, unknown> = { issueId: params.issueId, ...body }
  if (warning !== undefined) {
    result['warning'] = warning
    result['queueSize'] = queueSize
  }
  return result
}

async function setWorkItemStatusImpl(
  params: { issueId: string; status: string },
  config: Config,
  endpoint: WorkItemEndpoint
): Promise<Record<string, unknown>> {
  const itemPath = endpoint.item(config.projectId, params.issueId)

  let warning: string | undefined
  let queueSize: number | undefined

  try {
    await apiPatch<IssueResponse>(itemPath, { status: params.status }, config)
  } catch {
    const size = queueChange({
      method: 'PATCH',
      path: itemPath,
      body: { status: params.status },
      timestamp: new Date().toISOString(),
    })
    warning = 'Sync failed — change queued'
    queueSize = size
  }

  const existing = readIssueFile(config, params.issueId)
  if (existing !== null) {
    const updated = updateFrontmatterField(existing, 'status', params.status)
    writeIssueFile(config, params.issueId, updated)
  }

  const result: Record<string, unknown> = {
    issueId: params.issueId,
    status: params.status,
  }
  if (warning !== undefined) {
    result['warning'] = warning
    result['queueSize'] = queueSize
  }
  return result
}

async function listWorkItemsImpl(
  params: { type?: string; status?: string; workflow?: string },
  config: Config,
  endpoint: WorkItemEndpoint
): Promise<unknown[]> {
  const query = new URLSearchParams()
  if (params.type) query.set('type', params.type)
  if (params.status) query.set('status', params.status)
  if (params.workflow) query.set('workflow', params.workflow)

  const qs = query.toString()
  const listPath = `${endpoint.collection(config.projectId)}${qs ? `?${qs}` : ''}`
  return apiGet<unknown[]>(listPath, config)
}

async function getWorkItemImpl(
  params: { issueId: string },
  config: Config,
  endpoint: WorkItemEndpoint
): Promise<Record<string, unknown>> {
  let absolutePath: string | undefined
  try {
    absolutePath = path.join(resolveLocalPath(config), '.conductor', 'issues', params.issueId) + path.sep
  } catch {
    // localPath not configured — omit absolutePath
  }
  const localPath = `.conductor/issues/${params.issueId}/`

  const local = readIssueFile(config, params.issueId)
  if (local !== null) {
    return {
      issueId: params.issueId,
      content: local,
      source: 'local',
      localPath,
      ...(absolutePath ? { absolutePath } : {}),
    }
  }

  const issue = await apiGet<IssueResponse>(endpoint.item(config.projectId, params.issueId), config)
  return {
    ...(issue as unknown as Record<string, unknown>),
    localPath,
    ...(absolutePath ? { absolutePath } : {}),
  }
}

// --- Canonical v2 `work_item` handlers (hit /api/v2/.../work-items) ---

export async function createWorkItem(
  params: { type: string; title: string; description?: string; workflow: string },
  config: Config
): Promise<Record<string, unknown>> {
  return createWorkItemImpl(params, config, V2)
}

export async function updateWorkItem(
  params: { issueId: string; title?: string; description?: string },
  config: Config
): Promise<Record<string, unknown>> {
  return updateWorkItemImpl(params, config, V2)
}

export async function setWorkItemStatus(
  params: { issueId: string; status: string },
  config: Config
): Promise<Record<string, unknown>> {
  return setWorkItemStatusImpl(params, config, V2)
}

export async function listWorkItems(
  params: { type?: string; status?: string; workflow?: string },
  config: Config
): Promise<unknown[]> {
  return listWorkItemsImpl(params, config, V2)
}

export async function getWorkItem(
  params: { issueId: string },
  config: Config
): Promise<Record<string, unknown>> {
  return getWorkItemImpl(params, config, V2)
}
