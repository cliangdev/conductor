'use client'

import { ALL_EVENT_TYPES, EVENT_TYPE_DESCRIPTIONS } from '@/hooks/useNotifications'

interface EventTypeDropdownProps {
  value: string
  onChange: (v: string) => void
  usedEventTypes: string[]
  disabled?: boolean
}

export function EventTypeDropdown({ value, onChange, usedEventTypes, disabled }: EventTypeDropdownProps) {
  const availableTypes = disabled
    ? ALL_EVENT_TYPES
    : ALL_EVENT_TYPES.filter((t) => !usedEventTypes.includes(t) || t === value)

  return (
    <select
      value={value}
      onChange={(e) => onChange(e.target.value)}
      disabled={disabled}
      className="border border-border bg-background text-foreground rounded-md px-3 py-2 text-sm w-full focus:outline-none focus:ring-2 focus:ring-ring disabled:opacity-50 disabled:cursor-not-allowed"
    >
      <option value="">Select event type…</option>
      {availableTypes.map((type) => (
        <option key={type} value={type}>
          {EVENT_TYPE_DESCRIPTIONS[type]}
        </option>
      ))}
    </select>
  )
}
