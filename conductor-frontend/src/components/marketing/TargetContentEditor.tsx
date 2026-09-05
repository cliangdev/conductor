'use client'

import { ArrowDown, ArrowUp, RotateCcw } from 'lucide-react'

import { Button } from '@/components/ui/button'
import { isVideoContentType, type MediaAsset } from '@/components/workitems/MediaUploadPanel'

/** What one destination publishes, as the picker holds it while it is being edited. */
export interface TargetContent {
  /** null means "use the Post's caption" — the default, and what clearing the box returns to. */
  captionOverride: string | null
  /** null means "publish the Post's whole media set, in Post order". */
  assetIds: string[] | null
}

export const INHERITED_CONTENT: TargetContent = { captionOverride: null, assetIds: null }

interface TargetContentEditorProps {
  /** The Post's uploaded media, in Post order — everything this destination may choose from. */
  assets: MediaAsset[]
  /** The Post's own caption, shown as the placeholder a destination falls back to. */
  postCaption: string | null
  value: TargetContent
  onChange: (next: TargetContent) => void
  disabled?: boolean
}

/**
 * Per-destination caption and media, for one target.
 *
 * <p>Both fields inherit by default and say so, because "using the Post's media" and "happens to have
 * selected exactly the Post's media" are different states: the first keeps following the Post as files
 * are added and removed, and only an explicit Reset gets back to it.
 *
 * <p>Nothing here saves on its own. Every save is a PUT that can send an approved Post back for review,
 * so the parent commits the whole selection at once rather than firing on each keystroke.
 */
export function TargetContentEditor({
  assets,
  postCaption,
  value,
  onChange,
  disabled,
}: TargetContentEditorProps) {
  const inheritsCaption = value.captionOverride === null
  const inheritsMedia = value.assetIds === null
  // The effective order: the chosen subset, or the Post's whole set when inheriting.
  const selectedIds = value.assetIds ?? assets.map((a) => a.id)

  function setAssetIds(next: string[]) {
    onChange({ ...value, assetIds: next })
  }

  function toggle(assetId: string) {
    // The first edit turns an inherited set into an explicit one, starting from what was on screen —
    // so unticking one of three leaves the other two rather than starting from nothing.
    const current = selectedIds
    setAssetIds(
      current.includes(assetId) ? current.filter((id) => id !== assetId) : [...current, assetId]
    )
  }

  function move(assetId: string, delta: number) {
    const current = [...selectedIds]
    const from = current.indexOf(assetId)
    const to = from + delta
    if (from < 0 || to < 0 || to >= current.length) return
    const [moved] = current.splice(from, 1)
    current.splice(to, 0, moved!)
    setAssetIds(current)
  }

  return (
    <div className="mt-2 space-y-4 rounded-md border border-border bg-surface-2 p-3">
      <div className="space-y-1.5">
        <div className="flex items-center justify-between gap-2">
          <label
            className="text-xs font-medium text-foreground"
            htmlFor={`caption-override-${assets.length}`}
          >
            Caption for this destination
          </label>
          {!inheritsCaption && (
            <Button
              type="button"
              size="sm"
              variant="ghost"
              disabled={disabled}
              onClick={() => onChange({ ...value, captionOverride: null })}
            >
              <RotateCcw className="mr-1 h-3 w-3" aria-hidden />
              Use the Post&apos;s
            </Button>
          )}
        </div>
        <textarea
          id={`caption-override-${assets.length}`}
          rows={3}
          disabled={disabled}
          className="w-full rounded-md border border-input bg-background px-2 py-1.5 text-sm"
          placeholder={postCaption ?? "The Post's caption"}
          value={value.captionOverride ?? ''}
          onChange={(e) =>
            // An emptied box is not an empty caption — it is a return to the Post's.
            onChange({ ...value, captionOverride: e.target.value === '' ? null : e.target.value })
          }
        />
        {inheritsCaption && (
          <p className="text-xs text-muted-foreground">Using the Post&apos;s caption.</p>
        )}
      </div>

      <div className="space-y-1.5">
        <div className="flex items-center justify-between gap-2">
          <span className="text-xs font-medium text-foreground">Media for this destination</span>
          {!inheritsMedia && (
            <Button
              type="button"
              size="sm"
              variant="ghost"
              disabled={disabled}
              onClick={() => onChange({ ...value, assetIds: null })}
            >
              <RotateCcw className="mr-1 h-3 w-3" aria-hidden />
              Use all Post media
            </Button>
          )}
        </div>

        {assets.length === 0 ? (
          <p className="text-xs text-muted-foreground">
            Upload media to the Post first, then choose which files go here.
          </p>
        ) : (
          <>
            <ul className="space-y-1">
              {orderedForDisplay(assets, selectedIds).map((asset) => {
                const included = selectedIds.includes(asset.id)
                const position = selectedIds.indexOf(asset.id)
                return (
                  <li
                    key={asset.id}
                    className="flex items-center gap-2 rounded-md border border-border px-2 py-1.5"
                  >
                    <input
                      type="checkbox"
                      checked={included}
                      disabled={disabled}
                      onChange={() => toggle(asset.id)}
                      aria-label={`Publish ${asset.label || asset.type} here`}
                    />
                    <MediaThumb asset={asset} />
                    <span className="min-w-0 flex-1 truncate text-xs">
                      {asset.label || asset.type}
                    </span>
                    {included && (
                      <span className="flex shrink-0 items-center gap-0.5">
                        <Button
                          type="button"
                          size="sm"
                          variant="ghost"
                          disabled={disabled || position === 0}
                          onClick={() => move(asset.id, -1)}
                          aria-label={`Move ${asset.label || asset.type} earlier`}
                        >
                          <ArrowUp className="h-3 w-3" aria-hidden />
                        </Button>
                        <Button
                          type="button"
                          size="sm"
                          variant="ghost"
                          disabled={disabled || position === selectedIds.length - 1}
                          onClick={() => move(asset.id, 1)}
                          aria-label={`Move ${asset.label || asset.type} later`}
                        >
                          <ArrowDown className="h-3 w-3" aria-hidden />
                        </Button>
                      </span>
                    )}
                  </li>
                )
              })}
            </ul>
            <p className="text-xs text-muted-foreground">
              {inheritsMedia
                ? `Using all Post media (${assets.length}).`
                : selectedIds.length === 0
                  ? 'Nothing selected — this destination cannot be approved until it has media.'
                  : 'Order matters: Instagram crops a carousel to its first item, and TikTok covers a photo post with it.'}
            </p>
          </>
        )}
      </div>
    </div>
  )
}

/**
 * Selected media first, in its own order, then the rest of the Post's in Post order. Without this the
 * reorder buttons would move an item that stayed visually still, because the list was rendered in Post
 * order rather than in publish order.
 */
function orderedForDisplay(assets: MediaAsset[], selectedIds: string[]): MediaAsset[] {
  const byId = new Map(assets.map((asset) => [asset.id, asset]))
  const chosen = selectedIds.map((id) => byId.get(id)).filter((a): a is MediaAsset => Boolean(a))
  const rest = assets.filter((asset) => !selectedIds.includes(asset.id))
  return [...chosen, ...rest]
}

/** A small preview, or a neutral placeholder while an upload has not finished. */
function MediaThumb({ asset }: { asset: MediaAsset }) {
  if (!asset.previewUrl) {
    return <span className="h-8 w-8 shrink-0 rounded bg-surface-3" aria-hidden />
  }
  if (isVideoContentType(asset.contentType)) {
    return (
      <video
        src={asset.previewUrl}
        className="h-8 w-8 shrink-0 rounded bg-surface-3 object-cover"
        aria-label={asset.label || asset.type}
      />
    )
  }
  return (
    // eslint-disable-next-line @next/next/no-img-element
    <img
      src={asset.previewUrl}
      alt={asset.label || asset.type}
      className="h-8 w-8 shrink-0 rounded bg-surface-3 object-cover"
    />
  )
}
