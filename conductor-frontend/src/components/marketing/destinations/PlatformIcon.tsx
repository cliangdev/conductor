'use client'

import { useState } from 'react'
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
 * A 20px tile that says which platform a row is for: the platform's brand mark from
 * `/public/integrations/{platform}.svg` (the same folder `ConnectorIcon` reads), falling back to a
 * monogram when the asset is missing or fails to load. A brand mark is identity content, like a
 * connector logo, not UI chrome — lucide stays the one icon set for chrome.
 */
export function PlatformIcon({ platform, className }: { platform: PublishPlatform; className?: string }) {
  const [failed, setFailed] = useState(false)
  const name = NAME[platform] ?? platform

  if (!failed) {
    return (
      // eslint-disable-next-line @next/next/no-img-element
      <img
        src={`/integrations/${platform}.svg`}
        alt={name}
        title={name}
        className={cn('h-5 w-5 shrink-0 rounded-[5px]', className)}
        onError={() => setFailed(true)}
      />
    )
  }

  return (
    <span
      role="img"
      aria-label={name}
      title={name}
      className={cn(
        'inline-flex h-5 w-5 shrink-0 items-center justify-center rounded-[5px] bg-surface-3 text-[10px] font-semibold tracking-wide text-muted-foreground',
        className
      )}
    >
      {MONOGRAM[platform] ?? platform.slice(0, 2)}
    </span>
  )
}
