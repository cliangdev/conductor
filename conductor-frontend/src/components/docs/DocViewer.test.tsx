import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, act, within, fireEvent } from '@testing-library/react'

const { apiGet, apiPost, apiPatch, apiDelete } = vi.hoisted(() => ({
  apiGet: vi.fn(),
  apiPost: vi.fn(),
  apiPatch: vi.fn(),
  apiDelete: vi.fn(),
}))
vi.mock('@/lib/api', () => ({
  apiGet,
  apiPost,
  apiPatch,
  apiDelete,
  apiErrorMessage: (_e: unknown, fallback: string) => fallback,
}))
vi.mock('@/components/markdown/SignedImage', () => ({ SignedImage: () => null }))
vi.mock('@/components/markdown/MermaidDiagram', () => ({ MermaidDiagram: () => null }))

import { DocViewer } from './DocViewer'
import type { ProjectDoc } from '@/lib/docs-api'
import type { Comment } from '@/components/comments/types'

// Line numbers count from the raw source: the frontmatter is lines 1-3.
const SOURCE = '---\ntitle: Plan\n---\n\n# Heading\n\nBody line.\n\nLast words.\n'

const doc: ProjectDoc = {
  id: 'pd-1',
  projectId: 'proj-1',
  folderId: null,
  title: 'Plan',
  createdAt: '2026-10-01T00:00:00Z',
  updatedAt: '2026-10-01T00:00:00Z',
  createdByName: 'Ada',
  updatedByName: 'Ada',
  content: SOURCE,
  contentHash: 'abc123',
}

function comment(over: Partial<Comment> & { id: string }): Comment {
  return {
    documentId: null,
    kind: over.anchor ? 'QUOTE' : over.lineNumber ? 'LINE' : 'DOCUMENT',
    status: 'OPEN',
    authorId: 'u2',
    authorName: 'Grace',
    authorKind: 'USER',
    content: `comment ${over.id}`,
    createdAt: '2026-10-01T00:00:00Z',
    replies: [],
    ...over,
  }
}

async function renderViewer(comments: Comment[]) {
  apiGet.mockResolvedValue(comments)
  render(<DocViewer doc={doc} projectId="proj-1" token="tok" currentUserId="u1" />)
  await waitFor(() => expect(apiGet).toHaveBeenCalled())
  await act(async () => {})
}

describe('DocViewer', () => {
  let painted: Map<string, { ranges: Range[] }>

  beforeEach(() => {
    vi.clearAllMocks()
    painted = new Map()
    vi.stubGlobal(
      'Highlight',
      class {
        ranges: Range[]
        constructor(...r: Range[]) {
          this.ranges = r
        }
      }
    )
    vi.stubGlobal('CSS', {
      highlights: {
        set: (k: string, v: { ranges: Range[] }) => painted.set(k, v),
        delete: (k: string) => painted.delete(k),
      },
    })
  })
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('reads its comments from the unified resource by project doc id', async () => {
    await renderViewer([])
    expect(apiGet).toHaveBeenCalledTimes(1)
    const url = apiGet.mock.calls[0][0] as string
    expect(url).toContain('/api/v2/projects/proj-1/comments?')
    expect(url).toContain('projectDocId=pd-1')
    expect(url).not.toContain('/api/v1/')
  })

  it('shows a quote comment on the rail and highlights its words', async () => {
    await renderViewer([comment({ id: 'q1', anchor: { exact: 'Last words' } })])
    expect(document.querySelector('[data-comment-id="q1"]')).toBeInTheDocument()
    expect(painted.get('conductor-comment')!.ranges.map((r) => r.toString())).toEqual(['Last words'])
  })

  it('puts an older line comment on the right block despite the frontmatter', async () => {
    // Line 7 of the raw source is "Body line." — three frontmatter lines and a blank come first.
    await renderViewer([comment({ id: 'l1', lineNumber: 7 })])
    expect(painted.get('conductor-comment')!.ranges.map((r) => r.toString())).toEqual(['Body line.'])
  })

  it('offers whole-document comments, with reopen/filters from the shared rail', async () => {
    await renderViewer([comment({ id: 'd1' })])
    const section = screen.getByRole('region', { name: 'Whole document' })
    expect(within(section).getByText('comment d1')).toBeInTheDocument()
    expect(within(section).getByRole('button', { name: /Comment on whole document/ })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Resolved' })).toBeInTheDocument()
  })

  it('sends the content hash with a whole-document comment', async () => {
    apiPost.mockResolvedValue(comment({ id: 'new' }))
    await renderViewer([])
    fireEvent.click(screen.getByRole('button', { name: /Comment on whole document/ }))
    fireEvent.change(screen.getByRole('textbox'), { target: { value: 'Overall: good.' } })
    fireEvent.click(screen.getByRole('button', { name: 'Comment' }))
    await waitFor(() => expect(apiPost).toHaveBeenCalled())
    expect(apiPost.mock.calls[0][1]).toMatchObject({
      target: { projectDocId: 'pd-1' },
      content: 'Overall: good.',
      documentHash: 'abc123',
    })
  })
})
