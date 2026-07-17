// Emoji avatar for Agents — mirrors src/components/workitems/UserAvatar.tsx's conventions (static
// size lookup, not an interpolated class string; Tailwind can't see a template class at build time
// and would drop it).
import { cn } from '@/lib/utils'

/** Matches the backend's AgentAvatarDefaults.COLOR_TOKENS order exactly. */
export const AVATAR_COLOR_TOKENS = ['gray', 'blue', 'amber', 'violet', 'teal', 'green', 'rose', 'slate'] as const

export type AvatarColorToken = (typeof AVATAR_COLOR_TOKENS)[number]

export type AgentAvatarSize = 'sm' | 'md' | 'lg'

export const AVATAR_SIZE_CLASSES: Record<AgentAvatarSize, string> = {
  sm: 'h-5 w-5 text-[11px]',
  md: 'h-8 w-8 text-base',
  lg: 'h-12 w-12 text-2xl',
}

// bg-avatar-* classes read the identity tokens added to globals.css/tailwind.config.ts —
// deliberately separate from the status ramp (StatusBadge's HUE_CLASSES).
export const AVATAR_COLOR_CLASSES: Record<AvatarColorToken, string> = {
  gray: 'bg-avatar-gray',
  blue: 'bg-avatar-blue',
  amber: 'bg-avatar-amber',
  violet: 'bg-avatar-violet',
  teal: 'bg-avatar-teal',
  green: 'bg-avatar-green',
  rose: 'bg-avatar-rose',
  slate: 'bg-avatar-slate',
}

const FALLBACK_COLOR_CLASS = 'bg-muted'

export function isAvatarColorToken(value: string | null | undefined): value is AvatarColorToken {
  return !!value && (AVATAR_COLOR_TOKENS as readonly string[]).includes(value)
}

export function AgentAvatar({
  emoji,
  color,
  size = 'md',
  className,
}: {
  emoji: string
  color: string
  size?: AgentAvatarSize
  className?: string
}) {
  const colorClass = isAvatarColorToken(color) ? AVATAR_COLOR_CLASSES[color] : FALLBACK_COLOR_CLASS
  return (
    <div
      className={cn(
        'inline-flex items-center justify-center rounded-full shrink-0',
        AVATAR_SIZE_CLASSES[size],
        colorClass,
        className
      )}
    >
      {/* The agent name is always adjacent text — the emoji itself carries no independent meaning. */}
      <span aria-hidden className="leading-none">
        {emoji}
      </span>
    </div>
  )
}
