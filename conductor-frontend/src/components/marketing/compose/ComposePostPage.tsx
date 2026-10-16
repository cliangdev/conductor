'use client'

// Making a Post the way a person thinks about one: what it says, what it shows, where it goes, when —
// on one page laid out like the Post's own, so creating and reviewing feel like one product. Content on
// the left, destinations (the same rows the Post page shows, in pick mode, with per-destination
// customization) and the schedule on the right, one Create at the bottom. The server still does all the
// deciding: the same endpoints the Post page uses, in the same order an agent's create_post uses them.

import { useMemo, useRef, useState } from 'react'
import Link from 'next/link'
import { useRouter, useSearchParams } from 'next/navigation'
import { Film, ImageIcon, Plus, TriangleAlert, X } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Card, CardHeader } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'
import { toastError } from '@/components/ui/toast'
import { apiErrorMessage } from '@/lib/api'
import { browserTimeZone } from '@/lib/schedule'
import { workItemDetailPath, workItemListPath } from '@/lib/workflows'
import { PageContainer } from '@/components/layout/PageContainer'
import { PageHeader } from '@/components/layout/PageHeader'
import { ALLOWED_MEDIA_CONTENT_TYPES, isVideoContentType, type MediaAsset } from '@/components/workitems/MediaUploadPanel'
import { ScheduleEditor, type ScheduleEditorValue } from '@/components/workitems/WorkItemScheduleField'
import { DestinationsPanel } from '@/components/marketing/destinations/DestinationsPanel'
import { useDraftDestinations } from '@/components/marketing/destinations/useDraftDestinations'
import type { WorkflowView } from '@/types/workItem'
import { PickMediaDialog } from '@/components/marketing/media/PickMediaDialog'
import type { CreativePhoto } from '@/components/marketing/creatives/types'
import { CreativesTab, FrameStrip, useCreatives, type CreativeChoice } from '@/components/marketing/media/CreativesTab'
import { creativeCaption, creativeSummary, hasRender } from '@/components/marketing/creatives/creativeCopy'
import { groupFrames, placementChip, placementFor, shortReason } from '@/components/marketing/creatives/placementMap'
import type { DestinationRowModel } from '@/components/marketing/destinations/model'
import { createPost, PENDING_ASSET_PREFIX } from './createPost'

export interface ComposePostPageProps {
  projectId: string
  workflowSlug: string
  workflowView: WorkflowView
  /** The URL area segment, for the list and detail paths. */
  detailArea: string
  noun: string
  token: string
}

export function ComposePostPage({ projectId, workflowSlug, workflowView, detailArea, noun, token }: ComposePostPageProps) {
  const router = useRouter()
  const searchParams = useSearchParams()
  const types = useMemo(() => workflowView.types ?? [], [workflowView])
  const assetType = workflowView.assetTypes?.[0] ?? ''
  const lowerNoun = noun.toLowerCase()
  const listPath = workItemListPath(projectId, detailArea, noun)

  // Arrived from a Creative's "Use in Post" → "New Post". Media comes from the creative's render,
  // attached server-side after this Post exists — nothing to prefill here.
  const fromCreativeId = searchParams.get('creativeId')
  const fromRenderId = searchParams.get('renderId')
  const [urlCreativeDismissed, setUrlCreativeDismissed] = useState(false)
  // A Creative picked in the chooser (Creatives tab) wins over one arriving in the URL; both feed the same
  // server-side attach after the Post exists.
  const [creativeChoice, setCreativeChoice] = useState<CreativeChoice | null>(null)
  const [captionFromCreative, setCaptionFromCreative] = useState<string | null>(null)
  const attachCreative = creativeChoice
    ? { creativeId: creativeChoice.creative.id, renderId: creativeChoice.renderId }
    : fromCreativeId && fromRenderId && !urlCreativeDismissed
      ? { creativeId: fromCreativeId, renderId: fromRenderId }
      : undefined

  const [title, setTitle] = useState('')
  const [caption, setCaption] = useState('')
  const [files, setFiles] = useState<File[]>([])
  // Nothing is uploaded until the Post exists, but a destination can already choose among these files (a
  // reel's cover, media of its own). Each file gets a stand-in id when it is added, parallel to `files`;
  // createPost swaps it for the real asset id once the file is uploaded.
  const [fileIds, setFileIds] = useState<string[]>([])
  // Library media picked with "From media": linked to the Post after it is created, after the files.
  const [pickedMedia, setPickedMedia] = useState<CreativePhoto[]>([])
  const [mediaPickerOpen, setMediaPickerOpen] = useState(false)
  const mediaPendingId = (id: string) => `${PENDING_ASSET_PREFIX}media-${id}`
  const nextFileId = useRef(0)
  const [schedule, setSchedule] = useState<ScheduleEditorValue>({ onApproval: false, local: '', tz: browserTimeZone() })
  const [saving, setSaving] = useState(false)
  const [step, setStep] = useState<string | null>(null)

  const destinations = useDraftDestinations({ projectId, token })
  const picked = destinations.summary.total
  const { creatives, error: creativesError } = useCreatives(projectId, token)
  const hasRenderedCreative = (creatives ?? []).some(hasRender)

  // What the Creatives tab previews against: the destinations picked right now.
  const pickedDestinations = destinations.rows
    .filter((r) => r.checked)
    .map((r) => ({ platform: r.option.platform, format: r.format, label: r.option.label }))
  const creativeGroups = useMemo(() => (creativeChoice ? groupFrames(creativeChoice.frames) : []), [creativeChoice])

  // Small mono chip on a picked destination: the size it will get from the Creative, or why it gets none.
  function creativeChip(row: DestinationRowModel) {
    if (!creativeChoice || !row.checked) return null
    const dest = { platform: row.option.platform, format: row.format }
    const result = placementFor(creativeChoice.creative.kind ?? 'STILL', creativeGroups, dest)
    if ('reason' in result) {
      return (
        <span
          title={result.reason}
          data-testid={`creative-chip-${row.key.replace(/\s+/g, '-')}`}
          className="inline-flex items-center gap-1 whitespace-nowrap rounded border border-status-progress/40 px-1 font-mono text-[11px] text-status-progress"
        >
          <TriangleAlert className="h-3 w-3" aria-hidden />
          {shortReason(creativeChoice.creative.kind ?? 'STILL', dest)}
        </span>
      )
    }
    return (
      <span
        data-testid={`creative-chip-${row.key.replace(/\s+/g, '-')}`}
        className="whitespace-nowrap rounded border border-border px-1 font-mono text-[11px] text-foreground"
      >
        {placementChip(result)}
      </span>
    )
  }

  function useCreative(choice: CreativeChoice) {
    setCreativeChoice(choice)
    setMediaPickerOpen(false)
    if (choice.useCaption) {
      const text = creativeCaption(choice.creative)
      if (text) {
        setCaption(text)
        setCaptionFromCreative(choice.creative.displayId)
      }
    }
  }

  function removeCreative() {
    setCreativeChoice(null)
    setCaptionFromCreative(null)
    setUrlCreativeDismissed(true)
  }

  const canSubmit = caption.trim().length > 0 && picked > 0 && types.length > 0 && !saving
  // A disabled button with no reason is a dead end; say what is still missing, in the order the page
  // asks for it.
  const missing = [
    caption.trim().length === 0 ? 'a caption' : null,
    picked === 0 ? 'at least one destination' : null,
  ].filter((m): m is string => m !== null)

  // Object URLs for the thumbnails; revoked when the file leaves the list.
  const previews = useMemo(() => files.map((f) => ({ file: f, url: URL.createObjectURL(f) })), [files])

  const filePendingAssets: MediaAsset[] = useMemo(() => previews.map(({ file, url }, index) => ({
    id: fileIds[index] ?? `${PENDING_ASSET_PREFIX}unassigned-${index}`,
    issueId: '',
    type: isVideoContentType(file.type) ? 'video' : 'image',
    label: file.name,
    kind: 'file',
    ref: '',
    done: false,
    createdAt: '',
    updatedAt: '',
    contentType: file.type,
    uploadStatus: null,
    previewUrl: url,
  })), [previews, fileIds])

  const mediaPendingAssets: MediaAsset[] = useMemo(() => pickedMedia.map((m) => ({
    id: mediaPendingId(m.id),
    issueId: '',
    type: m.mediaKind === 'VIDEO' ? 'video' : 'image',
    label: m.label ?? '',
    kind: 'file',
    ref: '',
    done: false,
    createdAt: '',
    updatedAt: '',
    contentType: m.contentType,
    uploadStatus: null,
    previewUrl: (m.mediaKind === 'VIDEO' ? m.posterUrl : m.url) ?? undefined,
  })), [pickedMedia])

  // Uploaded files first, then picked media — the order createPost links them in.
  const pendingAssets = useMemo(() => [...filePendingAssets, ...mediaPendingAssets], [filePendingAssets, mediaPendingAssets])

  function addFiles(chosen: File[]) {
    const ids = chosen.map(() => `${PENDING_ASSET_PREFIX}${nextFileId.current++}`)
    setFiles((prev) => [...prev, ...chosen])
    setFileIds((prev) => [...prev, ...ids])
  }

  function removeFile(index: number) {
    URL.revokeObjectURL(previews[index]?.url ?? '')
    setFiles((prev) => prev.filter((_, i) => i !== index))
    setFileIds((prev) => prev.filter((_, i) => i !== index))
  }

  async function submit(event: React.FormEvent) {
    event.preventDefault()
    if (!canSubmit) return
    setSaving(true)
    try {
      const { created, problems } = await createPost({
        projectId,
        token,
        workflowSlug,
        types,
        assetType,
        noun,
        title,
        caption,
        files,
        fileIds,
        media: pickedMedia.map((m) => ({ pendingId: mediaPendingId(m.id), mediaId: m.id })),
        options: destinations.options,
        draft: destinations.draft,
        schedule: { onApproval: schedule.onApproval, local: schedule.local, timeZone: schedule.tz },
        attachCreative,
        onStep: setStep,
      })
      if (problems.length > 0) {
        toastError(`${noun} created, but: ${problems.join('; ')}`)
      }
      if (created.displayId) {
        router.push(workItemDetailPath(projectId, detailArea, noun, created.displayId))
        return
      }
      router.push(listPath)
    } catch (err) {
      // The create itself was refused: nothing exists yet, so the page stays as it is and says why.
      toastError(apiErrorMessage(err, `Could not create the ${lowerNoun}`))
      setSaving(false)
      setStep(null)
    }
  }

  const every = destinations.options
  const allByHand = every.length > 0 && every.every((o) => o.lane === 'MANUAL')

  return (
    <PageContainer>
      <PageHeader
        breadcrumbs={[{ label: workflowView.area ?? detailArea }, { label: `${noun}s`, href: listPath }, { label: `New ${lowerNoun}` }]}
        title={`New ${lowerNoun}`}
        description="Say what it says, show what it shows, pick where it goes and when. Everything can be changed afterwards."
      />
      <form id="compose-post" onSubmit={submit} className="pb-20">
        <div className="grid grid-cols-1 items-start gap-6 lg:grid-cols-[minmax(0,1fr)_minmax(20rem,26rem)]">
          <Card data-testid="compose-content">
            <CardHeader>
              <h2 className="text-sm font-semibold text-foreground">Content</h2>
            </CardHeader>
            <div className="space-y-5 px-4 py-4">
              <div className="space-y-1.5">
                <Label htmlFor="compose-caption">Caption</Label>
                <Textarea
                  id="compose-caption"
                  autoFocus
                  required
                  rows={6}
                  value={caption}
                  onChange={(e) => setCaption(e.target.value)}
                  placeholder={`What should this ${lowerNoun} say?`}
                />
                {captionFromCreative && creativeChoice && (
                  <p className="text-xs text-muted-foreground" data-testid="caption-from-creative">
                    From Creative {captionFromCreative}. Edit freely; the Creative stays as it is.
                  </p>
                )}
              </div>
              <div className="space-y-1.5">
                <Label htmlFor="compose-title">
                  Title <span className="font-normal text-muted-foreground">(optional; the caption&rsquo;s first line otherwise)</span>
                </Label>
                <Input id="compose-title" value={title} onChange={(e) => setTitle(e.target.value)} />
              </div>
              <div className="space-y-1.5">
                <Label htmlFor="compose-media">Media</Label>
                {creativeChoice && (
                  <div
                    data-testid="compose-creative-card"
                    className="flex items-center gap-3 rounded-[10px] border border-border bg-surface-2 p-3"
                  >
                    {creativeChoice.creative.latestRenderThumbnailUrl && (
                      // eslint-disable-next-line @next/next/no-img-element
                      <img
                        src={creativeChoice.creative.latestRenderThumbnailUrl}
                        alt=""
                        className="h-16 w-14 shrink-0 rounded-md object-cover"
                      />
                    )}
                    <div className="min-w-0 flex-1 space-y-1">
                      <p className="flex items-center gap-2 text-sm text-foreground">
                        <span className="rounded border border-border px-1 font-mono text-[11px] text-muted-foreground">
                          {creativeChoice.creative.displayId}
                        </span>
                        <span className="truncate font-medium">{creativeChoice.creative.name || creativeChoice.creative.headline || 'Untitled'}</span>
                      </p>
                      <p className="text-xs text-muted-foreground">
                        {creativeSummary(creativeChoice.creative)} · each destination gets its matching size
                      </p>
                      {creativeGroups[0] && <FrameStrip frames={creativeGroups[0].frames} />}
                    </div>
                    <div className="flex shrink-0 gap-1.5">
                      <Button type="button" variant="outline" size="sm" disabled={saving} onClick={() => setMediaPickerOpen(true)}>
                        Change
                      </Button>
                      <Button type="button" variant="ghost" size="sm" disabled={saving} onClick={removeCreative}>
                        Remove
                      </Button>
                    </div>
                  </div>
                )}
                {!creativeChoice && attachCreative && (
                  <p className="text-xs text-muted-foreground">
                    This {lowerNoun}&rsquo;s media will come from that creative&rsquo;s render once it&rsquo;s created.
                  </p>
                )}
                <input
                  id="compose-media"
                  type="file"
                  multiple
                  accept={ALLOWED_MEDIA_CONTENT_TYPES.join(',')}
                  className="sr-only"
                  onChange={(e) => {
                    // Read the list now: the input is cleared so the same file can be picked again.
                    addFiles(Array.from(e.target.files ?? []))
                    e.target.value = ''
                  }}
                />
                <div className="flex flex-wrap gap-2" data-testid="compose-media-strip">
                  {previews.map(({ file, url }, index) => (
                    <div key={`${file.name}-${index}`} className="group relative h-24 w-24 shrink-0 overflow-hidden rounded-md bg-surface-3">
                      {isVideoContentType(file.type) ? (
                        <video src={url} className="h-full w-full object-cover" aria-label={`Video ${index + 1}`} />
                      ) : (
                        // eslint-disable-next-line @next/next/no-img-element
                        <img src={url} alt={`Image ${index + 1}`} className="h-full w-full object-cover" />
                      )}
                      <span className="absolute bottom-1 left-1 inline-flex items-center gap-1 rounded bg-foreground/70 px-1 py-0.5 text-[10px] text-background">
                        {isVideoContentType(file.type) ? <Film className="h-3 w-3" aria-hidden /> : <ImageIcon className="h-3 w-3" aria-hidden />}
                        <span className="sr-only">{file.name}</span>
                      </span>
                      <button
                        type="button"
                        onClick={() => removeFile(index)}
                        aria-label={`Remove media ${index + 1}`}
                        disabled={saving}
                        className="absolute right-1 top-1 inline-flex h-5 w-5 items-center justify-center rounded-full bg-foreground/70 text-background opacity-0 transition-opacity focus:opacity-100 group-hover:opacity-100"
                      >
                        <X className="h-3 w-3" aria-hidden />
                      </button>
                    </div>
                  ))}
                  {pickedMedia.map((m, index) => (
                    <div key={m.id} data-testid={`compose-picked-${m.id}`} className="group relative h-24 w-24 shrink-0 overflow-hidden rounded-md bg-surface-3">
                      {m.mediaKind === 'VIDEO' ? (
                        <video src={m.url ?? undefined} poster={m.posterUrl ?? undefined} className="h-full w-full object-cover" aria-label={m.label || `Video ${index + 1}`} />
                      ) : (
                        // eslint-disable-next-line @next/next/no-img-element
                        <img src={m.url ?? undefined} alt={m.label || `Media ${index + 1}`} className="h-full w-full object-cover" />
                      )}
                      <button
                        type="button"
                        onClick={() => setPickedMedia((prev) => prev.filter((p) => p.id !== m.id))}
                        aria-label={`Remove ${m.label || `media ${index + 1}`}`}
                        disabled={saving}
                        className="absolute right-1 top-1 inline-flex h-5 w-5 items-center justify-center rounded-full bg-foreground/70 text-background opacity-0 transition-opacity focus:opacity-100 group-hover:opacity-100"
                      >
                        <X className="h-3 w-3" aria-hidden />
                      </button>
                    </div>
                  ))}
                  <button
                    type="button"
                    onClick={() => setMediaPickerOpen(true)}
                    disabled={saving}
                    className="flex h-24 w-24 shrink-0 flex-col items-center justify-center gap-1 rounded-md border border-dashed border-border-strong text-xs text-muted-foreground hover:bg-muted disabled:opacity-50"
                  >
                    <Plus className="h-4 w-4" aria-hidden />
                    Add media
                  </button>
                  <PickMediaDialog
                    open={mediaPickerOpen}
                    onOpenChange={setMediaPickerOpen}
                    projectId={projectId}
                    token={token}
                    onFilesChosen={addFiles}
                    defaultTab={hasRenderedCreative || creativeChoice ? 'creatives' : undefined}
                    extraTabs={[
                      {
                        value: 'creatives',
                        label: 'Creatives',
                        ownFooter: true,
                        content: (
                          <CreativesTab
                            creatives={creatives}
                            loadError={creativesError}
                            projectId={projectId}
                            token={token}
                            destinations={pickedDestinations}
                            captionEmpty={caption.trim().length === 0}
                            onUse={useCreative}
                            onCancel={() => setMediaPickerOpen(false)}
                          />
                        ),
                      },
                    ]}
                    onPicked={(items) =>
                      setPickedMedia((prev) => [...prev, ...items.filter((i) => !prev.some((p) => p.id === i.id))])
                    }
                  />
                </div>
                <p className="text-xs text-muted-foreground">
                  Images and video. Every destination gets all of them unless you customize it below.
                </p>
              </div>
            </div>
          </Card>

          <div className="space-y-6">
            <DestinationsPanel
              state={destinations}
              assets={pendingAssets}
              caption={caption}
              canEdit
              noun={noun}
              statusLabel="Draft"
              rowChip={creativeChip}
              headerSlot={
                allByHand ? (
                  // Every platform always offers a by-hand destination, so a list of only those means no
                  // account has been connected yet — say so, or the by-hand rows read as the only way.
                  <p className="text-sm text-muted-foreground">
                    No accounts are connected yet, so every destination here is posted by hand. Connect one under{' '}
                    <Link href={`/app/projects/${projectId}/integrations`} className="text-primary underline-offset-2 hover:underline">
                      Integrations
                    </Link>{' '}
                    and it appears here as a destination Conductor publishes to itself.
                  </p>
                ) : undefined
              }
            />
            <Card data-testid="compose-schedule">
              <CardHeader>
                <h2 className="text-sm font-semibold text-foreground">Schedule</h2>
              </CardHeader>
              <div className="space-y-2 px-4 py-4">
                <ScheduleEditor idPrefix="compose" value={schedule} onChange={setSchedule} disabled={saving} defaultOpen={false} align="start" />
                {schedule.onApproval ? (
                  <p className="text-sm text-muted-foreground">No date needed: approval puts it on the earliest slot every destination accepts.</p>
                ) : (
                  !schedule.local && (
                    <p className="text-sm text-muted-foreground">No time picked: the earliest time every destination accepts is used.</p>
                  )
                )}
              </div>
            </Card>
          </div>
        </div>

        <div className="fixed inset-x-0 bottom-0 z-10 border-t border-border bg-surface pb-[env(safe-area-inset-bottom)]">
          <div className="mx-auto flex w-full max-w-6xl items-center justify-between gap-3 px-4 py-3 sm:px-6 lg:px-8">
            <span className="text-sm text-muted-foreground" aria-live="polite">
              {step ?? (missing.length > 0 && !saving ? `Needs ${missing.join(' and ')}.` : '')}
            </span>
            <div className="flex gap-2">
              <Button type="button" variant="ghost" onClick={() => router.push(listPath)} disabled={saving}>
                Cancel
              </Button>
              <Button type="submit" disabled={!canSubmit}>
                {saving ? 'Creating…' : `Create ${lowerNoun}`}
              </Button>
            </div>
          </div>
        </div>
      </form>
    </PageContainer>
  )
}
