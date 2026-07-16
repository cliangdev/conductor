'use client'

import { useState } from 'react'
import { useRouter } from 'next/navigation'
import { Modal } from '@/components/ui/modal'
import { Button } from '@/components/ui/button'
import { useAuth } from '@/contexts/AuthContext'
import { useProject } from '@/contexts/ProjectContext'
import { apiPost } from '@/lib/api'
import { workspaceHomePath } from '@/lib/navigation'
import type { Project } from '@/types'

interface CreateWorkspaceDialogProps {
  open: boolean
  onClose: () => void
}

export function CreateWorkspaceDialog({ open, onClose }: CreateWorkspaceDialogProps) {
  const router = useRouter()
  const { accessToken } = useAuth()
  const { addProject, setActiveProject } = useProject()
  const [name, setName] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  function handleClose() {
    setName('')
    setError(null)
    onClose()
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    if (!accessToken || !name.trim()) return
    setError(null)
    setSubmitting(true)
    try {
      const project = await apiPost<Project>('/api/v1/projects', { name: name.trim() }, accessToken)
      addProject(project)
      setActiveProject(project)
      handleClose()
      router.push(workspaceHomePath(project.id))
    } catch {
      setError('Failed to create workspace.')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <Modal open={open} onOpenChange={(o) => { if (!o) handleClose() }} title="Create workspace">
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

        {error && <p className="text-sm text-destructive">{error}</p>}

        <div className="flex justify-end gap-2 pt-1">
          <Button type="button" variant="outline" onClick={handleClose} disabled={submitting}>
            Cancel
          </Button>
          <Button type="submit" disabled={submitting || !name.trim()}>
            {submitting ? 'Creating...' : 'Create'}
          </Button>
        </div>
      </form>
    </Modal>
  )
}
