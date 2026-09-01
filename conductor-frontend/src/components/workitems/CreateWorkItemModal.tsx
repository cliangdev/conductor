'use client'

// Making a Work Item, from the UI.
//
// It exists because there was no way to. The only apiPost to a work-items path in the whole frontend
// assigned reviewers — so every Work Item in the product had to come from the MCP server, the CLI or a
// raw API call, and a Posts page opened by a person was an empty calendar with nothing to click. That is
// also what made media upload unreachable: assets attach to a Post, and there was no way to make one.
//
// Workflow-agnostic like the list it sits on: the noun, the types and the initial status all come from
// the bound Workflow, so the same control creates an Issue, a Post, or whatever a Workflow declares next.

import { useEffect, useMemo, useState } from 'react'
import { useRouter } from 'next/navigation'
import { Button } from '@/components/ui/button'
import { Modal } from '@/components/ui/modal'
import { toastError } from '@/components/ui/toast'
import { apiErrorMessage, apiPost } from '@/lib/api'
import { humanizeId, workItemDetailPath } from '@/lib/workflows'
import type { WorkflowView } from '@/types/workItem'

interface CreatedWorkItem {
  id: string
  displayId?: string
}

export interface CreateWorkItemModalProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  projectId: string
  /** The Workflow the new item binds to — the list is always scoped to exactly one. */
  workflowSlug: string
  workflowView?: WorkflowView
  detailArea: string
  noun: string
  token: string
  /** Fired after a successful create so the list can pick the new row up. */
  onCreated: () => void
}

export function CreateWorkItemModal({
  open,
  onOpenChange,
  projectId,
  workflowSlug,
  workflowView,
  detailArea,
  noun,
  token,
  onCreated,
}: CreateWorkItemModalProps) {
  const router = useRouter()
  const types = useMemo(() => workflowView?.types ?? [], [workflowView])
  const [type, setType] = useState('')
  const [title, setTitle] = useState('')
  const [description, setDescription] = useState('')
  const [saving, setSaving] = useState(false)

  useEffect(() => {
    if (!open) return
    // A Workflow with exactly one type has nothing to ask about, so the field is preselected and hidden.
    setType(types[0] ?? '')
    setTitle('')
    setDescription('')
  }, [open, types])

  async function submit(event: React.FormEvent) {
    event.preventDefault()
    if (!title.trim() || !type || saving) return
    setSaving(true)
    try {
      const created = await apiPost<CreatedWorkItem>(
        `/api/v2/projects/${projectId}/work-items`,
        { type, title: title.trim(), description: description.trim() || undefined, workflow: workflowSlug },
        token
      )
      onCreated()
      onOpenChange(false)
      // Straight to the new item: creating one is nearly always the first half of editing it, and on a
      // calendar-first Workflow a brand new item has no date yet, so it would not even be on screen.
      if (created.displayId) {
        router.push(workItemDetailPath(projectId, detailArea, noun, created.displayId))
      }
    } catch (err) {
      // Never swallow: the server refuses an unknown type or an unbound Workflow by name, and that text
      // is more useful than anything this dialog could invent.
      toastError(apiErrorMessage(err, `Could not create the ${noun.toLowerCase()}`))
    } finally {
      setSaving(false)
    }
  }

  return (
    <Modal
      open={open}
      onOpenChange={onOpenChange}
      title={`New ${noun}`}
      description={`Creates a ${noun.toLowerCase()} in ${humanizeId(workflowSlug)}, at its first status.`}
      footer={
        <div className="flex justify-end gap-2">
          <Button variant="ghost" onClick={() => onOpenChange(false)} disabled={saving}>
            Cancel
          </Button>
          <Button type="submit" form="create-work-item" disabled={saving || !title.trim() || !type}>
            {saving ? 'Creating…' : `Create ${noun.toLowerCase()}`}
          </Button>
        </div>
      }
    >
      <form id="create-work-item" onSubmit={submit} className="space-y-4">
        <div className="space-y-1">
          <label htmlFor="new-wi-title" className="block text-sm font-medium text-foreground">
            Title
          </label>
          <input
            id="new-wi-title"
            autoFocus
            required
            value={title}
            onChange={(e) => setTitle(e.target.value)}
            placeholder={`What is this ${noun.toLowerCase()} about?`}
            className="w-full rounded-md border border-border bg-background px-2.5 py-1.5 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring"
          />
        </div>

        {types.length > 1 && (
          <div className="space-y-1">
            <label htmlFor="new-wi-type" className="block text-sm font-medium text-foreground">
              Type
            </label>
            <select
              id="new-wi-type"
              value={type}
              onChange={(e) => setType(e.target.value)}
              className="w-full rounded-md border border-border bg-background px-2.5 py-1.5 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
            >
              {types.map((t) => (
                <option key={t} value={t}>
                  {humanizeId(t)}
                </option>
              ))}
            </select>
          </div>
        )}

        <div className="space-y-1">
          <label htmlFor="new-wi-description" className="block text-sm font-medium text-foreground">
            Description <span className="text-muted-foreground">(optional)</span>
          </label>
          <textarea
            id="new-wi-description"
            rows={3}
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            className="w-full rounded-md border border-border bg-background px-2.5 py-1.5 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring"
          />
        </div>
      </form>
    </Modal>
  )
}
