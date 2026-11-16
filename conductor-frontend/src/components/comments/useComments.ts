'use client'

import { useCallback, useEffect, useRef, useState } from 'react'
import { apiDelete, apiErrorMessage, apiGet, apiPatch, apiPost } from '@/lib/api'
import { toastError } from '@/components/ui/toast'
import { hasAgentMention } from './commentBody'
import type { Comment, CommentAgentRequest, CommentReply, TextQuoteAnchor } from './types'

/** What the comments belong to: a Work Item document, or a project doc. */
export type CommentTarget =
  | { projectId: string; workItemId: string; documentId: string; projectDocId?: undefined }
  | { projectId: string; projectDocId: string; workItemId?: undefined; documentId?: undefined }

export interface CommentsOptions {
  token: string
  /** Used for the byline of a comment shown before the server has answered. */
  currentUser?: { id: string; name: string }
  /** Called after a change has been saved, so a page showing counts elsewhere can refresh them. */
  onChanged?: () => void
  /**
   * Called when the document itself was changed under the viewer: an `@agent` request finished, or a
   * suggestion was accepted. The page should reload the document's content and hash.
   */
  onDocumentChanged?: () => void
}

/** Wait before looking for the `@agent` request a just-posted comment started (the server answers before dispatch). */
export const AGENT_FIRST_REFETCH_MS = 1500
/** How often the list is re-read while an `@agent` request is in flight. */
export const AGENT_POLL_MS = 5000
/** Polling gives up after this long. */
export const AGENT_POLL_MAX_MS = 20 * 60 * 1000

function requestKey(commentId: string, r: CommentAgentRequest): string {
  return `${commentId}:${r.agentId}:${r.createdAt}`
}

function inFlight(r: CommentAgentRequest): boolean {
  return r.status === 'PENDING' || r.status === 'RUNNING'
}

export interface NewComment {
  content: string
  /** A selection in an HTML or markdown document. Leave out both `anchor` and `lineNumber` to comment on the whole document. */
  anchor?: TextQuoteAnchor
  lineNumber?: number
  documentHash?: string
  /** Makes this a suggested edit: `content` is the optional note, `suggestion.text` the replacement for the quote. */
  suggestion?: { text: string }
}

export interface CommentsApi {
  comments: Comment[]
  loading: boolean
  /** Reloads the list from the server. */
  /** Re-reads the threads. Background reads (polling, window focus) pass `silent` so an outage doesn't toast every few seconds. */
  refresh: (opts?: { silent?: boolean; skipIfPending?: boolean }) => Promise<void>
  create: (input: NewComment) => Promise<Comment | null>
  reply: (commentId: string, content: string) => Promise<void>
  edit: (commentId: string, content: string) => Promise<void>
  editReply: (commentId: string, replyId: string, content: string) => Promise<void>
  resolve: (commentId: string) => Promise<void>
  reopen: (commentId: string) => Promise<void>
  remove: (commentId: string) => Promise<void>
  removeReply: (commentId: string, replyId: string) => Promise<void>
  /** Accepts or rejects a suggested edit. Resolves true when it was recorded. */
  decideSuggestion: (commentId: string, decision: 'ACCEPT' | 'REJECT') => Promise<boolean>
}

function basePath(projectId: string): string {
  return `/api/v2/projects/${projectId}/comments`
}

function listQuery(target: CommentTarget): string {
  if (target.projectDocId) return `projectDocId=${encodeURIComponent(target.projectDocId)}`
  return `workItemId=${encodeURIComponent(target.workItemId ?? '')}&documentId=${encodeURIComponent(target.documentId ?? '')}`
}

let tempCounter = 0

/**
 * Comments on one document, against the unified `/comments` resource: list, create, reply, edit,
 * resolve/reopen and delete. Changes show at once and are reverted, with a toast, if the server refuses.
 * Every call resolves (a failure is already reported), so callers can fire and forget.
 */
export function useComments(target: CommentTarget, options: CommentsOptions): CommentsApi {
  const { token, currentUser } = options
  const { projectId } = target
  const query = listQuery(target)
  const [comments, setCommentsState] = useState<Comment[]>([])
  const [loading, setLoading] = useState(true)
  // The latest list, readable inside async handlers so a revert restores the state from before the call.
  const latest = useRef<Comment[]>([])
  const onChanged = useRef(options.onChanged)
  const onDocumentChanged = useRef(options.onDocumentChanged)
  useEffect(() => {
    onChanged.current = options.onChanged
    onDocumentChanged.current = options.onDocumentChanged
  })
  // `@agent` requests: the ones seen in flight, the ones already reported as finished, and when this
  // viewer last posted a comment that mentions an agent (a request made after that counts as ours even
  // if it finished before the first look at it).
  const seenActive = useRef(new Set<string>())
  const reported = useRef(new Set<string>())
  const expectSince = useRef<number | null>(null)
  const pollStart = useRef<number | null>(null)

  const setComments = useCallback((next: Comment[] | ((prev: Comment[]) => Comment[])) => {
    const value = typeof next === 'function' ? next(latest.current) : next
    latest.current = value
    setCommentsState(value)
  }, [])

  // Which read is the latest, and which document the hook points at now. A read that finishes after a newer
  // one was started, or after the target changed, is out of date and must not overwrite the list.
  const requestSeq = useRef(0)
  const targetKey = useRef(`${projectId}?${query}`)
  useEffect(() => {
    targetKey.current = `${projectId}?${query}`
  }, [projectId, query])

  // Reads still waiting, per target. A poll or focus refetch is skipped while one is pending: if the server is
  // slower than the poll interval, starting another would make every answer out of date on arrival.
  const pendingFor = useRef<Map<string, number>>(new Map())

  const refresh = useCallback(async (opts?: { silent?: boolean; skipIfPending?: boolean }) => {
    const startedFor = `${projectId}?${query}`
    if (opts?.skipIfPending && (pendingFor.current.get(startedFor) ?? 0) > 0) return
    const seq = ++requestSeq.current
    const isStale = () => seq !== requestSeq.current || startedFor !== targetKey.current
    pendingFor.current.set(startedFor, (pendingFor.current.get(startedFor) ?? 0) + 1)
    try {
      const data = await apiGet<Comment[]>(`${basePath(projectId)}?${query}&status=all`, token)
      if (isStale()) return
      let finished = false
      for (const c of data) {
        for (const r of c.agentRequests ?? []) {
          const key = requestKey(c.id, r)
          if (inFlight(r)) {
            seenActive.current.add(key)
            continue
          }
          if (r.status !== 'SUCCEEDED' || reported.current.has(key)) continue
          const ours =
            seenActive.current.has(key) ||
            (expectSince.current !== null && Date.parse(r.createdAt) >= expectSince.current - 5000)
          if (ours) {
            reported.current.add(key)
            finished = true
          }
        }
      }
      setComments(data)
      if (finished) onDocumentChanged.current?.()
    } catch (err) {
      if (isStale()) return
      if (!opts?.silent) toastError(apiErrorMessage(err, 'Failed to load comments'))
    } finally {
      const left = (pendingFor.current.get(startedFor) ?? 1) - 1
      if (left > 0) pendingFor.current.set(startedFor, left)
      else pendingFor.current.delete(startedFor)
      if (!isStale()) setLoading(false)
    }
  }, [projectId, query, token, setComments])

  useEffect(() => {
    void refresh()
  }, [refresh])

  // Poll while an `@agent` request is pending or running; stop when none is, or after 20 minutes.
  const anyInFlight = comments.some((c) => (c.agentRequests ?? []).some(inFlight))
  useEffect(() => {
    if (!anyInFlight) {
      pollStart.current = null
      return
    }
    if (pollStart.current === null) pollStart.current = Date.now()
    const id = setInterval(() => {
      if (pollStart.current !== null && Date.now() - pollStart.current > AGENT_POLL_MAX_MS) {
        clearInterval(id)
        return
      }
      void refresh({ silent: true, skipIfPending: true })
    }, AGENT_POLL_MS)
    return () => clearInterval(id)
  }, [anyInFlight, refresh])

  // Coming back to the tab: pick up whatever changed meanwhile.
  useEffect(() => {
    function onFocus() {
      void refresh({ silent: true, skipIfPending: true })
    }
    window.addEventListener('focus', onFocus)
    return () => window.removeEventListener('focus', onFocus)
  }, [refresh])

  // Posting a comment or reply that mentions an agent starts a request the server reports only after the
  // response, so look again shortly.
  const agentTimer = useRef<ReturnType<typeof setTimeout> | null>(null)
  useEffect(() => () => {
    if (agentTimer.current) clearTimeout(agentTimer.current)
  }, [])
  const afterAgentMention = useCallback(
    (content: string) => {
      if (!hasAgentMention(content)) return
      expectSince.current = Date.now()
      if (agentTimer.current) clearTimeout(agentTimer.current)
      agentTimer.current = setTimeout(() => void refresh({ silent: true }), AGENT_FIRST_REFETCH_MS)
    },
    [refresh]
  )

  /** Applies `change` now; `request` saves it; on failure the list goes back to what it was and a toast says why. */
  const mutate = useCallback(
    async (
      change: ((list: Comment[]) => Comment[]) | null,
      request: () => Promise<void>,
      failure: string
    ): Promise<boolean> => {
      const before = latest.current
      if (change) setComments(change(before))
      try {
        await request()
        onChanged.current?.()
        return true
      } catch (err) {
        setComments(before)
        toastError(apiErrorMessage(err, failure))
        return false
      }
    },
    [setComments]
  )

  const patchComment = useCallback(
    (id: string, patch: Partial<Comment>) => (list: Comment[]) =>
      list.map((c) => (c.id === id ? { ...c, ...patch } : c)),
    []
  )

  const create = useCallback(
    async (input: NewComment): Promise<Comment | null> => {
      const tempId = `tmp:${++tempCounter}`
      const now = new Date().toISOString()
      const temp: Comment = {
        id: tempId,
        documentId: target.documentId ?? null,
        kind: input.anchor ? 'QUOTE' : input.lineNumber ? 'LINE' : 'DOCUMENT',
        status: 'OPEN',
        authorId: currentUser?.id ?? null,
        authorName: currentUser?.name ?? 'You',
        authorKind: 'USER',
        content: input.content,
        anchor: input.anchor ?? null,
        suggestion: input.suggestion ? { text: input.suggestion.text, status: 'PENDING' } : null,
        lineNumber: input.lineNumber,
        quotedText: input.anchor?.exact ?? null,
        createdAt: now,
        replies: [],
      }
      let created: Comment | null = null
      await mutate(
        (list) => [...list, temp],
        async () => {
          created = await apiPost<Comment>(
            basePath(projectId),
            {
              target: target.projectDocId
                ? { projectDocId: target.projectDocId }
                : { workItemId: target.workItemId, documentId: target.documentId },
              content: input.content,
              ...(input.anchor ? { anchor: input.anchor } : {}),
              ...(input.lineNumber ? { lineNumber: input.lineNumber } : {}),
              ...(input.documentHash ? { documentHash: input.documentHash } : {}),
              ...(input.suggestion ? { suggestion: input.suggestion } : {}),
            },
            token
          )
          const saved = created
          if (saved) setComments((list) => list.map((c) => (c.id === tempId ? saved : c)))
          else await refresh()
        },
        'Failed to add comment'
      )
      if (created) afterAgentMention(input.content)
      return created
    },
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [projectId, target.projectDocId, target.workItemId, target.documentId, token, currentUser?.id, currentUser?.name, mutate, setComments, afterAgentMention, refresh]
  )

  const reply = useCallback(
    async (commentId: string, content: string) => {
      const tempId = `tmp:${++tempCounter}`
      const temp: CommentReply = {
        id: tempId,
        authorId: currentUser?.id ?? null,
        authorName: currentUser?.name ?? 'You',
        authorKind: 'USER',
        content,
        createdAt: new Date().toISOString(),
      }
      const ok = await mutate(
        (list) => list.map((c) => (c.id === commentId ? { ...c, replies: [...c.replies, temp] } : c)),
        async () => {
          const saved = await apiPost<CommentReply>(
            `${basePath(projectId)}/${commentId}/replies`,
            { content },
            token
          )
          setComments((list) =>
            list.map((c) =>
              c.id === commentId
                ? { ...c, replies: c.replies.map((r) => (r.id === tempId ? saved : r)) }
                : c
            )
          )
        },
        'Failed to send reply'
      )
      if (ok) afterAgentMention(content)
    },
    [projectId, token, currentUser?.id, currentUser?.name, mutate, setComments, afterAgentMention]
  )

  const edit = useCallback(
    async (commentId: string, content: string) => {
      await mutate(
        patchComment(commentId, { content, editedAt: new Date().toISOString() }),
        async () => {
          const saved = await apiPatch<Comment>(`${basePath(projectId)}/${commentId}`, { content }, token)
          if (saved) setComments((list) => list.map((c) => (c.id === commentId ? { ...c, ...saved, replies: c.replies } : c)))
        },
        'Failed to edit comment'
      )
    },
    [projectId, token, mutate, patchComment, setComments]
  )

  const editReply = useCallback(
    async (commentId: string, replyId: string, content: string) => {
      await mutate(
        (list) =>
          list.map((c) =>
            c.id === commentId
              ? {
                  ...c,
                  replies: c.replies.map((r) =>
                    r.id === replyId ? { ...r, content, editedAt: new Date().toISOString() } : r
                  ),
                }
              : c
          ),
        async () => {
          await apiPatch(`${basePath(projectId)}/${commentId}/replies/${replyId}`, { content }, token)
        },
        'Failed to edit reply'
      )
    },
    [projectId, token, mutate]
  )

  const setStatus = useCallback(
    async (commentId: string, status: 'OPEN' | 'RESOLVED') => {
      const resolving = status === 'RESOLVED'
      await mutate(
        patchComment(commentId, {
          status,
          resolvedAt: resolving ? new Date().toISOString() : null,
          resolvedByName: resolving ? (currentUser?.name ?? null) : null,
        }),
        async () => {
          const saved = await apiPatch<Comment>(`${basePath(projectId)}/${commentId}`, { status }, token)
          if (saved) setComments((list) => list.map((c) => (c.id === commentId ? { ...c, ...saved, replies: c.replies } : c)))
        },
        resolving ? 'Failed to resolve comment' : 'Failed to reopen comment'
      )
    },
    [projectId, token, currentUser?.name, mutate, patchComment, setComments]
  )

  const resolve = useCallback((id: string) => setStatus(id, 'RESOLVED'), [setStatus])
  const reopen = useCallback((id: string) => setStatus(id, 'OPEN'), [setStatus])

  const remove = useCallback(
    async (commentId: string) => {
      await mutate(
        (list) => list.filter((c) => c.id !== commentId),
        () => apiDelete(`${basePath(projectId)}/${commentId}`, token),
        'Failed to delete comment'
      )
    },
    [projectId, token, mutate]
  )

  const removeReply = useCallback(
    async (commentId: string, replyId: string) => {
      await mutate(
        (list) =>
          list.map((c) =>
            c.id === commentId ? { ...c, replies: c.replies.filter((r) => r.id !== replyId) } : c
          ),
        () => apiDelete(`${basePath(projectId)}/${commentId}/replies/${replyId}`, token),
        'Failed to delete reply'
      )
    },
    [projectId, token, mutate]
  )

  const decideSuggestion = useCallback(
    async (commentId: string, decision: 'ACCEPT' | 'REJECT'): Promise<boolean> => {
      try {
        const saved = await apiPost<Comment>(
          `${basePath(projectId)}/${commentId}/suggestion-decision`,
          { decision },
          token
        )
        if (saved) setComments((list) => list.map((c) => (c.id === commentId ? { ...c, ...saved, replies: saved.replies ?? c.replies } : c)))
        else await refresh()
        onChanged.current?.()
        if (decision === 'ACCEPT') {
          // The document was edited: the page needs the new text and hash, and other comments were re-anchored.
          onDocumentChanged.current?.()
          await refresh()
        }
        return true
      } catch (err) {
        const status = (err as { status?: number } | null)?.status
        if (status === 403) {
          toastError(
            decision === 'ACCEPT'
              ? 'Only editors can accept suggestions'
              : apiErrorMessage(err, 'You cannot decide this suggestion')
          )
        } else {
          toastError(apiErrorMessage(err, 'Could not record that decision'))
          if (status === 409) await refresh()
        }
        return false
      }
    },
    [projectId, token, setComments, refresh]
  )

  return { comments, loading, refresh, create, reply, edit, editReply, resolve, reopen, remove, removeReply, decideSuggestion }
}
