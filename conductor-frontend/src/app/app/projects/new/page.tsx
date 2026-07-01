'use client'

export const dynamic = 'force-dynamic'

import { useState } from 'react'
import { useRouter } from 'next/navigation'
import { Button } from '@/components/ui/button'
import { useAuth } from '@/contexts/AuthContext'
import { useProject } from '@/contexts/ProjectContext'
import { apiPost } from '@/lib/api'
import type { Project } from '@/types'

const NAME_MAX_LENGTH = 100

export default function NewWorkspacePage() {
  const router = useRouter()
  const { accessToken } = useAuth()
  const { addProject, setActiveProject } = useProject()

  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [nameError, setNameError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [serverError, setServerError] = useState<string | null>(null)

  function validateName(value: string): string | null {
    if (!value.trim()) return 'Workspace name is required'
    if (value.length > NAME_MAX_LENGTH) return `Workspace name must be ${NAME_MAX_LENGTH} characters or fewer`
    return null
  }

  function handleNameChange(e: React.ChangeEvent<HTMLInputElement>) {
    const value = e.target.value
    setName(value)
    if (nameError) {
      setNameError(validateName(value))
    }
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    setServerError(null)

    const error = validateName(name)
    if (error) {
      setNameError(error)
      return
    }

    if (!accessToken) return

    setSubmitting(true)
    try {
      const project = await apiPost<Project>(
        '/api/v1/projects',
        { name: name.trim(), description: description.trim() || undefined },
        accessToken,
      )

      addProject(project)
      setActiveProject(project)
      router.push(`/app/projects/${project.id}/engineering/issues`)
    } catch {
      setServerError('Failed to create workspace. Please try again.')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div className="max-w-lg mx-auto mt-16 px-4">
      <h1 className="text-2xl font-bold text-foreground mb-6">Create a new workspace</h1>

      <form onSubmit={handleSubmit} noValidate className="space-y-5">
        <div>
          <label htmlFor="workspace-name" className="block text-sm font-medium text-foreground mb-1">
            Workspace name <span className="text-destructive">*</span>
          </label>
          <input
            id="workspace-name"
            type="text"
            value={name}
            onChange={handleNameChange}
            onBlur={() => setNameError(validateName(name))}
            placeholder="My workspace"
            maxLength={NAME_MAX_LENGTH + 50}
            className="w-full rounded-md border border-input bg-background text-foreground px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-ring focus:border-transparent"
            aria-describedby={nameError ? 'name-error' : undefined}
          />
          {nameError && (
            <p id="name-error" className="mt-1 text-xs text-destructive" role="alert">
              {nameError}
            </p>
          )}
          <p className="mt-1 text-xs text-foreground-subtle">{name.length}/{NAME_MAX_LENGTH} characters</p>
        </div>

        <div>
          <label htmlFor="workspace-description" className="block text-sm font-medium text-foreground mb-1">
            Description <span className="text-foreground-subtle font-normal">(optional)</span>
          </label>
          <textarea
            id="workspace-description"
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            placeholder="What is this workspace about?"
            rows={3}
            className="w-full rounded-md border border-input bg-background text-foreground px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-ring focus:border-transparent resize-none"
          />
        </div>

        {serverError && (
          <p className="text-sm text-destructive" role="alert">
            {serverError}
          </p>
        )}

        <div className="flex gap-3 pt-2">
          <Button type="submit" disabled={submitting}>
            {submitting ? 'Creating…' : 'Create workspace'}
          </Button>
          <Button
            type="button"
            variant="outline"
            onClick={() => router.back()}
            disabled={submitting}
          >
            Cancel
          </Button>
        </div>
      </form>
    </div>
  )
}
