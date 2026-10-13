// The thumbnail body of one media item (image / video poster + duration / audio icon), shared by the
// MediaPicker grid and the Media library page. Render it inside a `relative` sized box.

import { FileAudioIcon, ImageOffIcon, PlayIcon, VideoOffIcon } from 'lucide-react'
import { formatDuration } from '@/lib/format'
import type { CreativePhoto } from '@/components/marketing/creatives/types'

export function MediaTile({ item }: { item: CreativePhoto }) {
  const kind = item.mediaKind ?? 'IMAGE'
  if (kind === 'VIDEO') {
    return (
      <>
        {item.posterUrl ? (
          // eslint-disable-next-line @next/next/no-img-element
          <img src={item.posterUrl} alt={item.label || 'Clip'} className="h-full w-full object-cover" />
        ) : (
          <span className="flex h-full items-center justify-center text-muted-foreground">
            <VideoOffIcon className="h-4 w-4" aria-hidden />
          </span>
        )}
        <span className="pointer-events-none absolute inset-0 flex items-center justify-center">
          <PlayIcon className="h-5 w-5 fill-background text-background drop-shadow" aria-hidden />
        </span>
        {item.durationSeconds != null && (
          <span className="pointer-events-none absolute bottom-1 right-1 rounded bg-foreground/70 px-1 py-0.5 text-[10px] text-background">
            {formatDuration(item.durationSeconds)}
          </span>
        )}
      </>
    )
  }
  if (kind === 'AUDIO') {
    return (
      <span className="flex h-full flex-col items-center justify-center gap-1 text-muted-foreground">
        <FileAudioIcon className="h-5 w-5" aria-hidden />
        {item.durationSeconds != null && <span className="text-[10px]">{formatDuration(item.durationSeconds)}</span>}
      </span>
    )
  }
  return item.url ? (
    // eslint-disable-next-line @next/next/no-img-element
    <img src={item.url} alt={item.label || 'Creative photo'} className="h-full w-full object-cover" />
  ) : (
    <span className="flex h-full items-center justify-center text-muted-foreground">
      <ImageOffIcon className="h-4 w-4" aria-hidden />
    </span>
  )
}

