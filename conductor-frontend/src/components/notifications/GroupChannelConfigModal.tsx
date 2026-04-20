'use client'

import { useState, useEffect } from 'react'
import { Modal } from '@/components/ui/modal'
import { cn } from '@/lib/utils'
import {
  CHANNEL_GROUPS,
  EVENT_TYPE_DESCRIPTIONS,
  EVENT_TYPE_SUBTITLES,
  type NotificationGroupRequest,
  type NotificationGroupResponse,
} from '@/hooks/useNotifications'

interface AvailableGroup {
  value: string
  label: string
  eventTypes: string[]
}

interface GroupChannelConfigModalProps {
  open: boolean
  onClose: () => void
  onSave: (channelGroup: string, req: NotificationGroupRequest) => Promise<void>
  existingGroup?: NotificationGroupResponse
  availableGroups: AvailableGroup[]
}

const PROVIDERS = [
  { value: 'DISCORD', label: 'Discord', available: true },
  { value: 'SLACK', label: 'Slack (coming soon)', available: false },
  { value: 'TEAMS', label: 'Microsoft Teams (coming soon)', available: false },
]

const WEBHOOK_INSTRUCTIONS: Record<string, string> = {
  DISCORD:
    'In your Discord server go to Server Settings → Integrations → Webhooks → New Webhook, select a channel, then click Copy Webhook URL.',
}

function validateWebhookUrl(url: string): string | null {
  if (!url.trim()) return 'Webhook URL is required.'
  if (!url.startsWith('http://') && !url.startsWith('https://'))
    return 'Webhook URL must start with http:// or https://'
  return null
}

export function GroupChannelConfigModal({
  open,
  onClose,
  onSave,
  existingGroup,
  availableGroups,
}: GroupChannelConfigModalProps) {
  const isEditing = Boolean(existingGroup)

  const [selectedGroup, setSelectedGroup] = useState('')
  const [provider, setProvider] = useState('DISCORD')
  const [webhookUrl, setWebhookUrl] = useState('')
  const [enabled, setEnabled] = useState(true)
  const [enabledEventTypes, setEnabledEventTypes] = useState<string[]>([])
  const [urlError, setUrlError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)
  const [saveError, setSaveError] = useState<string | null>(null)

  useEffect(() => {
    if (!open) return
    if (existingGroup) {
      setSelectedGroup(existingGroup.channelGroup)
      setProvider(existingGroup.provider)
      setWebhookUrl(existingGroup.webhookUrl)
      setEnabled(existingGroup.enabled)
      setEnabledEventTypes(
        existingGroup.events?.filter((e) => e.enabled).map((e) => e.eventType) ?? []
      )
    } else {
      setSelectedGroup(availableGroups[0]?.value ?? '')
      setProvider('DISCORD')
      setWebhookUrl('')
      setEnabled(true)
      setEnabledEventTypes([])
    }
    setUrlError(null)
    setSaveError(null)
  }, [open, existingGroup, availableGroups])

  // The event types for the currently selected group
  const currentGroupMeta = isEditing
    ? CHANNEL_GROUPS.find((g) => g.value === existingGroup?.channelGroup)
    : CHANNEL_GROUPS.find((g) => g.value === selectedGroup)
  const groupEventTypes = currentGroupMeta?.eventTypes ?? []

  function toggleEventType(type: string) {
    setEnabledEventTypes((prev) =>
      prev.includes(type) ? prev.filter((t) => t !== type) : [...prev, type]
    )
  }

  function toggleAll() {
    const allEnabled = groupEventTypes.every((t) => enabledEventTypes.includes(t))
    setEnabledEventTypes(allEnabled ? [] : [...groupEventTypes])
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    const validationError = validateWebhookUrl(webhookUrl)
    if (validationError) {
      setUrlError(validationError)
      return
    }
    if (!selectedGroup) return
    setUrlError(null)
    setSaveError(null)
    setSaving(true)
    try {
      await onSave(selectedGroup, {
        provider,
        webhookUrl: webhookUrl.trim(),
        enabled,
        enabledEventTypes,
      })
      onClose()
    } catch {
      setSaveError('Failed to save. Please try again.')
    } finally {
      setSaving(false)
    }
  }

  const allEnabled = groupEventTypes.length > 0 &&
    groupEventTypes.every((t) => enabledEventTypes.includes(t))
  const someEnabled = groupEventTypes.some((t) => enabledEventTypes.includes(t))
  const webhookInstruction = WEBHOOK_INSTRUCTIONS[provider]

  const footer = (
    <div className="flex items-center gap-3">
      <button
        type="submit"
        form="channel-form"
        disabled={saving || !selectedGroup}
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
  )

  return (
    <Modal
      open={open}
      onOpenChange={(o) => { if (!o) onClose() }}
      title={isEditing ? `Edit: ${existingGroup?.label}` : 'Add Notification Channel'}
      footer={footer}
    >
      <form id="channel-form" onSubmit={handleSubmit} noValidate className="space-y-5">

        {/* Channel group selector — only for new channels */}
        {!isEditing && availableGroups.length > 1 && (
          <div>
            <label className="block text-sm font-medium text-foreground mb-1">
              Channel
            </label>
            <div className="flex gap-2">
              {availableGroups.map((g) => (
                <button
                  key={g.value}
                  type="button"
                  onClick={() => {
                    setSelectedGroup(g.value)
                    setEnabledEventTypes([])
                  }}
                  className={cn(
                    'flex-1 py-2 rounded-md border text-sm font-medium transition-colors',
                    selectedGroup === g.value
                      ? 'border-primary bg-primary/10 text-primary'
                      : 'border-border bg-background text-foreground hover:bg-muted'
                  )}
                >
                  {g.label}
                </button>
              ))}
            </div>
          </div>
        )}

        {/* Events */}
        {groupEventTypes.length > 0 && (
          <div>
            <div className="flex items-center justify-between mb-2">
              <label className="text-sm font-medium text-foreground">Events</label>
              <button
                type="button"
                onClick={toggleAll}
                className="text-xs text-muted-foreground hover:text-foreground underline-offset-2 hover:underline"
              >
                {allEnabled ? 'Deselect all' : 'Select all'}
              </button>
            </div>
            <div className="rounded-md border border-border p-3 grid grid-cols-1 gap-2">
              {groupEventTypes.map((type) => (
                <label key={type} className="flex items-start gap-2.5 cursor-pointer">
                  <input
                    type="checkbox"
                    checked={enabledEventTypes.includes(type)}
                    onChange={() => toggleEventType(type)}
                    className="mt-0.5 rounded border-border"
                  />
                  <span className="flex flex-col">
                    <span className="text-sm text-foreground">
                      {EVENT_TYPE_DESCRIPTIONS[type] ?? type}
                    </span>
                    {EVENT_TYPE_SUBTITLES[type] && (
                      <span className="text-xs text-muted-foreground">
                        {EVENT_TYPE_SUBTITLES[type]}
                      </span>
                    )}
                  </span>
                </label>
              ))}
            </div>
          </div>
        )}

        {/* Provider */}
        <div>
          <label className="block text-sm font-medium text-foreground mb-1">Provider</label>
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

        {/* Webhook URL */}
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
            <p className="mt-1 text-sm text-destructive" role="alert">{urlError}</p>
          )}
          {webhookInstruction && (
            <p className="mt-1.5 text-xs text-muted-foreground leading-relaxed">
              {webhookInstruction}
            </p>
          )}
        </div>

        {/* Enabled */}
        <div className="flex items-center gap-2">
          <input
            id="channel-enabled"
            type="checkbox"
            checked={enabled}
            onChange={(e) => setEnabled(e.target.checked)}
            className="rounded border-border"
          />
          <label htmlFor="channel-enabled" className="text-sm text-foreground">Enabled</label>
        </div>

        {saveError && (
          <p className="text-sm text-destructive" role="alert">{saveError}</p>
        )}
      </form>
    </Modal>
  )
}
