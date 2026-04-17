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
import type { OrgMember, Team, TeamMember, TeamMemberRole } from '@/types'

interface ApiError extends Error {
  status?: number
}

const TEAM_ROLES: TeamMemberRole[] = ['LEAD', 'MEMBER']

function roleBadgeClass(role: TeamMemberRole): string {
  return role === 'LEAD'
    ? 'bg-blue-100 text-blue-800 dark:bg-blue-900 dark:text-blue-200'
    : 'bg-gray-100 text-gray-700 dark:bg-gray-800 dark:text-gray-300'
}

function memberInitials(name: string): string {
  return name
    .split(' ')
    .map((n) => n[0])
    .join('')
    .toUpperCase()
    .slice(0, 2)
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
      const [teamsData, membersData, orgMembersData] = await Promise.all([
        apiGet<Team[]>(`/api/v1/orgs/${activeOrg.id}/teams`, accessToken),
        apiGet<TeamMember[]>(`/api/v1/teams/${teamId}/members`, accessToken),
        apiGet<OrgMember[]>(`/api/v1/orgs/${activeOrg.id}/members`, accessToken),
      ])
      const found = teamsData.find((t) => t.id === teamId) ?? null
      setTeam(found)
      setMembers(membersData)
      setOrgMembers(orgMembersData)
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
      <div className="max-w-2xl mx-auto px-4 py-8">
        <p className="text-sm text-muted-foreground">No organization selected.</p>
      </div>
    )
  }

  return (
    <div className="max-w-2xl mx-auto px-4 py-8">
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
          <div className="mb-6 flex items-center justify-between">
            <div>
              <h1 className="text-2xl font-bold text-foreground">
                {team?.name ?? 'Team'}
              </h1>
              <p className="text-sm text-muted-foreground mt-1">{activeOrg.name}</p>
            </div>
            {canManage && eligibleOrgMembers.length > 0 && (
              <Button onClick={openAdd} size="sm">
                Add Member
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
                      <Avatar className="h-9 w-9 shrink-0">
                        <AvatarFallback>{memberInitials(member.name)}</AvatarFallback>
                      </Avatar>
                      <div className="min-w-0">
                        <p className="text-sm font-medium text-foreground truncate">
                          {member.name}
                          {member.userId === user?.id && (
                            <span className="ml-1 text-xs text-muted-foreground">(You)</span>
                          )}
                        </p>
                        <p className="text-xs text-muted-foreground truncate">{member.email}</p>
                      </div>
                    </div>
                    <div className="flex items-center gap-3 shrink-0 ml-4">
                      <span
                        className={`inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-medium ${roleBadgeClass(member.role)}`}
                      >
                        {member.role.charAt(0) + member.role.slice(1).toLowerCase()}
                      </span>
                      {canManage && (
                        <Button
                          variant="ghost"
                          size="sm"
                          onClick={() => {
                            setRemoveConfirm({ userId: member.userId, name: member.name })
                            setRemoveError(null)
                          }}
                          className="text-destructive hover:text-destructive hover:bg-destructive/10"
                          aria-label={`Remove ${member.name}`}
                        >
                          Remove
                        </Button>
                      )}
                    </div>
                  </div>
                ))}
              </div>
            )}
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
            <Button
              type="button"
              variant="outline"
              onClick={() => setAddOpen(false)}
              disabled={addSubmitting}
            >
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
