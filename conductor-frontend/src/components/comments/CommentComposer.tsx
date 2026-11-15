'use client'

import { useEffect, useRef, useState } from 'react'
import { cn } from '@/lib/utils'
import { Button } from '@/components/ui/button'

interface Props {
  /** Sends the text. May reject: the text is kept and an error shown so nothing typed is lost. */
  onSubmit: (content: string) => Promise<void> | void
  /** Escape, and the Cancel button. With no handler there is no Cancel button. */
  onCancel?: () => void
  placeholder?: string
  submitLabel?: string
  initialValue?: string
  /** Focus the textarea when it appears (a new comment, an edit). Leave off for a composer that is just sitting in a card. */
  autoFocus?: boolean
  /** Starts one line high and grows with the text; its buttons show once there is something to send. */
  compact?: boolean
  label?: string
}

/**
 * The one place comments, replies and edits are typed. Cmd/Ctrl+Enter sends, Escape cancels.
 * Plain text: line breaks are kept, nothing is rendered as markup.
 */
export function CommentComposer({
  onSubmit,
  onCancel,
  placeholder = 'Add a comment…',
  submitLabel = 'Comment',
  initialValue = '',
  autoFocus = false,
  compact = false,
  label = 'Comment text',
}: Props) {
  const [content, setContent] = useState(initialValue)
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [focused, setFocused] = useState(false)
  const ref = useRef<HTMLTextAreaElement>(null)

  useEffect(() => {
    const el = ref.current
    if (!autoFocus || !el) return
    el.focus()
    el.setSelectionRange(el.value.length, el.value.length)
  }, [autoFocus])

  // Grow with the text.
  useEffect(() => {
    const el = ref.current
    if (!el || !compact) return
    el.style.height = 'auto'
    el.style.height = `${el.scrollHeight}px`
  }, [content, compact])

  const trimmed = content.trim()
  const showActions = !compact || focused || trimmed.length > 0

  async function submit() {
    if (!trimmed || submitting) return
    setSubmitting(true)
    setError(null)
    try {
      await onSubmit(trimmed)
      setContent('')
    } catch {
      setError('Could not send. Please try again.')
    } finally {
      setSubmitting(false)
    }
  }

  function cancel() {
    setContent(initialValue)
    setError(null)
    ref.current?.blur()
    onCancel?.()
  }

  return (
    <div className="flex flex-col gap-2" onClick={(e) => e.stopPropagation()}>
      <textarea
        ref={ref}
        aria-label={label}
        value={content}
        rows={compact ? 1 : 3}
        placeholder={placeholder}
        disabled={submitting}
        onChange={(e) => setContent(e.target.value)}
        onFocus={() => setFocused(true)}
        onBlur={() => setFocused(false)}
        onKeyDown={(e) => {
          if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) {
            e.preventDefault()
            void submit()
          } else if (e.key === 'Escape') {
            e.preventDefault()
            e.stopPropagation()
            cancel()
          }
        }}
        className={cn(
          'w-full resize-none rounded-md border border-input bg-background px-2 py-1.5 text-sm text-foreground',
          'placeholder:text-muted-foreground focus:outline-none focus-visible:ring-2 focus-visible:ring-ring',
          !compact && 'min-h-[72px]'
        )}
      />
      {error && (
        <p role="alert" className="text-xs text-destructive">
          {error}
        </p>
      )}
      {showActions && (
        <div className="flex items-center justify-between gap-2">
          <span className="text-xs text-muted-foreground">
            <kbd className="font-mono">⌘</kbd> <kbd className="font-mono">Enter</kbd> to send
          </span>
          <span className="flex items-center gap-1.5">
            {onCancel && (
              // Mouse-down would blur the textarea and unmount the buttons before the click lands.
              <Button
                type="button"
                variant="ghost"
                size="sm"
                disabled={submitting}
                onMouseDown={(e) => e.preventDefault()}
                onClick={cancel}
              >
                Cancel
              </Button>
            )}
            <Button
              type="button"
              size="sm"
              disabled={submitting || !trimmed}
              onMouseDown={(e) => e.preventDefault()}
              onClick={() => void submit()}
            >
              {submitting ? 'Sending…' : submitLabel}
            </Button>
          </span>
        </div>
      )}
    </div>
  )
}
