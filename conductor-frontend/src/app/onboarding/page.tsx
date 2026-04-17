'use client'

export const dynamic = 'force-dynamic'

import { useState, useEffect } from 'react'
import { useRouter } from 'next/navigation'
import Link from 'next/link'
import { CheckCircleIcon } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { useAuth } from '@/contexts/AuthContext'
import { apiGet, apiPost } from '@/lib/api'

function slugify(name: string): string {
  return name
    .toLowerCase()
    .replace(/\s+/g, '-')
    .replace(/[^a-z0-9-]/g, '')
    .replace(/^-+|-+$/, '')
}

export default function OnboardingPage() {
  const router = useRouter()
  const { accessToken } = useAuth()

  const [name, setName] = useState('')
  const [slug, setSlug] = useState('')
  const [projectCount, setProjectCount] = useState<number | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [createdOrg, setCreatedOrg] = useState<{ name: string } | null>(null)

  useEffect(() => {
    setSlug(slugify(name))
  }, [name])

  useEffect(() => {
    if (!accessToken) return
    apiGet<{ id: string }[]>('/api/v1/projects', accessToken)
      .then((projects) => setProjectCount(projects.length))
      .catch(() => setProjectCount(0))
  }, [accessToken])

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    if (!accessToken) return

    setError(null)
    setSubmitting(true)
    try {
      await apiPost('/api/v1/orgs', { name: name.trim(), slug }, accessToken)
      setCreatedOrg({ name: name.trim() })
    } catch (err) {
      const apiErr = err as Error & { status?: number }
      if (apiErr.status === 409) {
        setError('This organization name is already taken. Try a different one.')
      } else {
        setError('Failed to create organization. Please try again.')
      }
    } finally {
      setSubmitting(false)
    }
  }

  // Step 2: post-creation next steps
  if (createdOrg) {
    return (
      <div className="flex min-h-screen items-center justify-center bg-background px-4">
        <div className="w-full max-w-md rounded-xl border border-border bg-card p-8 shadow-sm">
          <div className="flex items-center gap-3 mb-3">
            <CheckCircleIcon className="h-6 w-6 text-green-500 shrink-0" />
            <h1 className="text-2xl font-bold text-foreground">{createdOrg.name} is ready</h1>
          </div>
          {projectCount !== null && projectCount > 0 && (
            <p className="mb-6 text-sm text-muted-foreground">
              Your {projectCount} existing {projectCount === 1 ? 'project has' : 'projects have'} moved to this workspace.
            </p>
          )}

          <div className="space-y-3">
            <Link
              href="/app/org/members"
              onClick={() => { window.location.href = '/app/org/members' }}
              className="flex items-center justify-between w-full rounded-lg border border-primary bg-primary text-primary-foreground px-4 py-3 text-sm font-medium hover:bg-primary/90 transition-colors"
            >
              Invite your teammates
              <span>→</span>
            </Link>

            <button
              onClick={() => { window.location.href = '/app/projects' }}
              className="w-full text-left text-sm text-muted-foreground hover:text-foreground transition-colors py-1"
            >
              Browse your projects →
            </button>

            <button
              onClick={() => { window.location.href = '/app/projects/new' }}
              className="w-full text-left text-sm text-muted-foreground hover:text-foreground transition-colors py-1"
            >
              Create a new project →
            </button>
          </div>
        </div>
      </div>
    )
  }

  // Step 1: name input
  return (
    <div className="flex min-h-screen items-center justify-center bg-background px-4">
      <div className="w-full max-w-md rounded-xl border border-border bg-card p-8 shadow-sm">
        <h1 className="mb-6 text-2xl font-bold text-foreground text-center">
          What&apos;s your organization called?
        </h1>

        <form onSubmit={handleSubmit} className="space-y-4">
          <input
            id="org-name"
            type="text"
            value={name}
            onChange={(e) => setName(e.target.value)}
            required
            autoFocus
            placeholder="Acme Corp"
            className="w-full rounded-md border border-input bg-background text-foreground px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-ring"
          />

          {slug && (
            <p className="text-xs text-muted-foreground">
              conductor.app/{slug} &middot; change later
            </p>
          )}

          {projectCount !== null && projectCount > 0 && (
            <p className="text-xs text-muted-foreground">
              Your {projectCount} existing {projectCount === 1 ? 'project' : 'projects'} will move here.
            </p>
          )}

          {error && (
            <p className="text-sm text-destructive">{error}</p>
          )}

          <Button type="submit" className="w-full" size="lg" disabled={submitting || !name.trim()}>
            {submitting ? 'Creating...' : 'Create organization'}
          </Button>
        </form>
      </div>
    </div>
  )
}
