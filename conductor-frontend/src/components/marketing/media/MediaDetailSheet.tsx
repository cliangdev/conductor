'use client'

// One media item in a right-hand panel: large preview (video/audio play), facts, editable
// label/source/licence (200 chars, as the server), AI-generated and Checked switches, Block/Unblock
// with a reason and Delete with confirm. Server errors (409 in use, 400 too long) are shown verbatim.

import { useEffect, useState } from 'react'
import Link from 'next/link'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { ConfirmModal } from '@/components/ui/confirm-modal'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Sheet } from '@/components/ui/sheet'
import { Switch } from '@/components/ui/switch'
import { apiErrorMessage } from '@/lib/api'
import { formatDuration } from '@/lib/format'
import { useSidebarWorkNav, workItemDetailPath } from '@/lib/workflows'
import {
  deleteCreativePhoto,
  getMediaUsage,
  type MediaUsage,
  patchCreativePhoto,
  type CreativePhoto,
  type PatchCreativePhotoRequest,
} from '@/components/marketing/creatives/types'

const MAX_FIELD = 200

function formatBytes(n: number): string {
  if (n >= 1024 * 1024) return `${(n / (1024 * 1024)).toFixed(1)} MB`
  return `${Math.max(1, Math.round(n / 1024))} KB`
}

function TextField({
  id,
  label,
  value,
  onChange,
  disabled,
}: {
  id: string
  label: string
  value: string
  onChange: (v: string) => void
  disabled: boolean
}) {
  return (
    <div>
      <div className="flex items-baseline justify-between">
        <Label htmlFor={id} className="text-xs">
          {label}
        </Label>
        <span className="text-[11px] text-muted-foreground" aria-live="off">
          {value.length}/{MAX_FIELD}
        </span>
      </div>
      <Input id={id} value={value} maxLength={MAX_FIELD} disabled={disabled} onChange={(e) => onChange(e.target.value)} />
    </div>
  )
}

function Preview({ item }: { item: CreativePhoto }) {
  const kind = item.mediaKind ?? 'IMAGE'
  if (kind === 'VIDEO' && item.url) {
    return <video preload="metadata" src={item.url} poster={item.posterUrl ?? undefined} controls playsInline className="max-h-80 w-full rounded-md bg-surface-3" />
  }
  if (kind === 'AUDIO' && item.url) {
    return <audio src={item.url} controls className="w-full" />
  }
  if (kind === 'IMAGE' && item.url) {
    // eslint-disable-next-line @next/next/no-img-element
    return <img src={item.url} alt={item.label || 'Media preview'} className="max-h-80 w-full rounded-md bg-surface-3 object-contain" />
  }
  return <div className="flex h-40 items-center justify-center rounded-md bg-surface-3 text-sm text-muted-foreground">No preview</div>
}

/** "Used in": the Creatives referencing this media and the Posts carrying it. A usage failure only
 *  hides the section — it never blocks the rest of the panel. */
function UsedIn({ projectId, token, mediaId }: { projectId: string; token: string; mediaId: string }) {
  const [usage, setUsage] = useState<MediaUsage | null>(null)
  const { entries } = useSidebarWorkNav(projectId, token)

  useEffect(() => {
    let cancelled = false
    getMediaUsage(projectId, token, mediaId)
      .then((u) => {
        if (!cancelled) setUsage(u)
      })
      .catch(() => {})
    return () => {
      cancelled = true
    }
  }, [projectId, token, mediaId])

  if (!usage || (usage.creatives.length === 0 && usage.posts.length === 0)) return null

  const postHref = (p: MediaUsage['posts'][number]) => {
    const nav = entries.find((e) => e.slug === p.workflow)
    // Without the workflow's area/noun yet, the legacy issue route redirects to the right place.
    return nav
      ? workItemDetailPath(projectId, nav.area, nav.noun, p.displayId)
      : `/app/projects/${projectId}/issues/${p.displayId}`
  }

  return (
    <section aria-label="Used in" className="space-y-2 border-t border-border pt-3">
      <h3 className="text-sm font-medium text-foreground">Used in</h3>
      {usage.creatives.length > 0 && (
        <div className="space-y-1">
          <p className="text-xs text-muted-foreground">Creatives</p>
          <ul className="space-y-1 text-sm">
            {usage.creatives.map((c) => (
              <li key={c.id}>
                <Link href={`/app/projects/${projectId}/marketing/creatives/${c.id}`} className="text-primary hover:underline">
                  {c.displayId} {c.name}
                </Link>
              </li>
            ))}
          </ul>
        </div>
      )}
      {usage.posts.length > 0 && (
        <div className="space-y-1">
          <p className="text-xs text-muted-foreground">Posts</p>
          <ul className="space-y-1 text-sm">
            {usage.posts.map((p) => (
              <li key={p.workItemId}>
                <Link href={postHref(p)} className="text-primary hover:underline">
                  {p.displayId} {p.title}
                </Link>
              </li>
            ))}
          </ul>
        </div>
      )}
    </section>
  )
}

interface DetailProps {
  projectId: string
  token: string
  item: CreativePhoto
  canManage: boolean
  onChanged: (item: CreativePhoto) => void
  onDeleted: (id: string) => void
}

function MediaDetail({ projectId, token, item, canManage, onChanged, onDeleted }: DetailProps) {
  const [label, setLabel] = useState(item.label ?? '')
  const [source, setSource] = useState(item.source ?? '')
  const [licence, setLicence] = useState(item.licence ?? '')
  const [reason, setReason] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [confirmDelete, setConfirmDelete] = useState(false)

  const dirty = label !== (item.label ?? '') || source !== (item.source ?? '') || licence !== (item.licence ?? '')

  async function patch(body: PatchCreativePhotoRequest, fallback: string) {
    setBusy(true)
    setError(null)
    try {
      onChanged(await patchCreativePhoto(projectId, item.id, body, token))
      return true
    } catch (err) {
      setError(apiErrorMessage(err, fallback))
      return false
    } finally {
      setBusy(false)
    }
  }

  async function remove() {
    setBusy(true)
    setError(null)
    try {
      await deleteCreativePhoto(projectId, item.id, token)
      onDeleted(item.id)
    } catch (err) {
      setError(apiErrorMessage(err, 'Could not delete this media'))
      setConfirmDelete(false)
    } finally {
      setBusy(false)
    }
  }

  const facts = [
    item.width && item.height ? `${item.width}×${item.height}` : null,
    item.durationSeconds != null ? formatDuration(item.durationSeconds) : null,
    formatBytes(item.sizeBytes),
    item.contentType,
  ].filter(Boolean)

  return (
    <div className="space-y-4">
      {error && <Alert variant="destructive">{error}</Alert>}
      {item.blocked && (
        <Alert variant="warning">Blocked{item.blockedReason ? ` — ${item.blockedReason}` : ''}</Alert>
      )}
      <Preview item={item} />
      <p className="text-xs text-muted-foreground">{facts.join(' · ')}</p>
      <UsedIn projectId={projectId} token={token} mediaId={item.id} />

      <div className="space-y-3">
        <TextField id="media-detail-label" label="Label" value={label} onChange={setLabel} disabled={!canManage} />
        <TextField id="media-detail-source" label="Source" value={source} onChange={setSource} disabled={!canManage} />
        <TextField id="media-detail-licence" label="Licence" value={licence} onChange={setLicence} disabled={!canManage} />
        {canManage && (
          <Button
            type="button"
            size="sm"
            disabled={!dirty || busy}
            onClick={() => void patch({ label, source, licence }, 'Could not save changes')}
          >
            Save changes
          </Button>
        )}
      </div>

      <div className="space-y-2 border-t border-border pt-3">
        <div className="flex items-center justify-between">
          <Label htmlFor="media-detail-ai" className="text-sm">
            AI-generated
          </Label>
          <Switch
            id="media-detail-ai"
            checked={item.aiGenerated}
            disabled={!canManage || busy}
            onCheckedChange={(v) => void patch({ aiGenerated: v }, 'Could not update AI-generated')}
          />
        </div>
        <div className="flex items-center justify-between">
          <Label htmlFor="media-detail-checked" className="text-sm">
            Checked
          </Label>
          <Switch
            id="media-detail-checked"
            checked={item.checked}
            disabled={!canManage || busy}
            onCheckedChange={(v) => void patch({ checked: v }, 'Could not update Checked')}
          />
        </div>
      </div>

      {canManage && (
        <div className="space-y-2 border-t border-border pt-3">
          {item.blocked ? (
            <Button
              type="button"
              variant="outline"
              size="sm"
              disabled={busy}
              onClick={() => void patch({ blocked: false }, 'Could not unblock')}
            >
              Unblock
            </Button>
          ) : (
            <div className="flex items-end gap-2">
              <div className="flex-1">
                <Label htmlFor="media-detail-block-reason" className="text-xs">
                  Block reason
                </Label>
                <Input
                  id="media-detail-block-reason"
                  value={reason}
                  maxLength={MAX_FIELD}
                  onChange={(e) => setReason(e.target.value)}
                  placeholder="Why this can't be used"
                />
              </div>
              <Button
                type="button"
                variant="outline"
                size="sm"
                disabled={busy || !reason.trim()}
                onClick={() => void patch({ blocked: true, blockedReason: reason.trim() }, 'Could not block')}
              >
                Block
              </Button>
            </div>
          )}
          <Button type="button" variant="destructive" size="sm" disabled={busy} onClick={() => setConfirmDelete(true)}>
            Delete
          </Button>
        </div>
      )}

      <ConfirmModal
        open={confirmDelete}
        title="Delete this media"
        confirmLabel="Delete"
        busyLabel="Deleting…"
        busy={busy}
        onConfirm={() => void remove()}
        onCancel={() => setConfirmDelete(false)}
      >
        <p className="text-sm text-foreground">
          Permanently delete <strong>{item.label || 'this media'}</strong>? This cannot be undone.
        </p>
      </ConfirmModal>
    </div>
  )
}

export interface MediaDetailSheetProps extends Omit<DetailProps, 'item'> {
  item: CreativePhoto | null
  onClose: () => void
}

export function MediaDetailSheet({ item, onClose, ...rest }: MediaDetailSheetProps) {
  return (
    <Sheet open={item != null} onOpenChange={(o) => !o && onClose()} title={item?.label || 'Media'} description="Details and review state">
      {item && <MediaDetail key={item.id} item={item} {...rest} />}
    </Sheet>
  )
}
