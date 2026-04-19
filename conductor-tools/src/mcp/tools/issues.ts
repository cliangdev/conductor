import { Config } from '../config.js'
import { apiGet, apiPost, apiPatch } from '../api.js'
import { writeIssueFile, readIssueFile } from '../files.js'
import { queueChange } from '../queue.js'

interface IssueResponse {
  id: string
  displayId: string
  type: string
  title: string
  status: string
  description?: string
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

export async function createIssue(
  params: { type: string; title: string; description?: string },
  config: Config
): Promise<Record<string, unknown>> {
  if (!config.localPath) {
    return { error: 'Run conductor init to set up local project directory' }
  }

  let issueId: string
  let backendResult: IssueResponse | null = null
  let warning: string | undefined
  let queueSize: number | undefined

  try {
    backendResult = await apiPost<IssueResponse>(
      `/api/v1/projects/${config.projectId}/issues`,
      { type: params.type, title: params.title, description: params.description },
      config
    )
    issueId = backendResult.id
  } catch {
    issueId = `local_${Date.now()}`
    const size = queueChange({
      method: 'POST',
      path: `/api/v1/projects/${config.projectId}/issues`,
      body: { type: params.type, title: params.title, description: params.description },
      timestamp: new Date().toISOString(),
    })
    warning = 'Sync failed — change queued'
    queueSize = size
  }

  const content = buildIssueFrontmatter(
    issueId,
    params.type,
    params.title,
    'DRAFT',
    params.description
  )
  writeIssueFile(config, issueId, content)

  const localPath = `.conductor/issues/${issueId}/`

  const result: Record<string, unknown> = {
    issueId,
    displayId: backendResult?.displayId,
    type: params.type,
    title: params.title,
    status: 'DRAFT',
    localPath,
  }

  if (warning !== undefined) {
    result['warning'] = warning
    result['queueSize'] = queueSize
  }

  return result
}

export async function updateIssue(
  params: { issueId: string; title?: string; description?: string },
  config: Config
): Promise<Record<string, unknown>> {
  const body: Record<string, string> = {}
  if (params.title !== undefined) body['title'] = params.title
  if (params.description !== undefined) body['description'] = params.description

  let warning: string | undefined
  let queueSize: number | undefined

  try {
    await apiPatch<IssueResponse>(
      `/api/v1/projects/${config.projectId}/issues/${params.issueId}`,
      body,
      config
    )
  } catch {
    const size = queueChange({
      method: 'PATCH',
      path: `/api/v1/projects/${config.projectId}/issues/${params.issueId}`,
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

export async function setIssueStatus(
  params: { issueId: string; status: string },
  config: Config
): Promise<Record<string, unknown>> {
  let warning: string | undefined
  let queueSize: number | undefined

  try {
    await apiPatch<IssueResponse>(
      `/api/v1/projects/${config.projectId}/issues/${params.issueId}`,
      { status: params.status },
      config
    )
  } catch {
    const size = queueChange({
      method: 'PATCH',
      path: `/api/v1/projects/${config.projectId}/issues/${params.issueId}`,
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

export async function listIssues(
  params: { type?: string; status?: string },
  config: Config
): Promise<unknown[]> {
  const query = new URLSearchParams()
  if (params.type) query.set('type', params.type)
  if (params.status) query.set('status', params.status)

  const qs = query.toString()
  const path = `/api/v1/projects/${config.projectId}/issues${qs ? `?${qs}` : ''}`
  return apiGet<unknown[]>(path, config)
}

export async function getIssue(
  params: { issueId: string },
  config: Config
): Promise<Record<string, unknown>> {
  const local = readIssueFile(config, params.issueId)
  if (local !== null) {
    return { issueId: params.issueId, content: local, source: 'local' }
  }

  const issue = await apiGet<IssueResponse>(
    `/api/v1/projects/${config.projectId}/issues/${params.issueId}`,
    config
  )
  return issue as unknown as Record<string, unknown>
}
