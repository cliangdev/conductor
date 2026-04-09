'use client'

import { useRef, useState, useCallback, useEffect } from 'react'
import { apiPost } from '@/lib/api'
import { MarkdownRenderer } from '@/components/markdown/MarkdownRenderer'
import { CommentThread } from './CommentThread'
import { NewCommentForm } from './NewCommentForm'
import type { Comment } from './types'

interface Props {
  content: string
  documentId: string
  issueId: string
  projectId: string
  comments: Comment[]
  onCommentAdded: () => void
  token: string
  currentUserId: string
}

interface SelectionState {
  start: number
  length: number
  x: number
  y: number
}

interface LineThreadState {
  lineNumber: number
  showForm: boolean
}

function buildHighlightedContent(
  content: string,
  selectionComments: Comment[]
): string {
  if (selectionComments.length === 0) return content

  const sorted = [...selectionComments]
    .filter((c) => c.selectionStart != null && c.selectionLength != null && !c.resolvedAt)
    .sort((a, b) => (a.selectionStart ?? 0) - (b.selectionStart ?? 0))

  if (sorted.length === 0) return content

  let result = ''
  let cursor = 0

  for (const c of sorted) {
    const start = c.selectionStart ?? 0
    const end = start + (c.selectionLength ?? 0)
    if (start < cursor) continue
    result += escapeHtmlEntities(content.slice(cursor, start))
    result += `<mark class="bg-yellow-200 rounded" title="${escapeAttr(c.authorName)}: ${escapeAttr(c.content)}">`
    result += escapeHtmlEntities(content.slice(start, end))
    result += '</mark>'
    cursor = end
  }
  result += escapeHtmlEntities(content.slice(cursor))
  return result
}

function escapeHtmlEntities(str: string): string {
  return str
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
}

function escapeAttr(str: string): string {
  return str.replace(/"/g, '&quot;').replace(/'/g, '&#39;')
}

function getSelectionOffsets(
  container: HTMLElement,
  selection: Selection
): { start: number; length: number } | null {
  if (!selection.rangeCount) return null
  const range = selection.getRangeAt(0)
  if (range.collapsed) return null

  const preRange = document.createRange()
  preRange.selectNodeContents(container)
  preRange.setEnd(range.startContainer, range.startOffset)
  const start = preRange.toString().length
  const selectedText = range.toString()
  if (!selectedText.trim()) return null

  return { start, length: selectedText.length }
}

export function CommentableDocument({
  content,
  documentId,
  issueId,
  projectId,
  comments,
  onCommentAdded,
  token,
  currentUserId,
}: Props) {
  const contentRef = useRef<HTMLDivElement>(null)
  const [openLineThread, setOpenLineThread] = useState<LineThreadState | null>(null)
  const [selectionState, setSelectionState] = useState<SelectionState | null>(null)
  const [showSelectionForm, setShowSelectionForm] = useState(false)

  const lines = content.split('\n')

  const lineComments = comments.filter((c) => c.lineNumber != null)
  const selectionComments = comments.filter(
    (c) => c.selectionStart != null && c.selectionLength != null
  )

  function commentsForLine(lineNum: number): Comment[] {
    return lineComments.filter((c) => c.lineNumber === lineNum)
  }

  const handleMouseUp = useCallback(() => {
    const selection = window.getSelection()
    if (!selection || selection.isCollapsed || !contentRef.current) {
      return
    }
    if (!contentRef.current.contains(selection.anchorNode)) return

    const offsets = getSelectionOffsets(contentRef.current, selection)
    if (!offsets) return

    const range = selection.getRangeAt(0)
    const rect = range.getBoundingClientRect()
    const containerRect = contentRef.current.getBoundingClientRect()

    setSelectionState({
      start: offsets.start,
      length: offsets.length,
      x: rect.left - containerRect.left + rect.width / 2,
      y: rect.top - containerRect.top - 8,
    })
    setShowSelectionForm(false)
  }, [])

  useEffect(() => {
    function handleClick(e: MouseEvent) {
      if (!selectionState) return
      const target = e.target as HTMLElement
      if (!target.closest('[data-comment-btn]') && !target.closest('[data-comment-form]')) {
        setSelectionState(null)
        setShowSelectionForm(false)
      }
    }
    document.addEventListener('mousedown', handleClick)
    return () => document.removeEventListener('mousedown', handleClick)
  }, [selectionState])

  async function handleAddLineComment(lineNumber: number, text: string) {
    await apiPost(
      `/api/v1/projects/${projectId}/issues/${issueId}/comments`,
      { documentId, content: text, lineNumber },
      token
    )
    onCommentAdded()
    setOpenLineThread(null)
  }

  async function handleAddSelectionComment(text: string) {
    if (!selectionState) return
    await apiPost(
      `/api/v1/projects/${projectId}/issues/${issueId}/comments`,
      {
        documentId,
        content: text,
        selectionStart: selectionState.start,
        selectionLength: selectionState.length,
      },
      token
    )
    onCommentAdded()
    setSelectionState(null)
    setShowSelectionForm(false)
    window.getSelection()?.removeAllRanges()
  }

  const highlightedContent = buildHighlightedContent(content, selectionComments)
  const useHighlighted = selectionComments.some((c) => !c.resolvedAt)

  return (
    <div className="relative flex gap-0">
      {/* Gutter */}
      <div className="w-8 shrink-0 select-none" aria-label="comment gutter">
        {lines.map((_, idx) => {
          const lineNum = idx + 1
          const lineHasComments = commentsForLine(lineNum).length > 0
          const isOpen =
            openLineThread?.lineNumber === lineNum

          return (
            <div
              key={lineNum}
              className="relative flex items-center justify-center"
              style={{ height: '1.625rem' }}
            >
              {lineHasComments ? (
                <button
                  onClick={() =>
                    setOpenLineThread(
                      isOpen ? null : { lineNumber: lineNum, showForm: false }
                    )
                  }
                  className="text-sm text-blue-500 hover:text-blue-700 leading-none"
                  title={`${commentsForLine(lineNum).length} comment(s) on line ${lineNum}`}
                >
                  💬
                </button>
              ) : (
                <button
                  onClick={() =>
                    setOpenLineThread({ lineNumber: lineNum, showForm: true })
                  }
                  className="opacity-0 hover:opacity-100 text-xs text-gray-400 hover:text-gray-600 leading-none group-hover:opacity-100"
                  title={`Add comment on line ${lineNum}`}
                >
                  +
                </button>
              )}
            </div>
          )
        })}
      </div>

      {/* Document content */}
      <div className="flex-1 relative">
        <div
          ref={contentRef}
          className="relative"
          onMouseUp={handleMouseUp}
        >
          {useHighlighted ? (
            <div
              className="prose prose-sm max-w-none"
              dangerouslySetInnerHTML={{ __html: highlightedContent }}
            />
          ) : (
            <MarkdownRenderer content={content} />
          )}
        </div>

        {/* Floating "Add comment" button for selection */}
        {selectionState && !showSelectionForm && (
          <div
            data-comment-btn
            className="absolute z-20 transform -translate-x-1/2 -translate-y-full"
            style={{ left: selectionState.x, top: selectionState.y }}
          >
            <button
              onMouseDown={(e) => {
                e.preventDefault()
                setShowSelectionForm(true)
              }}
              className="bg-blue-600 text-white text-xs px-2 py-1 rounded shadow-md hover:bg-blue-700 whitespace-nowrap"
            >
              + Comment
            </button>
          </div>
        )}

        {/* Selection comment form */}
        {selectionState && showSelectionForm && (
          <div
            data-comment-form
            className="absolute z-20 transform -translate-x-1/2"
            style={{ left: selectionState.x, top: selectionState.y }}
          >
            <div className="bg-white border border-gray-200 rounded shadow-lg p-3 w-72">
              <p className="text-xs text-gray-500 mb-2">Comment on selection</p>
              <NewCommentForm
                onSubmit={handleAddSelectionComment}
                onCancel={() => {
                  setSelectionState(null)
                  setShowSelectionForm(false)
                }}
              />
            </div>
          </div>
        )}
      </div>

      {/* Inline thread panel */}
      {openLineThread && (
        <div className="absolute left-10 z-30" style={{ top: `${(openLineThread.lineNumber - 1) * 1.625}rem` }}>
          <div className="flex flex-col gap-2">
            {commentsForLine(openLineThread.lineNumber).map((c) => (
              <CommentThread
                key={c.id}
                comment={c}
                projectId={projectId}
                issueId={issueId}
                currentUserId={currentUserId}
                token={token}
                onUpdated={onCommentAdded}
                onClose={() => setOpenLineThread(null)}
              />
            ))}
            {(openLineThread.showForm || commentsForLine(openLineThread.lineNumber).length === 0) && (
              <div className="bg-white border border-gray-200 rounded shadow-sm p-3 w-72">
                <p className="text-xs text-gray-500 mb-2">
                  New comment on line {openLineThread.lineNumber}
                </p>
                <NewCommentForm
                  onSubmit={(text) => handleAddLineComment(openLineThread.lineNumber, text)}
                  onCancel={() => setOpenLineThread(null)}
                />
              </div>
            )}
          </div>
        </div>
      )}

      {/* Selection-based comments sidebar list (fallback visibility) */}
      {selectionComments.length > 0 && (
        <div className="w-64 ml-4 shrink-0 space-y-2">
          <p className="text-xs font-semibold text-gray-500 uppercase tracking-wide mb-1">
            Selection Comments
          </p>
          {selectionComments.map((c) => (
            <CommentThread
              key={c.id}
              comment={c}
              projectId={projectId}
              issueId={issueId}
              currentUserId={currentUserId}
              token={token}
              onUpdated={onCommentAdded}
            />
          ))}
        </div>
      )}
    </div>
  )
}
