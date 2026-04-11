'use client'

import { useState } from 'react'
import { apiPut, apiDelete, apiPost } from '@/lib/api'
import {
  useNotificationGroups,
  CHANNEL_GROUPS,
  type NotificationGroupRequest,
  type NotificationGroupResponse,
  type NotificationTestResponse,
} from '@/hooks/useNotifications'
import { useToast } from '@/components/ui/toast'
import { NotificationGroupTable } from './NotificationGroupTable'
import { GroupChannelConfigModal } from './GroupChannelConfigModal'

interface NotificationSettingsPageProps {
  projectId: string
  accessToken: string
}

export function NotificationSettingsPage({ projectId, accessToken }: NotificationSettingsPageProps) {
  const { groups, loading, error, refetch } = useNotificationGroups(projectId, accessToken)
  const { showToast } = useToast()

  const [showModal, setShowModal] = useState(false)
  const [editingGroup, setEditingGroup] = useState<NotificationGroupResponse | undefined>(undefined)
  const [testingGroup, setTestingGroup] = useState<string | null>(null)

  function handleAdd() {
    setEditingGroup(undefined)
    setShowModal(true)
  }

  function handleEdit(group: NotificationGroupResponse) {
    setEditingGroup(group)
    setShowModal(true)
  }

  function handleClose() {
    setShowModal(false)
    setEditingGroup(undefined)
  }

  async function handleDelete(channelGroup: string) {
    if (!confirm(`Remove notification channel for "${channelGroup}"?`)) return
    try {
      await apiDelete(
        `/api/v1/projects/${projectId}/notifications/groups/${channelGroup}`,
        accessToken
      )
      showToast('Notification channel removed.')
      refetch()
    } catch {
      showToast('Failed to remove channel. Please try again.', 'error')
    }
  }

  async function handleTest(channelGroup: string) {
    setTestingGroup(channelGroup)
    try {
      const result = await apiPost<NotificationTestResponse>(
        `/api/v1/projects/${projectId}/notifications/test/groups/${channelGroup}`,
        {},
        accessToken
      )
      if (result.success) {
        showToast(result.message || 'Test notification sent!')
      } else {
        showToast(result.message || 'Test notification failed.', 'error')
      }
    } catch {
      showToast('Failed to send test notification.', 'error')
    } finally {
      setTestingGroup(null)
    }
  }

  async function handleSave(channelGroup: string, req: NotificationGroupRequest) {
    await apiPut<NotificationGroupResponse>(
      `/api/v1/projects/${projectId}/notifications/groups/${channelGroup}`,
      req,
      accessToken
    )
    showToast('Notification channel saved.')
    refetch()
  }

  const configuredGroups = new Set(groups.map((g) => g.channelGroup))
  const availableGroups = CHANNEL_GROUPS.filter((g) => !configuredGroups.has(g.value))
  const hasUnconfiguredGroups = availableGroups.length > 0

  return (
    <section>
      <div className="flex items-center justify-between mb-4">
        <p className="text-sm text-muted-foreground">
          Configure webhooks to receive notifications for project events.
        </p>
        {hasUnconfiguredGroups && (
          <button
            type="button"
            onClick={handleAdd}
            className="bg-primary text-primary-foreground hover:bg-primary/90 px-4 py-2 rounded-md text-sm font-medium shrink-0"
          >
            Add Channel
          </button>
        )}
      </div>

      {loading && (
        <p className="text-sm text-muted-foreground">Loading…</p>
      )}
      {error && (
        <p className="text-sm text-destructive" role="alert">{error}</p>
      )}
      {!loading && !error && (
        <NotificationGroupTable
          groups={groups}
          onEdit={handleEdit}
          onDelete={handleDelete}
          onTest={handleTest}
          testingGroup={testingGroup}
        />
      )}

      <GroupChannelConfigModal
        open={showModal}
        onClose={handleClose}
        onSave={handleSave}
        existingGroup={editingGroup}
        availableGroups={availableGroups}
      />
    </section>
  )
}
