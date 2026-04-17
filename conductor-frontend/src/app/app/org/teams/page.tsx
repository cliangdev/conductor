'use client'

export const dynamic = 'force-dynamic'

import { useCallback, useEffect, useState } from 'react'
import Link from 'next/link'
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

interface TeamWithCount extends Team {
  memberCount: number
}

function fetchTeamMemberCount(teamId: string, token: string): Promise<number> {
  return apiGet<{ userId: string }[]>(`/api/v1/teams/${teamId}/members`, token).then(
    (members) => members.length,
  )
}

export default function TeamsPage() {
  const { accessToken, user } = useAuth()
  const { activeOrg } = useOrg()
  const { showToast } = useToast()

  const [teams, setTeams] = useState<TeamWithCount[]>([])
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
      // non-critical, used for role check
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
      setTeams((prev) => [...prev, { ...created, memberCount: 0 }])
      setCreateOpen(false)
      showToast(`Team "${created.name}" created.`)
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
          <h1 className="text-2xl font-bold text-foreground">Teams</h1>
          <p className="text-sm text-muted-foreground mt-1">{activeOrg.name}</p>
        </div>
        {isAdmin && (
          <Button onClick={openCreate} size="sm">
            Create Team
          </Button>
        )}
      </div>

      {loading && (
        <p className="text-sm text-muted-foreground">Loading teams…</p>
      )}

      {loadError && (
        <p className="text-sm text-destructive" role="alert">{loadError}</p>
      )}

      {!loading && !loadError && (
        <div className="bg-card rounded-lg border border-border">
          {teams.length === 0 ? (
            <p className="text-sm text-muted-foreground p-4">No teams yet.</p>
          ) : (
            <div className="divide-y divide-border">
              {teams.map((team) => (
                <div key={team.id} className="flex items-center justify-between px-4 py-3">
                  <div className="min-w-0">
                    <p className="text-sm font-medium text-foreground truncate">{team.name}</p>
                    <p className="text-xs text-muted-foreground">
                      {team.memberCount} {team.memberCount === 1 ? 'member' : 'members'}
                    </p>
                  </div>
                  <Link
                    href={`/app/org/teams/${team.id}`}
                    className="shrink-0 ml-4 text-sm font-medium text-primary hover:underline"
                  >
                    Manage
                  </Link>
                </div>
              ))}
            </div>
          )}
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
              placeholder="e.g. Frontend"
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
