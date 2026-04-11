'use client'

export const dynamic = 'force-dynamic'

import { useCallback, useEffect, useState } from 'react'
import { useParams } from 'next/navigation'
import { Button } from '@/components/ui/button'
import { Modal } from '@/components/ui/modal'
import { useToast } from '@/components/ui/toast'
import { MemberRow } from '@/components/members/MemberRow'
import { useAuth } from '@/contexts/AuthContext'
import { useProject } from '@/contexts/ProjectContext'
import { apiDelete, apiGet, apiPatch, apiPost } from '@/lib/api'
import type { Invite, Member, MemberRole } from '@/types'

interface ApiError extends Error {
  status?: number
}

const INVITEABLE_ROLES: MemberRole[] = ['CREATOR', 'REVIEWER']

export default function MembersPage() {
  const params = useParams()
  const projectId = params.projectId as string
  const { accessToken, user } = useAuth()
  const { activeProject } = useProject()
  const { showToast } = useToast()

  const [members, setMembers] = useState<Member[]>([])
  const [invites, setInvites] = useState<Invite[]>([])
  const [membersLoading, setMembersLoading] = useState(true)
  const [membersError, setMembersError] = useState<string | null>(null)

  const [inviteOpen, setInviteOpen] = useState(false)
  const [inviteEmail, setInviteEmail] = useState('')
  const [inviteRole, setInviteRole] = useState<MemberRole>('CREATOR')
  const [inviteError, setInviteError] = useState<string | null>(null)
  const [inviteSubmitting, setInviteSubmitting] = useState(false)
  const [createdInvite, setCreatedInvite] = useState<Invite | null>(null)

  const [removeConfirm, setRemoveConfirm] = useState<{ userId: string; name: string } | null>(null)
  const [removeError, setRemoveError] = useState<string | null>(null)

  const fetchMembers = useCallback(async () => {
    if (!accessToken) return
    try {
      const data = await apiGet<Member[]>(`/api/v1/projects/${projectId}/members`, accessToken)
      setMembers(data)
      setMembersError(null)
    } catch {
      setMembersError('Failed to load members.')
    } finally {
      setMembersLoading(false)
    }
  }, [accessToken, projectId])

  const fetchInvites = useCallback(async () => {
    if (!accessToken) return
    try {
      const data = await apiGet<Invite[]>(`/api/v1/projects/${projectId}/invites`, accessToken)
      setInvites(data)
    } catch {
      // Silently ignore — invites are secondary
    }
  }, [accessToken, projectId])

  useEffect(() => {
    fetchMembers()
  }, [fetchMembers])

  const currentUserRole = members.find((m) => m.userId === user?.id)?.role
  const isAdmin = currentUserRole === 'ADMIN'

  useEffect(() => {
    if (isAdmin) fetchInvites()
  }, [isAdmin, fetchInvites])

  async function handleRoleChange(userId: string, role: MemberRole) {
    if (!accessToken) return
    try {
      const updated = await apiPatch<Member>(
        `/api/v1/projects/${projectId}/members/${userId}`,
        { role },
        accessToken,
      )
      setMembers((prev) => prev.map((m) => (m.userId === userId ? { ...m, role: updated.role } : m)))
      showToast('Role updated successfully')
    } catch (err) {
      const apiErr = err as ApiError
      if (apiErr.status === 403) {
        showToast('You do not have permission to change roles.', 'error')
      } else {
        showToast('Failed to update role. Please try again.', 'error')
      }
    }
  }

  function openRemoveConfirm(userId: string, name: string) {
    setRemoveConfirm({ userId, name })
    setRemoveError(null)
  }

  async function handleRemove() {
    if (!removeConfirm || !accessToken) return
    try {
      await apiDelete(
        `/api/v1/projects/${projectId}/members/${removeConfirm.userId}`,
        accessToken,
      )
      setMembers((prev) => prev.filter((m) => m.userId !== removeConfirm.userId))
      setRemoveConfirm(null)
    } catch (err) {
      const apiErr = err as ApiError
      if (apiErr.status === 400) {
        setRemoveError('Cannot remove the last admin from the project.')
      } else {
        setRemoveError('Failed to remove member. Please try again.')
      }
    }
  }

  function openInviteModal() {
    setInviteEmail('')
    setInviteRole('CREATOR')
    setInviteError(null)
    setCreatedInvite(null)
    setInviteOpen(true)
  }

  function closeInviteModal() {
    setInviteOpen(false)
    setCreatedInvite(null)
  }

  async function handleInviteSubmit(e: React.FormEvent) {
    e.preventDefault()
    if (!accessToken) return
    if (!inviteEmail.trim()) {
      setInviteError('Email is required')
      return
    }

    setInviteSubmitting(true)
    setInviteError(null)
    try {
      const result = await apiPost<Invite>(
        `/api/v1/projects/${projectId}/invites`,
        { email: inviteEmail.trim(), role: inviteRole },
        accessToken,
      )
      setCreatedInvite(result)
      fetchInvites()
    } catch (err) {
      const apiErr = err as ApiError
      if (apiErr.status === 409) {
        setInviteError('An invite is already pending for this email')
      } else if (apiErr.status === 400) {
        setInviteError('Invalid email address')
      } else if (apiErr.status === 403) {
        setInviteError('You need admin access to invite members.')
      } else {
        setInviteError('Failed to send invite. Please try again.')
      }
    } finally {
      setInviteSubmitting(false)
    }
  }

  async function handleCancelInvite(inviteId: string) {
    if (!accessToken) return
    try {
      await apiDelete(`/api/v1/projects/${projectId}/invites/${inviteId}`, accessToken)
      setInvites((prev) => prev.filter((i) => i.id !== inviteId))
    } catch {
      showToast('Failed to cancel invite.', 'error')
    }
  }

  function copyInviteLink(token: string) {
    const link = `${window.location.origin}/invites/${token}/accept`
    navigator.clipboard.writeText(link).then(() => {
      showToast('Invite link copied to clipboard')
    }).catch(() => {
      showToast('Failed to copy link', 'error')
    })
  }

  const projectName = activeProject?.name ?? 'this project'

  return (
    <div className="max-w-2xl mx-auto px-4 py-8">
      <div className="flex items-center justify-between mb-6">
        <h1 className="text-2xl font-bold text-foreground">Members</h1>
        {isAdmin && (
          <Button onClick={openInviteModal} size="sm">
            Invite Member
          </Button>
        )}
      </div>

      {membersLoading && (
        <p className="text-sm text-muted-foreground">Loading members…</p>
      )}

      {membersError && (
        <p className="text-sm text-destructive" role="alert">{membersError}</p>
      )}

      {!membersLoading && !membersError && (
        <div className="bg-card rounded-lg border border-border">
          {members.length === 0 ? (
            <p className="text-sm text-muted-foreground p-4">No members yet.</p>
          ) : (
            <div className="px-4">
              {members.map((member) => (
                <MemberRow
                  key={member.userId}
                  member={member}
                  isAdmin={isAdmin}
                  currentUserId={user?.id ?? ''}
                  onRoleChange={handleRoleChange}
                  onRemove={openRemoveConfirm}
                />
              ))}
            </div>
          )}
        </div>
      )}

      {isAdmin && invites.length > 0 && (
        <div className="mt-8">
          <h2 className="text-lg font-semibold text-foreground mb-3">Pending Invites</h2>
          <div className="bg-card rounded-lg border border-border divide-y divide-border">
            {invites.map((invite) => (
              <div key={invite.id} className="flex items-center justify-between px-4 py-3">
                <div>
                  <p className="text-sm text-foreground">{invite.email}</p>
                  <p className="text-xs text-muted-foreground">
                    {invite.role} · Expires{' '}
                    {new Date(invite.expiresAt).toLocaleDateString('en-US', {
                      year: 'numeric',
                      month: 'short',
                      day: 'numeric',
                    })}
                  </p>
                </div>
                <div className="flex items-center gap-2">
                  {invite.token && (
                    <Button
                      variant="ghost"
                      size="sm"
                      onClick={() => copyInviteLink(invite.token!)}
                      className="text-muted-foreground hover:text-foreground"
                    >
                      Copy Link
                    </Button>
                  )}
                  <Button
                    variant="ghost"
                    size="sm"
                    onClick={() => handleCancelInvite(invite.id)}
                    className="text-destructive hover:text-destructive hover:bg-destructive/10"
                  >
                    Cancel
                  </Button>
                </div>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* Invite modal */}
      <Modal
        open={inviteOpen}
        onOpenChange={(open) => { if (!open) closeInviteModal() }}
        title={createdInvite ? 'Invite created' : 'Invite a member'}
        description={createdInvite ? undefined : `Send an invite to join ${projectName}`}
      >
        {createdInvite ? (
          <div className="space-y-4">
            <p className="text-sm text-foreground">
              Share this link with <strong>{createdInvite.email}</strong>. It expires in 72 hours.
            </p>
            <div className="flex items-center gap-2">
              <input
                readOnly
                value={`${typeof window !== 'undefined' ? window.location.origin : ''}/invites/${createdInvite.token}/accept`}
                className="flex-1 rounded-md border border-input bg-muted text-foreground px-3 py-2 text-sm font-mono focus:outline-none"
                onFocus={(e) => e.target.select()}
              />
              <Button
                type="button"
                size="sm"
                onClick={() => createdInvite.token && copyInviteLink(createdInvite.token)}
              >
                Copy
              </Button>
            </div>
            <div className="pt-1">
              <Button type="button" onClick={closeInviteModal}>
                Done
              </Button>
            </div>
          </div>
        ) : (
          <form onSubmit={handleInviteSubmit} noValidate className="space-y-4">
            <div>
              <label htmlFor="invite-email" className="block text-sm font-medium text-foreground mb-1">
                Email address <span className="text-destructive">*</span>
              </label>
              <input
                id="invite-email"
                type="email"
                value={inviteEmail}
                onChange={(e) => setInviteEmail(e.target.value)}
                placeholder="colleague@example.com"
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
                onChange={(e) => setInviteRole(e.target.value as MemberRole)}
                className="w-full rounded-md border border-input bg-background text-foreground px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-ring"
              >
                {INVITEABLE_ROLES.map((role) => (
                  <option key={role} value={role}>
                    {role.charAt(0) + role.slice(1).toLowerCase()}
                  </option>
                ))}
              </select>
            </div>

            {inviteError && (
              <p className="text-sm text-destructive" role="alert">
                {inviteError}
              </p>
            )}

            <div className="flex gap-3 pt-2">
              <Button type="submit" disabled={inviteSubmitting}>
                {inviteSubmitting ? 'Sending…' : 'Send Invite'}
              </Button>
              <Button
                type="button"
                variant="outline"
                onClick={closeInviteModal}
                disabled={inviteSubmitting}
              >
                Cancel
              </Button>
            </div>
          </form>
        )}
      </Modal>

      {/* Remove confirm modal */}
      <Modal
        open={removeConfirm !== null}
        onOpenChange={(open) => { if (!open) setRemoveConfirm(null) }}
        title="Remove member"
      >
        <p className="text-sm text-foreground">
          Remove <strong>{removeConfirm?.name}</strong> from {projectName}?
        </p>
        {removeError && (
          <p className="mt-2 text-sm text-destructive" role="alert">{removeError}</p>
        )}
        <div className="flex gap-3 mt-4">
          <Button variant="destructive" onClick={handleRemove}>
            Remove
          </Button>
          <Button variant="outline" onClick={() => setRemoveConfirm(null)}>
            Cancel
          </Button>
        </div>
      </Modal>
    </div>
  )
}
