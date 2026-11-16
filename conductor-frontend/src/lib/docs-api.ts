import { apiGet, apiPost, apiPut, apiPatch, apiDelete } from '@/lib/api'

export interface DocFolder {
  id: string
  projectId: string
  parentId: string | null
  name: string
  createdAt: string
  updatedAt: string
}

export interface ProjectDocSummary {
  id: string
  projectId: string
  folderId: string | null
  title: string
  createdAt: string
  updatedAt: string
  createdByName: string
  updatedByName: string
}

export interface ProjectDoc extends ProjectDocSummary {
  content: string | null
  /** SHA-256 of the stored content; sent with a comment so the server can tell which version it was written on. */
  contentHash?: string
}

export interface DocVersion {
  id: string
  docId: string
  versionNumber: number
  /** Null when an agent authored this — authorName still carries a byline. */
  authorId: string | null
  authorName: string
  createdAt: string
  content?: string
}

export function getFolders(projectId: string, token: string): Promise<DocFolder[]> {
  return apiGet<DocFolder[]>(`/api/v1/projects/${projectId}/docs/folders`, token)
}

export function createFolder(
  projectId: string,
  name: string,
  parentId: string | null,
  token: string,
): Promise<DocFolder> {
  return apiPost<DocFolder>(`/api/v1/projects/${projectId}/docs/folders`, { name, parentId }, token)
}

export function renameFolder(
  projectId: string,
  folderId: string,
  name: string,
  token: string,
): Promise<DocFolder> {
  return apiPatch<DocFolder>(`/api/v1/projects/${projectId}/docs/folders/${folderId}`, { name }, token) as Promise<DocFolder>
}

export function deleteFolder(projectId: string, folderId: string, token: string): Promise<void> {
  return apiDelete(`/api/v1/projects/${projectId}/docs/folders/${folderId}`, token)
}

export function getDocs(
  projectId: string,
  folderId: string | null,
  token: string,
): Promise<ProjectDocSummary[]> {
  const query = folderId ? `?folderId=${encodeURIComponent(folderId)}` : ''
  return apiGet<ProjectDocSummary[]>(`/api/v1/projects/${projectId}/docs${query}`, token)
}

export function createDoc(
  projectId: string,
  title: string,
  folderId: string | null,
  token: string,
): Promise<ProjectDoc> {
  return apiPost<ProjectDoc>(`/api/v1/projects/${projectId}/docs`, { title, folderId }, token)
}

export function getDoc(projectId: string, docId: string, token: string): Promise<ProjectDoc> {
  return apiGet<ProjectDoc>(`/api/v1/projects/${projectId}/docs/${docId}`, token)
}

export function updateDoc(
  projectId: string,
  docId: string,
  content: string,
  token: string,
): Promise<ProjectDoc> {
  return apiPut<ProjectDoc>(`/api/v1/projects/${projectId}/docs/${docId}`, { content }, token)
}

/**
 * Checks or unchecks one task list item in place. Unlike {@link updateDoc} this does not create a
 * version and does not mark line comments stale — see the endpoint description in `openapi.yaml`.
 * `lineNumber` is 1-based against the doc's raw content.
 */
export function setDocTaskState(
  projectId: string,
  docId: string,
  lineNumber: number,
  checked: boolean,
  token: string,
): Promise<ProjectDoc | undefined> {
  return apiPatch<ProjectDoc>(
    `/api/v1/projects/${projectId}/docs/${docId}/tasks/${lineNumber}`,
    { checked },
    token,
  )
}

export function deleteDoc(projectId: string, docId: string, token: string): Promise<void> {
  return apiDelete(`/api/v1/projects/${projectId}/docs/${docId}`, token)
}

export function listVersions(
  projectId: string,
  docId: string,
  token: string,
): Promise<DocVersion[]> {
  return apiGet<DocVersion[]>(`/api/v1/projects/${projectId}/docs/${docId}/versions`, token)
}

export function getDocVersion(
  projectId: string,
  docId: string,
  versionId: string,
  token: string,
): Promise<DocVersion> {
  return apiGet<DocVersion>(`/api/v1/projects/${projectId}/docs/${docId}/versions/${versionId}`, token)
}

export function restoreVersion(
  projectId: string,
  docId: string,
  versionId: string,
  token: string,
): Promise<ProjectDoc> {
  return apiPost<ProjectDoc>(
    `/api/v1/projects/${projectId}/docs/${docId}/versions/${versionId}/restore`,
    {},
    token,
  )
}

export interface DocSearchResult {
  id: string
  title: string
  folderId: string | null
  snippet: string
}

export function searchDocs(
  projectId: string,
  q: string,
  token: string,
): Promise<DocSearchResult[]> {
  return apiGet<DocSearchResult[]>(
    `/api/v1/projects/${projectId}/docs/search?q=${encodeURIComponent(q)}`,
    token,
  )
}
