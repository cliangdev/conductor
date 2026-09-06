'use client'

// COND-post-formats: which shape a destination publishes in.
//
// `feed` is a platform's ordinary post and the default; `reel` is short vertical video on Facebook
// or Instagram; `story` is a single image or clip on Facebook or Instagram that disappears after 24
// hours, takes no caption, and is fired by Conductor at its time because neither platform can
// schedule one natively. A platform's own `formats` (from `GET .../publish-targets`) says which of
// the three it offers — hidden entirely when that list is just `["feed"]`, since there is nothing to
// choose. The choice rides along as `format` on the same set-replace PUT everything else here uses;
// leaving it out (an older client, or a target this selector never rendered for) reads as `feed`.

import { cn } from '@/lib/utils'
import { Badge } from '@/components/ui/badge'
import type { PublishPlatform } from './PostTargetPicker'

export type PostFormat = 'feed' | 'reel' | 'story'

export const ALL_POST_FORMATS: PostFormat[] = ['feed', 'reel', 'story']

const FORMAT_LABELS: Record<PostFormat, string> = {
  feed: 'Feed',
  reel: 'Reel',
  story: 'Story',
}

/** Facebook's reel window is 3–90s; Instagram's stretches to 15 minutes of vertical video. */
const REEL_DURATION_HINT: Partial<Record<PublishPlatform, string>> = {
  facebook: 'Vertical video, 3–90 seconds.',
  instagram: 'Vertical video, up to 15 minutes.',
}

const STORY_HINT =
  "One image or clip, and no caption — it disappears after 24 hours, and Conductor fires it at the scheduled time."

/** The helper line under the segmented control for whichever format is currently chosen. */
export function formatHelperText(platform: PublishPlatform, format: PostFormat): string | null {
  if (format === 'story') return STORY_HINT
  if (format === 'reel') return REEL_DURATION_HINT[platform] ?? 'Short vertical video.'
  return null
}

interface PostFormatSelectorProps {
  idPrefix: string
  platform: PublishPlatform
  /** The formats this platform's option row offers, e.g. `['feed', 'reel', 'story']`. */
  formats: string[] | undefined
  value: PostFormat
  onChange: (next: PostFormat) => void
  disabled?: boolean
}

/**
 * A segmented control over the formats one destination offers. Renders nothing when the platform
 * only offers `feed` — there is no choice to show, and a control with one always-selected option
 * would just be chrome.
 */
export function PostFormatSelector({
  idPrefix,
  platform,
  formats,
  value,
  onChange,
  disabled,
}: PostFormatSelectorProps) {
  const offered = ALL_POST_FORMATS.filter((f) => (formats ?? ['feed']).includes(f))
  if (offered.length <= 1) return null

  const helper = formatHelperText(platform, value)

  return (
    <div className="space-y-1.5">
      <div
        role="radiogroup"
        aria-label="Post format"
        className="inline-flex rounded-md border border-border p-0.5"
      >
        {offered.map((format) => (
          <button
            key={format}
            type="button"
            role="radio"
            id={`${idPrefix}-format-${format}`}
            aria-checked={value === format}
            disabled={disabled}
            onClick={() => onChange(format)}
            className={cn(
              'rounded px-2.5 py-1 text-xs font-medium transition-colors disabled:cursor-not-allowed disabled:opacity-50',
              value === format
                ? 'bg-primary text-primary-foreground'
                : 'text-muted-foreground hover:bg-muted'
            )}
          >
            {FORMAT_LABELS[format]}
          </button>
        ))}
      </div>
      {helper && <p className="text-xs text-muted-foreground">{helper}</p>}
    </div>
  )
}

/** The small "Reel"/"Story" marker on a destination row. Nothing renders for `feed`. */
export function FormatBadge({ format }: { format?: string | null }) {
  if (!format || format === 'feed') return null
  return (
    <Badge variant="outline" className="ml-1.5 px-1.5 py-0 text-[10px] font-medium leading-4">
      {format === 'reel' ? 'Reel' : 'Story'}
    </Badge>
  )
}
