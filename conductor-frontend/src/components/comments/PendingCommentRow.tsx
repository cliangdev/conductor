'use client'

import { PencilIcon, TrashIcon } from 'lucide-react'
import { Badge } from '@/components/ui/badge'
import { NewCommentForm } from './NewCommentForm'
import type { PendingCommentDraft } from './types'

/** A single held-locally, not-yet-posted comment — editable/deletable until the review is submitted. */
export function PendingCommentRow({
  draft,
  editing,
  onStartEdit,
  onCancelEdit,
  onSave,
  onRemove,
}: {
  draft: PendingCommentDraft
  editing: boolean
  onStartEdit: () => void
  onCancelEdit: () => void
  onSave: (content: string) => void
  onRemove: () => void
}) {
  if (editing) {
    return (
      <div className="border border-border rounded p-2 bg-card">
        <NewCommentForm
          onSubmit={async (text) => onSave(text)}
          onCancel={onCancelEdit}
          size="compact"
          submitLabel="Save"
          initialValue={draft.content}
        />
      </div>
    )
  }

  return (
    <div className="border border-border rounded p-2 bg-card text-xs">
      <div className="flex items-center justify-between gap-2 mb-1">
        <Badge variant="outline" className="text-[10px]">
          Pending
        </Badge>
        <div className="flex items-center gap-1 shrink-0">
          <button
            onClick={onStartEdit}
            aria-label="Edit pending comment"
            className="p-0.5 text-muted-foreground hover:text-foreground transition-colors"
          >
            <PencilIcon className="h-3 w-3" />
          </button>
          <button
            onClick={onRemove}
            aria-label="Remove pending comment"
            className="p-0.5 text-destructive hover:text-destructive/80 transition-colors"
          >
            <TrashIcon className="h-3 w-3" />
          </button>
        </div>
      </div>
      <p className="text-foreground whitespace-pre-wrap leading-relaxed">{draft.content}</p>
    </div>
  )
}
