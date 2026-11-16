'use client'

import { useCallback, useEffect, useId, useRef, useState } from 'react'
import { PencilLineIcon } from 'lucide-react'
import { cn } from '@/lib/utils'
import { Button } from '@/components/ui/button'
import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar'
import { Badge } from '@/components/ui/badge'
import { AgentAvatar } from '@/components/agents/AgentAvatar'
import { displayToTokens, makeToken, tokensToDisplay, type MentionMap } from './commentBody'
import { useCommentsFeatures, type Mentionable } from './CommentsFeatures'
import type { TextQuoteAnchor } from './types'

/** Sent with the text when the composer is writing a suggested edit. */
export interface SubmitOptions {
  suggestion?: { text: string }
}

/** The note a suggestion gets when its author leaves "Why?" empty (the server requires some content). */
export const DEFAULT_SUGGESTION_NOTE = 'Suggested edit'

/** `@` at the start or after a space, then up to two short words: what the picker filters by. */
const MENTION_QUERY = /(^|\s)@([^\s@]{0,30}(?: [^\s@]{0,30})?)$/
const MENTION_DEBOUNCE_MS = 150

function initialsOf(name: string): string {
  return (
    name
      .split(/\s+/)
      .filter(Boolean)
      .slice(0, 2)
      .map((w) => w[0])
      .join('')
      .toUpperCase() || '?'
  )
}

function MentionOptionAvatar({ m }: { m: Mentionable }) {
  if (m.kind === 'AGENT') {
    return <AgentAvatar emoji={m.avatar?.emoji || '🤖'} color={m.avatar?.color ?? ''} size="sm" />
  }
  return (
    <Avatar className="h-5 w-5">
      {m.avatarUrl && <AvatarImage src={m.avatarUrl} alt="" />}
      <AvatarFallback className="text-[9px] text-foreground-subtle">{initialsOf(m.name)}</AvatarFallback>
    </Avatar>
  )
}

interface Props {
  /** Sends the text (mentions as tokens). May reject: the text is kept and an error shown so nothing typed is lost. */
  onSubmit: (content: string, options?: SubmitOptions) => Promise<void> | void
  /** Escape, and the Cancel button. With no handler there is no Cancel button. */
  onCancel?: () => void
  placeholder?: string
  submitLabel?: string
  /** The stored text; mention tokens in it are shown as `@Name`. */
  initialValue?: string
  /** Focus the textarea when it appears (a new comment, an edit). Leave off for a composer that is just sitting in a card. */
  autoFocus?: boolean
  /** Starts one line high and grows with the text; its buttons show once there is something to send. */
  compact?: boolean
  label?: string
  /**
   * Offers a "Suggest edit" toggle for this selection (a new comment on quoted text only). `onSubmit` then
   * gets the note as `content` and the replacement in `options.suggestion`.
   */
  suggestFor?: TextQuoteAnchor
}

/**
 * The one place comments, replies and edits are typed. Cmd/Ctrl+Enter sends, Escape cancels.
 * Plain text: line breaks are kept, nothing is rendered as markup. Typing `@` offers people and agents to
 * mention; the box shows `@Name` and the stored token goes out on send.
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
  suggestFor,
}: Props) {
  const features = useCommentsFeatures()
  // The box shows `@Name`; the tokens it stands for are kept on the side and put back on send.
  const [initial] = useState(() => tokensToDisplay(initialValue))
  const [content, setContent] = useState(initial.text)
  const mentionMap = useRef<MentionMap>(initial.map)
  const listId = useId()
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [focused, setFocused] = useState(false)
  const ref = useRef<HTMLTextAreaElement>(null)

  // ── @mention picker ────────────────────────────────────────────────────────
  const [picker, setPicker] = useState<{ query: string; start: number } | null>(null)
  const [options, setOptions] = useState<Mentionable[]>([])
  const [activeIdx, setActiveIdx] = useState(0)
  const pickerOpen = !!picker && options.length > 0

  const fetchMentionables = features?.fetchMentionables
  const pickerQuery = picker?.query
  const pickerOn = picker !== null
  useEffect(() => {
    if (!pickerOn || !fetchMentionables) {
      setOptions([])
      return
    }
    let cancelled = false
    const t = setTimeout(() => {
      fetchMentionables(pickerQuery ?? '')
        .then((list) => {
          if (cancelled) return
          setOptions(list)
          setActiveIdx(0)
        })
        .catch(() => {
          if (!cancelled) setOptions([])
        })
    }, MENTION_DEBOUNCE_MS)
    return () => {
      cancelled = true
      clearTimeout(t)
    }
  }, [pickerOn, pickerQuery, fetchMentionables])

  const hasFeatures = !!features
  const updatePicker = useCallback(
    (text: string, caret: number) => {
      if (!hasFeatures) return
      const m = MENTION_QUERY.exec(text.slice(0, caret))
      if (!m) {
        setPicker(null)
        return
      }
      const query = m[2]
      setPicker({ query, start: caret - query.length - 1 })
    },
    [hasFeatures]
  )

  function choose(m: Mentionable) {
    const el = ref.current
    if (!picker || !el) return
    const token = makeToken(m.name, m.kind === 'AGENT' ? 'agent' : 'user', m.id)
    mentionMap.current = { ...mentionMap.current, [`@${token.name}`]: token }
    const caret = el.selectionStart ?? content.length
    const next = `${content.slice(0, picker.start)}@${token.name} ${content.slice(caret)}`
    setContent(next)
    setPicker(null)
    setOptions([])
    const pos = picker.start + token.name.length + 2
    requestAnimationFrame(() => {
      el.focus()
      el.setSelectionRange(pos, pos)
    })
  }

  // ── Suggest edit ───────────────────────────────────────────────────────────
  const [suggestOn, setSuggestOn] = useState(false)
  const [suggestBlocked, setSuggestBlocked] = useState<string | null>(null)
  const [suggestChecking, setSuggestChecking] = useState(false)
  const [replacement, setReplacement] = useState(suggestFor?.exact ?? '')
  const canSuggest = !!suggestFor && !!features

  async function toggleSuggest() {
    if (suggestOn) {
      setSuggestOn(false)
      return
    }
    if (!suggestFor || !features || suggestChecking || suggestBlocked) return
    setSuggestChecking(true)
    try {
      const res = await features.checkSuggestion(suggestFor)
      if (res.suggestable) {
        setReplacement(suggestFor.exact)
        setSuggestOn(true)
      } else {
        setSuggestBlocked(res.reason || "This text can't be suggested on. Comment instead.")
      }
    } catch {
      setSuggestBlocked("Couldn't check this text. Comment instead.")
    } finally {
      setSuggestChecking(false)
    }
  }

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
  const showActions = !compact || focused || trimmed.length > 0 || suggestOn
  // A suggestion may delete the text (empty replacement) but must change it.
  const canSend = suggestOn ? replacement !== (suggestFor?.exact ?? '') : !!trimmed

  async function submit() {
    if (!canSend || submitting) return
    setSubmitting(true)
    setError(null)
    try {
      const text = displayToTokens(trimmed, mentionMap.current)
      if (suggestOn) {
        await onSubmit(text || DEFAULT_SUGGESTION_NOTE, { suggestion: { text: replacement } })
      } else {
        await onSubmit(text)
      }
      setContent('')
      mentionMap.current = {}
    } catch {
      setError('Could not send. Please try again.')
    } finally {
      setSubmitting(false)
    }
  }

  function cancel() {
    const reset = tokensToDisplay(initialValue)
    setContent(reset.text)
    mentionMap.current = reset.map
    setPicker(null)
    setSuggestOn(false)
    setError(null)
    ref.current?.blur()
    onCancel?.()
  }

  return (
    <div className="flex flex-col gap-2" onClick={(e) => e.stopPropagation()}>
      {suggestOn && (
        <label className="flex flex-col gap-1 text-xs text-muted-foreground">
          Suggested text
          <textarea
            aria-label="Suggested text"
            value={replacement}
            rows={3}
            disabled={submitting}
            onChange={(e) => setReplacement(e.target.value)}
            className="min-h-[72px] w-full resize-none rounded-md border border-input bg-background px-2 py-1.5 text-sm text-foreground focus:outline-none focus-visible:ring-2 focus-visible:ring-ring"
          />
        </label>
      )}
      <textarea
        ref={ref}
        aria-label={suggestOn ? 'Why? (optional)' : label}
        value={content}
        rows={suggestOn ? 2 : compact ? 1 : 3}
        placeholder={suggestOn ? 'Why? (optional)' : placeholder}
        disabled={submitting}
        role={pickerOpen ? 'combobox' : undefined}
        aria-autocomplete={pickerOpen ? 'list' : undefined}
        aria-expanded={pickerOpen ? true : undefined}
        aria-controls={pickerOpen ? listId : undefined}
        aria-activedescendant={pickerOpen ? `${listId}-${activeIdx}` : undefined}
        onChange={(e) => {
          setContent(e.target.value)
          if (!suggestOn) updatePicker(e.target.value, e.target.selectionStart ?? e.target.value.length)
        }}
        onFocus={() => setFocused(true)}
        onBlur={() => setFocused(false)}
        onKeyDown={(e) => {
          if (pickerOpen) {
            if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
              e.preventDefault()
              setActiveIdx((i) => (i + (e.key === 'ArrowDown' ? 1 : options.length - 1)) % options.length)
              return
            }
            if ((e.key === 'Enter' && !e.metaKey && !e.ctrlKey) || e.key === 'Tab') {
              e.preventDefault()
              choose(options[activeIdx])
              return
            }
            if (e.key === 'Escape') {
              e.preventDefault()
              e.stopPropagation()
              setPicker(null)
              return
            }
          }
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
          !compact && !suggestOn && 'min-h-[72px]'
        )}
      />
      {pickerOpen && (
        <ul
          id={listId}
          role="listbox"
          aria-label="Mention someone"
          className="max-h-48 overflow-y-auto rounded-md border border-border bg-card p-1 text-sm shadow-md"
        >
          {options.map((m, i) => (
            <li
              key={`${m.kind}:${m.id}`}
              id={`${listId}-${i}`}
              role="option"
              aria-selected={i === activeIdx}
              // Mouse-down would blur the textarea before the click lands.
              onMouseDown={(e) => e.preventDefault()}
              onClick={() => choose(m)}
              onMouseEnter={() => setActiveIdx(i)}
              className={cn(
                'flex cursor-pointer items-center gap-2 rounded px-2 py-1.5 text-foreground',
                i === activeIdx && 'bg-accent-soft'
              )}
            >
              <MentionOptionAvatar m={m} />
              <span className="truncate">{m.name}</span>
              {m.kind === 'AGENT' && (
                <Badge variant="outline" className="ml-auto px-1.5 py-0 text-[10px] uppercase tracking-wide">
                  Agent
                </Badge>
              )}
            </li>
          ))}
        </ul>
      )}
      {canSuggest && (
        <div className="flex flex-col gap-0.5">
          <button
            type="button"
            aria-pressed={suggestOn}
            disabled={submitting || suggestChecking || !!suggestBlocked}
            title={suggestBlocked ?? undefined}
            onMouseDown={(e) => e.preventDefault()}
            onClick={() => void toggleSuggest()}
            className={cn(
              'inline-flex w-fit items-center gap-1.5 rounded-md border px-2 py-1 text-xs focus:outline-none focus-visible:ring-2 focus-visible:ring-ring',
              suggestOn ? 'border-primary bg-accent-soft text-foreground' : 'border-border text-muted-foreground hover:bg-muted',
              'disabled:cursor-not-allowed disabled:opacity-60'
            )}
          >
            <PencilLineIcon className="h-3.5 w-3.5" aria-hidden />
            Suggest edit
          </button>
          {suggestBlocked && <p className="text-xs text-muted-foreground">{suggestBlocked}</p>}
        </div>
      )}
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
              disabled={submitting || !canSend}
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
