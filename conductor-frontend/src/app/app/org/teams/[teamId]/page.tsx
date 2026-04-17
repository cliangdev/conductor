'use client'

export const dynamic = 'force-dynamic'

import { useCallback, useEffect, useState } from 'react'
import { useParams } from 'next/navigation'
import Link from 'next/link'
import { Avatar, AvatarFallback } from '@/components/ui/avatar'
import { Button } from '@/components/ui/button'
import { Modal } from '@/components/ui/modal'
import { useToast } from '@/components/ui/toast'
import { useAuth } from '@/contexts/AuthContext'
import { useOrg } from '@/contexts/OrgContext'
import { apiDelete, apiGet, apiPost } from '@/lib/api'
import type { OrgMember, Project, Team, TeamMember, TeamMemberRole } from '@/types'

interface ApiError extends Error {
  status?: number
}

const TEAM_ROLES: TeamMemberRole[] = ['LEAD', 'MEMBER']

function memberInitials(name: string): string {
  return name
    .split(' ')
    .map((n) => n[0])
    .join('')
    .toUpperCase()
    .slice(0, 2)
}

function visibilityLabel(visibility: string | undefined): string {
  switch (visibility) {
    case 'TEAM': return 'Team only'
    case 'PRIVATE': return 'Private'
    case 'PUBLIC': return 'Public'
    default: return 'Visible to org'
  }
}

export default function TeamDetailPage() {
  const params = useParams()
  const teamId = params.teamId as string
  const { accessToken, user } = useAuth()
  const { activeOrg } = useOrg()
  const { showToast } = useToast()

  const [team, setTeam] = useState<Team | null>(null)
  const [members, setMembers] = useState<TeamMember[]>([])
  const [orgMembers, setOrgMembers] = useState<OrgMember[]>([])
  const [teamProjects, setTeamProjects] = useState<Project[]>([])
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState<string | null>(null)

  const [addOpen, setAddOpen] = useState(false)
  const [addUserId, setAddUserId] = useState('')
  const [addRole, setAddRole] = useState<TeamMemberRole>('MEMBER')
  const [addError, setAddError] = useState<string | null>(null)
  const [addSubmitting, setAddSubmitting] = useState(false)

  const [removeConfirm, setRemoveConfirm] = useState<{ userId: string; name: string } | null>(null)
  const [removeError, setRemoveError] = useState<string | null>(null)
  const [removeSubmitting, setRemoveSubmitting] = useState(false)

  const fetchTeamData = useCallback(async () => {
    if (!accessToken || !activeOrg) return
    setLoading(true)
    try {
      const [teamsData, membersData, orgMembersData, projectsData] = await Promise.all([
        apiGet<Team[]>(`/api/v1/orgs/${activeOrg.id}/teams`, accessToken),
        apiGet<TeamMember[]>(`/api/v1/teams/${teamId}/members`, accessToken),
        apiGet<OrgMember[]>(`/api/v1/orgs/${activeOrg.id}/members`, accessToken),
        apiGet<Project[]>('/api/v1/projects', accessToken),
      ])
      const found = teamsData.find((t) => t.id === teamId) ?? null
      setTeam(found)
      setMembers(membersData)
      setOrgMembers(orgMembersData)
      setTeamProjects(projectsData.filter((p) => p.teamId === teamId))
      setLoadError(null)
    } catch {
      setLoadError('Failed to load team data.')
    } finally {
      setLoading(false)
    }
  }, [accessToken, activeOrg, teamId])

  useEffect(() => {
    fetchTeamData()
  }, [fetchTeamData])

  const currentOrgMember = orgMembers.find((m) => m.userId === user?.id)
  const currentTeamMember = members.find((m) => m.userId === user?.id)
  const canManage = currentOrgMember?.role === 'ADMIN' || currentTeamMember?.role === 'LEAD'

  const memberUserIds = new Set(members.map((m) => m.userId))
  const eligibleOrgMembers = orgMembers.filter((m) => !memberUserIds.has(m.userId))

  function openAdd() {
    setAddUserId(eligibleOrgMembers[0]?.userId ?? '')
    setAddRole('MEMBER')
    setAddError(null)
    setAddOpen(true)
  }

  async function handleAdd(e: React.FormEvent) {
    e.preventDefault()
    if (!addUserId) {
      setAddError('Please select a member.')
      return
    }
    if (!accessToken) return
    setAddSubmitting(true)
    setAddError(null)
    try {
      const added = await apiPost<TeamMember>(
        `/api/v1/teams/${teamId}/members`,
        { userId: addUserId, role: addRole },
        accessToken,
      )
      setMembers((prev) => [...prev, added])
      setAddOpen(false)
      showToast(`${added.name} added to team.`)
    } catch (err) {
      const apiErr = err as ApiError
      if (apiErr.status === 409) {
        setAddError('This member is already in the team.')
      } else {
        setAddError('Failed to add member. Please try again.')
      }
    } finally {
      setAddSubmitting(false)
    }
  }

  async function handleRemove() {
    if (!removeConfirm || !accessToken) return
    setRemoveSubmitting(true)
    try {
      await apiDelete(`/api/v1/teams/${teamId}/members/${removeConfirm.userId}`, accessToken)
      setMembers((prev) => prev.filter((m) => m.userId !== removeConfirm.userId))
      setRemoveConfirm(null)
      showToast('Member removed from team.')
    } catch {
      setRemoveError('Failed to remove member. Please try again.')
    } finally {
      setRemoveSubmitting(false)
    }
  }

  if (!activeOrg) {
    return (
      <div className="max-w-4xl mx-auto px-4 py-8">
        <p className="text-sm text-muted-foreground">No organization selected.</p>
      </div>
    )
  }

  return (
    <div className="max-w-4xl mx-auto px-4 py-8">
      <div className="mb-4">
        <Link
          href="/app/org/teams"
          className="text-sm text-muted-foreground hover:text-foreground transition-colors"
        >
          &larr; Back to Teams
        </Link>
      </div>

      {loading && <p className="text-sm text-muted-foreground">Loading team…</p>}
      {loadError && <p className="text-sm text-destructive" role="alert">{loadError}</p>}

      {!loading && !loadError && (
        <>
          <div className="mb-6">
            <p className="text-xs text-muted-foreground mb-0.5">{activeOrg.name}</p>
            <h1 className="text-2xl font-bold text-foreground">{team?.name ?? 'Team'}</h1>
          </div>

          {/* Two-column layout */}
          <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
            {/* Members column */}
            <div>
              <div className="flex items-center justify-between mb-3">
                <h2 className="text-sm font-semibold text-foreground">
                  Members ({members.length})
                </h2>
                {canManage && eligibleOrgMembers.length > 0 && (
                  <Button onClick={openAdd} size="sm" variant="outline">
                    + Add member
                  </Button>
                )}
              </div>

              <div className="bg-card rounded-lg border border-border">
                {members.length === 0 ? (
                  <p className="text-sm text-muted-foreground p-4">No members yet.</p>
                ) : (
                  <div className="divide-y divide-border">
                    {members.map((member) => (
                      <div key={member.userId} className="flex items-center justify-between px-4 py-3">
                        <div className="flex items-center gap-3 min-w-0">
                          <Avatar className="h-8 w-8 shrink-0">
                            <AvatarFallback className="text-xs">{memberInitials(member.name)}</AvatarFallback>
                          </Avatar>
                          <div className="min-w-0">
                            <p className="text-sm font-medium text-foreground truncate">
                              {member.name}
                              {member.userId === user?.id && (
                                <span className="ml-1 text-xs text-muted-foreground font-normal">(You)</span>
                              )}
                            </p>
                          </div>
                        </div>
                        <div className="flex items-center gap-2 shrink-0 ml-3">
                          <span className="text-xs text-muted-foreground uppercase tracking-wide">
                            {member.role}
                          </span>
                          {canManage && (
                            <button
                              onClick={() => {
                                setRemoveConfirm({ userId: member.userId, name: member.name })
                                setRemoveError(null)
                              }}
                              className="text-xs text-muted-foreground hover:text-destructive transition-colors px-1"
                              aria-label={`Remove ${member.name}`}
                            >
                              ×
                            </button>
                          )}
                        </div>
                      </div>
                    ))}
                  </div>
                )}
              </div>
            </div>

            {/* Projects column */}
            <div>
              <div className="mb-3">
                <h2 className="text-sm font-semibold text-foreground">
                  Projects ({teamProjects.length})
                </h2>
              </div>

              <div className="bg-card rounded-lg border border-border">
                {teamProjects.length === 0 ? (
                  <p className="text-sm text-muted-foreground p-4">
                    No projects assigned to this team.
                  </p>
                ) : (
                  <div className="divide-y divide-border">
                    {teamProjects.map((project) => (
                      <Link
                        key={project.id}
                        href={`/app/projects/${project.id}/issues`}
                        className="flex items-center justify-between px-4 py-3 hover:bg-sidebar-hover transition-colors"
                      >
                        <div className="min-w-0">
                          <p className="text-sm font-medium text-foreground truncate">{project.name}</p>
                          <p className="text-xs text-muted-foreground mt-0.5">
                            {visibilityLabel(project.visibility)}
                          </p>
                        </div>
                        <span className="text-muted-foreground text-sm ml-3">→</span>
                      </Link>
                    ))}
                  </div>
                )}
              </div>
            </div>
          </div>
        </>
      )}

      {/* Add member modal */}
      <Modal
        open={addOpen}
        onOpenChange={(open) => { if (!open) setAddOpen(false) }}
        title="Add Member"
      >
        <form onSubmit={handleAdd} noValidate className="flex flex-col gap-4">
          <div>
            <label htmlFor="add-member-select" className="block text-sm font-medium text-foreground mb-1">
              Member
            </label>
            <select
              id="add-member-select"
              value={addUserId}
              onChange={(e) => setAddUserId(e.target.value)}
              className="w-full rounded-md border border-input bg-background text-foreground px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-ring"
            >
              {eligibleOrgMembers.length === 0 ? (
                <option value="">No eligible members</option>
              ) : (
                eligibleOrgMembers.map((m) => (
                  <option key={m.userId} value={m.userId}>
                    {m.name} ({m.email})
                  </option>
                ))
              )}
            </select>
          </div>
          <div>
            <label htmlFor="add-role-select" className="block text-sm font-medium text-foreground mb-1">
              Role
            </label>
            <select
              id="add-role-select"
              value={addRole}
              onChange={(e) => setAddRole(e.target.value as TeamMemberRole)}
              className="w-full rounded-md border border-input bg-background text-foreground px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-ring"
            >
              {TEAM_ROLES.map((role) => (
                <option key={role} value={role}>
                  {role.charAt(0) + role.slice(1).toLowerCase()}
                </option>
              ))}
            </select>
          </div>
          {addError && (
            <p className="text-sm text-destructive" role="alert">{addError}</p>
          )}
          <div className="flex gap-3">
            <Button type="submit" disabled={addSubmitting || eligibleOrgMembers.length === 0}>
              {addSubmitting ? 'Adding…' : 'Add'}
            </Button>
            <Button type="button" variant="outline" onClick={() => setAddOpen(false)} disabled={addSubmitting}>
              Cancel
            </Button>
          </div>
        </form>
      </Modal>

      {/* Remove confirm modal */}
      <Modal
        open={removeConfirm !== null}
        onOpenChange={(open) => { if (!open) setRemoveConfirm(null) }}
        title="Remove member"
      >
        <p className="text-sm text-foreground">
          Remove <strong>{removeConfirm?.name}</strong> from this team?
        </p>
        {removeError && (
          <p className="mt-2 text-sm text-destructive" role="alert">{removeError}</p>
        )}
        <div className="flex gap-3 mt-4">
          <Button variant="destructive" onClick={handleRemove} disabled={removeSubmitting}>
            {removeSubmitting ? 'Removing…' : 'Remove'}
          </Button>
          <Button variant="outline" onClick={() => setRemoveConfirm(null)} disabled={removeSubmitting}>
            Cancel
          </Button>
        </div>
      </Modal>
    </div>
  )
}
