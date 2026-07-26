import { describe, it, expect, vi, beforeEach } from 'vitest'
import type { Config } from '../mcp/config.js'

vi.mock('../mcp/api.js', () => ({
  apiGet: vi.fn(),
  apiPost: vi.fn(),
  apiPut: vi.fn(),
  apiPatch: vi.fn(),
  apiDelete: vi.fn(),
  apiPostFile: vi.fn(),
}))

vi.mock('node:fs/promises', () => ({ readFile: vi.fn() }))

import { readFile } from 'node:fs/promises'
import { apiGet, apiPost, apiPut, apiPatch, apiDelete, apiPostFile } from '../mcp/api.js'
import {
  listProjectDocs,
  readProjectDoc,
  writeProjectDoc,
  moveProjectDoc,
  deleteProjectDoc,
  setProjectDocTask,
  listProjectDocComments,
  commentOnProjectDoc,
  respondToProjectDocComment,
  listProjectDocVersions,
  restoreProjectDocVersion,
  uploadProjectDocImage,
  taskLines,
} from '../mcp/tools/project-docs.js'

const config: Config = {
  apiKey: 'k',
  projectId: 'proj-1',
  projectName: 'P',
  email: 'e@x.test',
  apiUrl: 'https://api.test',
}

const BASE = '/api/v1/projects/proj-1/docs'

const FOLDERS = [
  { id: 'f-plans', parentId: null, name: 'Plans' },
  { id: 'f-q3', parentId: 'f-plans', name: 'Q3' },
]

const mockGet = apiGet as ReturnType<typeof vi.fn>
const mockPost = apiPost as ReturnType<typeof vi.fn>
const mockPut = apiPut as ReturnType<typeof vi.fn>
const mockPatch = apiPatch as ReturnType<typeof vi.fn>
const mockDelete = apiDelete as ReturnType<typeof vi.fn>
const mockPostFile = apiPostFile as ReturnType<typeof vi.fn>
const mockReadFile = readFile as unknown as ReturnType<typeof vi.fn>

/** Routes GETs by URL so path resolution (folders, then docs in a folder) can be exercised for real. */
function routeGet(routes: Record<string, unknown>) {
  mockGet.mockImplementation((url: string) => {
    if (url in routes) return Promise.resolve(routes[url])
    return Promise.reject(new Error(`API error 404: unexpected GET ${url}`))
  })
}

describe('project docs MCP tools', () => {
  beforeEach(() => vi.clearAllMocks())

  describe('path resolution', () => {
    it('walks nested folders to find a doc by path', async () => {
      routeGet({
        [`${BASE}/folders`]: FOLDERS,
        [`${BASE}?folderId=f-q3`]: [{ id: 'doc-1', folderId: 'f-q3', title: 'Roadmap' }],
        [`${BASE}/doc-1`]: { id: 'doc-1', folderId: 'f-q3', title: 'Roadmap', content: 'hello' },
      })

      const result = await readProjectDoc({ path: 'Plans/Q3/Roadmap' }, config)

      expect(result['docId']).toBe('doc-1')
      expect(result['path']).toBe('Plans/Q3/Roadmap')
      expect(result['content']).toBe('hello')
    })

    it('reports the missing segment when a folder in the path does not exist', async () => {
      routeGet({ [`${BASE}/folders`]: FOLDERS })

      await expect(readProjectDoc({ path: 'Archive/Roadmap' }, config)).rejects.toThrow(
        'Folder "Archive" not found'
      )
    })

    it('reports the path when the folder exists but the doc does not', async () => {
      routeGet({
        [`${BASE}/folders`]: FOLDERS,
        [`${BASE}?folderId=f-plans`]: [],
      })

      await expect(readProjectDoc({ path: 'Plans/Missing' }, config)).rejects.toThrow(
        'No document at path "Plans/Missing"'
      )
    })

    it('takes docId directly, skipping resolution — the escape hatch for titles containing "/"', async () => {
      routeGet({
        [`${BASE}/doc-9`]: { id: 'doc-9', folderId: null, title: 'A/B', content: '' },
        [`${BASE}/folders`]: FOLDERS,
      })

      const result = await readProjectDoc({ docId: 'doc-9' }, config)

      expect(result['docId']).toBe('doc-9')
    })
  })

  describe('list_project_docs', () => {
    it('returns the whole tree as paths when no filter is given', async () => {
      routeGet({
        [`${BASE}/folders`]: FOLDERS,
        [`${BASE}`]: [{ id: 'doc-root', folderId: null, title: 'README' }],
        [`${BASE}?folderId=f-plans`]: [],
        [`${BASE}?folderId=f-q3`]: [{ id: 'doc-1', folderId: 'f-q3', title: 'Roadmap' }],
      })

      const result = await listProjectDocs({}, config)

      expect(result['folders']).toEqual(['Plans', 'Plans/Q3'])
      // Sorted by path, so a nested doc can precede a root one.
      expect(result['docs']).toEqual([
        { docId: 'doc-1', path: 'Plans/Q3/Roadmap', updatedAt: undefined, updatedByName: undefined },
        { docId: 'doc-root', path: 'README', updatedAt: undefined, updatedByName: undefined },
      ])
    })

    it('searches instead of listing when query is given', async () => {
      routeGet({
        [`${BASE}/search?q=roadmap`]: [{ id: 'doc-1', folderId: 'f-q3', title: 'Roadmap', snippet: '...' }],
        [`${BASE}/folders`]: FOLDERS,
      })

      const result = await listProjectDocs({ query: 'roadmap' }, config)

      expect(result['matches']).toEqual([
        { docId: 'doc-1', path: 'Plans/Q3/Roadmap', snippet: '...' },
      ])
    })
  })

  describe('write_project_doc', () => {
    it('creates the doc and any missing folders when the path is new', async () => {
      routeGet({
        [`${BASE}/folders`]: [],
        [`${BASE}?folderId=f-new`]: [],
      })
      mockPost.mockImplementation((url: string, body: Record<string, unknown>) => {
        if (url.endsWith('/folders')) {
          return Promise.resolve({ id: 'f-new', parentId: body['parentId'], name: body['name'] })
        }
        return Promise.resolve({ id: 'doc-new', folderId: 'f-new', title: body['title'] })
      })

      const result = await writeProjectDoc({ path: 'Archive/Notes', content: '# Notes' }, config)

      expect(mockPost).toHaveBeenCalledWith(`${BASE}/folders`, { name: 'Archive', parentId: null }, config)
      expect(mockPost).toHaveBeenCalledWith(
        BASE,
        { title: 'Notes', folderId: 'f-new', content: '# Notes' },
        config
      )
      expect(result).toEqual({ docId: 'doc-new', path: 'Archive/Notes', created: true })
    })

    it('replaces content in place when the doc already exists, minting no folder', async () => {
      routeGet({
        [`${BASE}/folders`]: FOLDERS,
        [`${BASE}?folderId=f-plans`]: [{ id: 'doc-2', folderId: 'f-plans', title: 'Notes' }],
      })
      mockPut.mockResolvedValue({ id: 'doc-2' })

      const result = await writeProjectDoc({ path: 'Plans/Notes', content: 'updated' }, config)

      expect(mockPut).toHaveBeenCalledWith(`${BASE}/doc-2`, { content: 'updated' }, config)
      expect(mockPost).not.toHaveBeenCalled()
      expect(result).toEqual({ docId: 'doc-2', path: 'Plans/Notes', created: false })
    })
  })

  describe('set_project_doc_task', () => {
    it('PATCHes the addressed line and returns the refreshed task index', async () => {
      routeGet({ [`${BASE}/folders`]: FOLDERS })
      mockPatch.mockResolvedValue({ id: 'doc-1', content: '- [x] alpha\n- [ ] beta' })

      const result = await setProjectDocTask({ docId: 'doc-1', lineNumber: 1, checked: true }, config)

      expect(mockPatch).toHaveBeenCalledWith(`${BASE}/doc-1/tasks/1`, { checked: true }, config)
      expect(result['taskLines']).toEqual([
        { lineNumber: 1, checked: true, text: 'alpha' },
        { lineNumber: 2, checked: false, text: 'beta' },
      ])
    })

    it('returns a 409 as actionable data rather than throwing', async () => {
      mockPatch.mockRejectedValue(new Error('API error 409: {"detail":"no longer a task list item"}'))

      const result = await setProjectDocTask({ docId: 'doc-1', lineNumber: 4, checked: true }, config)

      expect(result['conflict']).toBe(true)
      expect(result['message']).toContain('read_project_doc')
    })

    it('still throws on errors that are not conflicts', async () => {
      mockPatch.mockRejectedValue(new Error('API error 403: forbidden'))

      await expect(
        setProjectDocTask({ docId: 'doc-1', lineNumber: 1, checked: true }, config)
      ).rejects.toThrow('API error 403')
    })
  })

  describe('taskLines', () => {
    it('indexes every marker form with 1-based line numbers', () => {
      const content = ['# Heading', '- [ ] alpha', '  * [x] beta', '1. [ ] gamma', 'plain text'].join('\n')

      expect(taskLines(content)).toEqual([
        { lineNumber: 2, checked: false, text: 'alpha' },
        { lineNumber: 3, checked: true, text: 'beta' },
        { lineNumber: 4, checked: false, text: 'gamma' },
      ])
    })

    it('counts frontmatter lines, since the backend anchors against raw content', () => {
      const content = ['---', 'title: X', '---', '- [ ] alpha'].join('\n')

      expect(taskLines(content)).toEqual([{ lineNumber: 4, checked: false, text: 'alpha' }])
    })

    it('returns nothing for empty or missing content', () => {
      expect(taskLines(null)).toEqual([])
      expect(taskLines('')).toEqual([])
    })
  })

  describe('move and delete', () => {
    it('move_project_doc PATCHes the new title and folder together', async () => {
      routeGet({ [`${BASE}/folders`]: FOLDERS })
      mockPatch.mockResolvedValue({ id: 'doc-1' })

      const result = await moveProjectDoc({ docId: 'doc-1', newPath: 'Plans/Q3/Old Roadmap' }, config)

      expect(mockPatch).toHaveBeenCalledWith(
        `${BASE}/doc-1`,
        { title: 'Old Roadmap', folderId: 'f-q3' },
        config
      )
      expect(result).toEqual({ docId: 'doc-1', path: 'Plans/Q3/Old Roadmap' })
    })

    it('move_project_doc to a bare title moves the doc to the root', async () => {
      routeGet({ [`${BASE}/folders`]: FOLDERS })
      mockPatch.mockResolvedValue({ id: 'doc-1' })

      await moveProjectDoc({ docId: 'doc-1', newPath: 'Roadmap' }, config)

      expect(mockPatch).toHaveBeenCalledWith(`${BASE}/doc-1`, { title: 'Roadmap', folderId: null }, config)
    })

    it('delete_project_doc DELETEs the resolved doc', async () => {
      routeGet({
        [`${BASE}/folders`]: FOLDERS,
        [`${BASE}?folderId=f-plans`]: [{ id: 'doc-2', folderId: 'f-plans', title: 'Notes' }],
      })
      mockDelete.mockResolvedValue(undefined)

      const result = await deleteProjectDoc({ path: 'Plans/Notes' }, config)

      expect(mockDelete).toHaveBeenCalledWith(`${BASE}/doc-2`, config)
      expect(result).toEqual({ docId: 'doc-2', deleted: true })
    })
  })

  describe('comments', () => {
    it('hides resolved threads unless asked for them', async () => {
      const comments = [
        { id: 'c-1', content: 'open', resolvedAt: null },
        { id: 'c-2', content: 'done', resolvedAt: '2026-01-01T00:00:00Z' },
      ]
      routeGet({ [`${BASE}/doc-1/comments`]: comments })

      expect(await listProjectDocComments({ docId: 'doc-1' }, config)).toEqual([comments[0]])
      expect(await listProjectDocComments({ docId: 'doc-1', includeResolved: true }, config)).toEqual(
        comments
      )
    })

    it('comment_on_project_doc omits quotedText when not supplied', async () => {
      mockPost.mockResolvedValue({ id: 'c-3' })

      await commentOnProjectDoc({ docId: 'doc-1', lineNumber: 3, content: 'needs work' }, config)

      expect(mockPost).toHaveBeenCalledWith(
        `${BASE}/doc-1/comments`,
        { content: 'needs work', lineNumber: 3 },
        config
      )
    })

    it('respond_to_project_doc_comment replies and resolves in one call', async () => {
      mockPost.mockResolvedValue({ id: 'r-1' })
      mockPatch.mockResolvedValue({ id: 'c-1' })

      const result = await respondToProjectDocComment(
        { docId: 'doc-1', commentId: 'c-1', reply: 'fixed', resolve: true },
        config
      )

      expect(mockPost).toHaveBeenCalledWith(
        `${BASE}/doc-1/comments/c-1/replies`,
        { content: 'fixed' },
        config
      )
      expect(mockPatch).toHaveBeenCalledWith(`${BASE}/doc-1/comments/c-1/resolve`, {}, config)
      expect(result).toEqual({ docId: 'doc-1', commentId: 'c-1', replied: true, resolved: true })
    })

    it('respond_to_project_doc_comment refuses a call that would do nothing', async () => {
      await expect(
        respondToProjectDocComment({ docId: 'doc-1', commentId: 'c-1' }, config)
      ).rejects.toThrow('Provide reply, resolve: true, or both')
    })
  })

  describe('versions', () => {
    const VERSIONS = [
      { id: 'v-2', versionNumber: 2, authorName: 'Ada', createdAt: '2026-02-01T00:00:00Z' },
      { id: 'v-1', versionNumber: 1, authorName: 'Agent', createdAt: '2026-01-01T00:00:00Z' },
    ]

    it('list_project_doc_versions projects the history, dropping internal ids', async () => {
      routeGet({ [`${BASE}/doc-1/versions`]: VERSIONS })

      const result = await listProjectDocVersions({ docId: 'doc-1' }, config)

      expect(result).toEqual([
        { versionNumber: 2, authorName: 'Ada', createdAt: '2026-02-01T00:00:00Z' },
        { versionNumber: 1, authorName: 'Agent', createdAt: '2026-01-01T00:00:00Z' },
      ])
    })

    it('read_project_doc resolves a versionNumber to its version and returns that content', async () => {
      routeGet({
        [`${BASE}/doc-1`]: { id: 'doc-1', folderId: null, title: 'Notes', content: 'current' },
        [`${BASE}/folders`]: FOLDERS,
        [`${BASE}/doc-1/versions`]: VERSIONS,
        [`${BASE}/doc-1/versions/v-1`]: { id: 'v-1', versionNumber: 1, content: '- [ ] old' },
      })

      const result = await readProjectDoc({ docId: 'doc-1', versionNumber: 1 }, config)

      expect(result['content']).toBe('- [ ] old')
      expect(result['versionNumber']).toBe(1)
      // The task index must describe the version being read, not the live document.
      expect(result['taskLines']).toEqual([{ lineNumber: 1, checked: false, text: 'old' }])
    })

    it('names the versions that do exist when the requested one does not', async () => {
      routeGet({
        [`${BASE}/doc-1`]: { id: 'doc-1', folderId: null, title: 'Notes', content: '' },
        [`${BASE}/folders`]: FOLDERS,
        [`${BASE}/doc-1/versions`]: VERSIONS,
      })

      await expect(readProjectDoc({ docId: 'doc-1', versionNumber: 9 }, config)).rejects.toThrow(
        'No version 9 for this document — available: 2, 1'
      )
    })

    it('restore_project_doc_version POSTs to the resolved version', async () => {
      routeGet({ [`${BASE}/doc-1/versions`]: VERSIONS })
      mockPost.mockResolvedValue({ id: 'doc-1', content: '- [ ] old' })

      const result = await restoreProjectDocVersion({ docId: 'doc-1', versionNumber: 1 }, config)

      expect(mockPost).toHaveBeenCalledWith(`${BASE}/doc-1/versions/v-1/restore`, {}, config)
      expect(result).toEqual({ docId: 'doc-1', restoredFromVersion: 1, content: '- [ ] old' })
    })
  })

  describe('image upload', () => {
    it('posts the file as multipart and returns the snippet to embed', async () => {
      mockReadFile.mockResolvedValue(Buffer.from([1, 2, 3]))
      mockPostFile.mockResolvedValue({
        markdownSnippet: '![diagram.png](https://signed.test/x?sig=1)',
        storageUrl: 'https://signed.test/x?sig=1',
      })

      const result = await uploadProjectDocImage(
        { docId: 'doc-1', filePath: '/tmp/diagram.png' },
        config
      )

      expect(mockReadFile).toHaveBeenCalledWith('/tmp/diagram.png')
      expect(mockPostFile).toHaveBeenCalledWith(
        `${BASE}/doc-1/images`,
        expect.objectContaining({
          fieldName: 'image',
          filename: 'diagram.png',
          contentType: 'image/png',
        }),
        config
      )
      expect(result['markdownSnippet']).toBe('![diagram.png](https://signed.test/x?sig=1)')
    })

    it('maps .jpg to image/jpeg', async () => {
      mockReadFile.mockResolvedValue(Buffer.from([1]))
      mockPostFile.mockResolvedValue({ markdownSnippet: '', storageUrl: '' })

      await uploadProjectDocImage({ docId: 'doc-1', filePath: '/tmp/shot.JPG' }, config)

      expect(mockPostFile).toHaveBeenCalledWith(
        expect.any(String),
        expect.objectContaining({ contentType: 'image/jpeg' }),
        config
      )
    })

    it('rejects an unsupported type before reading the file or calling the API', async () => {
      await expect(
        uploadProjectDocImage({ docId: 'doc-1', filePath: '/tmp/notes.pdf' }, config)
      ).rejects.toThrow('Unsupported image type ".pdf"')

      expect(mockReadFile).not.toHaveBeenCalled()
      expect(mockPostFile).not.toHaveBeenCalled()
    })
  })
})
