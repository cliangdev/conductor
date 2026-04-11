'use client'

import { useState } from 'react'
import { apiPut, apiDelete, apiPost } from '@/lib/api'
import {
  useNotifications,
  type NotificationChannelRequest,
  type NotificationChannelResponse,
  type NotificationTestResponse,
} from '@/hooks/useNotifications'
import { useToast } from '@/components/ui/toast'
import { NotificationChannelTable } from './NotificationChannelTable'
import { ChannelConfigModal } from './ChannelConfigModal'

interface NotificationSettingsPageProps {
  projectId: string
  accessToken: string
}

export function NotificationSettingsPage({ projectId, accessToken }: NotificationSettingsPageProps) {
  const { channels, loading, error, refetch } = useNotifications(projectId, accessToken)
  const { showToast } = useToast()

  const [showModal, setShowModal] = useState(false)
  const [editingChannel, setEditingChannel] = useState<NotificationChannelResponse | undefined>(undefined)
  const [pendingEventType, setPendingEventType] = useState('')
  const [testingEventType, setTestingEventType] = useState<string | null>(null)

  function handleAdd() {
    setEditingChannel(undefined)
    setPendingEventType('')
    setShowModal(true)
  }

  function handleEdit(channel: NotificationChannelResponse) {
    setEditingChannel(channel)
    setPendingEventType(channel.eventType)
    setShowModal(true)
  }

  function handleClose() {
    setShowModal(false)
    setEditingChannel(undefined)
    setPendingEventType('')
  }

  async function handleDelete(eventType: string) {
    if (!confirm(`Delete notification channel for "${eventType}"?`)) return
    try {
      await apiDelete(
        `/api/v1/projects/${projectId}/notifications/channels/${eventType}`,
        accessToken,
      )
      showToast('Notification channel deleted.')
      refetch()
    } catch {
      showToast('Failed to delete channel. Please try again.', 'error')
    }
  }

  async function handleTest(eventType: string) {
    setTestingEventType(eventType)
    try {
      const result = await apiPost<NotificationTestResponse>(
        `/api/v1/projects/${projectId}/notifications/test/${eventType}`,
        {},
        accessToken,
      )
      if (result.success) {
        showToast(result.message || 'Test notification sent!')
      } else {
        showToast(result.message || 'Test notification failed.', 'error')
      }
    } catch {
      showToast('Failed to send test notification.', 'error')
    } finally {
      setTestingEventType(null)
    }
  }

  async function handleSave(req: NotificationChannelRequest) {
    const eventType = editingChannel ? editingChannel.eventType : pendingEventType
    if (!eventType) throw new Error('No event type selected')
    await apiPut<NotificationChannelResponse>(
      `/api/v1/projects/${projectId}/notifications/channels/${eventType}`,
      req,
      accessToken,
    )
    showToast('Notification channel saved.')
    refetch()
  }

  const usedEventTypes = channels.map((c) => c.eventType)

  return (
    <section>
      <div className="flex items-center justify-between mb-4">
        <h2 className="text-base font-semibold text-foreground">Notifications</h2>
        <button
          type="button"
          onClick={handleAdd}
          className="bg-primary text-primary-foreground hover:bg-primary/90 px-4 py-2 rounded-md text-sm font-medium"
        >
          Add Channel
        </button>
      </div>

      <div className="rounded-lg border border-border bg-card p-6">
        {loading && (
          <p className="text-sm text-muted-foreground">Loading channels…</p>
        )}
        {error && (
          <p className="text-sm text-destructive" role="alert">{error}</p>
        )}
        {!loading && !error && (
          <NotificationChannelTable
            channels={channels}
            onEdit={handleEdit}
            onDelete={handleDelete}
            onTest={handleTest}
            testingEventType={testingEventType}
          />
        )}
      </div>

      <ChannelConfigModal
        open={showModal}
        onClose={handleClose}
        onSave={handleSave}
        existingChannel={editingChannel}
        usedEventTypes={usedEventTypes}
        onEventTypeChange={setPendingEventType}
      />
    </section>
  )
}
