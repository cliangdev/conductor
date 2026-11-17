'use client'

import { useCallback, useEffect, useRef, useState } from 'react'

interface Link {
  comment: string
  doc: string | null
}

/** Where a Work Item page picks the document tab: the documents on the page and how to show one. */
export interface DeepLinkDocuments<D extends { id: string } = { id: string }> {
  docs: D[]
  docsLoading: boolean
  comments: { id: string; documentId: string | null }[]
  commentsLoading: boolean
  selectedDocId: string | null
  activeTab: string
  selectDocument: (doc: D) => void
}

/**
 * `?comment=<id>` (a notification's deep link), with `?doc=<documentId>` on a Work Item. Read once on
 * arrival; `focusCommentId` is set only while the document the comment is on is the one on screen, so the
 * viewer focuses it as soon as its comments have loaded. `clear` (the viewer calls it when done) drops the
 * parameters from the address with `history.replaceState`, which Next keeps in step without remounting.
 *
 * Pass `documents` on a page with several document tabs: the link selects the right one first (`doc`, else
 * the document the comment is on). A link to a comment that is not on any document is dropped.
 */
export function useCommentDeepLink<D extends { id: string }>(documents?: DeepLinkDocuments<D>): {
  focusCommentId: string | null
  clear: () => void
} {
  const [link, setLink] = useState<Link | null>(null)
  useEffect(() => {
    const p = new URLSearchParams(window.location.search)
    const comment = p.get('comment')
    if (comment) setLink({ comment, doc: p.get('doc') })
  }, [])

  const clear = useCallback(() => {
    setLink(null)
    const url = new URL(window.location.href)
    if (url.searchParams.has('comment') || url.searchParams.has('doc')) {
      url.searchParams.delete('comment')
      url.searchParams.delete('doc')
      window.history.replaceState(window.history.state, '', url.pathname + url.search + url.hash)
    }
  }, [])

  const docId = link && documents ? (link.doc ?? documents.comments.find((c) => c.id === link.comment)?.documentId ?? null) : null
  const selectedFor = useRef<string | null>(null)
  useEffect(() => {
    if (!link || !documents) return
    if (docId) {
      const doc = documents.docs.find((d) => d.id === docId)
      if (doc && selectedFor.current !== link.comment) {
        selectedFor.current = link.comment
        documents.selectDocument(doc)
      } else if (!doc && !documents.docsLoading) {
        clear()
      }
    } else if (!documents.commentsLoading && !link.doc) {
      clear()
    }
    // `selectDocument` is a fresh function each render; the inputs below are what can change the outcome.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [link, docId, documents?.docs, documents?.docsLoading, documents?.commentsLoading, clear])

  if (!link) return { focusCommentId: null, clear }
  if (!documents) return { focusCommentId: link.comment, clear }
  const onScreen = !!docId && documents.selectedDocId === docId && documents.activeTab === docId
  return { focusCommentId: onScreen ? link.comment : null, clear }
}
