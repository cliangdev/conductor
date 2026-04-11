'use client'

import { useState, useEffect } from 'react'
import { Modal } from '@/components/ui/modal'
import { EventTypeDropdown } from './EventTypeDropdown'
import type { NotificationChannelRequest, NotificationChannelResponse } from '@/hooks/useNotifications'

interface ChannelConfigModalProps {
  open: boolean
  onClose: () => void
  onSave: (req: NotificationChannelRequest) => Promise<void>
  existingChannel?: NotificationChannelResponse
  usedEventTypes: string[]
  onEventTypeChange?: (eventType: string) => void
}

const PROVIDERS = [
  { value: 'DISCORD', label: 'Discord', available: true },
  { value: 'SLACK', label: 'Slack (coming soon)', available: false },
  { value: 'TEAMS', label: 'Microsoft Teams (coming soon)', available: false },
]

function validateWebhookUrl(url: string): string | null {
  if (!url.trim()) return 'Webhook URL is required.'
  if (!url.startsWith('http://') && !url.startsWith('https://')) {
    return 'Webhook URL must start with http:// or https://'
  }
  return null
}

export function ChannelConfigModal({
  open,
  onClose,
  onSave,
  existingChannel,
  usedEventTypes,
  onEventTypeChange,
}: ChannelConfigModalProps) {
  const [eventType, setEventType] = useState('')
  const [provider, setProvider] = useState('DISCORD')
  const [webhookUrl, setWebhookUrl] = useState('')
  const [enabled, setEnabled] = useState(true)
  const [urlError, setUrlError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)
  const [saveError, setSaveError] = useState<string | null>(null)

  const isEditing = Boolean(existingChannel)

  useEffect(() => {
    if (open) {
      if (existingChannel) {
        setEventType(existingChannel.eventType)
        setProvider(existingChannel.provider)
        setWebhookUrl(existingChannel.webhookUrl)
        setEnabled(existingChannel.enabled)
        onEventTypeChange?.(existingChannel.eventType)
      } else {
        setEventType('')
        setProvider('DISCORD')
        setWebhookUrl('')
        setEnabled(true)
        onEventTypeChange?.('')
      }
      setUrlError(null)
      setSaveError(null)
    }
  }, [open, existingChannel])

  function handleEventTypeChange(v: string) {
    setEventType(v)
    onEventTypeChange?.(v)
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    const validationError = validateWebhookUrl(webhookUrl)
    if (validationError) {
      setUrlError(validationError)
      return
    }
    if (!eventType) {
      return
    }
    setUrlError(null)
    setSaveError(null)
    setSaving(true)
    try {
      await onSave({ provider, webhookUrl: webhookUrl.trim(), enabled })
      onClose()
    } catch {
      setSaveError('Failed to save channel. Please try again.')
    } finally {
      setSaving(false)
    }
  }

  return (
    <Modal
      open={open}
      onOpenChange={(o) => { if (!o) onClose() }}
      title={isEditing ? 'Edit Notification Channel' : 'Add Notification Channel'}
    >
      <form onSubmit={handleSubmit} noValidate className="space-y-4">
        <div>
          <label className="block text-sm font-medium text-foreground mb-1">
            Event Type
          </label>
          <EventTypeDropdown
            value={eventType}
            onChange={handleEventTypeChange}
            usedEventTypes={usedEventTypes}
            disabled={isEditing}
          />
        </div>

        <div>
          <label className="block text-sm font-medium text-foreground mb-1">
            Provider
          </label>
          <select
            value={provider}
            onChange={(e) => setProvider(e.target.value)}
            className="border border-border bg-background text-foreground rounded-md px-3 py-2 text-sm w-full focus:outline-none focus:ring-2 focus:ring-ring"
          >
            {PROVIDERS.map((p) => (
              <option key={p.value} value={p.value} disabled={!p.available}>
                {p.label}
              </option>
            ))}
          </select>
        </div>

        <div>
          <label htmlFor="webhook-url-input" className="block text-sm font-medium text-foreground mb-1">
            Webhook URL
          </label>
          <input
            id="webhook-url-input"
            type="url"
            value={webhookUrl}
            onChange={(e) => {
              setWebhookUrl(e.target.value)
              setUrlError(null)
            }}
            placeholder="https://..."
            className="border border-border bg-background text-foreground rounded-md px-3 py-2 text-sm w-full focus:outline-none focus:ring-2 focus:ring-ring"
          />
          {urlError && (
            <p className="mt-1 text-sm text-destructive" role="alert">
              {urlError}
            </p>
          )}
        </div>

        <div className="flex items-center gap-2">
          <input
            id="channel-enabled"
            type="checkbox"
            checked={enabled}
            onChange={(e) => setEnabled(e.target.checked)}
            className="rounded border-border"
          />
          <label htmlFor="channel-enabled" className="text-sm text-foreground">
            Enabled
          </label>
        </div>

        {saveError && (
          <p className="text-sm text-destructive" role="alert">
            {saveError}
          </p>
        )}

        <div className="flex items-center gap-3 pt-1">
          <button
            type="submit"
            disabled={saving || !eventType}
            className="bg-primary text-primary-foreground hover:bg-primary/90 px-4 py-2 rounded-md text-sm font-medium disabled:opacity-50 disabled:cursor-not-allowed"
          >
            {saving ? 'Saving…' : 'Save'}
          </button>
          <button
            type="button"
            onClick={onClose}
            className="border border-border bg-background text-foreground hover:bg-muted px-4 py-2 rounded-md text-sm"
          >
            Cancel
          </button>
        </div>
      </form>
    </Modal>
  )
}
