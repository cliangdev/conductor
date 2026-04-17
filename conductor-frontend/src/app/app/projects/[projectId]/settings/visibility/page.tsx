'use client'

export const dynamic = 'force-dynamic'

import { useCallback, useEffect, useState } from 'react'
import { useParams } from 'next/navigation'
import { useToast } from '@/components/ui/toast'
import { useAuth } from '@/contexts/AuthContext'
import { apiGet, apiPatch } from '@/lib/api'
import type { Member } from '@/types'

type Visibility = 'PRIVATE' | 'ORG' | 'TEAM' | 'PUBLIC'

interface ProjectWithVisibility {
  id: string
  name: string
  visibility: Visibility
  teamId: string | null
}

const VISIBILITY_OPTIONS: { value: Visibility; label: string; description: string }[] = [
  { value: 'PRIVATE', label: 'Private', description: 'Only project members can view' },
  { value: 'ORG', label: 'Org', description: 'All org members can view' },
  { value: 'TEAM', label: 'Team', description: 'Only team members can view' },
  { value: 'PUBLIC', label: 'Public', description: 'Anyone with the link can view' },
]

export default function VisibilitySettingsPage() {
  const params = useParams<{ projectId: string }>()
  const projectId = params.projectId
  const { accessToken, user } = useAuth()
  const { showToast } = useToast()

  const [members, setMembers] = useState<Member[]>([])
  const [membersLoading, setMembersLoading] = useState(true)

  const [project, setProject] = useState<ProjectWithVisibility | null>(null)
  const [projectLoading, setProjectLoading] = useState(true)

  const [visibility, setVisibility] = useState<Visibility>('PRIVATE')
  const [saving, setSaving] = useState(false)

  const fetchMembers = useCallback(async () => {
    if (!accessToken) return
    try {
      const data = await apiGet<Member[]>(`/api/v1/projects/${projectId}/members`, accessToken)
      setMembers(data)
    } catch {
      // non-fatal
    } finally {
      setMembersLoading(false)
    }
  }, [accessToken, projectId])

  const fetchProject = useCallback(async () => {
    if (!accessToken) return
    try {
      const data = await apiGet<ProjectWithVisibility>(`/api/v1/projects/${projectId}`, accessToken)
      setProject(data)
      setVisibility(data.visibility)
    } catch {
      // non-fatal
    } finally {
      setProjectLoading(false)
    }
  }, [accessToken, projectId])

  useEffect(() => { fetchMembers() }, [fetchMembers])
  useEffect(() => { fetchProject() }, [fetchProject])

  const currentUserRole = members.find((m) => m.userId === user?.id)?.role
  const isAdmin = currentUserRole === 'ADMIN'

  async function handleVisibilityChange(newVisibility: Visibility) {
    if (!accessToken || saving) return
    const previousVisibility = visibility
    setVisibility(newVisibility)
    setSaving(true)
    try {
      await apiPatch(`/api/v1/projects/${projectId}`, { visibility: newVisibility }, accessToken)
      showToast('Visibility updated', 'success')
    } catch {
      setVisibility(previousVisibility)
      showToast('Failed to update visibility. Please try again.', 'error')
    } finally {
      setSaving(false)
    }
  }

  if (membersLoading || projectLoading) {
    return (
      <div className="max-w-2xl mx-auto px-4 py-8">
        <p className="text-sm text-muted-foreground">Loading…</p>
      </div>
    )
  }

  if (!isAdmin) {
    return (
      <div className="max-w-2xl mx-auto px-4 py-8">
        <p className="text-sm text-muted-foreground">
          You don&apos;t have permission to manage settings.
        </p>
      </div>
    )
  }

  return (
    <div className="max-w-2xl mx-auto px-4 py-8">
      <h1 className="text-2xl font-bold text-foreground mb-6">Visibility</h1>

      <div className="bg-card rounded-lg border border-border p-6">
        <p className="text-sm text-muted-foreground mb-4">
          Control who can see this project.
        </p>

        <fieldset disabled={saving} className="space-y-3">
          <legend className="sr-only">Project visibility</legend>
          {VISIBILITY_OPTIONS.map(({ value, label, description }) => {
            const isTeamOption = value === 'TEAM'
            const isDisabled = isTeamOption && !project?.teamId

            return (
              <label
                key={value}
                className={`flex items-start gap-3 p-4 rounded-md border cursor-pointer transition-colors ${
                  isDisabled
                    ? 'opacity-50 cursor-not-allowed border-border'
                    : visibility === value
                    ? 'border-primary bg-primary/5'
                    : 'border-border hover:border-muted-foreground'
                }`}
              >
                <input
                  type="radio"
                  name="visibility"
                  value={value}
                  checked={visibility === value}
                  disabled={isDisabled}
                  onChange={() => handleVisibilityChange(value)}
                  aria-label={label}
                  className="mt-0.5 h-4 w-4 shrink-0 accent-primary"
                />
                <div className="min-w-0">
                  <p className="text-sm font-medium text-foreground">{label}</p>
                  <p className="text-xs text-muted-foreground mt-0.5">
                    {isDisabled
                      ? 'Assign a team to enable this option'
                      : description}
                  </p>
                </div>
              </label>
            )
          })}
        </fieldset>
      </div>
    </div>
  )
}
