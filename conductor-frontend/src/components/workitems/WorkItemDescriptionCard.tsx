'use client'

// A Work Item's description, and a way to change it.
//
// It exists because there was no way to. Nothing in the frontend PATCHed `description`, which for a
// publishing Workflow is not a note — it is the caption. `PostPublishScheduler` sends
// `post.getDescription()` to the platform as the post's text, so the words that go out to Facebook,
// Instagram, YouTube or TikTok could not be written or corrected in a browser at all.
//
// The label follows the Workflow: "Caption" where the item publishes, "Description" everywhere else.
// Calling it Description on a Post would understate it — that field IS the post.

import { useState } from 'react'
import { Pencil } from 'lucide-react'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card, CardHeader } from '@/components/ui/card'
import { toastError } from '@/components/ui/toast'
import { apiErrorMessage, apiPatch } from '@/lib/api'
import { isApprovedOrLater } from '@/components/workitems/MediaUploadPanel'
import type { WorkflowView } from '@/types/workItem'

export interface WorkItemDescriptionCardProps {
  projectId: string
  workItemId: string
  token: string
  description?: string | null
  status?: string
  workflowView?: WorkflowView
  /** True where the bound Workflow publishes — the field is then the caption, not a note. */
  isCaption: boolean
  canEdit: boolean
  onSaved: (description: string) => void
}

export function WorkItemDescriptionCard({
  projectId,
  workItemId,
  token,
  description,
  status,
  workflowView,
  isCaption,
  canEdit,
  onSaved,
}: WorkItemDescriptionCardProps) {
  const [editing, setEditing] = useState(false)
  const [draft, setDraft] = useState('')
  const [saving, setSaving] = useState(false)

  const label = isCaption ? 'Caption' : 'Description'
  const noun = workflowView?.noun?.toLowerCase() ?? 'item'
  // Editing the caption is a publish-bundle change, so an approved item goes back for review and
  // anything already handed to a platform is taken back down. Said before the edit, not after it.
  const revertsOnEdit = isCaption && isApprovedOrLater(workflowView, status)

  async function save() {
    setSaving(true)
    try {
      await apiPatch(
        `/api/v2/projects/${projectId}/work-items/${workItemId}`,
        { description: draft },
        token
      )
      onSaved(draft)
      setEditing(false)
    } catch (err) {
      // Never swallow: the gate refuses an edit in its own words, and those are more useful than ours.
      toastError(apiErrorMessage(err, `Could not update the ${label.toLowerCase()}`))
    } finally {
      setSaving(false)
    }
  }

  return (
    <Card>
      <CardHeader>
        <h2 className="text-sm font-medium text-foreground">{label}</h2>
        {canEdit && !editing && (
          <Button
            variant="ghost"
            size="sm"
            onClick={() => {
              setDraft(description ?? '')
              setEditing(true)
            }}
          >
            <Pencil className="mr-1.5 h-3.5 w-3.5" aria-hidden="true" />
            {description ? 'Edit' : `Write the ${label.toLowerCase()}`}
          </Button>
        )}
      </CardHeader>

      {editing ? (
        <div className="space-y-3 px-4 py-3">
          {revertsOnEdit && (
            <Alert variant="warning">
              Changing the {label.toLowerCase()} sends this {noun} back for review and takes back
              anything already scheduled on a platform.
            </Alert>
          )}
          <label htmlFor={`description-${workItemId}`} className="sr-only">
            {label}
          </label>
          <textarea
            id={`description-${workItemId}`}
            autoFocus
            rows={6}
            value={draft}
            onChange={(e) => setDraft(e.target.value)}
            placeholder={
              isCaption ? 'What should this post say?' : `What is this ${noun} about?`
            }
            className="w-full rounded-md border border-border bg-background px-2.5 py-1.5 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring"
          />
          <div className="flex justify-end gap-2">
            <Button variant="ghost" size="sm" onClick={() => setEditing(false)} disabled={saving}>
              Cancel
            </Button>
            <Button size="sm" onClick={() => void save()} disabled={saving}>
              {saving ? 'Saving…' : 'Save'}
            </Button>
          </div>
        </div>
      ) : (
        <div className="px-4 py-3">
          {description ? (
            // Whitespace preserved: a caption's line breaks are part of it, and go out as written.
            <p className="whitespace-pre-wrap text-sm text-foreground">{description}</p>
          ) : (
            <p className="text-sm text-muted-foreground">
              {isCaption
                ? `No caption yet — this is the text that goes out with the ${noun}.`
                : 'No description yet.'}
            </p>
          )}
        </div>
      )}
    </Card>
  )
}
