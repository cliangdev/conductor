'use client'

export const dynamic = 'force-dynamic'

import { useCallback, useEffect, useRef, useState } from 'react'
import { MoreHorizontalIcon } from 'lucide-react'
import { Avatar, AvatarFallback } from '@/components/ui/avatar'
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

interface OrgInvite {
  id: string
  email: string
  role: string
  token: string
  status: string
  expiresAt: string
}

const ORG_ROLES: OrgMemberRole[] = ['ADMIN', 'MEMBER']

function memberInitials(name: string): string {
  return name
    .split(' ')
    .map((n) => n[0])
    .join('')
    .toUpperCase()
    .slice(0, 2)
}

function RolePill({
  role,
  canEdit,
  onRoleChange,
}: {
  role: OrgMemberRole
  canEdit: boolean
  onRoleChange: (role: OrgMemberRole) => void
}) {
  const [open, setOpen] = useState(false)
  const ref = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!open) return
    function handleClick(e: MouseEvent) {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false)
    }
    document.addEventListener('mousedown', handleClick)
    return () => document.removeEventListener('mousedown', handleClick)
  }, [open])

  const pillClass =
    role === 'ADMIN'
      ? 'bg-blue-100 text-blue-800 dark:bg-blue-900 dark:text-blue-200'
      : 'bg-gray-100 text-gray-700 dark:bg-gray-800 dark:text-gray-300'

  return (
    <div className="relative" ref={ref}>
      <button
        onClick={() => canEdit && setOpen((o) => !o)}
        className={`inline-flex items-center gap-1 rounded-full px-2.5 py-0.5 text-xs font-medium ${pillClass} ${canEdit ? 'cursor-pointer hover:opacity-80' : 'cursor-default'}`}
        aria-haspopup={canEdit ? 'listbox' : undefined}
      >
        {role.charAt(0) + role.slice(1).toLowerCase()}
        {canEdit && <span className="ml-0.5 text-[10px]">▼</span>}
      </button>

      {open && (
        <div className="absolute right-0 top-full mt-1 z-10 bg-popover border border-border rounded-md shadow-md py-1 min-w-[100px]">
          {ORG_ROLES.map((r) => (
            <button
              key={r}
              onClick={() => { onRoleChange(r); setOpen(false) }}
              className={`w-full text-left px-3 py-1.5 text-sm hover:bg-sidebar-hover transition-colors flex items-center gap-2 ${r === role ? 'font-semibold' : ''}`}
            >
              <span className={`h-2 w-2 rounded-full ${r === role ? 'bg-primary' : 'bg-transparent border border-muted-foreground'}`} />
              {r.charAt(0) + r.slice(1).toLowerCase()}
            </button>
          ))}
        </div>
      )}
    </div>
  )
}

function MemberRow({
  member,
  isCurrentUser,
  isAdmin,
  onRoleChange,
  onRemove,
}: {
  member: OrgMember
  isCurrentUser: boolean
  isAdmin: boolean
  onRoleChange: (userId: string, role: OrgMemberRole) => void
  onRemove: (userId: string, name: string) => void
}) {
  const initials = memberInitials(member.name)
  const canEditRole = isAdmin && !isCurrentUser

  return (
    <div className="flex items-center justify-between px-4 py-3">
      <div className="flex items-center gap-3 min-w-0">
        <Avatar className="h-8 w-8 shrink-0">
          <AvatarFallback className="text-xs">{initials}</AvatarFallback>
        </Avatar>
        <div className="min-w-0">
          <p className="text-sm font-medium text-foreground truncate">
            {member.name}
            {isCurrentUser && (
              <span className="ml-1 text-xs text-muted-foreground font-normal">(You)</span>
            )}
          </p>
          <p className="text-xs text-muted-foreground truncate">{member.email}</p>
        </div>
      </div>

      <div className="flex items-center gap-2 shrink-0 ml-4">
        <RolePill
          role={member.role}
          canEdit={canEditRole}
          onRoleChange={(role) => onRoleChange(member.userId, role)}
        />

        {isAdmin && (
          <div className="relative group">
            <button
              onClick={() => onRemove(member.userId, member.name)}
              className="p-1 rounded text-muted-foreground hover:text-destructive hover:bg-destructive/10 transition-colors"
              aria-label={isCurrentUser ? 'Leave org' : `Remove ${member.name}`}
            >
              <MoreHorizontalIcon className="h-4 w-4" />
            </button>
          </div>
        )}
      </div>
    </div>
  )
}

export default function OrgMembersPage() {
  const { accessToken, user } = useAuth()
  const { activeOrg } = useOrg()
  const { showToast } = useToast()

  const [members, setMembers] = useState<OrgMember[]>([])
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState<string | null>(null)

  const [pendingInvites, setPendingInvites] = useState<OrgInvite[]>([])

  const [inviteOpen, setInviteOpen] = useState(false)
  const [inviteEmail, setInviteEmail] = useState('')
  const [inviteRole, setInviteRole] = useState<OrgMemberRole>('MEMBER')
  const [inviteSubmitting, setInviteSubmitting] = useState(false)
  const [inviteError, setInviteError] = useState<string | null>(null)
  const [inviteLink, setInviteLink] = useState<string | null>(null)

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

  const fetchPendingInvites = useCallback(async () => {
    if (!accessToken || !activeOrg) return
    try {
      const data = await apiGet<OrgInvite[]>(`/api/v1/orgs/${activeOrg.id}/members/invites`, accessToken)
      setPendingInvites(data)
    } catch {
      // non-critical, silently ignore
    }
  }, [accessToken, activeOrg])

  useEffect(() => {
    fetchMembers()
    fetchPendingInvites()
  }, [fetchMembers, fetchPendingInvites])

  const currentUserMember = members.find((m) => m.userId === user?.id)
  const isAdmin = currentUserMember?.role === 'ADMIN'

  const admins = members.filter((m) => m.role === 'ADMIN')
  const regularMembers = members.filter((m) => m.role !== 'ADMIN')

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

  function openInvite() {
    setInviteEmail('')
    setInviteRole('MEMBER')
    setInviteError(null)
    setInviteLink(null)
    setInviteOpen(true)
  }

  function closeInviteModal() {
    setInviteOpen(false)
    setInviteLink(null)
    setInviteError(null)
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
      const result = await apiPost<OrgInvite>(
        `/api/v1/orgs/${activeOrg.id}/members/invite`,
        { email, role: inviteRole },
        accessToken,
      )
      setInviteLink(`${window.location.origin}/org-invites/${result.token}/accept`)
      setPendingInvites((prev) => [...prev, result])
    } catch (err) {
      const apiErr = err as ApiError
      if (apiErr.status === 400) {
        setInviteError('Invalid email address.')
      } else if (apiErr.status === 409) {
        setInviteError('An invite is already pending for this email, or they are already a member.')
      } else {
        setInviteError('Failed to create invite. Please try again.')
      }
    } finally {
      setInviteSubmitting(false)
    }
  }

  async function handleResendInvite(inviteId: string) {
    if (!accessToken || !activeOrg) return
    try {
      const result = await apiPost<OrgInvite>(
        `/api/v1/orgs/${activeOrg.id}/members/invites/${inviteId}/resend`,
        {},
        accessToken,
      )
      setPendingInvites((prev) => prev.map((inv) => (inv.id === inviteId ? result : inv)))
      showToast('Invite resent')
    } catch {
      showToast('Failed to resend invite.', 'error')
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
      <div className="mb-6 flex items-center justify-between">
        <div>
          <p className="text-xs text-muted-foreground mb-0.5">{orgName}</p>
          <h1 className="text-2xl font-bold text-foreground">
            Members {!loading && members.length > 0 && (
              <span className="text-muted-foreground font-normal text-lg">({members.length})</span>
            )}
          </h1>
        </div>
        {isAdmin && (
          <Button onClick={openInvite} size="sm">
            + Invite
          </Button>
        )}
      </div>

      {loading && <p className="text-sm text-muted-foreground">Loading members…</p>}
      {loadError && <p className="text-sm text-destructive" role="alert">{loadError}</p>}

      {!loading && !loadError && (
        <div className="bg-card rounded-lg border border-border">
          {members.length === 0 ? (
            <p className="text-sm text-muted-foreground p-4">No members yet.</p>
          ) : (
            <div>
              {/* Admins group */}
              {admins.map((member) => (
                <MemberRow
                  key={member.userId}
                  member={member}
                  isCurrentUser={member.userId === user?.id}
                  isAdmin={isAdmin}
                  onRoleChange={handleRoleChange}
                  onRemove={openRemoveConfirm}
                />
              ))}

              {/* Divider between admins and members */}
              {admins.length > 0 && regularMembers.length > 0 && (
                <div className="border-t border-border mx-4" />
              )}

              {/* Regular members group */}
              {regularMembers.map((member) => (
                <MemberRow
                  key={member.userId}
                  member={member}
                  isCurrentUser={member.userId === user?.id}
                  isAdmin={isAdmin}
                  onRoleChange={handleRoleChange}
                  onRemove={openRemoveConfirm}
                />
              ))}
            </div>
          )}
        </div>
      )}

      {/* Pending invites */}
      {isAdmin && pendingInvites.length > 0 && (
        <div className="mt-6">
          <h2 className="text-sm font-medium text-muted-foreground mb-2">Pending invitations</h2>
          <div className="bg-card rounded-lg border border-border divide-y divide-border">
            {pendingInvites.map((invite) => (
              <div key={invite.id} className="flex items-center justify-between px-4 py-3">
                <div className="min-w-0">
                  <p className="text-sm text-foreground truncate">{invite.email}</p>
                  <p className="text-xs text-muted-foreground">
                    {invite.role.charAt(0) + invite.role.slice(1).toLowerCase()} · expires {new Date(invite.expiresAt).toLocaleDateString()}
                  </p>
                </div>
                <Button
                  size="sm"
                  variant="outline"
                  onClick={() => handleResendInvite(invite.id)}
                  className="ml-4 shrink-0"
                >
                  Resend
                </Button>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* Invite modal */}
      <Modal
        open={inviteOpen}
        onOpenChange={(open) => { if (!open) closeInviteModal() }}
        title="Invite a member"
      >
        {inviteLink ? (
          <div className="space-y-4">
            <p className="text-sm font-medium text-foreground">Invite link created</p>
            <p className="text-xs text-muted-foreground">Share this link with the person you want to invite to {orgName}.</p>
            <div className="flex gap-2">
              <input
                readOnly
                value={inviteLink}
                className="flex-1 rounded-md border border-input bg-muted text-foreground px-3 py-2 text-sm focus:outline-none"
              />
              <Button
                type="button"
                size="sm"
                onClick={() => {
                  navigator.clipboard.writeText(inviteLink)
                  showToast('Link copied to clipboard')
                }}
              >
                Copy
              </Button>
            </div>
            <p className="text-xs text-muted-foreground">Expires in 72 hours.</p>
            <Button type="button" variant="outline" onClick={closeInviteModal}>
              Close
            </Button>
          </div>
        ) : (
          <form onSubmit={handleInviteSubmit} noValidate className="flex flex-col gap-4">
            <div>
              <label htmlFor="invite-email" className="block text-sm font-medium text-foreground mb-1">
                Email address
              </label>
              <input
                id="invite-email"
                type="email"
                value={inviteEmail}
                onChange={(e) => setInviteEmail(e.target.value)}
                placeholder="colleague@example.com"
                autoFocus
                className="w-full rounded-md border border-input bg-background text-foreground px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-ring focus:border-transparent"
              />
            </div>
            <div>
              <label htmlFor="invite-role" className="block text-sm font-medium text-foreground mb-1">
                Role
              </label>
              <select
                id="invite-role"
                value={inviteRole}
                onChange={(e) => setInviteRole(e.target.value as OrgMemberRole)}
                className="w-full rounded-md border border-input bg-background text-foreground px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-ring"
              >
                {ORG_ROLES.map((role) => (
                  <option key={role} value={role}>
                    {role.charAt(0) + role.slice(1).toLowerCase()}
                  </option>
                ))}
              </select>
            </div>
            {inviteError && (
              <p className="text-sm text-destructive" role="alert">{inviteError}</p>
            )}
            <div className="flex gap-3">
              <Button type="submit" disabled={inviteSubmitting}>
                {inviteSubmitting ? 'Creating…' : 'Create invite link'}
              </Button>
              <Button type="button" variant="outline" onClick={closeInviteModal} disabled={inviteSubmitting}>
                Cancel
              </Button>
            </div>
          </form>
        )}
      </Modal>

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
