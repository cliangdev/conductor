'use client'

// The rows of a slide-text diff: "Slide 3 · Text 1" then the old words, an arrow, the new words, with
// the words that were removed or added marked. Shared by the revisions panel, the save confirmation and
// the Post page's "Changed since last review" card.

import { ArrowRightIcon } from 'lucide-react'
import { cn } from '@/lib/utils'
import { changeLabel, type TextChange } from './slideText'
import { wordDiff } from './wordDiff'

function Words({ side, oldText, newText }: { side: 'old' | 'new'; oldText: string; newText: string }) {
  const pieces = wordDiff(oldText, newText).filter((p) => (side === 'old' ? p.kind !== 'add' : p.kind !== 'del'))
  return (
    <>
      {pieces.map((p, i) =>
        p.kind === 'same' ? (
          <span key={i}>{p.text}</span>
        ) : p.kind === 'del' ? (
          <del key={i} className="rounded-sm bg-status-failed/10 text-status-failed">
            {p.text}
          </del>
        ) : (
          <ins key={i} className="rounded-sm bg-status-approved/10 text-status-approved no-underline">
            {p.text}
          </ins>
        ),
      )}
    </>
  )
}

export function TextChangeRows({ changes, className }: { changes: readonly TextChange[]; className?: string }) {
  if (changes.length === 0) return <p className="text-xs text-muted-foreground">No text changed.</p>
  return (
    <ul className={cn('space-y-2', className)} data-testid="text-change-rows">
      {changes.map((c) => {
        const o = c.old ?? ''
        const nw = c.new ?? ''
        return (
          <li key={`${c.slide}-${c.path}`} className="text-sm" data-testid="text-change-row">
            <p className="text-xs font-medium text-muted-foreground">{changeLabel(c)}</p>
            <p className="flex flex-wrap items-baseline gap-x-2 gap-y-0.5 whitespace-pre-wrap break-words text-foreground">
              <span data-testid="text-change-old">
                {c.old == null ? <span className="text-muted-foreground">(none)</span> : <Words side="old" oldText={o} newText={nw} />}
              </span>
              <ArrowRightIcon className="h-3 w-3 shrink-0 translate-y-0.5 text-muted-foreground" aria-label="changed to" role="img" />
              <span data-testid="text-change-new">
                {c.new == null ? <span className="text-muted-foreground">(removed)</span> : <Words side="new" oldText={o} newText={nw} />}
              </span>
            </p>
          </li>
        )
      })}
    </ul>
  )
}
