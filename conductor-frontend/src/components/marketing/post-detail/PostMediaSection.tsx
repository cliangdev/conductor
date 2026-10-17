'use client'

// The Post's media, read two ways. "By destination" answers what each account will actually get: that
// destination's files, in its order, at their real shape. "All files" answers where each file came from
// and which destinations use it. Every tile opens the full-size viewer, whose side panel says where the
// file goes, where it came from, and lets an author take it off the Post.

import { useMemo, useState } from 'react'
import Link from 'next/link'
import { ExternalLinkIcon, FolderOpenIcon, ImagesIcon, Lock, Trash2Icon } from 'lucide-react'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { ConfirmModal } from '@/components/ui/confirm-modal'
import { Modal } from '@/components/ui/modal'
import { apiDelete, apiErrorMessage } from '@/lib/api'
import { cn } from '@/lib/utils'
import {
  MediaUploadPanel,
  isUnderReviewOrLater,
  isVideoContentType,
  type MediaAsset,
} from '@/components/workitems/MediaUploadPanel'
import { AttachCreativeDialog } from '@/components/marketing/creatives/AttachCreativeDialog'
import { FullSizeViewer } from '@/components/marketing/creatives/FullSizeViewer'
import { addMediaToWorkItem, type CreativePhoto } from '@/components/marketing/creatives/types'
import { PickMediaDialog } from '@/components/marketing/media/PickMediaDialog'
import { TargetContentEditor } from '@/components/marketing/TargetContentEditor'
import { PlatformIcon } from '@/components/marketing/destinations/PlatformIcon'
import type { DestinationRowModel, DestinationsState } from '@/components/marketing/destinations/model'
import type { WorkflowView } from '@/types/workItem'
import {
  PLATFORM_NAME,
  activeDestinations,
  aspectLabel,
  aspectOf,
  assetDisplayLabel,
  destinationTitle,
  formatBytes,
  formatLabel,
  groupByOrigin,
  placementLabel,
  usageByAsset,
  type OriginGroup,
} from './mediaGroups'

export interface PostMediaSectionProps {
  projectId: string
  workItemId: string
  token: string
  status: string
  workflowView?: WorkflowView
  /** The Post's caption, shown as the fallback inside a destination's own content editor. */
  caption: string | null
  assets: MediaAsset[]
  canEdit: boolean
  /** Absent while the Post's destinations have not loaded; treated as none. */
  destinations?: DestinationsState
  onAssetsChanged: () => void | Promise<void>
}

type View = 'destination' | 'all'

interface ViewerState {
  ids: string[]
  index: number
  /** Where the walk is happening, for the "Slide i of n" line. */
  label: string
}

const TILE_HEIGHT = 120

/** A file at its real shape: fixed height, width follows the ratio. */
function Thumb({
  asset,
  height = TILE_HEIGHT,
  badge,
  className,
  responsive,
}: {
  asset: MediaAsset
  height?: number
  /** Shrink to the small tile height below the sm breakpoint. */
  responsive?: boolean
  badge?: number
  className?: string
}) {
  return (
    <span
      className={cn(
        'relative block shrink-0 overflow-hidden rounded-md border border-border bg-surface-3',
        responsive && 'h-[96px] sm:h-[120px]',
        className,
      )}
      style={responsive ? { aspectRatio: String(aspectOf(asset)) } : { height, aspectRatio: String(aspectOf(asset)) }}
    >
      {asset.previewUrl ? (
        isVideoContentType(asset.contentType) ? (
          <video src={asset.previewUrl} preload="metadata" muted className="h-full w-full object-cover" />
        ) : (
          // eslint-disable-next-line @next/next/no-img-element
          <img src={asset.previewUrl} alt={assetDisplayLabel(asset)} className="h-full w-full object-cover" />
        )
      ) : null}
      {badge != null && (
        <span className="absolute left-1 top-1 rounded bg-foreground/70 px-1 text-[10px] font-medium text-background">
          {badge}
        </span>
      )}
    </span>
  )
}

export function PostMediaSection({
  projectId,
  workItemId,
  token,
  status,
  workflowView,
  caption,
  assets,
  canEdit,
  destinations,
  onAssetsChanged,
}: PostMediaSectionProps) {
  const files = useMemo(() => assets.filter((a) => a.kind === 'file'), [assets])
  const rows = useMemo(() => activeDestinations(destinations?.rows ?? []), [destinations])
  const usage = useMemo(() => usageByAsset(rows), [rows])
  const groups = useMemo(() => groupByOrigin(files), [files])
  const creativeGroups = groups.filter((g) => g.kind === 'creative')

  const locked = isUnderReviewOrLater(workflowView, status)
  const editable = canEdit && !locked
  const canUpload = (workflowView?.assetTypes?.length ?? 0) > 0

  const [view, setView] = useState<View>('destination')
  const [viewer, setViewer] = useState<ViewerState | null>(null)
  const [mediaOpen, setMediaOpen] = useState(false)
  const [attachOpen, setAttachOpen] = useState(false)
  const [customizeKey, setCustomizeKey] = useState<string | null>(null)
  const [removeId, setRemoveId] = useState<string | null>(null)
  const [removing, setRemoving] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function addFromMedia(items: CreativePhoto[]) {
    setError(null)
    try {
      await addMediaToWorkItem(projectId, token, workItemId, items.map((i) => i.id))
      await onAssetsChanged()
    } catch (err) {
      setError(apiErrorMessage(err, 'Could not add this media'))
    }
  }

  async function confirmRemove() {
    if (!removeId) return
    setError(null)
    setRemoving(true)
    try {
      await apiDelete(`/api/v2/projects/${projectId}/work-items/${workItemId}/assets/${removeId}`, token)
      await onAssetsChanged()
      setRemoveId(null)
    } catch (err) {
      setError(apiErrorMessage(err, 'Could not remove the file'))
      setRemoveId(null)
    } finally {
      setRemoving(false)
    }
  }

  const byId = useMemo(() => new Map(files.map((a) => [a.id, a])), [files])
  const creativeHref = (id: string) => `/app/projects/${projectId}/marketing/creatives/${id}`
  const removeTarget = removeId ? byId.get(removeId) : undefined
  const customizeRow = customizeKey ? rows.find((r) => r.key === customizeKey) : undefined
  const canCustomize = (row: DestinationRowModel) =>
    editable && !!destinations && destinations.mode === 'pick' && !destinations.locked && !row.settled

  function openViewer(ids: string[], id: string, label: string) {
    setViewer({ ids, index: Math.max(0, ids.indexOf(id)), label })
  }

  // The viewer walks a snapshot of ids taken when it opened, minus anything removed since.
  const viewerIds = viewer ? viewer.ids.filter((id) => byId.has(id)) : []
  const viewerIndex = viewer ? Math.min(viewer.index, Math.max(viewerIds.length - 1, 0)) : 0
  const current = viewer ? byId.get(viewerIds[viewerIndex] ?? '') : undefined

  function renderAside(asset: MediaAsset) {
    const uses = usage.get(asset.id) ?? []
    const aspect = aspectLabel(asset.width, asset.height)
    const size = formatBytes(asset.sizeBytes)
    return (
      <div className="space-y-4 text-sm" data-testid="viewer-aside">
        <div>
          <p className="font-medium text-foreground">
            Slide {viewerIndex + 1} of {viewerIds.length}
          </p>
          <p className="text-xs text-muted-foreground">{viewer?.label}</p>
        </div>
        <div>
          <p className="text-xs font-medium text-muted-foreground">Size</p>
          <p className="text-foreground">
            {[aspect, asset.width && asset.height ? `${asset.width} × ${asset.height} px` : null, size || null]
              .filter(Boolean)
              .join(' · ') || 'Not measured'}
          </p>
        </div>
        <div>
          <p className="text-xs font-medium text-muted-foreground">Goes to</p>
          {uses.length === 0 ? (
            <p className="text-muted-foreground">
              {rows.length === 0 ? 'No destinations yet.' : 'Not used by any destination.'}
            </p>
          ) : (
            <ul className="mt-1 space-y-1.5">
              {uses.map(({ row, position }) => (
                <li key={row.key} className="flex items-start gap-2">
                  <PlatformIcon platform={row.option.platform} className="mt-0.5" />
                  <span className="text-foreground">
                    {destinationTitle(row)}
                    <span className="block text-xs text-muted-foreground">
                      {formatLabel(row)} · position {position}
                    </span>
                  </span>
                </li>
              ))}
            </ul>
          )}
        </div>
        <div>
          <p className="text-xs font-medium text-muted-foreground">Came from</p>
          {asset.creativeFrameId ? (
            <div className="space-y-1">
              <p className="text-foreground">
                {asset.creativeDisplayId ? `Creative ${asset.creativeDisplayId}` : 'A Creative'}
                {asset.placementKey ? ` · ${placementLabel(asset.placementKey)}` : ''}
                {asset.sequenceIndex != null ? ` · slide ${asset.sequenceIndex + 1}` : ''}
              </p>
              {asset.creativeId && (
                <Link
                  href={creativeHref(asset.creativeId)}
                  className="inline-flex items-center gap-1 text-primary hover:underline"
                >
                  Open Creative <ExternalLinkIcon className="h-3 w-3" aria-hidden />
                </Link>
              )}
            </div>
          ) : asset.mediaId ? (
            <p className="text-foreground">Media library</p>
          ) : (
            <p className="text-foreground">Uploaded</p>
          )}
        </div>
        {editable && (
          <Button
            variant="outline"
            size="sm"
            onClick={() => setRemoveId(asset.id)}
            className="w-full justify-center gap-1.5 text-destructive"
          >
            <Trash2Icon className="h-3.5 w-3.5" aria-hidden />
            Remove from Post
          </Button>
        )}
      </div>
    )
  }

  function destinationGroup(row: DestinationRowModel) {
    const media = row.effectiveAssets
    const aspects = [...new Set(media.map((a) => aspectLabel(a.width, a.height)).filter(Boolean))]
    const inherits = row.content.assetIds === null
    const title = destinationTitle(row)
    const ids = media.map((a) => a.id)
    const hasFrames = files.some((f) => !!f.creativeFrameId)
    return (
      <section key={row.key} aria-label={title} className="space-y-2" data-testid="media-destination-group">
        <header className="flex flex-wrap items-center gap-2 text-sm">
          <PlatformIcon platform={row.option.platform} />
          <span className="font-medium text-foreground">{title}</span>
          <span className="text-muted-foreground">
            {[formatLabel(row), aspects.length === 1 ? aspects[0] : null, `${media.length} file${media.length === 1 ? '' : 's'}`]
              .filter(Boolean)
              .join(' · ')}
          </span>
          <span className="ml-auto flex items-center gap-3">
            {inherits && <span className="text-xs text-muted-foreground">Uses all Post media</span>}
            {canCustomize(row) && (
              <Button variant="link" size="sm" className="h-auto p-0 text-sm" onClick={() => setCustomizeKey(row.key)}>
                Customize
              </Button>
            )}
          </span>
        </header>
        {media.length === 0 ? (
          <p className="text-sm text-muted-foreground">
            {hasFrames
              ? `Nothing for this destination — ${PLATFORM_NAME[row.option.platform]} can't post a photo carousel. Remove it or pick a video.`
              : 'Nothing selected for this destination.'}
          </p>
        ) : (
          <div className="flex flex-wrap gap-2">
            {media.map((a, i) => (
              <button
                key={a.id}
                type="button"
                aria-label={`Open ${a.label || a.type} full size`}
                onClick={() => openViewer(ids, a.id, title)}
                className="shrink-0 rounded-md outline-none focus-visible:ring-2 focus-visible:ring-ring"
              >
                <Thumb asset={a} badge={media.length > 1 ? i + 1 : undefined} responsive />
              </button>
            ))}
          </div>
        )}
      </section>
    )
  }

  function fileTile(a: MediaAsset, ids: string[], label: string) {
    const uses = usage.get(a.id) ?? []
    const platforms = [...new Set(uses.map((u) => u.row.option.platform))]
    const unused = rows.length > 0 && uses.length === 0
    return (
      <div key={a.id} className={cn('w-fit max-w-[220px] space-y-1', unused && 'opacity-60')} data-testid="media-file-tile">
        <button
          type="button"
          aria-label={`Open ${a.label || a.type} full size`}
          onClick={() => openViewer(ids, a.id, label)}
          className="block rounded-md outline-none focus-visible:ring-2 focus-visible:ring-ring"
        >
          <Thumb asset={a} height={96} />
        </button>
        <p className="truncate text-xs text-foreground" title={assetDisplayLabel(a)}>
          {assetDisplayLabel(a)}
        </p>
        <div className="flex items-center gap-1 text-xs text-muted-foreground">
          <span>{formatBytes(a.sizeBytes)}</span>
          {platforms.map((p) => (
            <span key={p} title={PLATFORM_NAME[p]} className="inline-flex">
              <PlatformIcon platform={p} className="h-4 w-4" />
            </span>
          ))}
        </div>
        {unused && (
          <div className="flex flex-wrap items-center gap-2">
            <span className="rounded border border-border px-1 text-[10px] text-muted-foreground">
              Not used by any destination
            </span>
            {editable && (
              <button
                type="button"
                onClick={() => setRemoveId(a.id)}
                className="text-xs text-destructive hover:underline"
                aria-label={`Remove ${a.label || a.type}`}
              >
                Remove
              </button>
            )}
          </div>
        )}
      </div>
    )
  }

  function originGroup(g: OriginGroup) {
    return (
      <section key={g.key} aria-label={g.title} className="space-y-2" data-testid="media-origin-group">
        <header className="flex flex-wrap items-center gap-2 text-sm">
          <span className="font-medium text-foreground">{g.title}</span>
          <span className="text-muted-foreground">
            {[g.detail, `${g.assets.length} file${g.assets.length === 1 ? '' : 's'}`].filter(Boolean).join(' · ')}
          </span>
        </header>
        {g.rows.map((r) => (
          <div key={r.key} className="space-y-1">
            {g.kind === 'creative' && <p className="text-xs text-muted-foreground">{placementLabel(r.placementKey)}</p>}
            <div className="flex flex-wrap items-start gap-3">
              {r.assets.map((a) => fileTile(a, g.assets.map((x) => x.id), g.title))}
            </div>
          </div>
        ))}
      </section>
    )
  }

  return (
    <div className="space-y-3" data-testid="post-media">
      <div className="flex flex-wrap items-center gap-x-2 gap-y-2">
        <h3 className="text-xs font-medium text-muted-foreground">Media</h3>
        <div role="radiogroup" aria-label="Media view" className="inline-flex rounded-md border border-border p-0.5">
          {(
            [
              ['destination', 'By destination'],
              ['all', `All files ${files.length}`],
            ] as [View, string][]
          ).map(([v, label]) => (
            <button
              key={v}
              type="button"
              role="radio"
              aria-checked={view === v}
              onClick={() => setView(v)}
              className={cn(
                'rounded px-2.5 py-1 text-xs font-medium transition-colors',
                view === v ? 'bg-primary text-primary-foreground' : 'text-muted-foreground hover:bg-muted',
              )}
            >
              {label}
            </button>
          ))}
        </div>
        {editable && (
          <div className="flex w-full flex-wrap items-center gap-2 sm:ml-auto sm:w-auto">
            <span className="text-xs text-muted-foreground">Add media</span>
            {canUpload && (
              <MediaUploadPanel
                variant="button"
                projectId={projectId}
                workItemId={workItemId}
                token={token}
                status={status}
                workflowView={workflowView}
                assets={assets}
                onUploaded={onAssetsChanged}
              />
            )}
            <Button variant="outline" size="sm" className="gap-1.5" onClick={() => setMediaOpen(true)}>
              <FolderOpenIcon className="h-3.5 w-3.5" aria-hidden />
              From media
            </Button>
            <Button variant="outline" size="sm" className="gap-1.5" onClick={() => setAttachOpen(true)}>
              <ImagesIcon className="h-3.5 w-3.5" aria-hidden />
              From a creative
            </Button>
          </div>
        )}
      </div>

      {creativeGroups.length > 0 && (
        <p className="text-xs text-muted-foreground" data-testid="media-origin-line">
          {creativeGroups.map((g, i) => (
            <span key={g.key}>
              {i > 0 && '; '}
              From Creative {g.creativeDisplayId ?? ''}
              {g.creativeId && (
                <>
                  {' · '}
                  <Link href={creativeHref(g.creativeId)} className="text-primary hover:underline">
                    Open Creative
                  </Link>
                </>
              )}
            </span>
          ))}
        </p>
      )}

      {locked && canEdit && (
        <p className="flex items-center gap-1.5 text-sm text-muted-foreground">
          <Lock className="h-3.5 w-3.5 shrink-0" aria-hidden />
          Media is locked while this {workflowView?.noun?.toLowerCase() ?? 'item'} is{' '}
          {workflowView?.statuses.find((s) => s.id === status)?.label ?? status}.
        </p>
      )}

      {files.length === 0 ? (
        <p className="text-sm text-muted-foreground">No media yet.</p>
      ) : view === 'destination' && rows.length > 0 ? (
        <div className="space-y-4">{rows.map(destinationGroup)}</div>
      ) : view === 'destination' ? (
        <div className="space-y-3">
          <p className="text-sm text-muted-foreground">Add destinations to see which files each one gets.</p>
          <div className="flex flex-wrap gap-2">
            {files.map((a) => (
              <button
                key={a.id}
                type="button"
                aria-label={`Open ${a.label || a.type} full size`}
                onClick={() => openViewer(files.map((f) => f.id), a.id, 'All files')}
                className="rounded-md outline-none focus-visible:ring-2 focus-visible:ring-ring"
              >
                <Thumb asset={a} />
              </button>
            ))}
          </div>
        </div>
      ) : (
        <div className="space-y-5">{groups.map(originGroup)}</div>
      )}

      {error && <Alert variant="destructive">{error}</Alert>}

      <FullSizeViewer
        open={!!current}
        onOpenChange={(open) => !open && setViewer(null)}
        title={current ? assetDisplayLabel(current, { withRatio: true }) : 'Media'}
        subtitle={viewer?.label}
        onPrev={viewerIndex > 0 ? () => setViewer((v) => v && { ...v, index: viewerIndex - 1 }) : undefined}
        onNext={
          viewerIndex < viewerIds.length - 1 ? () => setViewer((v) => v && { ...v, index: viewerIndex + 1 }) : undefined
        }
        aside={current ? renderAside(current) : undefined}
      >
        {current?.previewUrl &&
          (isVideoContentType(current.contentType) ? (
            <video src={current.previewUrl} controls className="max-h-[75vh] max-w-full rounded-md" />
          ) : (
            // eslint-disable-next-line @next/next/no-img-element
            <img
              src={current.previewUrl}
              alt={current.label || current.type}
              className="max-h-[75vh] max-w-full rounded-md object-contain"
            />
          ))}
      </FullSizeViewer>

      <ConfirmModal
        open={!!removeTarget}
        title="Remove from Post?"
        description={`${removeTarget?.label || removeTarget?.type || 'This file'} will be taken off the Post and every destination that uses it. It stays in the media library.`}
        confirmLabel="Remove"
        busyLabel="Removing…"
        busy={removing}
        onConfirm={() => void confirmRemove()}
        onCancel={() => setRemoveId(null)}
      />

      {customizeRow && destinations && (
        <Modal
          open
          onOpenChange={(open) => !open && setCustomizeKey(null)}
          title={`Customize ${destinationTitle(customizeRow)}`}
          description="Choose this destination's own media and caption. Leave it alone to use the whole Post."
          footer={
            <div className="flex justify-end">
              <Button size="sm" onClick={() => setCustomizeKey(null)}>
                Done
              </Button>
            </div>
          }
        >
          <TargetContentEditor
            assets={files}
            postCaption={caption}
            value={customizeRow.content}
            disabled={destinations.saving || destinations.locked}
            onChange={(next) => destinations.actions.setContent(customizeRow, next)}
          />
        </Modal>
      )}

      {editable && (
        <PickMediaDialog
          open={mediaOpen}
          onOpenChange={setMediaOpen}
          projectId={projectId}
          token={token}
          onPicked={(items) => void addFromMedia(items)}
        />
      )}
      {editable && (
        <AttachCreativeDialog
          open={attachOpen}
          onOpenChange={setAttachOpen}
          projectId={projectId}
          workItemId={workItemId}
          token={token}
          destinations={rows.map((r) => ({ platform: r.option.platform, format: r.format, label: r.option.label, keepsOwnMedia: (r.content.assetIds?.length ?? 0) > 0 }))}
          onAttached={onAssetsChanged}
        />
      )}
    </div>
  )
}
