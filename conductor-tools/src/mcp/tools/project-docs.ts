import { readFile } from 'node:fs/promises'
import { basename, extname } from 'node:path'
import { Config } from '../config.js'
import { apiGet, apiPost, apiPut, apiPatch, apiDelete, apiPostFile } from '../api.js'

/**
 * Project Docs MCP tools — the project's own document tree (folders → Markdown docs → line comments →
 * tickable checkboxes), distinct from Work Item documents (see documents.ts) and from Knowledge pages.
 *
 * Docs are addressed by path ("Plans/Q3 Roadmap"): folder segments plus the doc title. Flow:
 * list_project_docs maps the tree, read_project_doc returns content plus a ready-made index of its task
 * lines, write_project_doc upserts (creating folders on the way), set_project_doc_task ticks one box,
 * and the comment tools read and answer human feedback. Every full-content write is versioned, so
 * list_project_doc_versions / restore_project_doc_version can walk that history back.
 */

const TITLE_SEPARATOR = '/'

interface FolderRow {
  id: string
  parentId: string | null
  name: string
}

interface DocRow {
  id: string
  folderId: string | null
  title: string
  content?: string | null
  createdByName?: string
  updatedByName?: string
  updatedAt?: string
}

export interface DocTaskLine {
  lineNumber: number
  checked: boolean
  text: string
}

/**
 * One GFM task list item, kept in sync with {@code ProjectDocService.TASK_LIST_ITEM} on the backend and
 * {@code toggleTaskLine} in the frontend. Group 1 is the checked-state character.
 */
const TASK_LIST_ITEM = /^\s*(?:>\s*)*(?:[-*+]|\d{1,9}[.)])\s+\[([ xX])\]\s?(.*)$/

function docsBase(config: Config): string {
  return `/api/v1/projects/${config.projectId}/docs`
}

async function listFolders(config: Config): Promise<FolderRow[]> {
  const raw = await apiGet<FolderRow[]>(`${docsBase(config)}/folders`, config)
  return Array.isArray(raw) ? raw : []
}

async function listDocsIn(folderId: string | null, config: Config): Promise<DocRow[]> {
  const query = folderId === null ? '' : `?folderId=${encodeURIComponent(folderId)}`
  const raw = await apiGet<DocRow[]>(`${docsBase(config)}${query}`, config)
  return Array.isArray(raw) ? raw : []
}

/** Full path of a folder, e.g. "Plans/Q3". */
function folderPath(folder: FolderRow, byId: Map<string, FolderRow>): string {
  const segments: string[] = []
  let current: FolderRow | undefined = folder
  // Guard against a cycle rather than hanging: the backend has no such data, but a path walk that can
  // loop forever is not worth the risk in a tool an agent calls unattended.
  const seen = new Set<string>()
  while (current && !seen.has(current.id)) {
    seen.add(current.id)
    segments.unshift(current.name)
    current = current.parentId ? byId.get(current.parentId) : undefined
  }
  return segments.join(TITLE_SEPARATOR)
}

function splitPath(path: string): { folderSegments: string[]; title: string } {
  const segments = path
    .split(TITLE_SEPARATOR)
    .map((s) => s.trim())
    .filter((s) => s.length > 0)
  if (segments.length === 0) {
    throw new Error('Path is empty — expected something like "Plans/Q3 Roadmap"')
  }
  const title = segments.pop() as string
  return { folderSegments: segments, title }
}

/** Walks folder segments, returning the id of the deepest one (null = project root). */
function resolveFolder(
  folderSegments: string[],
  folders: FolderRow[],
  path: string
): string | null {
  let parentId: string | null = null
  for (const segment of folderSegments) {
    const match = folders.find((f) => f.parentId === parentId && f.name === segment)
    if (!match) {
      throw new Error(`Folder "${segment}" not found in path "${path}"`)
    }
    parentId = match.id
  }
  return parentId
}

/** Creates any missing folder along the path and returns the deepest folder id (null = root). */
async function ensureFolder(
  folderSegments: string[],
  config: Config
): Promise<string | null> {
  let folders: FolderRow[] = await listFolders(config)
  let parentId: string | null = null
  for (const segment of folderSegments) {
    let match: FolderRow | undefined = folders.find((f) => f.parentId === parentId && f.name === segment)
    if (!match) {
      const created: FolderRow = await apiPost<FolderRow>(
        `${docsBase(config)}/folders`,
        { name: segment, parentId },
        config
      )
      match = { id: created.id, parentId: created.parentId ?? null, name: created.name }
      folders = [...folders, match]
    }
    parentId = match.id
  }
  return parentId
}

interface ResolvedDoc {
  docId: string
  folderId: string | null
  title: string
}

/**
 * Resolves `path` to a doc id, or returns the folder it should live in when no such doc exists yet.
 * A title containing "/" cannot be addressed this way — pass `docId` for those.
 */
async function resolvePath(
  path: string,
  config: Config
): Promise<{ doc: ResolvedDoc | null; folderId: string | null; title: string }> {
  const { folderSegments, title } = splitPath(path)
  const folders = await listFolders(config)
  const folderId = resolveFolder(folderSegments, folders, path)
  const docs = await listDocsIn(folderId, config)
  const match = docs.find((d) => d.title === title)
  return {
    doc: match ? { docId: match.id, folderId: match.folderId ?? null, title: match.title } : null,
    folderId,
    title,
  }
}

/** Every tool accepts either form; `docId` wins when both are given. */
async function requireDocId(
  params: { path?: string; docId?: string },
  config: Config
): Promise<string> {
  if (params.docId) return params.docId
  if (!params.path) {
    throw new Error('Provide either path or docId')
  }
  const { doc } = await resolvePath(params.path, config)
  if (!doc) {
    throw new Error(`No document at path "${params.path}"`)
  }
  return doc.docId
}

export function taskLines(content: string | null | undefined): DocTaskLine[] {
  if (!content) return []
  const lines: DocTaskLine[] = []
  content.split('\n').forEach((line, index) => {
    const match = line.match(TASK_LIST_ITEM)
    if (match) {
      lines.push({
        lineNumber: index + 1,
        checked: match[1].toLowerCase() === 'x',
        text: match[2].trim(),
      })
    }
  })
  return lines
}

export async function listProjectDocs(
  params: { folder?: string; query?: string },
  config: Config
): Promise<Record<string, unknown>> {
  if (params.query) {
    const results = await apiGet<Record<string, unknown>[]>(
      `${docsBase(config)}/search?q=${encodeURIComponent(params.query)}`,
      config
    )
    const folders = await listFolders(config)
    const byId = new Map(folders.map((f) => [f.id, f]))
    return {
      matches: (Array.isArray(results) ? results : []).map((r) => ({
        docId: r['id'],
        path: joinPath(r['folderId'] as string | null, r['title'] as string, byId),
        snippet: r['snippet'],
      })),
    }
  }

  const folders = await listFolders(config)
  const byId = new Map(folders.map((f) => [f.id, f]))

  // Without a folder filter, walk every folder plus the root so one call returns the whole tree.
  const wanted =
    params.folder === undefined
      ? [null, ...folders.map((f) => f.id)]
      : [resolveFolder(splitPathAsFolders(params.folder), folders, params.folder)]

  const docs: Record<string, unknown>[] = []
  for (const folderId of wanted) {
    for (const doc of await listDocsIn(folderId, config)) {
      docs.push({
        docId: doc.id,
        path: joinPath(doc.folderId ?? null, doc.title, byId),
        updatedAt: doc.updatedAt,
        updatedByName: doc.updatedByName,
      })
    }
  }

  return {
    folders: folders.map((f) => folderPath(f, byId)).sort(),
    docs: docs.sort((a, b) => String(a['path']).localeCompare(String(b['path']))),
  }
}

/** Like splitPath, but every segment is a folder (no trailing doc title). */
function splitPathAsFolders(path: string): string[] {
  return path
    .split(TITLE_SEPARATOR)
    .map((s) => s.trim())
    .filter((s) => s.length > 0)
}

function joinPath(folderId: string | null, title: string, byId: Map<string, FolderRow>): string {
  const folder = folderId ? byId.get(folderId) : undefined
  return folder ? `${folderPath(folder, byId)}${TITLE_SEPARATOR}${title}` : title
}

export async function readProjectDoc(
  params: { path?: string; docId?: string; versionNumber?: number },
  config: Config
): Promise<Record<string, unknown>> {
  const docId = await requireDocId(params, config)
  const doc = await apiGet<DocRow>(`${docsBase(config)}/${docId}`, config)
  const folders = await listFolders(config)
  const byId = new Map(folders.map((f) => [f.id, f]))

  // An earlier version is the same intent — "read this document" — so it rides on the same tool rather
  // than a third one the agent has to learn.
  let content = doc.content ?? ''
  if (params.versionNumber !== undefined) {
    const version = await findVersion(docId, params.versionNumber, config)
    const full = await apiGet<VersionRow>(
      `${docsBase(config)}/${docId}/versions/${version.id}`,
      config
    )
    content = full.content ?? ''
  }

  return {
    docId: doc.id,
    path: joinPath(doc.folderId ?? null, doc.title, byId),
    title: doc.title,
    versionNumber: params.versionNumber,
    content,
    updatedByName: doc.updatedByName,
    updatedAt: doc.updatedAt,
    // Precomputed so a checkbox can be ticked by line number without the caller counting lines itself.
    taskLines: taskLines(content),
  }
}

interface VersionRow {
  id: string
  versionNumber: number
  authorName?: string
  createdAt?: string
  content?: string | null
}

async function findVersion(
  docId: string,
  versionNumber: number,
  config: Config
): Promise<VersionRow> {
  const raw = await apiGet<VersionRow[]>(`${docsBase(config)}/${docId}/versions`, config)
  const versions = Array.isArray(raw) ? raw : []
  const match = versions.find((v) => v.versionNumber === versionNumber)
  if (!match) {
    const known = versions.map((v) => v.versionNumber).join(', ')
    throw new Error(
      `No version ${versionNumber} for this document${known ? ` — available: ${known}` : ''}`
    )
  }
  return match
}

export async function listProjectDocVersions(
  params: { path?: string; docId?: string },
  config: Config
): Promise<Record<string, unknown>[]> {
  const docId = await requireDocId(params, config)
  const raw = await apiGet<VersionRow[]>(`${docsBase(config)}/${docId}/versions`, config)
  return (Array.isArray(raw) ? raw : []).map((v) => ({
    versionNumber: v.versionNumber,
    authorName: v.authorName,
    createdAt: v.createdAt,
  }))
}

/**
 * Restoring writes the old content as a *new* version rather than rewinding, so the history stays
 * append-only and the restore itself is undoable.
 */
export async function restoreProjectDocVersion(
  params: { path?: string; docId?: string; versionNumber: number },
  config: Config
): Promise<Record<string, unknown>> {
  const docId = await requireDocId(params, config)
  const version = await findVersion(docId, params.versionNumber, config)
  const doc = await apiPost<DocRow>(
    `${docsBase(config)}/${docId}/versions/${version.id}/restore`,
    {},
    config
  )
  return {
    docId,
    restoredFromVersion: params.versionNumber,
    content: doc.content ?? '',
  }
}

const IMAGE_CONTENT_TYPES: Record<string, string> = {
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.gif': 'image/gif',
  '.webp': 'image/webp',
}

/**
 * Uploads a local image file and returns the Markdown to embed. The URL inside that snippet is
 * short-lived, but writing it into a doc is safe: the backend stores a stable reference and re-signs on
 * every read, so the embedded image does not expire.
 */
export async function uploadProjectDocImage(
  params: { path?: string; docId?: string; filePath: string },
  config: Config
): Promise<Record<string, unknown>> {
  const extension = extname(params.filePath).toLowerCase()
  const contentType = IMAGE_CONTENT_TYPES[extension]
  if (!contentType) {
    throw new Error(
      `Unsupported image type "${extension || params.filePath}" — allowed: ${Object.keys(IMAGE_CONTENT_TYPES).join(', ')}`
    )
  }

  const docId = await requireDocId(params, config)
  const bytes = await readFile(params.filePath)

  const result = await apiPostFile<{ markdownSnippet: string; storageUrl: string }>(
    `${docsBase(config)}/${docId}/images`,
    {
      fieldName: 'image',
      filename: basename(params.filePath),
      contentType,
      bytes: new Uint8Array(bytes),
    },
    config
  )

  return { docId, markdownSnippet: result.markdownSnippet }
}

export async function writeProjectDoc(
  params: { path: string; content: string },
  config: Config
): Promise<Record<string, unknown>> {
  const { folderSegments, title } = splitPath(params.path)
  const folderId = await ensureFolder(folderSegments, config)
  const existing = (await listDocsIn(folderId, config)).find((d) => d.title === title)

  if (existing) {
    await apiPut<DocRow>(`${docsBase(config)}/${existing.id}`, { content: params.content }, config)
    return { docId: existing.id, path: params.path, created: false }
  }

  const created = await apiPost<DocRow>(
    `${docsBase(config)}`,
    { title, folderId, content: params.content },
    config
  )
  return { docId: created.id, path: params.path, created: true }
}

export async function moveProjectDoc(
  params: { path?: string; docId?: string; newPath: string },
  config: Config
): Promise<Record<string, unknown>> {
  const docId = await requireDocId(params, config)
  const { folderSegments, title } = splitPath(params.newPath)
  const folderId = await ensureFolder(folderSegments, config)

  const body: Record<string, unknown> = { title, folderId }
  const moved = await apiPatch<DocRow>(`${docsBase(config)}/${docId}`, body, config)
  return { docId: moved.id, path: params.newPath }
}

export async function deleteProjectDoc(
  params: { path?: string; docId?: string },
  config: Config
): Promise<Record<string, unknown>> {
  const docId = await requireDocId(params, config)
  await apiDelete(`${docsBase(config)}/${docId}`, config)
  return { docId, deleted: true }
}

/**
 * A 409 here means the addressed line is no longer a task item — the doc moved under the caller. That
 * is information the agent can act on, so it comes back as data rather than an exception
 * (docs/mcp-tool-guidelines.md § "no false promises"), mirroring knowledge.ts's conflict handling.
 */
export async function setProjectDocTask(
  params: { path?: string; docId?: string; lineNumber: number; checked: boolean },
  config: Config
): Promise<Record<string, unknown>> {
  const docId = await requireDocId(params, config)
  try {
    const doc = await apiPatch<DocRow>(
      `${docsBase(config)}/${docId}/tasks/${params.lineNumber}`,
      { checked: params.checked },
      config
    )
    return { docId, lineNumber: params.lineNumber, checked: params.checked, taskLines: taskLines(doc.content) }
  } catch (err) {
    if (err instanceof Error && /^API error 409:/.test(err.message)) {
      return {
        conflict: true,
        docId,
        lineNumber: params.lineNumber,
        message:
          `Line ${params.lineNumber} is no longer a task list item — the document changed. Call ` +
          'read_project_doc to get current taskLines, then retry once with the right line number.',
      }
    }
    throw err
  }
}

export async function listProjectDocComments(
  params: { path?: string; docId?: string; includeResolved?: boolean },
  config: Config
): Promise<unknown[]> {
  const docId = await requireDocId(params, config)
  const raw = await apiGet<Record<string, unknown>[]>(`${docsBase(config)}/${docId}/comments`, config)
  const comments = Array.isArray(raw) ? raw : []
  if (params.includeResolved) return comments
  return comments.filter((c) => c['resolvedAt'] == null)
}

export async function commentOnProjectDoc(
  params: { path?: string; docId?: string; lineNumber: number; content: string; quotedText?: string },
  config: Config
): Promise<Record<string, unknown>> {
  const docId = await requireDocId(params, config)
  const body: Record<string, unknown> = { content: params.content, lineNumber: params.lineNumber }
  if (params.quotedText !== undefined) body['quotedText'] = params.quotedText
  return apiPost<Record<string, unknown>>(`${docsBase(config)}/${docId}/comments`, body, config)
}

/**
 * Reply to and/or resolve one thread — the two halves of answering a review comment, which agents
 * almost always do together.
 */
export async function respondToProjectDocComment(
  params: { path?: string; docId?: string; commentId: string; reply?: string; resolve?: boolean },
  config: Config
): Promise<Record<string, unknown>> {
  if (params.reply === undefined && params.resolve !== true) {
    throw new Error('Provide reply, resolve: true, or both')
  }
  const docId = await requireDocId(params, config)
  const result: Record<string, unknown> = { docId, commentId: params.commentId }

  if (params.reply !== undefined) {
    await apiPost(
      `${docsBase(config)}/${docId}/comments/${params.commentId}/replies`,
      { content: params.reply },
      config
    )
    result['replied'] = true
  }

  if (params.resolve === true) {
    await apiPatch(`${docsBase(config)}/${docId}/comments/${params.commentId}/resolve`, {}, config)
    result['resolved'] = true
  }

  return result
}
