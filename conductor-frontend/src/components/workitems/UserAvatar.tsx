// Shared avatar renderer for Work Item surfaces (list rows, board cards, mobile cards). Promoted out
// of WorkItemListView so WorkItemBoardView and the new row/group components share one implementation.

export type AvatarSize = 4 | 5 | 6

// Static lookup, not an interpolated `w-${size}` string — Tailwind can't see a template class at
// build time and would drop it.
export const AVATAR_SIZE_CLASSES: Record<AvatarSize, string> = {
  4: 'w-4 h-4',
  5: 'w-5 h-5',
  6: 'w-6 h-6',
}

export function UserAvatar({
  name,
  avatarUrl,
  size = 6,
  className,
  label,
}: {
  name: string
  avatarUrl?: string | null
  size?: AvatarSize
  className?: string
  /** Text alternative override (title + aria-label) — e.g. a reviewer's name plus their verdict, so color isn't the only signal. Defaults to `name`. */
  label?: string
}) {
  const cls = `${AVATAR_SIZE_CLASSES[size]} rounded-full ${className ?? ''}`.trim()
  const text = label ?? name
  if (avatarUrl) {
    // eslint-disable-next-line @next/next/no-img-element
    return <img src={avatarUrl} alt={text} aria-label={text} className={`${cls} border border-border object-cover`} title={text} />
  }
  return (
    <div
      className={`${cls} bg-muted flex items-center justify-center text-xs font-medium text-muted-foreground border border-border shrink-0`}
      title={text}
      aria-label={text}
      role="img"
    >
      {name.charAt(0).toUpperCase()}
    </div>
  )
}

/** The dashed-circle "unassigned" placeholder — one implementation shared by AssigneeCell, ReviewerCell, and WorkItemRow instead of three pasted copies. */
export function EmptyAvatarSlot({ size = 5, className }: { size?: AvatarSize; className?: string }) {
  return (
    <span
      className={`${AVATAR_SIZE_CLASSES[size]} rounded-full border border-dashed border-border shrink-0 ${className ?? ''}`.trim()}
    />
  )
}
