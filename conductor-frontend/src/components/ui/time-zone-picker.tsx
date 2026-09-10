'use client'

// A time zone control a non-technical person can actually use. The bare `Intl.supportedValuesOf`
// list it replaces is several hundred IANA ids in alphabetical order — "America/Los_Angeles" tells
// nobody outside engineering what "Pacific Time" is. This shows the current zone as a readable name,
// with a "Change" button that opens a searchable list: current zone first, then the viewer's own, then
// the rest grouped by region.

import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { Check, Globe2 } from 'lucide-react'
import { cn } from '@/lib/utils'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'

export interface TimeZonePickerProps {
  id?: string
  value: string
  onChange: (id: string) => void
  disabled?: boolean
  className?: string
  label?: string
}

function cityFromId(id: string): string {
  const last = id.split('/').pop() ?? id
  return last.replace(/_/g, ' ')
}

/** The zone's own long name — "Pacific Time" rather than "Pacific Standard/Daylight Time", which would
 *  flip with the season for no reason a reader could follow. Falls back through a couple of `Intl`
 *  option shapes, then to the bare id, for a runtime that doesn't support one of them. */
export function zoneLongName(id: string): string {
  for (const timeZoneName of ['longGeneric', 'long'] as const) {
    try {
      const parts = new Intl.DateTimeFormat('en-US', { timeZone: id, timeZoneName }).formatToParts(new Date())
      const name = parts.find((p) => p.type === 'timeZoneName')?.value
      if (name && name !== id) return name
    } catch {
      // Try the next shape.
    }
  }
  return id
}

/** "Pacific Time (Los Angeles)" — the long name plus the city the id names, when they differ. */
export function zoneDisplayLabel(id: string): string {
  const long = zoneLongName(id)
  if (long === id) return id
  const city = cityFromId(id)
  return city && city !== long ? `${long} (${city})` : long
}

function browserTimeZone(): string {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC'
  } catch {
    return 'UTC'
  }
}

function allTimeZones(current: string): string[] {
  let all: string[] = []
  try {
    all = (Intl as { supportedValuesOf?: (k: string) => string[] }).supportedValuesOf?.('timeZone') ?? []
  } catch {
    all = []
  }
  if (all.length === 0) {
    all = ['UTC', 'America/Los_Angeles', 'America/New_York', 'Europe/London', 'Europe/Berlin', 'Asia/Tokyo']
  }
  const withUtc = all.includes('UTC') ? all : ['UTC', ...all]
  return withUtc.includes(current) ? withUtc : [current, ...withUtc]
}

interface ZoneGroup {
  header: string | null
  zones: string[]
}

function buildGroups(allZones: string[], current: string, viewerZone: string, query: string): ZoneGroup[] {
  const q = query.trim().toLowerCase()
  const matches = (z: string) => !q || z.toLowerCase().includes(q) || zoneDisplayLabel(z).toLowerCase().includes(q)
  const pinned = [current, viewerZone].filter((z, i, arr) => arr.indexOf(z) === i && allZones.includes(z))
  const pinnedMatching = pinned.filter(matches)
  const rest = allZones.filter((z) => !pinned.includes(z) && matches(z))
  const byRegion = new Map<string, string[]>()
  for (const z of rest) {
    const region = z.split('/')[0] ?? 'Other'
    const list = byRegion.get(region) ?? []
    list.push(z)
    byRegion.set(region, list)
  }
  const groups: ZoneGroup[] = []
  if (pinnedMatching.length > 0) groups.push({ header: null, zones: pinnedMatching })
  for (const region of [...byRegion.keys()].sort((a, b) => a.localeCompare(b))) {
    groups.push({ header: region, zones: byRegion.get(region)!.sort((a, b) => a.localeCompare(b)) })
  }
  return groups
}

export function TimeZonePicker({ id, value, onChange, disabled, className, label = 'Time zone' }: TimeZonePickerProps) {
  const [open, setOpen] = useState(false)
  const [query, setQuery] = useState('')
  const viewerZone = useMemo(() => browserTimeZone(), [])
  const zones = useMemo(() => allTimeZones(value), [value])
  const groups = useMemo(() => buildGroups(zones, value, viewerZone, query), [zones, value, viewerZone, query])

  const rootRef = useRef<HTMLDivElement>(null)
  const panelRef = useRef<HTMLDivElement>(null)
  const inputRef = useRef<HTMLInputElement>(null)
  const [position, setPosition] = useState<{ top: number; left: number; width: number }>({ top: 0, left: 0, width: 280 })

  const place = useCallback(() => {
    const trigger = rootRef.current
    if (!trigger || typeof window === 'undefined') return
    const rect = trigger.getBoundingClientRect()
    setPosition({ top: rect.bottom + 4, left: rect.left, width: Math.max(rect.width, 280) })
  }, [])

  useLayoutEffect(() => {
    if (!open) return
    place()
    inputRef.current?.focus()
    window.addEventListener('resize', place)
    window.addEventListener('scroll', place, true)
    return () => {
      window.removeEventListener('resize', place)
      window.removeEventListener('scroll', place, true)
    }
  }, [open, place])

  useEffect(() => {
    if (!open) return
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        e.stopPropagation()
        setOpen(false)
      }
    }
    const onPointerDown = (e: MouseEvent) => {
      const target = e.target as Node
      const inTrigger = rootRef.current?.contains(target) ?? false
      const inPanel = panelRef.current?.contains(target) ?? false
      if (!inTrigger && !inPanel) setOpen(false)
    }
    document.addEventListener('keydown', onKey)
    document.addEventListener('mousedown', onPointerDown)
    return () => {
      document.removeEventListener('keydown', onKey)
      document.removeEventListener('mousedown', onPointerDown)
    }
  }, [open])

  function choose(zone: string) {
    onChange(zone)
    setOpen(false)
    setQuery('')
  }

  const triggerId = id ?? 'time-zone-picker'

  return (
    <div ref={rootRef} className={cn('relative', className)}>
      <div className="flex items-center gap-2">
        <span className="flex min-w-0 items-center gap-1.5 text-sm text-foreground">
          <Globe2 className="h-3.5 w-3.5 shrink-0 text-muted-foreground" aria-hidden="true" />
          <span className="truncate">{zoneDisplayLabel(value)}</span>
        </span>
        <Button
          type="button"
          id={triggerId}
          variant="link"
          size="sm"
          disabled={disabled}
          aria-expanded={open}
          onClick={() => setOpen((o) => !o)}
        >
          Change
        </Button>
      </div>

      {open &&
        typeof document !== 'undefined' &&
        createPortal(
          <div
            ref={panelRef}
            role="listbox"
            aria-label={label}
            style={{ position: 'fixed', top: position.top, left: position.left, width: position.width }}
            className="z-[60] max-h-80 overflow-hidden rounded-md border border-border bg-background shadow-md"
          >
            <div className="border-b border-border p-2">
              <Input
                ref={inputRef}
                value={query}
                onChange={(e) => setQuery(e.target.value)}
                placeholder="Search time zones…"
                aria-label="Search time zones"
                onKeyDown={(e) => {
                  if (e.key === 'Escape') {
                    e.preventDefault()
                    setOpen(false)
                  }
                }}
              />
            </div>
            <div className="max-h-64 overflow-y-auto py-1">
              {groups.length === 0 && (
                <p className="px-3 py-2 text-sm text-muted-foreground">No time zones match &ldquo;{query}&rdquo;.</p>
              )}
              {groups.map((group, gi) => (
                <div key={group.header ?? `pinned-${gi}`}>
                  {group.header && (
                    <div className="px-3 pt-2 pb-1 text-[11.5px] font-semibold uppercase tracking-[0.06em] text-muted-foreground">
                      {group.header}
                    </div>
                  )}
                  {group.zones.map((zone) => (
                    <button
                      key={zone}
                      type="button"
                      role="option"
                      aria-selected={zone === value}
                      onClick={() => choose(zone)}
                      className={cn(
                        'flex w-full items-center justify-between gap-2 px-3 py-1.5 text-left text-sm hover:bg-muted',
                        zone === value ? 'text-foreground' : 'text-foreground'
                      )}
                    >
                      <span className="min-w-0 truncate">{zoneDisplayLabel(zone)}</span>
                      {zone === value && <Check className="h-3.5 w-3.5 shrink-0 text-accent" aria-hidden="true" />}
                    </button>
                  ))}
                </div>
              ))}
            </div>
            <div className="flex justify-end border-t border-border p-1.5">
              <Button type="button" variant="ghost" size="sm" onClick={() => setOpen(false)}>
                Cancel
              </Button>
            </div>
          </div>,
          document.body
        )}
    </div>
  )
}
