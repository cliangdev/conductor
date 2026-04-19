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
import type { Invite, Member, MemberRole, OrgMember } from '@/types'

interface ApiError extends Error {
  status?: number
}

const ADDABLE_ROLES: MemberRole[] = ['CREATOR', 'REVIEWER']

export default function MembersPage() {
  const params = useParams()
  const projectId = params.projectId as string
  const { accessToken, user } = useAuth()
  const { activeProject } = useProject()
  const { showToast } = useToast()

  const [members, setMembers] = useState<Member[]>([])
  const [membersLoading, setMembersLoading] = useState(true)
  const [membersError, setMembersError] = useState<string | null>(null)

  const [addOpen, setAddOpen] = useState(false)
  const [orgMembers, setOrgMembers] = useState<OrgMember[]>([])
  const [orgMembersLoading, setOrgMembersLoading] = useState(false)
  const [addUserId, setAddUserId] = useState('')
  const [addRole, setAddRole] = useState<MemberRole>('CREATOR')
  const [addError, setAddError] = useState<string | null>(null)
  const [addSubmitting, setAddSubmitting] = useState(false)

  const [inviteOpen, setInviteOpen] = useState(false)
  const [inviteEmail, setInviteEmail] = useState('')
  const [inviteRole, setInviteRole] = useState<MemberRole>('CREATOR')
  const [inviteError, setInviteError] = useState<string | null>(null)
  const [inviteSubmitting, setInviteSubmitting] = useState(false)
  const [inviteLink, setInviteLink] = useState<string | null>(null)

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

  useEffect(() => {
    fetchMembers()
  }, [fetchMembers])

  const currentUserRole = members.find((m) => m.userId === user?.id)?.role
  const isAdmin = currentUserRole === 'ADMIN'

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

  async function openAddModal() {
    setAddUserId('')
    setAddRole('CREATOR')
    setAddError(null)
    setAddOpen(true)

    const orgId = activeProject?.orgId
    if (!orgId || !accessToken) return

    setOrgMembersLoading(true)
    try {
      const data = await apiGet<OrgMember[]>(`/api/v1/orgs/${orgId}/members`, accessToken)
      setOrgMembers(data)
    } catch {
      setAddError('Failed to load org members.')
    } finally {
      setOrgMembersLoading(false)
    }
  }

  function closeAddModal() {
    setAddOpen(false)
  }

  const memberUserIds = new Set(members.map((m) => m.userId))
  const candidates = orgMembers.filter((om) => !memberUserIds.has(om.userId))

  async function handleAddSubmit(e: React.FormEvent) {
    e.preventDefault()
    if (!accessToken || !addUserId) {
      setAddError('Please select a member to add.')
      return
    }

    setAddSubmitting(true)
    setAddError(null)
    try {
      await apiPost(
        `/api/v1/projects/${projectId}/members`,
        { userId: addUserId, role: addRole },
        accessToken,
      )
      await fetchMembers()
      closeAddModal()
      showToast('Member added')
    } catch (err) {
      const apiErr = err as ApiError
      if (apiErr.status === 403) {
        setAddError('Only project admins can add members.')
      } else if (apiErr.status === 409) {
        setAddError('User is already a project member.')
      } else {
        setAddError('Failed to add member.')
      }
    } finally {
      setAddSubmitting(false)
    }
  }

  function openInviteModal() {
    setInviteEmail('')
    setInviteRole('CREATOR')
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
    if (!accessToken || !inviteEmail.trim()) {
      setInviteError('Please enter an email address.')
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
      setInviteLink(`${window.location.origin}/invites/${result.token}/accept`)
    } catch (err) {
      const apiErr = err as ApiError
      if (apiErr.status === 403) {
        setInviteError('Only project admins can send invites.')
      } else if (apiErr.status === 409) {
        setInviteError('This email already has a pending invite or is already a member.')
      } else {
        setInviteError('Failed to send invite. Please try again.')
      }
    } finally {
      setInviteSubmitting(false)
    }
  }

  const projectName = activeProject?.name ?? 'this project'

  return (
    <div className="max-w-2xl mx-auto px-4 py-8">
      <div className="flex items-center justify-between mb-6">
        <h1 className="text-2xl font-bold text-foreground">Members</h1>
        {isAdmin && (
          <div className="flex gap-2">
            <Button onClick={openInviteModal} size="sm" variant="outline">
              Invite by Email
            </Button>
            <Button onClick={openAddModal} size="sm">
              Add Member
            </Button>
          </div>
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

      {/* Invite by email modal */}
      <Modal
        open={inviteOpen}
        onOpenChange={(open) => { if (!open) closeInviteModal() }}
        title="Invite by email"
        description={`Send an invite link for ${projectName}`}
      >
        {inviteLink ? (
          <div className="space-y-4">
            <p className="text-sm font-medium text-foreground">Invite link created</p>
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
                className="w-full rounded-md border border-input bg-background text-foreground px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-ring"
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
                {ADDABLE_ROLES.map((role) => (
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
                {inviteSubmitting ? 'Creating invite…' : 'Create invite link'}
              </Button>
              <Button type="button" variant="outline" onClick={closeInviteModal} disabled={inviteSubmitting}>
                Cancel
              </Button>
            </div>
          </form>
        )}
      </Modal>

      {/* Add member modal */}
      <Modal
        open={addOpen}
        onOpenChange={(open) => { if (!open) closeAddModal() }}
        title="Add a member"
        description={`Add an org member to ${projectName}`}
      >
        <form onSubmit={handleAddSubmit} noValidate className="space-y-4">
          <div>
            <label htmlFor="add-user" className="block text-sm font-medium text-foreground mb-1">
              Org member <span className="text-destructive">*</span>
            </label>
            {orgMembersLoading ? (
              <p className="text-sm text-muted-foreground">Loading org members…</p>
            ) : (
              <select
                id="add-user"
                value={addUserId}
                onChange={(e) => setAddUserId(e.target.value)}
                className="w-full rounded-md border border-input bg-background text-foreground px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-ring"
              >
                <option value="">Select a member…</option>
                {candidates.map((om) => (
                  <option key={om.userId} value={om.userId}>
                    {om.name} ({om.email})
                  </option>
                ))}
              </select>
            )}
            {!orgMembersLoading && candidates.length === 0 && !addError && (
              <p className="mt-1 text-xs text-muted-foreground">
                All org members are already in this project.
              </p>
            )}
          </div>

          <div>
            <label htmlFor="add-role" className="block text-sm font-medium text-foreground mb-1">
              Role
            </label>
            <select
              id="add-role"
              value={addRole}
              onChange={(e) => setAddRole(e.target.value as MemberRole)}
              className="w-full rounded-md border border-input bg-background text-foreground px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-ring"
            >
              {ADDABLE_ROLES.map((role) => (
                <option key={role} value={role}>
                  {role.charAt(0) + role.slice(1).toLowerCase()}
                </option>
              ))}
            </select>
          </div>

          {addError && (
            <p className="text-sm text-destructive" role="alert">
              {addError}
            </p>
          )}

          <div className="flex gap-3 pt-2">
            <Button type="submit" disabled={addSubmitting || orgMembersLoading || candidates.length === 0}>
              {addSubmitting ? 'Adding…' : 'Add Member'}
            </Button>
            <Button
              type="button"
              variant="outline"
              onClick={closeAddModal}
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
