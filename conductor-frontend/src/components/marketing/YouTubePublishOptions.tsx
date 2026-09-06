'use client'

// The per-target publish options a YouTube destination carries, beyond caption and media.
//
// YouTube is feed-only (a video upload has no reel or story shape), so nothing here branches on
// format. Every field is optional and omitted rather than defaulted when nobody has touched it, the
// same rule the other platforms' bags follow.

import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Select } from '@/components/ui/select'
import { Switch } from '@/components/ui/switch'
import type { MediaAsset } from '@/components/workitems/MediaUploadPanel'

export interface YouTubePublishOptionValues {
  notifySubscribers?: boolean
  madeForKids?: boolean
  containsSyntheticMedia?: boolean
  playlistIds?: string[]
  thumbnailAssetId?: string
}

/** Fills a stored (or absent) payload out without inventing values for fields nobody set. */
export function normalizeYouTubeOptions(
  raw: Partial<YouTubePublishOptionValues> | null | undefined
): YouTubePublishOptionValues {
  return { ...raw }
}

/** Parses the comma-separated playlist ids box back into a trimmed list, dropping blanks. */
export function parsePlaylistIds(text: string): string[] {
  return text
    .split(',')
    .map((s) => s.trim())
    .filter((s) => s.length > 0)
}

interface SwitchRowProps {
  id: string
  label: string
  checked: boolean
  disabled?: boolean
  onCheckedChange: (checked: boolean) => void
}

function SwitchRow({ id, label, checked, disabled, onCheckedChange }: SwitchRowProps) {
  return (
    <div className="flex items-start justify-between gap-3">
      <span id={`${id}-label`} className="block text-sm text-foreground">
        {label}
      </span>
      <Switch
        id={id}
        checked={checked}
        disabled={disabled}
        onCheckedChange={onCheckedChange}
        aria-labelledby={`${id}-label`}
      />
    </div>
  )
}

interface YouTubePublishOptionsProps {
  idPrefix: string
  /** The Post's own image assets, for the thumbnail picker. */
  images: MediaAsset[]
  value: YouTubePublishOptionValues
  onChange: (next: YouTubePublishOptionValues) => void
  disabled?: boolean
}

export function YouTubePublishOptions({
  idPrefix,
  images,
  value,
  onChange,
  disabled,
}: YouTubePublishOptionsProps) {
  const set = (patch: Partial<YouTubePublishOptionValues>) => onChange({ ...value, ...patch })
  const playlistsText = (value.playlistIds ?? []).join(', ')

  return (
    <div className="space-y-4 border-t border-border bg-surface-raised px-4 py-3">
      <SwitchRow
        id={`${idPrefix}-notify`}
        label="Notify subscribers"
        checked={value.notifySubscribers ?? false}
        disabled={disabled}
        onCheckedChange={(checked) => set({ notifySubscribers: checked })}
      />
      <SwitchRow
        id={`${idPrefix}-made-for-kids`}
        label="Made for kids"
        checked={value.madeForKids ?? false}
        disabled={disabled}
        onCheckedChange={(checked) => set({ madeForKids: checked })}
      />
      <SwitchRow
        id={`${idPrefix}-synthetic-media`}
        label="Contains altered or synthetic media"
        checked={value.containsSyntheticMedia ?? false}
        disabled={disabled}
        onCheckedChange={(checked) => set({ containsSyntheticMedia: checked })}
      />

      <div className="space-y-1">
        <Label htmlFor={`${idPrefix}-playlists`}>Playlists</Label>
        <Input
          id={`${idPrefix}-playlists`}
          placeholder="playlist id, playlist id"
          disabled={disabled}
          value={playlistsText}
          onChange={(e) => {
            const parsed = parsePlaylistIds(e.target.value)
            set({ playlistIds: parsed.length === 0 ? undefined : parsed })
          }}
        />
        <p className="text-xs text-muted-foreground">Comma-separated playlist ids to add this video to.</p>
      </div>

      <div className="space-y-1">
        <Label htmlFor={`${idPrefix}-thumbnail`}>Thumbnail</Label>
        <Select
          id={`${idPrefix}-thumbnail`}
          disabled={disabled}
          value={value.thumbnailAssetId ?? ''}
          onChange={(e) => set({ thumbnailAssetId: e.target.value || undefined })}
        >
          <option value="">Let YouTube choose</option>
          {images.map((asset) => (
            <option key={asset.id} value={asset.id}>
              {asset.label || asset.type}
            </option>
          ))}
        </Select>
      </div>
    </div>
  )
}
