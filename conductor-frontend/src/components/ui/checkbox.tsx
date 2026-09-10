'use client'

import * as React from 'react'
import { Check } from 'lucide-react'
import { cn } from '@/lib/utils'

export interface CheckboxProps {
  checked: boolean
  onCheckedChange: (checked: boolean) => void
  disabled?: boolean
  className?: string
  id?: string
  /** The clickable label text/content, associated with the box via a generated id when `id` is omitted. */
  label: React.ReactNode
  /** A quieter sentence under the label — extra context that isn't the label itself. */
  description?: React.ReactNode
  /**
   * Why this control is disabled, rendered as a visible sentence beside the label — not a `title`
   * attribute, which a person using a screen reader or a touch device never sees.
   */
  disabledReason?: React.ReactNode
  'aria-label'?: string
  'aria-describedby'?: string
}

/**
 * A checkbox with a real, clickable `<label>` — so `getByLabelText` and a screen reader both find it —
 * built from a native `<input type="checkbox">` visually hidden under a token-styled box, rather than a
 * styled `<div>` faking the role. Tokens only: `border-strong` for the box, `accent` when checked, the
 * same focus ring as `Switch`.
 */
const Checkbox = React.forwardRef<HTMLInputElement, CheckboxProps>(
  (
    {
      checked,
      onCheckedChange,
      disabled,
      className,
      id,
      label,
      description,
      disabledReason,
      'aria-describedby': ariaDescribedBy,
      ...props
    },
    ref
  ) => {
    const generatedId = React.useId()
    const inputId = id ?? generatedId
    const descId = description ? `${inputId}-description` : undefined
    const reasonId = disabled && disabledReason ? `${inputId}-disabled-reason` : undefined

    return (
      <div className={cn('flex items-start gap-2.5', className)}>
        <span className="relative mt-0.5 inline-flex h-4 w-4 shrink-0 items-center justify-center">
          <input
            ref={ref}
            id={inputId}
            type="checkbox"
            checked={checked}
            disabled={disabled}
            aria-describedby={[descId, reasonId, ariaDescribedBy].filter(Boolean).join(' ') || undefined}
            onChange={(e) => onCheckedChange(e.target.checked)}
            className={cn(
              'peer h-4 w-4 shrink-0 cursor-pointer appearance-none rounded border border-border-strong bg-background',
              'transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background',
              'checked:border-accent checked:bg-accent',
              'disabled:cursor-not-allowed disabled:opacity-50'
            )}
            {...props}
          />
          <Check
            aria-hidden="true"
            className="pointer-events-none absolute h-3 w-3 text-accent-foreground opacity-0 peer-checked:opacity-100"
          />
        </span>
        <span className="min-w-0 flex-1">
          <label
            htmlFor={inputId}
            className={cn('block text-sm text-foreground', disabled ? 'cursor-not-allowed' : 'cursor-pointer')}
          >
            {label}
          </label>
          {description && (
            <span id={descId} className="block text-sm text-muted-foreground">
              {description}
            </span>
          )}
          {disabled && disabledReason && (
            <span id={reasonId} className="block text-sm text-muted-foreground">
              {disabledReason}
            </span>
          )}
        </span>
      </div>
    )
  }
)
Checkbox.displayName = 'Checkbox'

export { Checkbox }
