'use client'

export const dynamic = 'force-dynamic'

import { useCallback, useEffect, useState } from 'react'
import { Avatar, AvatarFallback } from '@/components/ui/avatar'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Modal } from '@/components/ui/modal'
import { useToast } from '@/components/ui/toast'
import { useAuth } from '@/contexts/AuthContext'
import { useOrg } from '@/contexts/OrgContext'
import { apiDelete, apiGet, apiPatch, apiPost } from '@/lib/api'
import type { OrgMember, OrgMemberRole } from '@/types'

interface ApiError extends Error {
  status?: number
}

const ORG_ROLES: OrgMemberRole[] = ['ADMIN', 'MEMBER']

function roleBadgeClass(role: OrgMemberRole): string {
  return role === 'ADMIN'
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

export default function OrgMembersPage() {
  const { accessToken, user } = useAuth()
  const { activeOrg } = useOrg()
  const { showToast } = useToast()

  const [members, setMembers] = useState<OrgMember[]>([])
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState<string | null>(null)

  const [inviteEmail, setInviteEmail] = useState('')
  const [inviteRole, setInviteRole] = useState<OrgMemberRole>('MEMBER')
  const [inviteSubmitting, setInviteSubmitting] = useState(false)
  const [inviteError, setInviteError] = useState<string | null>(null)

  const [removeConfirm, setRemoveConfirm] = useState<{ userId: string; name: string } | null>(null)
  const [removeError, setRemoveError] = useState<string | null>(null)
  const [removeSubmitting, setRemoveSubmitting] = useState(false)

  const fetchMembers = useCallback(async () => {
    if (!accessToken || !activeOrg) return
    setLoading(true)
    try {
      const data = await apiGet<OrgMember[]>(`/api/v1/orgs/${activeOrg.id}/members`, accessToken)
      setMembers(data)
      setLoadError(null)
    } catch {
      setLoadError('Failed to load members.')
    } finally {
      setLoading(false)
    }
  }, [accessToken, activeOrg])

  useEffect(() => {
    fetchMembers()
  }, [fetchMembers])

  const currentUserMember = members.find((m) => m.userId === user?.id)
  const isAdmin = currentUserMember?.role === 'ADMIN'

  async function handleRoleChange(userId: string, role: OrgMemberRole) {
    if (!accessToken || !activeOrg) return
    try {
      const updated = await apiPatch<OrgMember>(
        `/api/v1/orgs/${activeOrg.id}/members/${userId}`,
        { role },
        accessToken,
      )
      setMembers((prev) => prev.map((m) => (m.userId === userId ? { ...m, role: updated.role } : m)))
    } catch {
      showToast('Failed to update role. Please try again.', 'error')
    }
  }

  function openRemoveConfirm(userId: string, name: string) {
    setRemoveConfirm({ userId, name })
    setRemoveError(null)
  }

  async function handleRemove() {
    if (!removeConfirm || !accessToken || !activeOrg) return
    setRemoveSubmitting(true)
    try {
      await apiDelete(`/api/v1/orgs/${activeOrg.id}/members/${removeConfirm.userId}`, accessToken)
      setMembers((prev) => prev.filter((m) => m.userId !== removeConfirm.userId))
      setRemoveConfirm(null)
    } catch (err) {
      const apiErr = err as ApiError
      if (apiErr.status === 400) {
        setRemoveError('Cannot remove the last admin.')
      } else {
        setRemoveError('Failed to remove member. Please try again.')
      }
    } finally {
      setRemoveSubmitting(false)
    }
  }

  async function handleInviteSubmit(e: React.FormEvent) {
    e.preventDefault()
    if (!accessToken || !activeOrg) return
    const email = inviteEmail.trim()
    if (!email) {
      setInviteError('Email is required.')
      return
    }
    setInviteSubmitting(true)
    setInviteError(null)
    try {
      await apiPost<{ message: string }>(
        `/api/v1/orgs/${activeOrg.id}/members/invite`,
        { email, role: inviteRole },
        accessToken,
      )
      showToast(`Invitation sent to ${email}`)
      setInviteEmail('')
      setInviteRole('MEMBER')
    } catch (err) {
      const apiErr = err as ApiError
      if (apiErr.status === 400) {
        setInviteError('Invalid email address.')
      } else if (apiErr.status === 409) {
        setInviteError('An invite is already pending for this email.')
      } else {
        setInviteError('Failed to send invite. Please try again.')
      }
    } finally {
      setInviteSubmitting(false)
    }
  }

  const orgName = activeOrg?.name ?? 'your organization'

  if (!activeOrg) {
    return (
      <div className="max-w-2xl mx-auto px-4 py-8">
        <p className="text-sm text-muted-foreground">No organization selected.</p>
      </div>
    )
  }

  return (
    <div className="max-w-2xl mx-auto px-4 py-8">
      <div className="mb-6">
        <h1 className="text-2xl font-bold text-foreground">Organization Members</h1>
        <p className="text-sm text-muted-foreground mt-1">{orgName}</p>
      </div>

      {/* Invite section — ADMIN only */}
      {isAdmin && (
        <div className="mb-8 bg-card rounded-lg border border-border p-4">
          <h2 className="text-sm font-semibold text-foreground mb-3">Invite a member</h2>
          <form onSubmit={handleInviteSubmit} noValidate className="flex flex-col sm:flex-row gap-2">
            <input
              type="email"
              value={inviteEmail}
              onChange={(e) => setInviteEmail(e.target.value)}
              placeholder="colleague@example.com"
              aria-label="Email address to invite"
              className="flex-1 rounded-md border border-input bg-background text-foreground px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-ring focus:border-transparent"
            />
            <select
              value={inviteRole}
              onChange={(e) => setInviteRole(e.target.value as OrgMemberRole)}
              aria-label="Role for invited member"
              className="rounded-md border border-input bg-background text-foreground px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-ring"
            >
              {ORG_ROLES.map((role) => (
                <option key={role} value={role}>
                  {role.charAt(0) + role.slice(1).toLowerCase()}
                </option>
              ))}
            </select>
            <Button type="submit" disabled={inviteSubmitting} size="sm">
              {inviteSubmitting ? 'Sending…' : 'Send Invite'}
            </Button>
          </form>
          {inviteError && (
            <p className="mt-2 text-sm text-destructive" role="alert">{inviteError}</p>
          )}
        </div>
      )}

      {/* Members list */}
      {loading && (
        <p className="text-sm text-muted-foreground">Loading members…</p>
      )}

      {loadError && (
        <p className="text-sm text-destructive" role="alert">{loadError}</p>
      )}

      {!loading && !loadError && (
        <div className="bg-card rounded-lg border border-border">
          {members.length === 0 ? (
            <p className="text-sm text-muted-foreground p-4">No members yet.</p>
          ) : (
            <div className="divide-y divide-border">
              {members.map((member) => {
                const isCurrentUser = member.userId === user?.id
                const initials = memberInitials(member.name)
                return (
                  <div key={member.userId} className="flex items-center justify-between px-4 py-3">
                    <div className="flex items-center gap-3 min-w-0">
                      <Avatar className="h-9 w-9 shrink-0">
                        <AvatarFallback>{initials}</AvatarFallback>
                      </Avatar>
                      <div className="min-w-0">
                        <p className="text-sm font-medium text-foreground truncate">
                          {member.name}
                          {isCurrentUser && (
                            <span className="ml-1 text-xs text-muted-foreground">(You)</span>
                          )}
                        </p>
                        <p className="text-xs text-muted-foreground truncate">{member.email}</p>
                      </div>
                    </div>

                    <div className="flex items-center gap-3 shrink-0 ml-4">
                      {isAdmin && !isCurrentUser ? (
                        <select
                          value={member.role}
                          onChange={(e) => handleRoleChange(member.userId, e.target.value as OrgMemberRole)}
                          aria-label={`Role for ${member.name}`}
                          className="text-sm border border-input bg-background text-foreground rounded-md px-2 py-1 focus:outline-none focus:ring-2 focus:ring-ring"
                        >
                          {ORG_ROLES.map((role) => (
                            <option key={role} value={role}>
                              {role.charAt(0) + role.slice(1).toLowerCase()}
                            </option>
                          ))}
                        </select>
                      ) : (
                        <span
                          className={`inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-medium ${roleBadgeClass(member.role)}`}
                        >
                          {member.role.charAt(0) + member.role.slice(1).toLowerCase()}
                        </span>
                      )}

                      {isAdmin && (
                        <Button
                          variant="ghost"
                          size="sm"
                          onClick={() => openRemoveConfirm(member.userId, member.name)}
                          className="text-destructive hover:text-destructive hover:bg-destructive/10"
                          aria-label={isCurrentUser ? 'Leave org' : `Remove ${member.name}`}
                        >
                          {isCurrentUser ? 'Leave org' : 'Remove'}
                        </Button>
                      )}
                    </div>
                  </div>
                )
              })}
            </div>
          )}
        </div>
      )}

      {/* Remove / Leave confirm modal */}
      <Modal
        open={removeConfirm !== null}
        onOpenChange={(open) => { if (!open) setRemoveConfirm(null) }}
        title={removeConfirm?.userId === user?.id ? 'Leave organization' : 'Remove member'}
      >
        <p className="text-sm text-foreground">
          {removeConfirm?.userId === user?.id
            ? `Leave ${orgName}?`
            : <>Remove <strong>{removeConfirm?.name}</strong> from {orgName}?</>
          }
        </p>
        {removeError && (
          <p className="mt-2 text-sm text-destructive" role="alert">{removeError}</p>
        )}
        <div className="flex gap-3 mt-4">
          <Button variant="destructive" onClick={handleRemove} disabled={removeSubmitting}>
            {removeSubmitting
              ? 'Removing…'
              : removeConfirm?.userId === user?.id
                ? 'Leave'
                : 'Remove'
            }
          </Button>
          <Button variant="outline" onClick={() => setRemoveConfirm(null)} disabled={removeSubmitting}>
            Cancel
          </Button>
        </div>
      </Modal>
    </div>
  )
}
