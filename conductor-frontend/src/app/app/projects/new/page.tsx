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

interface CreateProjectResponse {
  id: string
  name: string
  description: string | null
  createdBy: string
  createdAt: string
}

export default function NewProjectPage() {
  const router = useRouter()
  const { accessToken } = useAuth()
  const { addProject, setActiveProject } = useProject()

  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [nameError, setNameError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [serverError, setServerError] = useState<string | null>(null)

  function validateName(value: string): string | null {
    if (!value.trim()) return 'Project name is required'
    if (value.length > NAME_MAX_LENGTH) return `Project name must be ${NAME_MAX_LENGTH} characters or fewer`
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
      const created = await apiPost<CreateProjectResponse>(
        '/api/v1/projects',
        { name: name.trim(), description: description.trim() || undefined },
        accessToken,
      )

      const project: Project = {
        id: created.id,
        name: created.name,
        description: created.description,
        createdAt: created.createdAt,
        updatedAt: created.createdAt,
      }

      addProject(project)
      setActiveProject(project)
      router.push(`/app/projects/${project.id}/issues`)
    } catch {
      setServerError('Failed to create project. Please try again.')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div className="max-w-lg mx-auto mt-16 px-4">
      <h1 className="text-2xl font-bold text-gray-900 mb-6">Create a new project</h1>

      <form onSubmit={handleSubmit} noValidate className="space-y-5">
        <div>
          <label htmlFor="project-name" className="block text-sm font-medium text-gray-700 mb-1">
            Project name <span className="text-red-500">*</span>
          </label>
          <input
            id="project-name"
            type="text"
            value={name}
            onChange={handleNameChange}
            onBlur={() => setNameError(validateName(name))}
            placeholder="My project"
            maxLength={NAME_MAX_LENGTH + 50}
            className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary focus:border-transparent"
            aria-describedby={nameError ? 'name-error' : undefined}
          />
          {nameError && (
            <p id="name-error" className="mt-1 text-xs text-red-600" role="alert">
              {nameError}
            </p>
          )}
          <p className="mt-1 text-xs text-gray-400">{name.length}/{NAME_MAX_LENGTH} characters</p>
        </div>

        <div>
          <label htmlFor="project-description" className="block text-sm font-medium text-gray-700 mb-1">
            Description <span className="text-gray-400 font-normal">(optional)</span>
          </label>
          <textarea
            id="project-description"
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            placeholder="What is this project about?"
            rows={3}
            className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary focus:border-transparent resize-none"
          />
        </div>

        {serverError && (
          <p className="text-sm text-red-600" role="alert">
            {serverError}
          </p>
        )}

        <div className="flex gap-3 pt-2">
          <Button type="submit" disabled={submitting}>
            {submitting ? 'Creating…' : 'Create Project'}
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
