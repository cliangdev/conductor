import { describe, it, expect, vi, beforeEach } from 'vitest'
import { renderHook, act, waitFor } from '@testing-library/react'

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
  apiErrorMessage: (_e: unknown, fallback: string) => fallback,
}))
vi.mock('@/components/ui/toast', () => ({ toastError }))

import { useComments } from './useComments'
import type { Comment } from './types'

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

const target = { projectId: 'p1', workItemId: 'wi1', documentId: 'doc-1' }
const options = { token: 'tok', currentUser: { id: 'u1', name: 'Ada' } }

async function load(list: Comment[] = [thread('c1')], t: Parameters<typeof useComments>[0] = target) {
  apiGet.mockResolvedValue(list)
  const hook = renderHook(() => useComments(t, options))
  await waitFor(() => expect(hook.result.current.loading).toBe(false))
  return hook
}

describe('useComments', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('lists a Work Item document’s comments and a project doc’s', async () => {
    await load()
    expect(apiGet).toHaveBeenCalledWith('/api/v2/projects/p1/comments?workItemId=wi1&documentId=doc-1&status=all', 'tok')
    await load([], { projectId: 'p1', projectDocId: 'pd1' })
    expect(apiGet).toHaveBeenLastCalledWith('/api/v2/projects/p1/comments?projectDocId=pd1&status=all', 'tok')
  })

  it('creates a project-doc comment with that target', async () => {
    apiPost.mockResolvedValue(thread('new'))
    const { result } = await load([], { projectId: 'p1', projectDocId: 'pd1' })
    await act(async () => {
      await result.current.create({ content: 'hi' })
    })
    expect(apiPost).toHaveBeenCalledWith('/api/v2/projects/p1/comments', { target: { projectDocId: 'pd1' }, content: 'hi' }, 'tok')
    expect(result.current.comments.map((c) => c.id)).toEqual(['new'])
  })

  it('shows a change at once, and reverts it with a toast when the server refuses', async () => {
    let fail: (e: Error) => void = () => {}
    apiPatch.mockReturnValue(new Promise((_, r) => (fail = r)))
    const { result } = await load()
    let done: Promise<void> = Promise.resolve()
    act(() => {
      done = result.current.resolve('c1')
    })
    expect(result.current.comments[0].status).toBe('RESOLVED')
    expect(result.current.comments[0].resolvedByName).toBe('Ada')
    await act(async () => {
      fail(new Error('403'))
      await done
    })
    expect(result.current.comments[0].status).toBe('OPEN')
    expect(toastError).toHaveBeenCalledWith('Failed to resolve comment')
  })

  it('edits, reopens and deletes through the resource', async () => {
    apiPatch.mockResolvedValue(undefined)
    apiDelete.mockResolvedValue(undefined)
    const { result } = await load([thread('c1', { status: 'RESOLVED', resolvedAt: '2026-10-02T00:00:00Z' })])
    await act(async () => {
      await result.current.edit('c1', 'new text')
      await result.current.reopen('c1')
    })
    expect(apiPatch).toHaveBeenCalledWith('/api/v2/projects/p1/comments/c1', { content: 'new text' }, 'tok')
    expect(apiPatch).toHaveBeenCalledWith('/api/v2/projects/p1/comments/c1', { status: 'OPEN' }, 'tok')
    expect(result.current.comments[0]).toMatchObject({ content: 'new text', status: 'OPEN', resolvedAt: null })
    expect(result.current.comments[0].editedAt).toBeTruthy()

    await act(async () => {
      await result.current.remove('c1')
    })
    expect(apiDelete).toHaveBeenCalledWith('/api/v2/projects/p1/comments/c1', 'tok')
    expect(result.current.comments).toEqual([])
  })

  it('replies, edits and deletes a reply, and reverts a failed delete', async () => {
    apiPost.mockResolvedValue({ id: 'r1', authorId: 'u1', authorName: 'Ada', content: 'yes', createdAt: '2026-10-01T02:00:00Z' })
    apiPatch.mockResolvedValue(undefined)
    const { result } = await load()
    await act(async () => {
      await result.current.reply('c1', 'yes')
    })
    expect(result.current.comments[0].replies.map((r) => r.id)).toEqual(['r1'])
    await act(async () => {
      await result.current.editReply('c1', 'r1', 'yes!')
    })
    expect(apiPatch).toHaveBeenCalledWith('/api/v2/projects/p1/comments/c1/replies/r1', { content: 'yes!' }, 'tok')
    expect(result.current.comments[0].replies[0]).toMatchObject({ content: 'yes!' })

    apiDelete.mockRejectedValue(new Error('500'))
    await act(async () => {
      await result.current.removeReply('c1', 'r1')
    })
    expect(result.current.comments[0].replies).toHaveLength(1)
    expect(toastError).toHaveBeenCalledWith('Failed to delete reply')
  })

  it('tells the page when something was saved', async () => {
    apiPatch.mockResolvedValue(undefined)
    const onChanged = vi.fn()
    apiGet.mockResolvedValue([thread('c1')])
    const { result } = renderHook(() => useComments(target, { ...options, onChanged }))
    await waitFor(() => expect(result.current.loading).toBe(false))
    await act(async () => {
      await result.current.resolve('c1')
    })
    expect(onChanged).toHaveBeenCalledTimes(1)
  })
})
