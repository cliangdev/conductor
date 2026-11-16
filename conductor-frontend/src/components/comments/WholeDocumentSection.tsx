'use client'

import { useState, type ReactNode } from 'react'
import { MessageSquarePlusIcon } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { CommentComposer } from './CommentComposer'

/** Why a comment sits under "Whole document" when it was written on some text. */
export function unplacedNote(quoted: string | null | undefined): ReactNode {
  const text = (quoted ?? '').trim()
  if (!text) return "Was on text that isn't in this version."
  const shown = text.length > 80 ? `${text.slice(0, 80)}…` : text
  return (
    <>
      Was on <s className="text-foreground-subtle">&ldquo;{shown}&rdquo;</s> &mdash; that text isn&rsquo;t in this
      version.
    </>
  )
}

/** The note for an older comment made on a source line, in a document that has no lines to point at. */
export function legacyLineNote(lineNumber: number | undefined): ReactNode {
  return lineNumber ? `Was on line ${lineNumber}, before this document was shown as a page.` : 'Was on a line of an earlier version.'
}

interface Props {
  /** The cards: comments about the document as a whole, and the ones whose text can't be found. */
  children?: ReactNode
  /** Adds a comment with no anchor. Omit to hide the "Comment on whole document" button. */
  onCreate?: (content: string) => Promise<void> | void
  className?: string
}

/** The top of the rail: comments about the document as a whole, and a way to add one. */
export function WholeDocumentSection({ children, onCreate, className }: Props) {
  const [composing, setComposing] = useState(false)
  const empty = !children || (Array.isArray(children) && children.length === 0)

  return (
    <section aria-label="Whole document" className={className}>
      <div className="mb-2 flex items-center justify-between gap-2">
        <h3 className="text-[11.5px] font-semibold uppercase tracking-[0.06em] text-muted-foreground">
          Whole document
        </h3>
        {onCreate && !composing && (
          <Button variant="ghost" size="sm" className="h-7 px-2 text-xs" onClick={() => setComposing(true)}>
            <MessageSquarePlusIcon className="mr-1.5 h-3.5 w-3.5" />
            Comment on whole document
          </Button>
        )}
      </div>
      <div className="space-y-2">
        {composing && onCreate && (
          <div className="rounded-lg border border-primary bg-card p-3 shadow-md">
            <CommentComposer
              autoFocus
              label="Comment on whole document"
              placeholder="Comment on the whole document…"
              onCancel={() => setComposing(false)}
              onSubmit={async (text) => {
                await onCreate(text)
                setComposing(false)
              }}
            />
          </div>
        )}
        {children}
        {empty && !composing && (
          <p className="text-xs text-muted-foreground">Nothing yet. Select text to comment on it.</p>
        )}
      </div>
    </section>
  )
}
