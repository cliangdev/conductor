'use client'
// A date and time picker that expands in place: a month grid, then hour, minute and AM/PM. It works on
// the same wall-clock string a `datetime-local` input does (`YYYY-MM-DDTHH:mm`, no zone) so every
// caller keeps its zone handling and only swaps the control. The panel is rendered into the document
// body and fixed to the viewport beside the field, flipping above it when there is no room below: the
// fields it serves sit in a narrow sidebar and inside a scrolling dialog whose footer would otherwise
// clip anything positioned within it.
import { useCallback, useEffect, useId, useLayoutEffect, useMemo, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { CalendarClock, ChevronLeft, ChevronRight } from 'lucide-react'
import { cn } from '@/lib/utils'
import { Button } from '@/components/ui/button'
import { Select } from '@/components/ui/select'

export interface DateTimePickerProps {
  id?: string
  /** `YYYY-MM-DDTHH:mm` or empty. */
  value: string
  onChange: (value: string) => void
  /** Accessible name for the field; also the trigger's label when nothing is chosen. */
  label: string
  /** Earliest day that may be chosen, `YYYY-MM-DD` or `YYYY-MM-DDTHH:mm`. Earlier days are disabled. */
  min?: string
  disabled?: boolean
  /** Whether to offer a Clear action. */
  clearable?: boolean
  className?: string
  /** Start expanded — for an editor that opens straight into choosing a time. */
  defaultOpen?: boolean
  /** Which edge of the field the floating panel hangs from; `end` for a field against the right edge. */
  align?: 'start' | 'end'
}

interface WallClock {
  y: number
  m: number
  d: number
  h: number
  mi: number
}

const pad = (n: number) => String(n).padStart(2, '0')

export function parseWallClock(value: string | undefined | null): WallClock | null {
  const m = /^(\d{4})-(\d{2})-(\d{2})(?:T(\d{2}):(\d{2}))?$/.exec(value ?? '')
  if (!m) return null
  return { y: Number(m[1]), m: Number(m[2]), d: Number(m[3]), h: Number(m[4] ?? 0), mi: Number(m[5] ?? 0) }
}

export function formatWallClock(w: WallClock): string {
  return `${w.y}-${pad(w.m)}-${pad(w.d)}T${pad(w.h)}:${pad(w.mi)}`
}

/** A readable form of a wall clock, zone-free: "Tue, Sep 8, 2026, 9:15 AM". */
export function describeWallClock(value: string): string {
  const w = parseWallClock(value)
  if (!w) return ''
  return new Intl.DateTimeFormat(undefined, {
    month: 'short',
    day: 'numeric',
    year: 'numeric',
    hour: 'numeric',
    minute: '2-digit',
    timeZone: 'UTC',
  }).format(new Date(Date.UTC(w.y, w.m - 1, w.d, w.h, w.mi)))
}

function dayKey(y: number, m: number, d: number): number {
  return y * 10000 + m * 100 + d
}

function daysInMonth(y: number, m: number): number {
  return new Date(Date.UTC(y, m, 0)).getUTCDate()
}

/** Sunday-first weekday of the 1st of the month, 0–6. */
function firstWeekday(y: number, m: number): number {
  return new Date(Date.UTC(y, m - 1, 1)).getUTCDay()
}

const MONTHS = Array.from({ length: 12 }, (_, i) =>
  new Intl.DateTimeFormat(undefined, { month: 'long', timeZone: 'UTC' }).format(new Date(Date.UTC(2026, i, 1)))
)
const WEEKDAYS = Array.from({ length: 7 }, (_, i) =>
  new Intl.DateTimeFormat(undefined, { weekday: 'narrow', timeZone: 'UTC' }).format(new Date(Date.UTC(2026, 1, 1 + i)))
)
const MINUTES = [0, 15, 30, 45]

export function DateTimePicker({
  id,
  value,
  onChange,
  label,
  min,
  disabled,
  clearable,
  className,
  defaultOpen = false,
  align = 'start',
}: DateTimePickerProps) {
  const generatedId = useId()
  const triggerId = id ?? generatedId
  const [open, setOpen] = useState(defaultOpen)
  const chosen = useMemo(() => parseWallClock(value), [value])
  const today = useMemo(() => {
    const now = new Date()
    return { y: now.getFullYear(), m: now.getMonth() + 1, d: now.getDate() }
  }, [])
  const minDay = useMemo(() => {
    const w = parseWallClock(min)
    return w ? dayKey(w.y, w.m, w.d) : null
  }, [min])
  // The month on display follows the chosen day, else today.
  const [view, setView] = useState({ y: chosen?.y ?? today.y, m: chosen?.m ?? today.m })
  useEffect(() => {
    if (chosen) setView({ y: chosen.y, m: chosen.m })
  }, [chosen?.y, chosen?.m]) // eslint-disable-line react-hooks/exhaustive-deps

  const rootRef = useRef<HTMLDivElement>(null)
  const panelRef = useRef<HTMLDivElement>(null)
  const [position, setPosition] = useState<{ top?: number; bottom?: number; left: number }>({ left: 0 })

  // Place the panel next to the field, below it when it fits and above it otherwise, and follow the
  // field while anything scrolls or the window resizes.
  const place = useCallback(() => {
    const trigger = rootRef.current
    if (!trigger || typeof window === 'undefined') return
    const rect = trigger.getBoundingClientRect()
    const panelHeight = panelRef.current?.offsetHeight ?? 340
    const width = 288
    const gap = 4
    const left = Math.max(8, Math.min(align === 'end' ? rect.right - width : rect.left, window.innerWidth - width - 8))
    const fitsBelow = rect.bottom + gap + panelHeight <= window.innerHeight
    if (fitsBelow || rect.top - gap - panelHeight < 0) {
      setPosition({ top: rect.bottom + gap, left })
    } else {
      setPosition({ bottom: window.innerHeight - rect.top + gap, left })
    }
  }, [align])
  useLayoutEffect(() => {
    if (!open) return
    place()
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
      if (e.key === 'Escape') setOpen(false)
    }
    const onPointerDown = (e: MouseEvent) => {
      const target = e.target as Node
      const inField = rootRef.current?.contains(target) ?? false
      const inPanel = panelRef.current?.contains(target) ?? false
      if (!inField && !inPanel) setOpen(false)
    }
    document.addEventListener('keydown', onKey)
    document.addEventListener('mousedown', onPointerDown)
    return () => {
      document.removeEventListener('keydown', onKey)
      document.removeEventListener('mousedown', onPointerDown)
    }
  }, [open])

  const years = useMemo(() => {
    const base = today.y
    const set = new Set<number>()
    for (let y = base - 1; y <= base + 5; y++) set.add(y)
    if (chosen) set.add(chosen.y)
    set.add(view.y)
    return Array.from(set).sort((a, b) => a - b)
  }, [today.y, chosen, view.y])

  const emit = useCallback(
    (next: WallClock) => onChange(formatWallClock(next)),
    [onChange]
  )

  const pickDay = (d: number) => {
    // A first pick with no time yet lands on a sensible hour rather than midnight.
    const base: WallClock = chosen ?? { y: view.y, m: view.m, d, h: 9, mi: 0 }
    emit({ ...base, y: view.y, m: view.m, d })
  }
  const setTime = (h: number, mi: number) => {
    const base: WallClock = chosen ?? { y: today.y, m: today.m, d: today.d, h: 9, mi: 0 }
    emit({ ...base, h, mi })
  }
  const pickNow = () => {
    const now = new Date()
    const slot = new Date(now.getTime())
    slot.setSeconds(0, 0)
    slot.setMinutes(Math.ceil(slot.getMinutes() / 15) * 15)
    emit({ y: slot.getFullYear(), m: slot.getMonth() + 1, d: slot.getDate(), h: slot.getHours(), mi: slot.getMinutes() })
  }

  const hour12 = chosen ? ((chosen.h + 11) % 12) + 1 : 9
  const meridiem = chosen ? (chosen.h >= 12 ? 'PM' : 'AM') : 'AM'
  const minute = chosen?.mi ?? 0
  const minuteOptions = MINUTES.includes(minute) ? MINUTES : [...MINUTES, minute].sort((a, b) => a - b)
  const toHour24 = (h12: number, mer: string) => (mer === 'PM' ? (h12 % 12) + 12 : h12 % 12)

  const monthLabel = `${MONTHS[view.m - 1]} ${view.y}`
  const leading = firstWeekday(view.y, view.m)
  const count = daysInMonth(view.y, view.m)
  const cells: Array<number | null> = [...Array<null>(leading).fill(null), ...Array.from({ length: count }, (_, i) => i + 1)]
  while (cells.length % 7 !== 0) cells.push(null)

  const stepMonth = (delta: number) => {
    let m = view.m + delta
    let y = view.y
    if (m < 1) { m = 12; y -= 1 }
    if (m > 12) { m = 1; y += 1 }
    setView({ y, m })
  }

  const cell = 'h-8 w-8 rounded-md text-sm tabular-nums'

  return (
    <div ref={rootRef} className={cn('relative', className)}>
      <button
        type="button"
        id={triggerId}
        aria-label={label}
        aria-expanded={open}
        disabled={disabled}
        onClick={() => setOpen((o) => !o)}
        className={cn(
          'flex w-full items-center gap-2 rounded-md border border-border bg-background px-2.5 py-1.5 text-left text-sm',
          'focus:outline-none focus:ring-2 focus:ring-ring disabled:opacity-50',
          chosen ? 'text-foreground' : 'text-muted-foreground'
        )}
      >
        <CalendarClock className="h-3.5 w-3.5 shrink-0 text-muted-foreground" aria-hidden="true" />
        <span className="min-w-0 flex-1 truncate">{chosen ? describeWallClock(value) : 'Pick a date and time'}</span>
      </button>

      {open && typeof document !== 'undefined' && createPortal(
        <div
          ref={panelRef}
          role="group"
          aria-label={`${label} picker`}
          style={{ position: 'fixed', top: position.top, bottom: position.bottom, left: position.left, width: 288 }}
          className="z-[60] rounded-md border border-border bg-background p-3 shadow-md"
        >
          <div className="mb-2 flex items-center gap-1">
            <button
              type="button"
              aria-label="Previous month"
              onClick={() => stepMonth(-1)}
              className="rounded-md p-1 text-muted-foreground hover:bg-muted hover:text-foreground"
            >
              <ChevronLeft className="h-4 w-4" aria-hidden="true" />
            </button>
            <label className="sr-only" htmlFor={`${triggerId}-month`}>Month</label>
            <div className="min-w-0 flex-1">
              <Select
                id={`${triggerId}-month`}
                value={view.m}
                onChange={(e) => setView({ ...view, m: Number(e.target.value) })}
                className="h-8 w-full py-0 text-sm"
              >
                {MONTHS.map((name, i) => (
                  <option key={name} value={i + 1}>{name}</option>
                ))}
              </Select>
            </div>
            <label className="sr-only" htmlFor={`${triggerId}-year`}>Year</label>
            <div className="w-20 shrink-0">
              <Select
                id={`${triggerId}-year`}
                value={view.y}
                onChange={(e) => setView({ ...view, y: Number(e.target.value) })}
                className="h-8 w-full py-0 text-sm"
              >
                {years.map((y) => (
                  <option key={y} value={y}>{y}</option>
                ))}
              </Select>
            </div>
            <button
              type="button"
              aria-label="Next month"
              onClick={() => stepMonth(1)}
              className="rounded-md p-1 text-muted-foreground hover:bg-muted hover:text-foreground"
            >
              <ChevronRight className="h-4 w-4" aria-hidden="true" />
            </button>
          </div>

          <div className="grid grid-cols-7 gap-0.5" role="grid" aria-label={monthLabel}>
            {WEEKDAYS.map((wd, i) => (
              <div key={i} className="h-6 text-center text-xs text-muted-foreground" aria-hidden="true">{wd}</div>
            ))}
            {cells.map((d, i) => {
              if (d === null) return <div key={`e${i}`} className={cell} aria-hidden="true" />
              const key = dayKey(view.y, view.m, d)
              const isChosen = !!chosen && chosen.y === view.y && chosen.m === view.m && chosen.d === d
              const isToday = today.y === view.y && today.m === view.m && today.d === d
              const tooEarly = minDay !== null && key < minDay
              const dateLabel = new Intl.DateTimeFormat(undefined, { month: 'long', day: 'numeric', year: 'numeric', timeZone: 'UTC' })
                .format(new Date(Date.UTC(view.y, view.m - 1, d)))
              return (
                <button
                  key={d}
                  type="button"
                  role="gridcell"
                  aria-label={dateLabel}
                  aria-pressed={isChosen}
                  disabled={tooEarly}
                  onClick={() => pickDay(d)}
                  className={cn(
                    cell,
                    'hover:bg-muted disabled:cursor-not-allowed disabled:opacity-40 disabled:hover:bg-transparent',
                    isChosen && 'bg-primary text-primary-foreground hover:bg-primary',
                    !isChosen && isToday && 'ring-1 ring-inset ring-border font-medium'
                  )}
                >
                  {d}
                </button>
              )
            })}
          </div>

          <div className="mt-3 flex items-center gap-1">
            <label className="sr-only" htmlFor={`${triggerId}-hour`}>Hour</label>
            <div className="w-16 shrink-0">
              <Select
                id={`${triggerId}-hour`}
                value={hour12}
                onChange={(e) => setTime(toHour24(Number(e.target.value), meridiem), minute)}
                className="h-8 w-full py-0 text-sm"
              >
                {Array.from({ length: 12 }, (_, i) => i + 1).map((h) => (
                  <option key={h} value={h}>{h}</option>
                ))}
              </Select>
            </div>
            <span className="text-sm text-muted-foreground" aria-hidden="true">:</span>
            <label className="sr-only" htmlFor={`${triggerId}-minute`}>Minute</label>
            <div className="w-16 shrink-0">
              <Select
                id={`${triggerId}-minute`}
                value={minute}
                onChange={(e) => setTime(toHour24(hour12, meridiem), Number(e.target.value))}
                className="h-8 w-full py-0 text-sm"
              >
                {minuteOptions.map((mi) => (
                  <option key={mi} value={mi}>{pad(mi)}</option>
                ))}
              </Select>
            </div>
            <label className="sr-only" htmlFor={`${triggerId}-meridiem`}>AM or PM</label>
            <div className="w-[4.5rem] shrink-0">
              <Select
                id={`${triggerId}-meridiem`}
                value={meridiem}
                onChange={(e) => setTime(toHour24(hour12, e.target.value), minute)}
                className="h-8 w-full py-0 text-sm"
              >
                <option value="AM">AM</option>
                <option value="PM">PM</option>
              </Select>
            </div>
          </div>
          <div className="mt-2 flex items-center justify-end gap-1">
            <Button type="button" variant="ghost" size="sm" onClick={pickNow}>Now</Button>
            {clearable && chosen && (
              <Button type="button" variant="ghost" size="sm" onClick={() => onChange('')}>Clear</Button>
            )}
            <Button type="button" variant="outline" size="sm" onClick={() => setOpen(false)}>Done</Button>
          </div>
        </div>,
        document.body
      )}
    </div>
  )
}
