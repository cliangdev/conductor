import * as fs from 'fs'
import * as path from 'path'
import { Config } from '../config.js'
import { apiPost, apiDelete } from '../api.js'
import { deleteDocumentFile, resolveLocalPath } from '../files.js'
import { queueChange } from '../queue.js'

interface DocumentResponse {
  id: string
  filename: string
  issueId: string
}

export async function deleteDocument(
  params: { issueId: string; documentId: string; filename: string },
  config: Config
): Promise<Record<string, unknown>> {
  deleteDocumentFile(config, params.issueId, params.filename)

  await apiDelete(
    `/api/v1/projects/${config.projectId}/issues/${params.issueId}/documents/${params.documentId}`,
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
    return { localPath: relativePath, alreadyExists: true }
  }

  fs.mkdirSync(path.dirname(localFilePath), { recursive: true })
  fs.writeFileSync(localFilePath, '', 'utf8')

  let documentId: string
  try {
    const result = await apiPost<DocumentResponse>(
      `/api/v1/projects/${config.projectId}/issues/${params.issueId}/documents`,
      { filename: params.filename, contentType: 'text/markdown' },
      config
    )
    documentId = result.id
  } catch (err) {
    queueChange({
      method: 'PUT',
      path: `/api/v1/projects/${config.projectId}/issues/${params.issueId}/documents/${encodeURIComponent(params.filename)}`,
      body: { content: '', contentType: 'text/markdown' },
      timestamp: new Date().toISOString(),
    })
    return {
      localPath: relativePath,
      warning: 'Backend sync failed — queued for retry',
    }
  }

  return {
    documentId,
    localPath: relativePath,
  }
}
