'use client'

export const dynamic = 'force-dynamic'

import { useState, useEffect } from 'react'
import { useRouter } from 'next/navigation'
import { Button } from '@/components/ui/button'
import { useAuth } from '@/contexts/AuthContext'
import { apiPost } from '@/lib/api'

function slugify(name: string): string {
  return name
    .toLowerCase()
    .replace(/\s+/g, '-')
    .replace(/[^a-z0-9-]/g, '')
}

export default function OnboardingPage() {
  const router = useRouter()
  const { accessToken } = useAuth()

  const [name, setName] = useState('')
  const [slug, setSlug] = useState('')
  const [slugManuallyEdited, setSlugManuallyEdited] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!slugManuallyEdited) {
      setSlug(slugify(name))
    }
  }, [name, slugManuallyEdited])

  function handleSlugChange(e: React.ChangeEvent<HTMLInputElement>) {
    setSlugManuallyEdited(true)
    setSlug(e.target.value)
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    if (!accessToken) return

    setError(null)
    setSubmitting(true)
    try {
      await apiPost('/api/v1/orgs', { name, slug }, accessToken)
      // Hard navigate so OrgProvider re-initializes with the new org
      window.location.href = '/app/projects'
    } catch (err) {
      const apiErr = err as Error & { status?: number }
      if (apiErr.status === 409) {
        setError('This slug is already taken. Try a different one.')
      } else {
        setError('Failed to create organization. Please try again.')
      }
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div className="flex min-h-screen items-center justify-center bg-background px-4">
      <div className="w-full max-w-md rounded-xl border border-border bg-card p-8 shadow-sm">
        <h1 className="mb-2 text-2xl font-bold text-foreground">Create your organization</h1>
        <p className="mb-8 text-sm text-muted-foreground">
          Set up your organization to get started with Conductor.
        </p>

        <form onSubmit={handleSubmit} className="space-y-5">
          <div>
            <label htmlFor="org-name" className="block text-sm font-medium text-foreground mb-1">
              Organization name
            </label>
            <input
              id="org-name"
              type="text"
              value={name}
              onChange={(e) => setName(e.target.value)}
              required
              placeholder="Acme Corp"
              className="w-full rounded-md border border-input bg-background text-foreground px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-ring"
            />
          </div>

          <div>
            <label htmlFor="org-slug" className="block text-sm font-medium text-foreground mb-1">
              Slug
            </label>
            <input
              id="org-slug"
              type="text"
              value={slug}
              onChange={handleSlugChange}
              required
              placeholder="acme-corp"
              className="w-full rounded-md border border-input bg-background text-foreground px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-ring"
            />
            <p className="mt-1 text-xs text-muted-foreground">
              Used in URLs. Lowercase letters, numbers, and hyphens only.
            </p>
          </div>

          {error && (
            <p className="text-sm text-destructive">{error}</p>
          )}

          <Button type="submit" className="w-full" size="lg" disabled={submitting || !name || !slug}>
            {submitting ? 'Creating...' : 'Create Organization'}
          </Button>
        </form>
      </div>
    </div>
  )
}
