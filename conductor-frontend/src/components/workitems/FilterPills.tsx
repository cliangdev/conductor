'use client'

import { ListFilter, X } from 'lucide-react'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import type { ActiveFilter } from '@/components/workitems/listTypes'

/**
 * Replaces the two native Type/Status <select>s with a "Filter" menu (add a Type or Status value) and
 * removable pills for whatever is active. Multiple values of the same kind can be active at once
 * (OR'd within a kind, AND'd across kinds) — a small upgrade over the old single-value selects.
 */
export function FilterPills({
  typeOptions,
  statusOptions,
  tagOptions = [],
  activeFilters,
  onAdd,
  onRemove,
}: {
  typeOptions: string[]
  statusOptions: { id: string; label: string }[]
  /** Every tag in use on the items in view — there is no registry, tags are just what people typed. */
  tagOptions?: string[]
  activeFilters: ActiveFilter[]
  onAdd: (filter: ActiveFilter) => void
  onRemove: (kind: ActiveFilter['kind'], value: string) => void
}) {
  const activeTypeValues = new Set(activeFilters.filter((f) => f.kind === 'type').map((f) => f.value))
  const activeStatusValues = new Set(activeFilters.filter((f) => f.kind === 'status').map((f) => f.value))
  const availableTypes = typeOptions.filter((t) => !activeTypeValues.has(t))
  const availableStatuses = statusOptions.filter((s) => !activeStatusValues.has(s.id))
  const activeTagValues = new Set(activeFilters.filter((f) => f.kind === 'tag').map((f) => f.value))
  const availableTags = tagOptions.filter((t) => !activeTagValues.has(t))

  return (
    <div className="flex items-center gap-2 flex-wrap">
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button variant="ghost" size="sm" className="gap-1.5 text-muted-foreground">
            <ListFilter className="h-3.5 w-3.5" />
            Filter
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="start" className="w-48">
          <DropdownMenuLabel className="text-xs text-muted-foreground font-medium">Type</DropdownMenuLabel>
          {availableTypes.length === 0 ? (
            <DropdownMenuItem disabled>No more types</DropdownMenuItem>
          ) : (
            availableTypes.map((t) => (
              <DropdownMenuItem key={t} className="cursor-pointer" onClick={() => onAdd({ kind: 'type', value: t, label: t })}>
                {t}
              </DropdownMenuItem>
            ))
          )}
          <DropdownMenuSeparator />
          <DropdownMenuLabel className="text-xs text-muted-foreground font-medium">Status</DropdownMenuLabel>
          {availableStatuses.length === 0 ? (
            <DropdownMenuItem disabled>No more statuses</DropdownMenuItem>
          ) : (
            availableStatuses.map((s) => (
              <DropdownMenuItem
                key={s.id}
                className="cursor-pointer"
                onClick={() => onAdd({ kind: 'status', value: s.id, label: s.label })}
              >
                {s.label}
              </DropdownMenuItem>
            ))
          )}
          {tagOptions.length > 0 && (
            <>
              <DropdownMenuSeparator />
              <DropdownMenuLabel className="text-xs text-muted-foreground font-medium">Tag</DropdownMenuLabel>
              {availableTags.length === 0 ? (
                <DropdownMenuItem disabled>No more tags</DropdownMenuItem>
              ) : (
                availableTags.map((t) => (
                  <DropdownMenuItem
                    key={t}
                    className="cursor-pointer"
                    onClick={() => onAdd({ kind: 'tag', value: t, label: t })}
                  >
                    {t}
                  </DropdownMenuItem>
                ))
              )}
            </>
          )}
        </DropdownMenuContent>
      </DropdownMenu>

      {activeFilters.map((f) => (
        <Badge key={`${f.kind}-${f.value}`} variant="outline" className="gap-1 font-normal">
          <span className="text-muted-foreground">
            {f.kind === 'type' ? 'Type' : f.kind === 'tag' ? 'Tag' : 'Status'}:
          </span>
          {f.label}
          <button
            type="button"
            onClick={() => onRemove(f.kind, f.value)}
            aria-label={`Remove ${f.kind} filter: ${f.label}`}
            className="hover:opacity-70"
          >
            <X className="h-3 w-3" />
          </button>
        </Badge>
      ))}
    </div>
  )
}
