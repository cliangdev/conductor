'use client'

export const dynamic = 'force-dynamic'

import { useCallback, useEffect, useState } from 'react'
import { useParams } from 'next/navigation'
import { Button } from '@/components/ui/button'
import { Modal } from '@/components/ui/modal'
import { useToast } from '@/components/ui/toast'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Select } from '@/components/ui/select'
import { Card, CardContent } from '@/components/ui/card'
import { MemberRow, ROLE_LABELS, roleLabel } from '@/components/members/MemberRow'
import { PageHeader } from '@/components/layout/PageHeader'
import { useAuth } from '@/contexts/AuthContext'
import { useProject } from '@/contexts/ProjectContext'
import { usePermissions } from '@/contexts/PermissionsContext'
import { apiDelete, apiGet, apiPatch, apiPost, apiErrorMessage } from '@/lib/api'
import { settingsBreadcrumbs } from '@/lib/navigation'
import type { Invite, Member, MemberRole } from '@/types'

const INVITE_ROLES: MemberRole[] = ['CREATOR', 'REVIEWER']

function inviteLink(token: string): string {
  const origin = typeof window !== 'undefined' ? window.location.origin : ''
  return `${origin}/invites/${token}/accept`
}

export default function MembersPage() {
  const params = useParams()
  const projectId = params.projectId as string
  const { accessToken, user } = useAuth()
  const { activeProject } = useProject()
  const { can } = usePermissions()
  const { showToast } = useToast()

  const [members, setMembers] = useState<Member[]>([])
  const [membersLoading, setMembersLoading] = useState(true)
  const [membersError, setMembersError] = useState<string | null>(null)

  const [invites, setInvites] = useState<Invite[]>([])

  const [inviteOpen, setInviteOpen] = useState(false)
  const [inviteEmail, setInviteEmail] = useState('')
  const [inviteRole, setInviteRole] = useState<MemberRole>('CREATOR')
  const [inviteError, setInviteError] = useState<string | null>(null)
  const [inviteSubmitting, setInviteSubmitting] = useState(false)
  const [createdLink, setCreatedLink] = useState<string | null>(null)
  const [copied, setCopied] = useState(false)

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
      // Non-admins may not have access — leave invites empty
      setInvites([])
    }
  }, [accessToken, projectId])

  useEffect(() => {
    fetchMembers()
  }, [fetchMembers])

  const isAdmin = can('members.manage')

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
      setMembers((prev) => prev.map((m) => (m.userId === userId ? { ...m, role: updated?.role ?? role } : m)))
      showToast('Role updated successfully')
    } catch (err) {
      showToast(apiErrorMessage(err, 'Failed to update role. Please try again.'), 'error')
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
      setRemoveError(apiErrorMessage(err, 'Failed to remove member. Please try again.'))
    }
  }

  function openInviteModal() {
    setInviteEmail('')
    setInviteRole('CREATOR')
    setInviteError(null)
    setCreatedLink(null)
    setCopied(false)
    setInviteOpen(true)
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
      const invite = await apiPost<Invite>(
        `/api/v1/projects/${projectId}/invites`,
        { email: inviteEmail.trim(), role: inviteRole },
        accessToken,
      )
      if (invite.token) setCreatedLink(inviteLink(invite.token))
      await fetchInvites()
      showToast('Invitation sent')
    } catch (err) {
      setInviteError(apiErrorMessage(err, 'Failed to send invitation.'))
    } finally {
      setInviteSubmitting(false)
    }
  }

  function copyLink() {
    if (!createdLink) return
    navigator.clipboard.writeText(createdLink).then(() => {
      setCopied(true)
      setTimeout(() => setCopied(false), 2000)
    })
  }

  async function handleCancelInvite(inviteId: string) {
    if (!accessToken) return
    try {
      await apiDelete(`/api/v1/projects/${projectId}/invites/${inviteId}`, accessToken)
      setInvites((prev) => prev.filter((i) => i.id !== inviteId))
    } catch (err) {
      showToast(apiErrorMessage(err, 'Failed to cancel invitation.'), 'error')
    }
  }

  const workspaceName = activeProject?.name ?? 'this workspace'

  return (
    <>
      <PageHeader
        title="Members & Roles"
        actions={
          isAdmin && (
            <Button onClick={openInviteModal} size="sm">
              Invite member
            </Button>
          )
        }
        breadcrumbs={settingsBreadcrumbs(projectId, 'settings-members')}
      />

      {membersLoading && (
        <p className="text-sm text-muted-foreground">Loading members…</p>
      )}

      {membersError && (
        <p className="text-sm text-destructive" role="alert">{membersError}</p>
      )}

      {!membersLoading && !membersError && (
        <Card>
          {members.length === 0 ? (
            <p className="text-sm text-muted-foreground p-4">No members yet.</p>
          ) : (
            <CardContent className="px-4">
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
            </CardContent>
          )}
        </Card>
      )}

      {isAdmin && invites.length > 0 && (
        <div className="mt-8">
          <h2 className="text-sm font-semibold text-foreground mb-3">Pending invitations</h2>
          <Card>
            <CardContent>
              {invites.map((invite) => (
                <div key={invite.id} className="flex items-center justify-between px-4 py-3">
                  <div className="min-w-0">
                    <p className="text-sm text-foreground truncate">{invite.email}</p>
                    <p className="text-xs text-muted-foreground">{roleLabel(invite.role)}</p>
                  </div>
                  <Button
                    variant="ghost"
                    size="sm"
                    onClick={() => handleCancelInvite(invite.id)}
                    className="text-destructive hover:text-destructive hover:bg-destructive/10"
                  >
                    Cancel
                  </Button>
                </div>
              ))}
            </CardContent>
          </Card>
        </div>
      )}

      {/* Invite member modal */}
      <Modal
        open={inviteOpen}
        onOpenChange={(open) => { if (!open) setInviteOpen(false) }}
        title="Invite a member"
        description={`Invite someone to ${workspaceName} by email.`}
      >
        {createdLink ? (
          <div className="space-y-4">
            <p className="text-sm text-foreground">
              Invitation sent. Share this link so they can join:
            </p>
            <div className="flex items-center gap-2">
              <Input readOnly value={createdLink} className="flex-1 bg-muted font-mono text-xs" />
              <Button type="button" size="sm" variant="outline" onClick={copyLink}>
                {copied ? 'Copied!' : 'Copy'}
              </Button>
            </div>
            <div className="flex justify-end gap-3 pt-2">
              <Button type="button" variant="outline" onClick={() => { setCreatedLink(null); setInviteEmail('') }}>
                Invite another
              </Button>
              <Button type="button" onClick={() => setInviteOpen(false)}>
                Done
              </Button>
            </div>
          </div>
        ) : (
          <form onSubmit={handleInviteSubmit} noValidate className="space-y-4">
            <div>
              <Label htmlFor="invite-email">
                Email <span className="text-destructive">*</span>
              </Label>
              <Input
                id="invite-email"
                type="email"
                value={inviteEmail}
                onChange={(e) => setInviteEmail(e.target.value)}
                placeholder="teammate@example.com"
              />
            </div>

            <div>
              <Label htmlFor="invite-role">Role</Label>
              <Select
                id="invite-role"
                value={inviteRole}
                onChange={(e) => setInviteRole(e.target.value as MemberRole)}
              >
                {INVITE_ROLES.map((role) => (
                  <option key={role} value={role}>
                    {ROLE_LABELS[role]}
                  </option>
                ))}
              </Select>
            </div>

            {inviteError && (
              <p className="text-sm text-destructive" role="alert">
                {inviteError}
              </p>
            )}

            <div className="flex gap-3 pt-2">
              <Button type="submit" disabled={inviteSubmitting || !inviteEmail.trim()}>
                {inviteSubmitting ? 'Sending…' : 'Send invite'}
              </Button>
              <Button
                type="button"
                variant="outline"
                onClick={() => setInviteOpen(false)}
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
          Remove <strong>{removeConfirm?.name}</strong> from {workspaceName}?
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
    </>
  )
}
