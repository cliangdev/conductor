'use client'

import type { ReactNode } from 'react'
import { MoreHorizontalIcon, PencilIcon, Trash2Icon } from 'lucide-react'
import {
  DropdownMenu,
  DropdownMenuTrigger,
  DropdownMenuContent,
  DropdownMenuItem,
} from '@/components/ui/dropdown-menu'

/** An extra menu entry rendered between Edit and Delete. */
export interface RowActionMenuItem {
  label: string
  icon?: ReactNode
  onSelect: () => void
  destructive?: boolean
}

/**
 * A row/card kebab menu (Edit / Delete) built on the shared Radix dropdown. Stops click
 * propagation so it can sit inside a clickable row without triggering the row's navigation.
 * Items are only shown when their handler is provided. Pass `extraItems` to render additional
 * actions (e.g. Disable/Enable) between Edit and Delete.
 */
export function RowActionsMenu({
  onEdit,
  onDelete,
  editLabel = 'Edit',
  deleteLabel = 'Delete',
  extraItems = [],
}: {
  onEdit?: () => void
  onDelete?: () => void
  editLabel?: string
  deleteLabel?: string
  extraItems?: RowActionMenuItem[]
}) {
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <button
          type="button"
          aria-label="More actions"
          onClick={(e) => e.stopPropagation()}
          className="rounded p-1.5 text-muted-foreground hover:bg-muted/50 hover:text-foreground focus:outline-none focus-visible:ring-2 focus-visible:ring-ring"
        >
          <MoreHorizontalIcon className="h-4 w-4" />
        </button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" onClick={(e) => e.stopPropagation()}>
        {onEdit && (
          <DropdownMenuItem onSelect={onEdit} className="gap-2">
            <PencilIcon className="h-4 w-4" />
            {editLabel}
          </DropdownMenuItem>
        )}
        {extraItems.map((item) => (
          <DropdownMenuItem
            key={item.label}
            onSelect={item.onSelect}
            className={`gap-2${item.destructive ? ' text-destructive focus:text-destructive' : ''}`}
          >
            {item.icon}
            {item.label}
          </DropdownMenuItem>
        ))}
        {onDelete && (
          <DropdownMenuItem
            onSelect={onDelete}
            className="gap-2 text-destructive focus:text-destructive"
          >
            <Trash2Icon className="h-4 w-4" />
            {deleteLabel}
          </DropdownMenuItem>
        )}
      </DropdownMenuContent>
    </DropdownMenu>
  )
}
