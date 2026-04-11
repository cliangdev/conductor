'use client'

import { Badge } from '@/components/ui/badge'
import { EVENT_TYPE_DESCRIPTIONS, type NotificationChannelResponse } from '@/hooks/useNotifications'

interface NotificationChannelTableProps {
  channels: NotificationChannelResponse[]
  onEdit: (channel: NotificationChannelResponse) => void
  onDelete: (eventType: string) => void
  onTest: (eventType: string) => void
  testingEventType: string | null
}

export function NotificationChannelTable({
  channels,
  onEdit,
  onDelete,
  onTest,
  testingEventType,
}: NotificationChannelTableProps) {
  if (channels.length === 0) {
    return (
      <p className="text-sm text-muted-foreground py-4">
        No notification channels configured. Add one to get started.
      </p>
    )
  }

  return (
    <div className="overflow-x-auto">
      <table className="w-full text-sm">
        <thead>
          <tr className="border-b border-border">
            <th className="text-left text-xs font-medium text-muted-foreground pb-3 pr-4">Event Type</th>
            <th className="text-left text-xs font-medium text-muted-foreground pb-3 pr-4">Provider</th>
            <th className="text-left text-xs font-medium text-muted-foreground pb-3 pr-4">Webhook URL</th>
            <th className="text-left text-xs font-medium text-muted-foreground pb-3 pr-4">Enabled</th>
            <th className="text-left text-xs font-medium text-muted-foreground pb-3">Actions</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-border">
          {channels.map((channel) => (
            <tr key={channel.eventType}>
              <td className="py-3 pr-4">
                <div className="font-medium text-foreground">
                  {EVENT_TYPE_DESCRIPTIONS[channel.eventType] ?? channel.eventType}
                </div>
                <div className="text-xs text-muted-foreground">{channel.eventType}</div>
              </td>
              <td className="py-3 pr-4 text-foreground">{channel.provider}</td>
              <td className="py-3 pr-4 max-w-xs">
                <span
                  className="block truncate text-foreground font-mono text-xs"
                  title={channel.webhookUrl}
                >
                  {channel.webhookUrl}
                </span>
              </td>
              <td className="py-3 pr-4">
                {channel.enabled ? (
                  <Badge className="bg-green-100 text-green-700 border-green-200">Enabled</Badge>
                ) : (
                  <Badge variant="secondary">Disabled</Badge>
                )}
              </td>
              <td className="py-3">
                <div className="flex items-center gap-2">
                  <button
                    type="button"
                    onClick={() => onEdit(channel)}
                    className="border border-border bg-background text-foreground hover:bg-muted px-3 py-1 rounded-md text-xs"
                  >
                    Edit
                  </button>
                  <button
                    type="button"
                    onClick={() => onDelete(channel.eventType)}
                    className="border border-destructive/30 bg-background text-destructive hover:bg-destructive/10 px-3 py-1 rounded-md text-xs"
                  >
                    Delete
                  </button>
                  <button
                    type="button"
                    onClick={() => onTest(channel.eventType)}
                    disabled={testingEventType === channel.eventType}
                    className="border border-border bg-background text-foreground hover:bg-muted px-3 py-1 rounded-md text-xs disabled:opacity-50 disabled:cursor-not-allowed"
                  >
                    {testingEventType === channel.eventType ? 'Testing…' : 'Test'}
                  </button>
                </div>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
