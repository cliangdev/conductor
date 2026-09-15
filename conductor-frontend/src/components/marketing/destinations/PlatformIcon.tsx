import { cn } from '@/lib/utils'
import type { PublishPlatform } from './types'

const MONOGRAM: Record<PublishPlatform, string> = {
  facebook: 'Fb',
  instagram: 'Ig',
  youtube: 'Yt',
  tiktok: 'Tt',
}

const NAME: Record<PublishPlatform, string> = {
  facebook: 'Facebook',
  instagram: 'Instagram',
  youtube: 'YouTube',
  tiktok: 'TikTok',
}

/**
 * A 20px monogram tile that says which platform a row is for. A monogram rather than a brand mark on
 * purpose: the icon set is lucide, which has no brand icons, and the design system admits no other.
 */
export function PlatformIcon({ platform, className }: { platform: PublishPlatform; className?: string }) {
  return (
    <span
      role="img"
      aria-label={NAME[platform] ?? platform}
      title={NAME[platform] ?? platform}
      className={cn(
        'inline-flex h-5 w-5 shrink-0 items-center justify-center rounded-[5px] bg-surface-3 text-[10px] font-semibold tracking-wide text-muted-foreground',
        className
      )}
    >
      {MONOGRAM[platform] ?? platform.slice(0, 2)}
    </span>
  )
}
