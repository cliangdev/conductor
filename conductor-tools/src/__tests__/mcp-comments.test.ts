import { describe, it, expect, vi, beforeEach } from 'vitest'
import * as fs from 'fs'
import * as os from 'os'
import * as path from 'path'
import type { Config } from '../mcp/config.js'

vi.mock('../mcp/api.js', () => ({
  apiGet: vi.fn(),
  apiPost: vi.fn(),
  apiPut: vi.fn(),
  apiPatch: vi.fn(),
  apiDelete: vi.fn(),
  apiPostFile: vi.fn(),
}))
vi.mock('../mcp/queue.js', () => ({ queueChange: vi.fn() }))
vi.mock('node:fs/promises', () => ({ readFile: vi.fn() }))

import { apiGet, apiPost, apiPut, apiPatch } from '../mcp/api.js'
import { listComments, addComment, respondToComment, readDocument } from '../mcp/tools/comments.js'
import { writeDocument } from '../mcp/tools/documents.js'
import { writeProjectDoc, readProjectDoc } from '../mcp/tools/project-docs.js'

const mockGet = apiGet as ReturnType<typeof vi.fn>
const mockPost = apiPost as ReturnType<typeof vi.fn>
const mockPut = apiPut as ReturnType<typeof vi.fn>
const mockPatch = apiPatch as ReturnType<typeof vi.fn>

const config: Config = {
  apiKey: 'k',
  projectId: 'proj-1',
  projectName: 'P',
  email: 'e@x.test',
  apiUrl: 'https://api.test',
}

const COMMENTS = '/api/v2/projects/proj-1/comments'
const DOCS = '/api/v2/projects/proj-1/work-items/w1/documents'

function conflict(): Error {
  const err = new Error('Document changed since you read it — read it again before writing.')
  ;(err as Error & { status: number }).status = 409
  return err
}

beforeEach(() => vi.resetAllMocks())

describe('list_comments', () => {
  const threads = [
    {
      id: 'c1',
      kind: 'QUOTE',
      status: 'OPEN',
      documentName: 'prd.md',
      authorName: 'Ada',
      authorKind: 'USER',
      content: 'Is 26 right?',
      quotedText: 'twenty of 26',
      anchor: { exact: 'twenty of 26', prefix: 'about ', suffix: ' users' },
      anchorStatus: 'ORPHANED',
      lineStale: false,
      outdated: false,
      replies: [{ id: 'r1', authorName: 'Bot', authorKind: 'AGENT', content: 'Checking', createdAt: 'x' }],
      createdAt: '2026-01-01',
    },
    {
      id: 'c2',
      kind: 'LINE',
      status: 'RESOLVED',
      authorName: 'Bo',
      authorKind: 'USER',
      content: 'typo',
      lineNumber: 4,
      quotedText: 'teh line',
      lineStale: true,
      replies: [],
    },
    { id: 'c3', kind: 'DOCUMENT', status: 'OPEN', authorName: 'Cy', authorKind: 'USER', content: 'Overall: tighten', replies: [] },
  ]

  it('lists open threads of one Work Item document, resolving the filename to a document id', async () => {
    mockGet.mockImplementation(async (url: string) =>
      url === DOCS ? [{ id: 'd9', filename: 'prd.md' }, { id: 'd8', filename: 'x.md' }] : threads
    )

    await listComments({ issueId: 'w1', filename: 'prd.md' }, config)

    expect(mockGet).toHaveBeenLastCalledWith(
      `${COMMENTS}?workItemId=w1&documentId=d9&status=open`,
      config
    )
  })

  it('lists every document of a Work Item when no filename is given', async () => {
    mockGet.mockResolvedValue([])
    await listComments({ issueId: 'w1', status: 'all' }, config)
    expect(mockGet).toHaveBeenCalledTimes(1)
    expect(mockGet).toHaveBeenCalledWith(`${COMMENTS}?workItemId=w1&status=all`, config)
  })

  it('lists a project doc by docId', async () => {
    mockGet.mockResolvedValue([])
    await listComments({ docId: 'pd-1', status: 'resolved' }, config)
    expect(mockGet).toHaveBeenCalledWith(`${COMMENTS}?projectDocId=pd-1&status=resolved`, config)
  })

  it('returns a compact thread per comment with only the fields an agent needs', async () => {
    mockGet.mockResolvedValue(threads)

    const result = (await listComments({ docId: 'pd-1' }, config)) as {
      count: number
      status: string
      threads: Record<string, unknown>[]
    }

    expect(result.count).toBe(3)
    expect(result.status).toBe('open')
    expect(result.threads[0]).toEqual({
      id: 'c1',
      kind: 'QUOTE',
      author: 'Ada',
      body: 'Is 26 right?',
      resolved: false,
      document: 'prd.md',
      quote: 'twenty of 26',
      anchorStatus: 'ORPHANED',
      replies: [{ author: 'Bot (agent)', body: 'Checking' }],
    })
    expect(result.threads[1]).toMatchObject({ kind: 'LINE', line: 4, lineText: 'teh line', resolved: true, stale: true })
    expect(result.threads[1]).not.toHaveProperty('replies')
    expect(result.threads[2]).toEqual({ id: 'c3', kind: 'DOCUMENT', author: 'Cy', body: 'Overall: tighten', resolved: false })
  })

  it('truncates a very long quote', async () => {
    const long = 'x'.repeat(500)
    mockGet.mockResolvedValue([{ id: 'c', kind: 'QUOTE', status: 'OPEN', content: 'b', anchor: { exact: long } }])
    const result = (await listComments({ docId: 'd' }, config)) as { threads: { quote: string }[] }
    expect(result.threads[0]!.quote.length).toBeLessThan(250)
  })

  it('names the real filenames when the filename is unknown', async () => {
    mockGet.mockResolvedValue([{ id: 'd1', filename: 'prd.md' }])
    await expect(listComments({ issueId: 'w1', filename: 'nope.md' }, config)).rejects.toThrow(
      'No document "nope.md" on Work Item w1 — available: prd.md'
    )
  })

  it('needs a target', async () => {
    await expect(listComments({}, config)).rejects.toThrow('issueId')
  })
})

describe('add_comment', () => {
  it('anchors a quote on a Work Item document', async () => {
    mockGet.mockResolvedValue([{ id: 'd9', filename: 'prd.md' }])
    mockPost.mockResolvedValue({ id: 'c1', kind: 'QUOTE', status: 'OPEN', content: 'why?', anchor: { exact: 'Goals' } })

    const result = await addComment({ issueId: 'w1', filename: 'prd.md', body: 'why?', quote: 'Goals' }, config)

    expect(mockPost).toHaveBeenCalledWith(
      COMMENTS,
      { target: { workItemId: 'w1', documentId: 'd9' }, content: 'why?', anchor: { exact: 'Goals' } },
      config
    )
    expect(result).toMatchObject({ id: 'c1', kind: 'QUOTE', quote: 'Goals' })
  })

  it('sends a suggested edit with its quote and shows it compactly', async () => {
    mockGet.mockResolvedValue([{ id: 'd9', filename: 'prd.md' }])
    mockPost.mockResolvedValue({
      id: 'c3', kind: 'QUOTE', status: 'OPEN', content: 'typo', anchor: { exact: 'teh' },
      suggestion: { text: 'the', status: 'PENDING', decidedAt: null },
    })

    const result = await addComment(
      { issueId: 'w1', filename: 'prd.md', body: 'typo', quote: 'teh', suggestion: 'the' }, config)

    expect(mockPost).toHaveBeenCalledWith(
      COMMENTS,
      { target: { workItemId: 'w1', documentId: 'd9' }, content: 'typo', anchor: { exact: 'teh' }, suggestion: { text: 'the' } },
      config
    )
    expect(result).toMatchObject({ id: 'c3', suggestion: { text: 'the', status: 'PENDING' } })
    expect((result['suggestion'] as object)).not.toHaveProperty('decidedAt')
  })

  it('refuses a suggestion without a quote before calling the API', async () => {
    await expect(addComment({ docId: 'd', body: 'x', suggestion: 'y' }, config)).rejects.toThrow('needs quote')
    expect(mockPost).not.toHaveBeenCalled()
  })

  it('lists suggestions with their status', async () => {
    mockGet.mockResolvedValue([
      { id: 'c4', kind: 'QUOTE', status: 'RESOLVED', content: 'fix', anchor: { exact: 'a' }, suggestion: { text: 'b', status: 'ACCEPTED' }, replies: [] },
    ])
    const result = await listComments({ docId: 'pd', status: 'all' }, config)
    expect((result['threads'] as Array<Record<string, unknown>>)[0]).toMatchObject({ suggestion: { text: 'b', status: 'ACCEPTED' } })
  })

  it('makes a whole-document comment on a project doc when no quote is given', async () => {
    mockPost.mockResolvedValue({ id: 'c2', kind: 'DOCUMENT', status: 'OPEN', content: 'overall' })

    await addComment({ docId: 'pd-1', body: 'overall' }, config)

    expect(mockPost).toHaveBeenCalledWith(
      COMMENTS,
      { target: { projectDocId: 'pd-1' }, content: 'overall' },
      config
    )
  })

  it('needs filename with issueId', async () => {
    await expect(addComment({ issueId: 'w1', body: 'x' }, config)).rejects.toThrow('issueId and filename')
  })

  it('rejects an empty body and an over-long quote', async () => {
    await expect(addComment({ docId: 'd', body: '  ' }, config)).rejects.toThrow('body is required')
    await expect(addComment({ docId: 'd', body: 'x', quote: 'q'.repeat(2001) }, config)).rejects.toThrow('too long')
  })
})

describe('respond_to_comment', () => {
  it('replies only', async () => {
    mockPost.mockResolvedValue({ id: 'r' })
    const result = await respondToComment({ commentId: 'c1', reply: 'Changed: x' }, config)
    expect(mockPost).toHaveBeenCalledWith(`${COMMENTS}/c1/replies`, { content: 'Changed: x' }, config)
    expect(mockPatch).not.toHaveBeenCalled()
    expect(result).toEqual({ commentId: 'c1', replied: true })
  })

  it('replies then resolves', async () => {
    mockPost.mockResolvedValue({ id: 'r' })
    mockPatch.mockResolvedValue({ id: 'c1', status: 'RESOLVED' })
    const result = await respondToComment({ commentId: 'c1', reply: 'done', resolve: true }, config)
    expect(mockPatch).toHaveBeenCalledWith(`${COMMENTS}/c1`, { status: 'RESOLVED' }, config)
    expect(result).toEqual({ commentId: 'c1', replied: true, resolved: true })
  })

  it('resolves without a reply', async () => {
    mockPatch.mockResolvedValue({ id: 'c1', status: 'RESOLVED' })
    await respondToComment({ commentId: 'c1', resolve: true }, config)
    expect(mockPost).not.toHaveBeenCalled()
  })

  it('reopens, optionally with a reply', async () => {
    mockPost.mockResolvedValue({ id: 'r' })
    mockPatch.mockResolvedValue({ id: 'c1', status: 'OPEN' })
    const result = await respondToComment({ commentId: 'c1', reply: 'not fixed', reopen: true }, config)
    expect(mockPatch).toHaveBeenCalledWith(`${COMMENTS}/c1`, { status: 'OPEN' }, config)
    expect(result).toEqual({ commentId: 'c1', replied: true, resolved: false })
  })

  it('refuses a call that does nothing, and resolve with reopen', async () => {
    await expect(respondToComment({ commentId: 'c1' }, config)).rejects.toThrow('Provide reply')
    await expect(respondToComment({ commentId: 'c1', reply: '  ' }, config)).rejects.toThrow('Provide reply')
    await expect(respondToComment({ commentId: 'c1', resolve: true, reopen: true }, config)).rejects.toThrow('not both')
    expect(mockPost).not.toHaveBeenCalled()
    expect(mockPatch).not.toHaveBeenCalled()
  })
})

describe('read_document', () => {
  it('returns content and contentHash for a filename', async () => {
    mockGet.mockImplementation(async (url: string) =>
      url === DOCS
        ? [{ id: 'd9', filename: 'prd.md' }]
        : { id: 'd9', filename: 'prd.md', contentType: 'text/markdown', content: '# Hi', contentHash: 'abc' }
    )

    const result = await readDocument({ issueId: 'w1', filename: 'prd.md' }, config)

    expect(mockGet).toHaveBeenLastCalledWith(`${DOCS}/d9`, config)
    expect(result).toEqual({
      documentId: 'd9',
      filename: 'prd.md',
      contentType: 'text/markdown',
      contentHash: 'abc',
      content: '# Hi',
    })
  })

  it('explains a document with no readable text', async () => {
    mockGet.mockImplementation(async (url: string) =>
      url === DOCS ? [{ id: 'd1', filename: 'a.png' }] : { id: 'd1', filename: 'a.png', contentType: 'image/png', content: null }
    )
    await expect(readDocument({ issueId: 'w1', filename: 'a.png' }, config)).rejects.toThrow('no readable text')
  })
})

describe('baseContentHash on writes', () => {
  let tmp: string
  let cfg: Config

  beforeEach(() => {
    tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'conductor-comments-'))
    cfg = { ...config, localPath: tmp }
  })

  it('write_document sends baseContentHash only when given', async () => {
    mockPut.mockResolvedValue({ id: 'd9' })

    await writeDocument({ issueId: 'w1', filename: 'prd.md', content: 'x' }, cfg)
    expect(mockPut.mock.calls[0]![1]).not.toHaveProperty('baseContentHash')

    await writeDocument({ issueId: 'w1', filename: 'prd.md', content: 'x', baseContentHash: 'h1' }, cfg)
    expect(mockPut.mock.calls[1]![1]).toMatchObject({ content: 'x', baseContentHash: 'h1' })
  })

  it('write_document turns a 409 into an instruction to re-read', async () => {
    mockPut.mockRejectedValue(conflict())
    await expect(
      writeDocument({ issueId: 'w1', filename: 'prd.md', content: 'x', baseContentHash: 'old' }, cfg)
    ).rejects.toThrow('read it again, redo your edit')
  })

  it('write_document passes other errors through untouched', async () => {
    mockPut.mockRejectedValue(new Error('boom'))
    await expect(writeDocument({ issueId: 'w1', filename: 'prd.md', content: 'x' }, cfg)).rejects.toThrow('boom')
  })

  function routeDocs() {
    mockGet.mockImplementation(async (url: string) => {
      if (url.endsWith('/docs/folders')) return []
      if (url === '/api/v1/projects/proj-1/docs') return [{ id: 'pd-1', folderId: null, title: 'Notes' }]
      if (url.endsWith('/docs/pd-1')) return { id: 'pd-1', folderId: null, title: 'Notes', content: 'body', contentHash: 'h9' }
      return []
    })
  }

  it('write_project_doc sends baseContentHash on update and surfaces a 409 clearly', async () => {
    routeDocs()
    mockPut.mockResolvedValueOnce({ id: 'pd-1' })
    await writeProjectDoc({ path: 'Notes', content: 'new', baseContentHash: 'h9' }, config)
    expect(mockPut).toHaveBeenCalledWith(
      '/api/v1/projects/proj-1/docs/pd-1',
      { content: 'new', baseContentHash: 'h9' },
      config
    )

    mockPut.mockRejectedValueOnce(conflict())
    await expect(
      writeProjectDoc({ path: 'Notes', content: 'new', baseContentHash: 'stale' }, config)
    ).rejects.toThrow('read it again')
  })

  it('write_project_doc without a hash keeps the old request body', async () => {
    routeDocs()
    mockPut.mockResolvedValue({ id: 'pd-1' })
    await writeProjectDoc({ path: 'Notes', content: 'new' }, config)
    expect(mockPut).toHaveBeenCalledWith('/api/v1/projects/proj-1/docs/pd-1', { content: 'new' }, config)
  })

  it('read_project_doc returns the contentHash of the current content only', async () => {
    routeDocs()
    const current = await readProjectDoc({ docId: 'pd-1' }, config)
    expect(current['contentHash']).toBe('h9')
  })
})
