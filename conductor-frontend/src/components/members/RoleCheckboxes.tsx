'use client'

import { Checkbox } from '@/components/ui/checkbox'
import { ROLE_LABELS, ROLE_ORDER } from '@/lib/permissions'
import type { MemberRole } from '@/types'

const ROLE_HINTS: Record<MemberRole, string> = {
  ADMIN: 'Manages the workspace; can also edit and review.',
  CREATOR: 'Can create and edit.',
  REVIEWER: 'Can approve or request changes.',
}

interface RoleCheckboxesProps {
  roles: MemberRole[]
  onChange: (roles: MemberRole[]) => void
  /** Accessible-name suffix, e.g. a member's name. */
  label?: string
  idPrefix: string
  disabled?: boolean
  /** Show the one-line description under each box (roomy layouts such as the invite dialog). */
  showHints?: boolean
  /** Leave the Admin box out, e.g. for an invite (an invite can't grant Admin). */
  hideAdmin?: boolean
  className?: string
}

/**
 * Admin / Editor / Reviewer boxes. Admin implies the other two, so ticking it shows them as
 * checked-and-disabled; ticking none means a read-only Viewer, which gets a muted hint.
 */
export function RoleCheckboxes({ roles, onChange, label, idPrefix, disabled, showHints, hideAdmin, className }: RoleCheckboxesProps) {
  const isAdmin = roles.includes('ADMIN')

  function toggle(role: MemberRole, on: boolean) {
    let next: MemberRole[]
    if (on) next = Array.from(new Set([...roles, role]))
    else if (role === 'ADMIN') {
      // Editor and Reviewer were shown ticked as implied; unticking Admin keeps what was displayed.
      next = Array.from(new Set([...roles.filter((r) => r !== 'ADMIN'), 'CREATOR', 'REVIEWER'])) as MemberRole[]
    } else next = roles.filter((r) => r !== role)
    onChange(ROLE_ORDER.filter((r) => next.includes(r)))
  }

  return (
    <div className={className}>
      <div className="flex flex-wrap items-start gap-x-4 gap-y-2">
        {ROLE_ORDER.filter((r) => !(hideAdmin && r === 'ADMIN')).map((role) => {
          const implied = isAdmin && role !== 'ADMIN'
          return (
            <Checkbox
              key={role}
              id={`${idPrefix}-${role}`}
              checked={implied || roles.includes(role)}
              disabled={disabled || implied}
              onCheckedChange={(on) => toggle(role, on)}
              aria-label={label ? `${ROLE_LABELS[role]} role for ${label}` : undefined}
              label={ROLE_LABELS[role]}
              description={showHints ? ROLE_HINTS[role] : undefined}
              disabledReason={implied ? <span className="text-xs">Included with Admin</span> : undefined}
            />
          )
        })}
      </div>
      {roles.length === 0 && <p className="mt-1 text-xs text-muted-foreground">Viewer (read-only)</p>}
    </div>
  )
}
