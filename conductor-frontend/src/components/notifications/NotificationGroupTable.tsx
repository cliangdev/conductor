'use client'

import { cn } from '@/lib/utils'
import type { NotificationGroupResponse } from '@/hooks/useNotifications'

interface NotificationGroupTableProps {
  groups: NotificationGroupResponse[]
  onEdit: (group: NotificationGroupResponse) => void
  onDelete: (channelGroup: string) => void
  onTest: (channelGroup: string) => void
  testingGroup: string | null
}

export function NotificationGroupTable({
  groups,
  onEdit,
  onDelete,
  onTest,
  testingGroup,
}: NotificationGroupTableProps) {
  if (groups.length === 0) {
    return (
      <div className="rounded-lg border border-border bg-card p-8 text-center">
        <p className="text-sm text-muted-foreground">
          No notification channels configured. Click &ldquo;Add Channel&rdquo; to get started.
        </p>
      </div>
    )
  }

  return (
    <div className="space-y-3">
      {groups.map((group) => (
        <GroupRow
          key={group.channelGroup}
          group={group}
          onEdit={onEdit}
          onDelete={onDelete}
          onTest={onTest}
          isTesting={testingGroup === group.channelGroup}
        />
      ))}
    </div>
  )
}

function GroupRow({
  group,
  onEdit,
  onDelete,
  onTest,
  isTesting,
}: {
  group: NotificationGroupResponse
  onEdit: (group: NotificationGroupResponse) => void
  onDelete: (channelGroup: string) => void
  onTest: (channelGroup: string) => void
  isTesting: boolean
}) {
  const enabledCount = group.events?.filter((e) => e.enabled).length ?? 0
  const totalCount = group.events?.length ?? 0

  return (
    <div className="rounded-lg border border-border bg-card overflow-hidden">
      {/* Row header */}
      <div className="flex items-center gap-4 px-4 py-3 border-b border-border/50">
        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-2.5">
            <span className="text-sm font-semibold text-foreground">{group.label}</span>
            <span className="text-xs text-muted-foreground bg-muted px-1.5 py-0.5 rounded">
              {group.provider}
            </span>
            <span className={cn(
              'text-xs px-1.5 py-0.5 rounded font-medium',
              group.enabled
                ? 'bg-green-500/15 text-green-600 dark:text-green-400'
                : 'bg-muted text-muted-foreground'
            )}>
              {group.enabled ? 'Enabled' : 'Disabled'}
            </span>
          </div>
          <p className="text-xs text-muted-foreground mt-0.5 truncate" title={group.webhookUrl}>
            {group.webhookUrl}
          </p>
        </div>
        <div className="flex items-center gap-2 shrink-0">
          <button
            type="button"
            onClick={() => onTest(group.channelGroup)}
            disabled={isTesting}
            className="text-xs px-2.5 py-1.5 rounded border border-border bg-background hover:bg-muted text-foreground disabled:opacity-50 disabled:cursor-not-allowed"
          >
            {isTesting ? 'Sending…' : 'Test'}
          </button>
          <button
            type="button"
            onClick={() => onEdit(group)}
            className="text-xs px-2.5 py-1.5 rounded border border-border bg-background hover:bg-muted text-foreground"
          >
            Edit
          </button>
          <button
            type="button"
            onClick={() => onDelete(group.channelGroup)}
            className="text-xs px-2.5 py-1.5 rounded border border-destructive/40 bg-background hover:bg-destructive/10 text-destructive"
          >
            Delete
          </button>
        </div>
      </div>

      {/* Event type toggles */}
      <div className="px-4 py-3">
        <p className="text-xs text-muted-foreground mb-2">
          {enabledCount} of {totalCount} event{totalCount !== 1 ? 's' : ''} enabled
        </p>
        <div className="grid grid-cols-2 gap-x-6 gap-y-1.5">
          {group.events?.map((event) => (
            <div key={event.eventType} className="flex items-center gap-2">
              <span className={cn(
                'h-1.5 w-1.5 rounded-full shrink-0',
                event.enabled ? 'bg-green-500' : 'bg-muted-foreground/30'
              )} />
              <span className={cn(
                'text-xs',
                event.enabled ? 'text-foreground' : 'text-muted-foreground/60'
              )}>
                {event.label}
              </span>
            </div>
          ))}
        </div>
      </div>
    </div>
  )
}
