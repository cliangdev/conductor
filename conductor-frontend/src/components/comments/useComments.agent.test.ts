import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { renderHook, act } from '@testing-library/react'

const { apiGet, apiPost, apiPatch, apiDelete, toastError } = vi.hoisted(() => ({
  apiGet: vi.fn(),
  apiPost: vi.fn(),
  apiPatch: vi.fn(),
  apiDelete: vi.fn(),
  toastError: vi.fn(),
}))
vi.mock('@/lib/api', () => ({
  apiGet,
  apiPost,
  apiPatch,
  apiDelete,
  apiErrorMessage: (e: unknown, fallback: string) => (e as { message?: string })?.message || fallback,
}))
vi.mock('@/components/ui/toast', () => ({ toastError }))

import { AGENT_FIRST_REFETCH_MS, AGENT_POLL_MAX_MS, AGENT_POLL_MS, useComments } from './useComments'
import type { Comment, CommentAgentRequest } from './types'

const target = { projectId: 'p1', workItemId: 'wi1', documentId: 'doc-1' }
const AGENT_TOKEN = '@[Scribe](agent:a1)'

function req(status: CommentAgentRequest['status'], over: Partial<CommentAgentRequest> = {}): CommentAgentRequest {
  return { agentId: 'a1', agentName: 'Scribe', status, createdAt: new Date().toISOString(), ...over }
}

function thread(id: string, over: Partial<Comment> = {}): Comment {
  return {
    id,
    documentId: 'doc-1',
    kind: 'DOCUMENT',
    status: 'OPEN',
    authorId: 'u1',
    authorName: 'Ada',
    content: `c ${id}`,
    createdAt: '2026-10-01T00:00:00Z',
    replies: [],
    ...over,
  }
}

async function flush() {
  await act(async () => {
    await Promise.resolve()
  })
}

async function advance(ms: number) {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(ms)
  })
}

function mount(extra: { onDocumentChanged?: () => void } = {}) {
  return renderHook(() =>
    useComments(target, { token: 'tok', currentUser: { id: 'u1', name: 'Ada' }, ...extra })
  )
}

describe('useComments @agent polling', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.clearAllMocks()
  })
  afterEach(() => {
    vi.useRealTimers()
  })

  it('refetches once ~1.5s after a comment that mentions an agent', async () => {
    apiGet.mockResolvedValue([])
    const hook = mount()
    await flush()
    expect(apiGet).toHaveBeenCalledTimes(1)
    apiPost.mockResolvedValue(thread('c1', { content: `hi ${AGENT_TOKEN}` }))
    await act(async () => {
      await hook.result.current.create({ content: `hi ${AGENT_TOKEN}` })
    })
    expect(apiGet).toHaveBeenCalledTimes(1)
    await advance(AGENT_FIRST_REFETCH_MS + 50)
    expect(apiGet).toHaveBeenCalledTimes(2)
  })

  it('does not refetch for a comment with no agent mention', async () => {
    apiGet.mockResolvedValue([])
    const hook = mount()
    await flush()
    apiPost.mockResolvedValue(thread('c1'))
    await act(async () => {
      await hook.result.current.create({ content: 'plain' })
    })
    await advance(AGENT_FIRST_REFETCH_MS + 50)
    expect(apiGet).toHaveBeenCalledTimes(1)
  })

  it('also refetches after a reply that mentions an agent', async () => {
    apiGet.mockResolvedValue([thread('c1')])
    const hook = mount()
    await flush()
    apiPost.mockResolvedValue({ id: 'r1', authorId: 'u1', authorName: 'Ada', content: AGENT_TOKEN, createdAt: 'x' })
    await act(async () => {
      await hook.result.current.reply('c1', AGENT_TOKEN)
    })
    await advance(AGENT_FIRST_REFETCH_MS + 50)
    expect(apiGet).toHaveBeenCalledTimes(2)
  })

  it('polls every 5s while a request is pending or running, and stops when none is', async () => {
    apiGet.mockResolvedValue([thread('c1', { agentRequests: [req('PENDING')] })])
    mount()
    await flush()
    expect(apiGet).toHaveBeenCalledTimes(1)
    await advance(AGENT_POLL_MS)
    expect(apiGet).toHaveBeenCalledTimes(2)
    apiGet.mockResolvedValue([thread('c1', { agentRequests: [req('RUNNING')] })])
    await advance(AGENT_POLL_MS)
    expect(apiGet).toHaveBeenCalledTimes(3)
    apiGet.mockResolvedValue([thread('c1', { agentRequests: [req('SUCCEEDED')] })])
    await advance(AGENT_POLL_MS)
    expect(apiGet).toHaveBeenCalledTimes(4)
    await advance(AGENT_POLL_MS * 4)
    expect(apiGet).toHaveBeenCalledTimes(4)
  })

  it('does not poll when nothing is in flight', async () => {
    apiGet.mockResolvedValue([thread('c1', { agentRequests: [req('FAILED', { error: 'nope' })] })])
    mount()
    await flush()
    await advance(AGENT_POLL_MS * 3)
    expect(apiGet).toHaveBeenCalledTimes(1)
  })

  it('gives up after 20 minutes', async () => {
    apiGet.mockResolvedValue([thread('c1', { agentRequests: [req('RUNNING')] })])
    mount()
    await flush()
    await advance(AGENT_POLL_MAX_MS + AGENT_POLL_MS)
    const calls = apiGet.mock.calls.length
    expect(calls).toBeGreaterThan(100)
    await advance(AGENT_POLL_MS * 5)
    expect(apiGet).toHaveBeenCalledTimes(calls)
  })

  it('refetches when the window regains focus', async () => {
    apiGet.mockResolvedValue([])
    mount()
    await flush()
    await act(async () => {
      window.dispatchEvent(new Event('focus'))
    })
    expect(apiGet).toHaveBeenCalledTimes(2)
  })

  it('reports a changed document when a watched request finishes (once)', async () => {
    const onDocumentChanged = vi.fn()
    apiGet.mockResolvedValue([thread('c1', { agentRequests: [req('RUNNING')] })])
    mount({ onDocumentChanged })
    await flush()
    expect(onDocumentChanged).not.toHaveBeenCalled()
    apiGet.mockResolvedValue([thread('c1', { agentRequests: [req('SUCCEEDED')] })])
    await advance(AGENT_POLL_MS)
    expect(onDocumentChanged).toHaveBeenCalledTimes(1)
    await act(async () => {
      window.dispatchEvent(new Event('focus'))
    })
    expect(onDocumentChanged).toHaveBeenCalledTimes(1)
  })

  it('reports it when a request we started finished before we first saw it running', async () => {
    const onDocumentChanged = vi.fn()
    apiGet.mockResolvedValue([])
    const hook = mount({ onDocumentChanged })
    await flush()
    apiPost.mockResolvedValue(thread('c1'))
    await act(async () => {
      await hook.result.current.create({ content: `go ${AGENT_TOKEN}` })
    })
    apiGet.mockResolvedValue([thread('c1', { agentRequests: [req('SUCCEEDED')] })])
    await advance(AGENT_FIRST_REFETCH_MS + 50)
    expect(onDocumentChanged).toHaveBeenCalledTimes(1)
  })

  it('does not report a request that was already finished when the page loaded, or a failed one', async () => {
    const onDocumentChanged = vi.fn()
    apiGet.mockResolvedValue([
      thread('c1', { agentRequests: [req('SUCCEEDED', { createdAt: '2026-01-01T00:00:00Z' })] }),
      thread('c2', { agentRequests: [req('FAILED', { error: 'x' })] }),
    ])
    mount({ onDocumentChanged })
    await flush()
    expect(onDocumentChanged).not.toHaveBeenCalled()
  })
})

describe('useComments suggestion decisions', () => {
  beforeEach(() => {
    vi.useRealTimers()
    vi.clearAllMocks()
    apiGet.mockResolvedValue([thread('c1', { kind: 'QUOTE', suggestion: { text: 'new', status: 'PENDING' } })])
  })

  async function setup(onDocumentChanged = vi.fn()) {
    const hook = mount({ onDocumentChanged })
    await act(async () => {
      await Promise.resolve()
    })
    await act(async () => {
      await Promise.resolve()
    })
    return { hook, onDocumentChanged }
  }

  it('accepting posts the decision, updates the thread and reloads the document', async () => {
    const { hook, onDocumentChanged } = await setup()
    const accepted = thread('c1', {
      status: 'RESOLVED',
      suggestion: { text: 'new', status: 'ACCEPTED', decidedByName: 'Sam' },
    })
    apiPost.mockResolvedValue(accepted)
    apiGet.mockResolvedValue([accepted])
    let ok = false
    await act(async () => {
      ok = await hook.result.current.decideSuggestion('c1', 'ACCEPT')
    })
    expect(ok).toBe(true)
    expect(apiPost).toHaveBeenCalledWith(
      '/api/v2/projects/p1/comments/c1/suggestion-decision',
      { decision: 'ACCEPT' },
      'tok'
    )
    expect(hook.result.current.comments[0].suggestion?.status).toBe('ACCEPTED')
    expect(onDocumentChanged).toHaveBeenCalledTimes(1)
  })

  it('rejecting does not reload the document', async () => {
    const { hook, onDocumentChanged } = await setup()
    apiPost.mockResolvedValue(thread('c1', { status: 'RESOLVED', suggestion: { text: 'new', status: 'REJECTED' } }))
    await act(async () => {
      await hook.result.current.decideSuggestion('c1', 'REJECT')
    })
    expect(onDocumentChanged).not.toHaveBeenCalled()
  })

  it('explains a 403 on accept and leaves the thread alone', async () => {
    const { hook, onDocumentChanged } = await setup()
    apiPost.mockRejectedValue(Object.assign(new Error('Forbidden'), { status: 403 }))
    let ok = true
    await act(async () => {
      ok = await hook.result.current.decideSuggestion('c1', 'ACCEPT')
    })
    expect(ok).toBe(false)
    expect(toastError).toHaveBeenCalledWith('Only editors can accept suggestions')
    expect(hook.result.current.comments[0].suggestion?.status).toBe('PENDING')
    expect(onDocumentChanged).not.toHaveBeenCalled()
  })

  it('shows the server detail on a 409 and refetches', async () => {
    const { hook } = await setup()
    apiPost.mockRejectedValue(Object.assign(new Error('The text changed since this was suggested'), { status: 409 }))
    const before = apiGet.mock.calls.length
    await act(async () => {
      await hook.result.current.decideSuggestion('c1', 'ACCEPT')
    })
    expect(toastError).toHaveBeenCalledWith('The text changed since this was suggested')
    expect(apiGet.mock.calls.length).toBe(before + 1)
  })

  it('creates a suggestion with the replacement text', async () => {
    apiGet.mockResolvedValue([])
    const { hook } = await setup()
    apiPost.mockResolvedValue(thread('c9', { kind: 'QUOTE', suggestion: { text: 'x', status: 'PENDING' } }))
    await act(async () => {
      await hook.result.current.create({ content: 'Why', anchor: { exact: 'old' }, suggestion: { text: 'x' } })
    })
    expect(apiPost).toHaveBeenCalledWith(
      '/api/v2/projects/p1/comments',
      expect.objectContaining({ content: 'Why', anchor: { exact: 'old' }, suggestion: { text: 'x' } }),
      'tok'
    )
  })
})
