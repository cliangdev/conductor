'use client'

import { useState, useEffect } from 'react'
import { Modal } from '@/components/ui/modal'
import { Button } from '@/components/ui/button'
import { useAuth } from '@/contexts/AuthContext'
import { useOrg } from '@/contexts/OrgContext'
import { apiPost } from '@/lib/api'
import type { Org } from '@/types'

function slugify(name: string): string {
  return name
    .toLowerCase()
    .replace(/\s+/g, '-')
    .replace(/[^a-z0-9-]/g, '')
    .replace(/^-+|-+$/, '')
}

interface CreateOrgDialogProps {
  open: boolean
  onClose: () => void
}

export function CreateOrgDialog({ open, onClose }: CreateOrgDialogProps) {
  const { accessToken } = useAuth()
  const { refetch, setActiveOrg } = useOrg()
  const [name, setName] = useState('')
  const [slug, setSlug] = useState('')
  const [slugEdited, setSlugEdited] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!slugEdited) setSlug(slugify(name))
  }, [name, slugEdited])

  function handleClose() {
    setName('')
    setSlug('')
    setSlugEdited(false)
    setError(null)
    onClose()
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    if (!accessToken || !name.trim() || !slug) return
    setError(null)
    setSubmitting(true)
    try {
      const org = await apiPost<Org>('/api/v1/orgs', { name: name.trim(), slug }, accessToken)
      await refetch()
      setActiveOrg(org)
      handleClose()
    } catch (err) {
      const apiErr = err as Error & { status?: number }
      setError(apiErr.status === 409 ? 'This slug is already taken.' : 'Failed to create organization.')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <Modal open={open} onOpenChange={(o) => { if (!o) handleClose() }} title="Create organization">
      <form onSubmit={handleSubmit} className="space-y-4">
        <div className="space-y-1.5">
          <label className="text-sm font-medium text-foreground">Name</label>
          <input
            type="text"
            value={name}
            onChange={(e) => setName(e.target.value)}
            required
            autoFocus
            placeholder="Acme Corp"
            className="w-full rounded-md border border-input bg-background text-foreground px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-ring"
          />
        </div>

        <div className="space-y-1.5">
          <label className="text-sm font-medium text-foreground">URL slug</label>
          <div className="flex items-center gap-1.5">
            <span className="text-sm text-muted-foreground shrink-0">conductor.app/</span>
            <input
              type="text"
              value={slug}
              onChange={(e) => { setSlug(e.target.value); setSlugEdited(true) }}
              required
              placeholder="acme-corp"
              className="flex-1 rounded-md border border-input bg-background text-foreground px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-ring"
            />
          </div>
        </div>

        {error && <p className="text-sm text-destructive">{error}</p>}

        <div className="flex justify-end gap-2 pt-1">
          <Button type="button" variant="outline" onClick={handleClose} disabled={submitting}>
            Cancel
          </Button>
          <Button type="submit" disabled={submitting || !name.trim() || !slug}>
            {submitting ? 'Creating...' : 'Create'}
          </Button>
        </div>
      </form>
    </Modal>
  )
}
