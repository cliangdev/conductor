'use client'

import { useEffect, useState } from 'react'
import { Button } from '@/components/ui/button'
import { Modal } from '@/components/ui/modal'
import { useAuth } from '@/contexts/AuthContext'
import { createRuntimeTarget, apiErrorMessage } from '@/lib/api'
import type { ConnectionSummary, RuntimeTarget } from '@/lib/api'

const SLUG_PATTERN = /^[a-z0-9][a-z0-9-]{0,63}$/
const RESERVED_NAMES = ['conductor', 'self-hosted', 'cloud-run']

interface CreateFormState {
  name: string
  connectionId: string
  gcpProjectId: string
  region: string
  image: string
  jobName: string
}

const EMPTY_FORM: CreateFormState = {
  name: '',
  connectionId: '',
  gcpProjectId: '',
  region: '',
  image: '',
  jobName: '',
}

/**
 * Create-a-runtime-target modal, shared by {@code RuntimeTargetsPanel} (Integrations → Google Cloud)
 * and {@code ClaudeRuntimeSection} (Settings → AI Providers → Runtime) — the same form either way
 * (`gcp-cloud-run` is the only provider today), just triggered from two places. Owns its own form
 * state; the caller only owns whether it's open and what happens on success.
 */
export function RuntimeTargetCreateModal({
  projectId,
  connections,
  open,
  onOpenChange,
  onCreated,
}: {
  projectId: string
  connections: ConnectionSummary[]
  open: boolean
  onOpenChange: (open: boolean) => void
  onCreated: (target: RuntimeTarget) => void
}) {
  const { accessToken } = useAuth()
  const [form, setForm] = useState<CreateFormState>(EMPTY_FORM)
  const [creating, setCreating] = useState(false)
  const [error, setError] = useState<string | null>(null)

  // Reset the form each time the modal opens — seeds connectionId from the first active connection,
  // same as the old inline openCreateModal() did.
  useEffect(() => {
    if (!open) return
    setForm({ ...EMPTY_FORM, connectionId: connections[0]?.id ?? '' })
    setError(null)
    // eslint-disable-next-line react-hooks/exhaustive-deps -- reset only on the open transition, not on every connections change
  }, [open])

  function nameError(): string | null {
    if (!form.name) return null
    if (!SLUG_PATTERN.test(form.name)) {
      return 'Lowercase letters, numbers, and hyphens only — must start with a letter or number.'
    }
    if (RESERVED_NAMES.includes(form.name)) {
      return 'That name is reserved.'
    }
    return null
  }

  async function handleCreate(e: React.FormEvent) {
    e.preventDefault()
    if (!accessToken) return
    const slugProblem = nameError()
    if (slugProblem || !SLUG_PATTERN.test(form.name)) {
      setError(slugProblem ?? 'Enter a valid name.')
      return
    }
    if (!form.connectionId) {
      setError('Select a Google Cloud connection.')
      return
    }
    setCreating(true)
    setError(null)
    try {
      const created = await createRuntimeTarget(
        projectId,
        {
          name: form.name,
          provider: 'gcp-cloud-run',
          connectionId: form.connectionId,
          gcpProjectId: form.gcpProjectId,
          region: form.region,
          image: form.image,
          ...(form.jobName ? { jobName: form.jobName } : {}),
        },
        accessToken,
      )
      onCreated(created)
      onOpenChange(false)
    } catch (err) {
      setError(apiErrorMessage(err, 'Failed to create runtime target.'))
    } finally {
      setCreating(false)
    }
  }

  return (
    <Modal
      open={open}
      onOpenChange={(o) => { if (!o) onOpenChange(false) }}
      title="Add runtime target"
      description="Runs claude-code workflow steps as a Cloud Run Job in your GCP project."
    >
      <form onSubmit={handleCreate} className="space-y-4">
        <div>
          <label htmlFor="runtime-name" className="block text-sm font-medium text-foreground mb-1">Name</label>
          <input
            id="runtime-name"
            type="text"
            value={form.name}
            onChange={(e) => setForm((f) => ({ ...f, name: e.target.value }))}
            placeholder="my-cloud-run"
            required
            className="w-full rounded-md border border-input bg-background px-3 py-2 text-sm font-mono placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring"
          />
          {nameError() && <p className="mt-1 text-xs text-destructive">{nameError()}</p>}
        </div>
        <div>
          <label htmlFor="runtime-connection" className="block text-sm font-medium text-foreground mb-1">Connection</label>
          <select
            id="runtime-connection"
            value={form.connectionId}
            onChange={(e) => setForm((f) => ({ ...f, connectionId: e.target.value }))}
            required
            className="w-full rounded-md border border-input bg-background px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-ring"
          >
            <option value="" disabled>Select a connection…</option>
            {connections.map((c) => (
              <option key={c.id} value={c.id}>{c.label || c.id}</option>
            ))}
          </select>
        </div>
        <div>
          <label htmlFor="runtime-gcp-project-id" className="block text-sm font-medium text-foreground mb-1">GCP Project ID</label>
          <input
            id="runtime-gcp-project-id"
            type="text"
            value={form.gcpProjectId}
            onChange={(e) => setForm((f) => ({ ...f, gcpProjectId: e.target.value }))}
            placeholder="my-gcp-project"
            required
            className="w-full rounded-md border border-input bg-background px-3 py-2 text-sm placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring"
          />
        </div>
        <div>
          <label htmlFor="runtime-region" className="block text-sm font-medium text-foreground mb-1">Region</label>
          <input
            id="runtime-region"
            type="text"
            value={form.region}
            onChange={(e) => setForm((f) => ({ ...f, region: e.target.value }))}
            placeholder="us-central1"
            required
            className="w-full rounded-md border border-input bg-background px-3 py-2 text-sm placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring"
          />
        </div>
        <div>
          <label htmlFor="runtime-image" className="block text-sm font-medium text-foreground mb-1">Image</label>
          <input
            id="runtime-image"
            type="text"
            value={form.image}
            onChange={(e) => setForm((f) => ({ ...f, image: e.target.value }))}
            placeholder="us-central1-docker.pkg.dev/PROJECT/repo/image:tag"
            required
            className="w-full rounded-md border border-input bg-background px-3 py-2 text-sm font-mono placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring"
          />
        </div>
        <div>
          <label htmlFor="runtime-job-name" className="block text-sm font-medium text-foreground mb-1">
            Job name <span className="text-muted-foreground">(optional)</span>
          </label>
          <input
            id="runtime-job-name"
            type="text"
            value={form.jobName}
            onChange={(e) => setForm((f) => ({ ...f, jobName: e.target.value }))}
            placeholder={`defaults to conductor-${form.name || '<name>'}`}
            className="w-full rounded-md border border-input bg-background px-3 py-2 text-sm font-mono placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring"
          />
        </div>
        {error && <p className="text-sm text-destructive" role="alert">{error}</p>}
        <div className="flex gap-3 pt-1">
          <Button type="button" variant="outline" className="flex-1" onClick={() => onOpenChange(false)}>
            Cancel
          </Button>
          <Button type="submit" className="flex-1" disabled={creating || !!nameError()}>
            {creating ? 'Creating…' : 'Create'}
          </Button>
        </div>
      </form>
    </Modal>
  )
}
