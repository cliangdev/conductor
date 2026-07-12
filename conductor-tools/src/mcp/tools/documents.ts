import * as fs from 'fs'
import * as path from 'path'
import { Config } from '../config.js'
import { apiPost, apiPut, apiDelete } from '../api.js'
import { deleteDocumentFile, resolveLocalPath, writeDocumentFile } from '../files.js'
import { queueChange } from '../queue.js'

interface DocumentResponse {
  id: string
  filename: string
  issueId: string
}

/**
 * Upsert a document's full content on the backend (works headlessly — no local project required).
 * Mirrors to the local file only best-effort: a workflow container has no `.conductor` checkout, so a
 * missing local path is expected there, not an error — the backend write is authoritative either way.
 */
export async function writeDocument(
  params: { issueId: string; filename: string; content: string; contentType?: string },
  config: Config
): Promise<Record<string, unknown>> {
  const body: Record<string, unknown> = { content: params.content }
  if (params.contentType !== undefined) body['contentType'] = params.contentType

  const result = await apiPut<Record<string, unknown>>(
    `/api/v2/projects/${config.projectId}/work-items/${params.issueId}/documents/${encodeURIComponent(params.filename)}`,
    body,
    config
  )

  try {
    resolveLocalPath(config)
    writeDocumentFile(config, params.issueId, params.filename, params.content)
  } catch {
    // No local project directory (e.g. running in a headless workflow container) — fine, skip the mirror.
  }

  return result
}

export async function deleteDocument(
  params: { issueId: string; documentId: string; filename: string },
  config: Config
): Promise<Record<string, unknown>> {
  deleteDocumentFile(config, params.issueId, params.filename)

  await apiDelete(
    `/api/v2/projects/${config.projectId}/work-items/${params.issueId}/documents/${params.documentId}`,
    config
  )

  return { success: true }
}

export async function scaffoldDocument(
  params: { issueId: string; filename: string },
  config: Config
): Promise<Record<string, unknown>> {
  let localRoot: string
  try {
    localRoot = resolveLocalPath(config)
  } catch {
    return { error: 'Run conductor init to set up local project directory' }
  }

  const localFilePath = path.join(
    localRoot,
    '.conductor',
    'issues',
    params.issueId,
    params.filename
  )
  const relativePath = `.conductor/issues/${params.issueId}/${params.filename}`

  if (fs.existsSync(localFilePath)) {
    return { localPath: relativePath, absolutePath: localFilePath, alreadyExists: true }
  }

  fs.mkdirSync(path.dirname(localFilePath), { recursive: true })
  fs.writeFileSync(localFilePath, '', 'utf8')

  let documentId: string
  try {
    const result = await apiPost<DocumentResponse>(
      `/api/v2/projects/${config.projectId}/work-items/${params.issueId}/documents`,
      { filename: params.filename, contentType: 'text/markdown' },
      config
    )
    documentId = result.id
  } catch (err) {
    queueChange({
      method: 'PUT',
      path: `/api/v2/projects/${config.projectId}/work-items/${params.issueId}/documents/${encodeURIComponent(params.filename)}`,
      body: { content: '', contentType: 'text/markdown' },
      timestamp: new Date().toISOString(),
    })
    return {
      localPath: relativePath,
      absolutePath: localFilePath,
      warning: 'Backend sync failed — queued for retry',
    }
  }

  return {
    documentId,
    localPath: relativePath,
    absolutePath: localFilePath,
  }
}
