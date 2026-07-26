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
}

export interface DocVersion {
  id: string
  docId: string
  versionNumber: number
  authorId: string
  authorName: string
  createdAt: string
  content?: string
}

export interface DocCommentReply {
  id: string
  commentId: string
  authorId: string
  authorName: string
  content: string
  createdAt: string
}

export interface DocComment {
  id: string
  docId: string
  authorId: string
  authorName: string
  content: string
  lineNumber: number | null
  quotedText: string | null
  lineStale: boolean
  resolvedAt: string | null
  createdAt: string
  updatedAt: string
  replies: DocCommentReply[]
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

export function getDocComments(
  projectId: string,
  docId: string,
  token: string,
): Promise<DocComment[]> {
  return apiGet<DocComment[]>(`/api/v1/projects/${projectId}/docs/${docId}/comments`, token)
}

export function createDocComment(
  projectId: string,
  docId: string,
  content: string,
  lineNumber: number,
  quotedText: string | null,
  token: string,
): Promise<DocComment> {
  return apiPost<DocComment>(
    `/api/v1/projects/${projectId}/docs/${docId}/comments`,
    { content, lineNumber, quotedText },
    token,
  )
}

export function deleteDocComment(
  projectId: string,
  docId: string,
  commentId: string,
  token: string,
): Promise<void> {
  return apiDelete(`/api/v1/projects/${projectId}/docs/${docId}/comments/${commentId}`, token)
}

export function addDocCommentReply(
  projectId: string,
  docId: string,
  commentId: string,
  content: string,
  token: string,
): Promise<DocCommentReply> {
  return apiPost<DocCommentReply>(
    `/api/v1/projects/${projectId}/docs/${docId}/comments/${commentId}/replies`,
    { content },
    token,
  )
}

export function resolveDocComment(
  projectId: string,
  docId: string,
  commentId: string,
  token: string,
): Promise<DocComment> {
  return apiPost<DocComment>(
    `/api/v1/projects/${projectId}/docs/${docId}/comments/${commentId}/resolve`,
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
