'use client'

// Freeform tags on a Work Item.
//
// The grouping mechanism CLAUDE.md names as the intended one — "labels + saved views", rather than a
// container above projects. Deliberately not the single `tag` column agents and workflows got in V107:
// one tag per item cannot say "autumn campaign" and "paid" at once, which is usually the point.
//
// Suggestions come from the tags already in use on the surface that rendered this, so a project
// converges on a vocabulary instead of accumulating near-duplicates nobody can filter by.

import { useMemo, useState } from 'react'
import { X } from 'lucide-react'
import { toastError } from '@/components/ui/toast'
import { apiErrorMessage, apiPatch } from '@/lib/api'

export interface WorkItemTagsFieldProps {
  projectId: string
  workItemId: string
  token: string
  tags: string[]
  /** Tags already in use elsewhere, offered as suggestions. */
  known?: string[]
  canEdit: boolean
  onChanged: (tags: string[]) => void
}

/** Stored form: trimmed, lower-cased. Matches the server, so the two never disagree about identity. */
export function normalizeTag(tag: string): string {
  return tag.trim().toLowerCase()
}

export function WorkItemTagsField({
  projectId,
  workItemId,
  token,
  tags,
  known = [],
  canEdit,
  onChanged,
}: WorkItemTagsFieldProps) {
  const [draft, setDraft] = useState('')
  const [saving, setSaving] = useState(false)
  const listId = `tag-suggestions-${workItemId}`

  const suggestions = useMemo(
    () => known.filter((t) => !tags.includes(t)).slice(0, 20),
    [known, tags]
  )

  async function save(next: string[]) {
    setSaving(true)
    try {
      // Sent whole, like every other field: the stored set becomes exactly this list.
      await apiPatch(`/api/v2/projects/${projectId}/work-items/${workItemId}`, { tags: next }, token)
      onChanged(next)
    } catch (err) {
      toastError(apiErrorMessage(err, 'Could not update the tags'))
    } finally {
      setSaving(false)
    }
  }

  function add(raw: string) {
    const tag = normalizeTag(raw)
    // Adding one the item already has is a no-op rather than a duplicate — same rule as the DB's key.
    if (!tag || tags.includes(tag)) {
      setDraft('')
      return
    }
    setDraft('')
    void save([...tags, tag])
  }

  return (
    <div className="space-y-2">
      {tags.length > 0 && (
        <div className="flex flex-wrap gap-1">
          {tags.map((tag) => (
            <span
              key={tag}
              className="inline-flex items-center gap-1 rounded-full bg-muted px-2 py-0.5 text-xs text-foreground"
            >
              {tag}
              {canEdit && (
                <button
                  type="button"
                  disabled={saving}
                  aria-label={`Remove tag ${tag}`}
                  onClick={() => void save(tags.filter((t) => t !== tag))}
                  className="text-muted-foreground hover:text-foreground"
                >
                  <X className="h-3 w-3" aria-hidden="true" />
                </button>
              )}
            </span>
          ))}
        </div>
      )}

      {canEdit && (
        <>
          <label htmlFor={`tag-input-${workItemId}`} className="sr-only">
            Add a tag
          </label>
          <input
            id={`tag-input-${workItemId}`}
            list={listId}
            value={draft}
            disabled={saving}
            placeholder="Add a tag…"
            onChange={(e) => setDraft(e.target.value)}
            onKeyDown={(e) => {
              // Enter and comma both commit — people type one or the other without thinking about it.
              if (e.key === 'Enter' || e.key === ',') {
                e.preventDefault()
                add(draft)
              }
            }}
            onBlur={() => draft && add(draft)}
            className="w-full rounded-md border border-border bg-background px-2.5 py-1 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring"
          />
          <datalist id={listId}>
            {suggestions.map((tag) => (
              <option key={tag} value={tag} />
            ))}
          </datalist>
        </>
      )}

      {!canEdit && tags.length === 0 && <p className="text-sm text-muted-foreground">No tags</p>}
    </div>
  )
}
