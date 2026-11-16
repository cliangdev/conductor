'use client'

import { createContext, useContext, useMemo, type ReactNode } from 'react'
import { apiGet, apiPost } from '@/lib/api'
import type { CommentTarget } from './useComments'
import type { TextQuoteAnchor } from './types'

export interface Mentionable {
  kind: 'USER' | 'AGENT'
  id: string
  name: string
  avatarUrl?: string | null
  avatar?: { emoji?: string; color?: string } | null
}

export interface SuggestionCheck {
  suggestable: boolean
  reason?: string | null
}

export interface CommentsFeatures {
  /** Members, then addressable agents, whose name contains `q` (max 20). */
  fetchMentionables: (q: string) => Promise<Mentionable[]>
  /** Whether a selection can carry a suggested edit. */
  checkSuggestion: (anchor: TextQuoteAnchor) => Promise<SuggestionCheck>
  /** Accept is offered only to people who can edit; when unknown it is offered and a 403 is explained. */
  canAcceptSuggestions: boolean
  decideSuggestion: (commentId: string, decision: 'ACCEPT' | 'REJECT') => Promise<boolean>
}

const Ctx = createContext<CommentsFeatures | null>(null)

/** What the composer and card need beyond their props: the mention picker, suggestion check and decision. */
export function useCommentsFeatures(): CommentsFeatures | null {
  return useContext(Ctx)
}

interface ProviderProps {
  target: CommentTarget
  token: string
  canAcceptSuggestions?: boolean
  decideSuggestion: (commentId: string, decision: 'ACCEPT' | 'REJECT') => Promise<boolean>
  children: ReactNode
}

/** Wraps a document viewer so every card and composer inside it (rail, sheets, popover) gets the same features. */
export function CommentsFeaturesProvider({
  target,
  token,
  canAcceptSuggestions = true,
  decideSuggestion,
  children,
}: ProviderProps) {
  const { projectId, projectDocId, workItemId, documentId } = target
  const value = useMemo<CommentsFeatures>(
    () => ({
      fetchMentionables: (q) =>
        apiGet<Mentionable[]>(
          `/api/v2/projects/${projectId}/comments/mentionables?q=${encodeURIComponent(q)}`,
          token
        ),
      checkSuggestion: (anchor) =>
        apiPost<SuggestionCheck>(
          `/api/v2/projects/${projectId}/comments/suggestion-check`,
          {
            target: projectDocId ? { projectDocId } : { workItemId, documentId },
            anchor,
          },
          token
        ),
      canAcceptSuggestions,
      decideSuggestion,
    }),
    [projectId, projectDocId, workItemId, documentId, token, canAcceptSuggestions, decideSuggestion]
  )
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>
}
