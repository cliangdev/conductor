'use client'

export const dynamic = 'force-dynamic'

import { useCallback, useEffect, useState } from 'react'
import { useRouter } from 'next/navigation'
import { ChevronRightIcon } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Modal } from '@/components/ui/modal'
import { useToast } from '@/components/ui/toast'
import { useAuth } from '@/contexts/AuthContext'
import { useOrg } from '@/contexts/OrgContext'
import { apiGet, apiPost } from '@/lib/api'
import type { OrgMember, Team } from '@/types'

interface ApiError extends Error {
  status?: number
}

interface TeamWithCounts extends Team {
  memberCount: number
  projectCount: number
}

function fetchTeamMemberCount(teamId: string, token: string): Promise<number> {
  return apiGet<{ userId: string }[]>(`/api/v1/teams/${teamId}/members`, token).then(
    (members) => members.length,
  )
}

export default function TeamsPage() {
  const router = useRouter()
  const { accessToken, user } = useAuth()
  const { activeOrg, refetch: refetchOrg } = useOrg()
  const { showToast } = useToast()

  const [teams, setTeams] = useState<TeamWithCounts[]>([])
  const [orgMembers, setOrgMembers] = useState<OrgMember[]>([])
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState<string | null>(null)

  const [createOpen, setCreateOpen] = useState(false)
  const [teamName, setTeamName] = useState('')
  const [createError, setCreateError] = useState<string | null>(null)
  const [createSubmitting, setCreateSubmitting] = useState(false)

  const fetchTeams = useCallback(async () => {
    if (!accessToken || !activeOrg) return
    setLoading(true)
    try {
      const data = await apiGet<Team[]>(`/api/v1/orgs/${activeOrg.id}/teams`, accessToken)
      const withCounts = await Promise.all(
        data.map(async (team) => ({
          ...team,
          memberCount: await fetchTeamMemberCount(team.id, accessToken),
          projectCount: 0,
        })),
      )
      setTeams(withCounts)
      setLoadError(null)
    } catch {
      setLoadError('Failed to load teams.')
    } finally {
      setLoading(false)
    }
  }, [accessToken, activeOrg])

  const fetchOrgMembers = useCallback(async () => {
    if (!accessToken || !activeOrg) return
    try {
      const data = await apiGet<OrgMember[]>(`/api/v1/orgs/${activeOrg.id}/members`, accessToken)
      setOrgMembers(data)
    } catch {
      // non-critical
    }
  }, [accessToken, activeOrg])

  useEffect(() => {
    fetchTeams()
    fetchOrgMembers()
  }, [fetchTeams, fetchOrgMembers])

  const currentUserMember = orgMembers.find((m) => m.userId === user?.id)
  const isAdmin = currentUserMember?.role === 'ADMIN'

  function openCreate() {
    setTeamName('')
    setCreateError(null)
    setCreateOpen(true)
  }

  async function handleCreate(e: React.FormEvent) {
    e.preventDefault()
    const name = teamName.trim()
    if (!name) {
      setCreateError('Team name is required.')
      return
    }
    if (!accessToken || !activeOrg) return
    setCreateSubmitting(true)
    setCreateError(null)
    try {
      const created = await apiPost<Team>(
        `/api/v1/orgs/${activeOrg.id}/teams`,
        { name },
        accessToken,
      )
      setTeams((prev) => [...prev, { ...created, memberCount: 0, projectCount: 0 }])
      setCreateOpen(false)
      showToast(`Team "${created.name}" created.`)
      refetchOrg()
    } catch (err) {
      const apiErr = err as ApiError
      if (apiErr.status === 409) {
        setCreateError('A team with this name already exists.')
      } else {
        setCreateError('Failed to create team. Please try again.')
      }
    } finally {
      setCreateSubmitting(false)
    }
  }

  if (!activeOrg) {
    return (
      <div className="max-w-2xl mx-auto px-4 py-8">
        <p className="text-sm text-muted-foreground">No organization selected.</p>
      </div>
    )
  }

  return (
    <div className="max-w-2xl mx-auto px-4 py-8">
      <div className="mb-6 flex items-center justify-between">
        <div>
          <p className="text-xs text-muted-foreground mb-0.5">{activeOrg.name}</p>
          <h1 className="text-2xl font-bold text-foreground">
            Teams {!loading && teams.length > 0 && (
              <span className="text-muted-foreground font-normal text-lg">({teams.length})</span>
            )}
          </h1>
        </div>
        {isAdmin && teams.length > 0 && (
          <Button onClick={openCreate} size="sm">
            + New team
          </Button>
        )}
      </div>

      {loading && <p className="text-sm text-muted-foreground">Loading teams…</p>}
      {loadError && <p className="text-sm text-destructive" role="alert">{loadError}</p>}

      {!loading && !loadError && teams.length === 0 && (
        <div className="flex flex-col items-center justify-center py-16 text-center">
          <h2 className="text-base font-semibold text-foreground mb-2">No teams yet</h2>
          <p className="text-sm text-muted-foreground max-w-sm mb-6">
            Teams let larger orgs partition work and control project visibility by department.
            Skip this if everyone works together on everything.
          </p>
          {isAdmin && (
            <Button onClick={openCreate}>
              + Create a team
            </Button>
          )}
        </div>
      )}

      {!loading && !loadError && teams.length > 0 && (
        <div className="bg-card rounded-lg border border-border divide-y divide-border">
          {teams.map((team) => (
            <button
              key={team.id}
              onClick={() => router.push(`/app/org/teams/${team.id}`)}
              className="w-full flex items-center justify-between px-4 py-4 hover:bg-sidebar-hover transition-colors text-left"
            >
              <div className="min-w-0">
                <p className="text-sm font-medium text-foreground">{team.name}</p>
                <p className="text-xs text-muted-foreground mt-0.5">
                  {team.memberCount} {team.memberCount === 1 ? 'member' : 'members'}
                  {team.projectCount > 0 && ` · ${team.projectCount} ${team.projectCount === 1 ? 'project' : 'projects'}`}
                </p>
              </div>
              <ChevronRightIcon className="h-4 w-4 text-muted-foreground shrink-0 ml-4" />
            </button>
          ))}
        </div>
      )}

      <Modal
        open={createOpen}
        onOpenChange={(open) => { if (!open) setCreateOpen(false) }}
        title="Create Team"
      >
        <form onSubmit={handleCreate} noValidate className="flex flex-col gap-4">
          <div>
            <label htmlFor="team-name" className="block text-sm font-medium text-foreground mb-1">
              Team name
            </label>
            <input
              id="team-name"
              type="text"
              value={teamName}
              onChange={(e) => setTeamName(e.target.value)}
              placeholder="e.g. Engineering"
              autoFocus
              className="w-full rounded-md border border-input bg-background text-foreground px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-ring focus:border-transparent"
            />
          </div>
          {createError && (
            <p className="text-sm text-destructive" role="alert">{createError}</p>
          )}
          <div className="flex gap-3">
            <Button type="submit" disabled={createSubmitting}>
              {createSubmitting ? 'Creating…' : 'Create'}
            </Button>
            <Button
              type="button"
              variant="outline"
              onClick={() => setCreateOpen(false)}
              disabled={createSubmitting}
            >
              Cancel
            </Button>
          </div>
        </form>
      </Modal>
    </div>
  )
}
