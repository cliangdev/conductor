'use client'

export const dynamic = 'force-dynamic'

import { useCallback, useEffect, useState } from 'react'
import { useParams } from 'next/navigation'
import { useToast } from '@/components/ui/toast'
import { useAuth } from '@/contexts/AuthContext'
import { useOrg } from '@/contexts/OrgContext'
import { apiGet, apiPatch } from '@/lib/api'
import type { Member, OrgMember, Team } from '@/types'

type Visibility = 'PRIVATE' | 'ORG' | 'TEAM' | 'PUBLIC'

interface ProjectWithVisibility {
  id: string
  name: string
  visibility: Visibility
  teamId: string | null
}

const VISIBILITY_OPTIONS: { value: Visibility; label: string }[] = [
  { value: 'ORG', label: 'Everyone in the organization' },
  { value: 'TEAM', label: 'Team members only' },
  { value: 'PRIVATE', label: 'Project members only' },
]

export default function VisibilitySettingsPage() {
  const params = useParams<{ projectId: string }>()
  const projectId = params.projectId
  const { accessToken, user } = useAuth()
  const { activeOrg } = useOrg()
  const { showToast } = useToast()

  const [projectMembers, setProjectMembers] = useState<Member[]>([])
  const [orgMembers, setOrgMembers] = useState<OrgMember[]>([])
  const [project, setProject] = useState<ProjectWithVisibility | null>(null)
  const [loading, setLoading] = useState(true)

  const [selectedVisibility, setSelectedVisibility] = useState<Visibility>('ORG')
  const [selectedTeamId, setSelectedTeamId] = useState<string>('')
  const [saving, setSaving] = useState(false)
  const [isDirty, setIsDirty] = useState(false)

  const [teams, setTeams] = useState<Team[]>([])
  const [teamsLoading, setTeamsLoading] = useState(true)

  const fetchAll = useCallback(async () => {
    if (!accessToken) return
    try {
      const [membersData, projectData] = await Promise.all([
        apiGet<Member[]>(`/api/v1/projects/${projectId}/members`, accessToken),
        apiGet<ProjectWithVisibility>(`/api/v1/projects/${projectId}`, accessToken),
      ])
      setProjectMembers(membersData)
      setProject(projectData)
      setSelectedVisibility(projectData.visibility)
      setSelectedTeamId(projectData.teamId ?? '')
    } catch {
      // non-fatal
    } finally {
      setLoading(false)
    }
  }, [accessToken, projectId])

  const fetchOrgMembers = useCallback(async () => {
    if (!accessToken || !activeOrg) return
    try {
      const data = await apiGet<OrgMember[]>(`/api/v1/orgs/${activeOrg.id}/members`, accessToken)
      setOrgMembers(data)
    } catch {
      // non-fatal
    }
  }, [accessToken, activeOrg])

  const fetchTeams = useCallback(async () => {
    if (!accessToken || !activeOrg) {
      setTeamsLoading(false)
      return
    }
    try {
      const data = await apiGet<Team[]>(`/api/v1/orgs/${activeOrg.id}/teams`, accessToken)
      setTeams(data)
    } catch {
      // non-fatal
    } finally {
      setTeamsLoading(false)
    }
  }, [accessToken, activeOrg])

  useEffect(() => { fetchAll() }, [fetchAll])
  useEffect(() => { fetchOrgMembers() }, [fetchOrgMembers])
  useEffect(() => { fetchTeams() }, [fetchTeams])

  const currentUserRole = projectMembers.find((m) => m.userId === user?.id)?.role
  const isAdmin = currentUserRole === 'ADMIN'

  const owningTeam = teams.find((t) => t.id === selectedTeamId)

  function handleVisibilitySelect(v: Visibility) {
    setSelectedVisibility(v)
    setIsDirty(true)
  }

  function handleTeamSelect(teamId: string) {
    setSelectedTeamId(teamId)
    setIsDirty(true)
  }

  function handleCancel() {
    setSelectedVisibility(project?.visibility ?? 'ORG')
    setSelectedTeamId(project?.teamId ?? '')
    setIsDirty(false)
  }

  async function handleSave() {
    if (!accessToken || saving) return
    setSaving(true)
    try {
      const teamId = selectedTeamId === '' ? null : selectedTeamId
      await apiPatch(`/api/v1/projects/${projectId}`, {
        visibility: selectedVisibility,
        teamId,
      }, accessToken)
      setProject((prev) => prev ? { ...prev, visibility: selectedVisibility, teamId } : prev)
      setIsDirty(false)
      showToast('Visibility updated', 'success')
    } catch {
      showToast('Failed to update visibility. Please try again.', 'error')
    } finally {
      setSaving(false)
    }
  }

  function impactText(v: Visibility): string | null {
    const orgCount = orgMembers.length
    const projCount = projectMembers.length
    const currentVis = project?.visibility ?? 'ORG'

    // Only show impact when selecting something more restrictive
    const restrictiveness: Record<Visibility, number> = { PUBLIC: 0, ORG: 1, TEAM: 2, PRIVATE: 3 }
    if (restrictiveness[v] <= restrictiveness[currentVis] && v === selectedVisibility) return null

    if (v === 'PRIVATE' && (currentVis === 'ORG' || currentVis === 'TEAM')) {
      const willLose = orgCount - projCount
      if (willLose > 0) return `⚠ ${willLose} member${willLose !== 1 ? 's' : ''} will lose view access`
    }
    if (v === 'TEAM' && currentVis === 'ORG' && owningTeam) {
      return `${orgCount > 0 ? `${orgCount} org members can view` : ''}`
    }
    return null
  }

  function optionDescription(v: Visibility): string {
    const orgCount = orgMembers.length
    switch (v) {
      case 'ORG':
        return `All ${orgCount > 0 ? orgCount + ' ' : ''}org member${orgCount !== 1 ? 's' : ''} can view`
      case 'TEAM':
        return owningTeam ? `${owningTeam.name} team members can view` : 'Assign a team below to enable this option'
      case 'PRIVATE':
        return `Only the ${projectMembers.length} explicit project member${projectMembers.length !== 1 ? 's' : ''} can view`
      default:
        return ''
    }
  }

  if (loading) {
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

      <div className="bg-card rounded-lg border border-border p-6 mb-6">
        <p className="text-sm font-medium text-foreground mb-4">Who can view this project?</p>

        <fieldset className="space-y-3 mb-0">
          <legend className="sr-only">Project visibility</legend>
          {VISIBILITY_OPTIONS.map(({ value, label }) => {
            const isTeamOption = value === 'TEAM'
            const isDisabled = isTeamOption && !selectedTeamId
            const impact = selectedVisibility === value ? impactText(value) : null

            return (
              <label
                key={value}
                className={`flex items-start gap-3 p-4 rounded-md border cursor-pointer transition-colors ${
                  isDisabled
                    ? 'opacity-50 cursor-not-allowed border-border'
                    : selectedVisibility === value
                    ? 'border-primary bg-primary/5'
                    : 'border-border hover:border-muted-foreground'
                }`}
              >
                <input
                  type="radio"
                  name="visibility"
                  value={value}
                  checked={selectedVisibility === value}
                  disabled={isDisabled}
                  onChange={() => handleVisibilitySelect(value)}
                  aria-label={label}
                  className="mt-0.5 h-4 w-4 shrink-0 accent-primary"
                />
                <div className="min-w-0 flex-1">
                  <div className="flex items-center gap-2">
                    <p className="text-sm font-medium text-foreground">{label}</p>
                    {value === 'ORG' && <span className="text-xs text-muted-foreground">(default)</span>}
                  </div>
                  <p className="text-xs text-muted-foreground mt-0.5">
                    {optionDescription(value)}
                  </p>
                  {impact && (
                    <p className="text-xs text-amber-600 dark:text-amber-400 mt-1">{impact}</p>
                  )}
                </div>
              </label>
            )
          })}
        </fieldset>
      </div>

      {/* Owning team selector */}
      <div className="bg-card rounded-lg border border-border p-6 mb-6">
        <h2 className="text-base font-semibold text-foreground mb-1">Owning team</h2>
        <p className="text-sm text-muted-foreground mb-3">
          Assign a team to enable team-restricted visibility.
        </p>
        <select
          disabled={teamsLoading}
          value={selectedTeamId}
          onChange={(e) => handleTeamSelect(e.target.value)}
          className="w-full rounded-md border border-input bg-background text-foreground px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-ring focus:border-transparent disabled:opacity-50 disabled:cursor-not-allowed"
        >
          <option value="">None</option>
          {teams.map((team) => (
            <option key={team.id} value={team.id}>
              {team.name}
            </option>
          ))}
        </select>
      </div>

      {/* Save / Cancel */}
      {isDirty && (
        <div className="flex gap-3">
          <button
            onClick={handleSave}
            disabled={saving}
            className="px-4 py-2 rounded-md bg-primary text-primary-foreground text-sm font-medium hover:bg-primary/90 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
          >
            {saving ? 'Saving…' : 'Save'}
          </button>
          <button
            onClick={handleCancel}
            disabled={saving}
            className="px-4 py-2 rounded-md border border-border text-sm font-medium hover:bg-sidebar-hover transition-colors disabled:opacity-50"
          >
            Cancel
          </button>
        </div>
      )}
    </div>
  )
}
