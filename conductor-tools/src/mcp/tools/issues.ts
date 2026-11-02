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
  params: {
    type: string
    title: string
    description?: string
    workflow?: string
    tags?: string[]
    fields?: Record<string, unknown>
    dueOn?: string
    parentWorkItemId?: string
    priority?: string
    milestoneId?: string
    appetite?: string
    criteria?: Array<{ text: string; kind?: string; externalRef?: string }>
    blockedBy?: string[]
  },
  config: Config,
  endpoint: WorkItemEndpoint
): Promise<Record<string, unknown>> {
  // Resolve through the per-project map like everything below does; the top-level localPath is only
  // the last project initialised, so checking it alone refused (or misplaced) multi-project setups.
  try {
    resolveLocalPath(config)
  } catch {
    return { error: 'Run conductor init to set up local project directory' }
  }

  // COND-18: bind the Work Item to a Workflow (slug). Omitted → backend defaults to ENGINEERING.
  const body = {
    type: params.type,
    title: params.title,
    description: params.description,
    workflow: params.workflow,
    tags: params.tags,
    fields: params.fields,
    dueOn: params.dueOn,
    parentWorkItemId: params.parentWorkItemId,
    priority: params.priority,
    milestoneId: params.milestoneId,
    appetite: params.appetite,
    criteria: params.criteria,
    blockedBy: params.blockedBy,
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

  const result: Record<string, unknown> = {
    issueId,
    displayId: backendResult?.displayId,
    type: params.type,
    title: params.title,
    status: initialStatus,
  }

  // A child task never gets a local folder (one folder per task would swamp .conductor/issues), so
  // localPath/absolutePath are absent for it. Documents can still be attached via write_document.
  if (!params.parentWorkItemId) {
    const content = buildIssueFrontmatter(
      issueId,
      params.type,
      params.title,
      initialStatus,
      params.description
    )
    writeIssueFile(config, issueId, content)
    result['localPath'] = `.conductor/issues/${issueId}/`
    result['absolutePath'] =
      path.join(resolveLocalPath(config), '.conductor', 'issues', issueId) + path.sep
  }

  if (warning !== undefined) {
    result['warning'] = warning
    result['queueSize'] = queueSize
  }

  return result
}

async function updateWorkItemImpl(
  params: {
    issueId: string
    title?: string
    description?: string
    scheduledFor?: string
    scheduleTimezone?: string
    /** Publish as soon as approved: no date is needed, entering the scheduled status stamps one. */
    publishOnApproval?: boolean
    tags?: string[]
    /** Merged key by key on the backend; a key set to null clears it. */
    fields?: Record<string, unknown>
    /** YYYY-MM-DD; null clears the date. */
    dueOn?: string | null
    assigneeId?: string
    parentWorkItemId?: string | null
    priority?: string | null
    milestoneId?: string | null
    appetite?: string | null
  },
  config: Config,
  endpoint: WorkItemEndpoint
): Promise<Record<string, unknown>> {
  const body: Record<string, unknown> = {}
  if (params.title !== undefined) body['title'] = params.title
  if (params.description !== undefined) body['description'] = params.description
  // Omitted leaves the stored schedule alone — only a field the caller actually sent is on the wire, so
  // editing a title can never silently unschedule a Post.
  if (params.scheduledFor !== undefined) body['scheduledFor'] = params.scheduledFor
  if (params.scheduleTimezone !== undefined) body['scheduleTimezone'] = params.scheduleTimezone
  if (params.publishOnApproval !== undefined) body['publishOnApproval'] = params.publishOnApproval
  // Sent whole: the stored set becomes exactly this list. Omitted leaves existing tags alone, an empty
  // array clears them — same rule as every other field here, so editing a title never drops a tag.
  if (params.tags !== undefined) body['tags'] = params.tags
  if (params.fields !== undefined) body['fields'] = params.fields
  // The wire cannot tell an omitted field from an explicit null, so clearing is an empty string.
  if (params.dueOn !== undefined) body['dueOn'] = params.dueOn === null ? '' : params.dueOn
  if (params.assigneeId !== undefined) body['assigneeId'] = params.assigneeId
  // Same convention as dueOn: null (or "") clears, so the wire carries an empty string.
  if (params.parentWorkItemId !== undefined) body['parentWorkItemId'] = params.parentWorkItemId ?? ''
  if (params.priority !== undefined) body['priority'] = params.priority ?? ''
  if (params.milestoneId !== undefined) body['milestoneId'] = params.milestoneId ?? ''
  if (params.appetite !== undefined) body['appetite'] = params.appetite ?? ''

  const itemPath = endpoint.item(config.projectId, params.issueId)

  let warning: string | undefined
  let queueSize: number | undefined

  try {
    await apiPatch<IssueResponse>(itemPath, body, config)
  } catch (err) {
    // A 4xx (validation, permission, unknown id) is permanent — surface it rather than queuing a replay
    // that can never succeed. Publishing validators reject this way by design.
    if (isClientError(err)) {
      return { error: `Work Item not updated: ${err instanceof Error ? err.message : String(err)}` }
    }
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
  } catch (err) {
    // A 4xx (an invalid transition, an unsatisfied review gate) is permanent — surface it rather than
    // queuing a replay that can never succeed.
    if (isClientError(err)) {
      return { error: `Status not changed: ${err instanceof Error ? err.message : String(err)}` }
    }
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

export interface ListWorkItemsParams {
  type?: string
  status?: string
  workflow?: string
  tag?: string
  dueBefore?: string
  parentId?: string
  rootId?: string
  milestoneId?: string
  priority?: string
  assigneeId?: string
  ready?: boolean
  blocked?: boolean
  stale?: boolean
  sort?: string
}

interface ListedWorkItem {
  id: string
  displayId?: string
  title?: string
  type?: string
  status?: string
  priority?: string | null
  childCount?: number
  signals?: { blocked?: boolean; ready?: boolean }
  assignee?: { userId?: string; id?: string; name?: string; displayName?: string } | null
  [key: string]: unknown
}

/** Query string for list_work_items. Booleans are sent only when true — `false` means "don't filter". */
export function buildListQuery(params: ListWorkItemsParams): string {
  const query = new URLSearchParams()
  const strings: Array<keyof ListWorkItemsParams> = [
    'type', 'status', 'workflow', 'tag', 'dueBefore', 'parentId', 'rootId', 'milestoneId', 'priority', 'assigneeId', 'sort',
  ]
  for (const key of strings) {
    const value = params[key]
    if (typeof value === 'string' && value) query.set(key, value)
  }
  for (const key of ['ready', 'blocked', 'stale'] as const) {
    if (params[key] === true) query.set(key, 'true')
  }
  return query.toString()
}

/** Compact row: the few fields an agent needs to pick or reference an item; get_work_item has the rest. */
export function toCompactRow(item: ListedWorkItem): Record<string, unknown> {
  const row: Record<string, unknown> = {
    id: item.id,
    displayId: item.displayId,
    title: item.title,
    type: item.type,
    status: item.status,
    priority: item.priority ?? null,
    childCount: item.childCount ?? 0,
    blocked: item.signals?.blocked ?? false,
    ready: item.signals?.ready ?? false,
  }
  const a = item.assignee
  if (a) {
    const userId = a.userId ?? a.id
    const name = a.name ?? a.displayName
    if (userId || name) row['assignee'] = { userId, name }
  }
  return row
}

async function listWorkItemsRawImpl(
  params: ListWorkItemsParams,
  config: Config,
  endpoint: WorkItemEndpoint
): Promise<ListedWorkItem[]> {
  const qs = buildListQuery(params)
  const listPath = `${endpoint.collection(config.projectId)}${qs ? `?${qs}` : ''}`
  return apiGet<ListedWorkItem[]>(listPath, config)
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

  // Always the server's view. The local file used to short-circuit this read, and its frontmatter is only
  // ever updated by this client — so a status the backend changed on its own (a publish roll-up to
  // Published or Failed, a bundle-guard revert) was invisible to an agent polling here. The local file
  // still comes along, as what it is: the locally authored content.
  const issue = await apiGet<IssueResponse>(endpoint.item(config.projectId, params.issueId), config)
  const local = readIssueFile(config, params.issueId)
  return {
    ...(issue as unknown as Record<string, unknown>),
    localPath,
    ...(absolutePath ? { absolutePath } : {}),
    ...(local !== null ? { localContent: local } : {}),
  }
}

// --- Canonical v2 `work_item` handlers (hit /api/v2/.../work-items) ---

export async function createWorkItem(
  params: {
    type: string
    title: string
    description?: string
    /** Required unless parentWorkItemId is set (the backend then defaults to the TASK workflow). */
    workflow?: string
    tags?: string[]
    fields?: Record<string, unknown>
    dueOn?: string
    parentWorkItemId?: string
    priority?: string
    milestoneId?: string
    appetite?: string
    criteria?: Array<{ text: string; kind?: string; externalRef?: string }>
    blockedBy?: string[]
  },
  config: Config
): Promise<Record<string, unknown>> {
  return createWorkItemImpl(params, config, V2)
}

export async function updateWorkItem(
  params: {
    issueId: string
    title?: string
    description?: string
    scheduledFor?: string
    scheduleTimezone?: string
    /** Publish as soon as approved: no date is needed, entering the scheduled status stamps one. */
    publishOnApproval?: boolean
    tags?: string[]
    fields?: Record<string, unknown>
    dueOn?: string | null
    assigneeId?: string
    parentWorkItemId?: string | null
    priority?: string | null
    milestoneId?: string | null
    appetite?: string | null
  },
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

/** The MCP `list_work_items` tool: compact rows only, to protect the caller's context budget. */
export async function listWorkItems(
  params: ListWorkItemsParams,
  config: Config
): Promise<Record<string, unknown>[]> {
  const items = await listWorkItemsRawImpl(params, config, V2)
  return items.map(toCompactRow)
}

/** Full, unreduced rows, for in-process callers (e.g. list_posts) that need fields the compact row drops. */
export async function listWorkItemsRaw(
  params: ListWorkItemsParams,
  config: Config
): Promise<ListedWorkItem[]> {
  return listWorkItemsRawImpl(params, config, V2)
}

export async function getWorkItem(
  params: { issueId: string },
  config: Config
): Promise<Record<string, unknown>> {
  return getWorkItemImpl(params, config, V2)
}
