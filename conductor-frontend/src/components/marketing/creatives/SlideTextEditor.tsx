'use client'

// Edit the text on a slideshow Creative's slides. The words are fields of the stored spec; the preview
// builds the same page the render job builds (the composer's own builder) and runs the same in-page
// checks, so most problems show before anything is saved. Saving records one revision, re-renders only
// the slides whose text changed, and — once applied — reaches every Post that uses the Creative.
// A reviewer of an In Review Post can use the same editor with `postId`: that fix stays in the Post's
// review round.

import { useEffect, useMemo, useRef, useState } from 'react'
import { Dialog } from '@base-ui/react/dialog'
import { ChevronLeftIcon, ChevronRightIcon, XIcon } from 'lucide-react'
import { listTextFields, MAX_TEXT_LENGTH, type TextEdit } from '@cliangdev/creative-render/slideshow/text-fields.js'
import type { SlideRatio } from '@cliangdev/creative-render/slideshow/spec.js'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { Label } from '@/components/ui/label'
import { Tabs } from '@/components/ui/tabs'
import { Textarea } from '@/components/ui/textarea'
import { apiErrorMessage, type ApiError } from '@/lib/api'
import { SlideChecksList } from './SlideChecksList'
import { SlidePreview } from './SlidePreview'
import { TextChangeRows } from './TextChangeRows'
import {
  asChecks,
  buildPreviewHtml,
  createSlideTextEdit,
  creativeRatios,
  fieldLabel,
  getAffectedPosts,
  getRenderAvailability,
  specMediaIds,
  type AffectedOutcome,
  type AffectedPost,
  type RenderAvailability,
  type SlideshowCheck,
  type TextChange,
} from './slideText'
import { getCreative, listCreativePhotosByIds, type Creative, type CreativePhoto } from './types'

const RATIO_LABEL: Record<string, string> = { '9x16': '9:16', '4x5': '4:5' }

const OUTCOME_TEXT: Record<AffectedOutcome, string> = {
  'back-to-review': 'goes back to review (it was approved)',
  'new-round': 'starts a new review round',
  updated: 'is updated (still a draft)',
  unchanged: 'is not changed (already published)',
  'reviewer-fix': 'stays in this review round',
}

type Step = 'edit' | 'confirm' | 'rendering' | 'applied' | 'failed'

export interface SlideTextEditorProps {
  projectId: string
  token: string
  /** A CAROUSEL Creative with a `slideshowSpec`, read from the single-Creative endpoint. */
  creative: Creative
  /** 0-based slide to open on. */
  initialSlide?: number
  /** Media already loaded by the page; any other image the spec names is fetched. */
  mediaById?: Map<string, CreativePhoto>
  /** A reviewer's fix on this Post: the edit stays in the Post's current review round. */
  postId?: string
  /** Called once the edit has been applied (the page reloads the Creative). */
  onApplied: () => void | Promise<void>
  onClose: () => void
  /** How often to ask whether a rendering edit has settled. */
  pollMs?: number
}

function useDebounced<T>(value: T, ms: number): T {
  const [v, setV] = useState(value)
  useEffect(() => {
    const t = setTimeout(() => setV(value), ms)
    return () => clearTimeout(t)
  }, [value, ms])
  return v
}

const keyOf = (slide: number, path: string) => `${slide}|${path}`

function describeSaveError(err: unknown): string {
  const e = err as Partial<ApiError>
  if (e.violations?.length) return e.violations.map((v) => v.message).join(' ')
  if (e.status === 409) {
    if (e.code === 'SLIDE_EDIT_ACTIVE') return 'Another text edit is still rendering. Wait for it to finish, then try again.'
    if (e.code === 'RENDER_RUN_ACTIVE') return 'This Creative is rendering. Try again when that finishes.'
    return apiErrorMessage(err, 'This Creative changed since you opened the editor. Close it and open it again.')
  }
  return apiErrorMessage(err, 'Could not save this edit.')
}

export function SlideTextEditor({
  projectId,
  token,
  creative,
  initialSlide = 0,
  mediaById,
  postId,
  onApplied,
  onClose,
  pollMs = 3000,
}: SlideTextEditorProps) {
  const spec = creative.slideshowSpec
  const slideCount = spec?.slides.length ?? 0
  const ratios = useMemo(() => creativeRatios(creative.slides), [creative.slides])

  const [slide, setSlide] = useState(Math.max(0, Math.min(initialSlide, slideCount - 1)))
  const [ratio, setRatio] = useState<SlideRatio>(ratios[0]!)
  const [drafts, setDrafts] = useState<Record<string, string>>({})
  const [safeZones, setSafeZones] = useState(true)
  const [checks, setChecks] = useState<SlideshowCheck[] | null>(null)
  const [step, setStep] = useState<Step>(creative.pendingRevision ? 'rendering' : 'edit')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [availability, setAvailability] = useState<RenderAvailability | null>(null)
  const [affected, setAffected] = useState<AffectedPost[] | null>(null)
  const [failure, setFailure] = useState<{ message: string; checks: SlideshowCheck[] } | null>(null)
  const [revisionId, setRevisionId] = useState<string | null>(creative.pendingRevision?.id ?? null)
  const [fetchedMedia, setFetchedMedia] = useState<Map<string, CreativePhoto>>(new Map())

  // ── The spec's fields, and what has been changed ──────────────────────────────────────────────
  const fields = useMemo(() => (spec ? listTextFields(spec) : []), [spec])
  const original = useMemo(() => new Map(fields.map((f) => [keyOf(f.slide, f.path), f.text])), [fields])
  const edits: TextEdit[] = useMemo(
    () =>
      fields
        .filter((f) => {
          const d = drafts[keyOf(f.slide, f.path)]
          return d !== undefined && d !== f.text
        })
        .map((f) => ({ slide: f.slide, path: f.path, text: drafts[keyOf(f.slide, f.path)]! })),
    [fields, drafts],
  )
  const changes: TextChange[] = useMemo(
    () => edits.map((e) => ({ slide: e.slide, path: e.path, old: original.get(keyOf(e.slide, e.path)) ?? null, new: e.text })),
    [edits, original],
  )
  const changedSlides = new Set(edits.map((e) => e.slide)).size
  const slideFields = fields.filter((f) => f.slide === slide + 1)

  // ── Images for the preview ────────────────────────────────────────────────────────────────────
  const ids = useMemo(() => (spec ? specMediaIds(spec) : []), [spec])
  const missing = useMemo(() => ids.filter((id) => !mediaById?.has(id)), [ids, mediaById])
  const [mediaSettled, setMediaSettled] = useState(missing.length === 0)
  useEffect(() => {
    if (missing.length === 0) return
    let cancelled = false
    listCreativePhotosByIds(projectId, token, missing)
      .then((items) => !cancelled && setFetchedMedia(new Map(items.map((p) => [p.id, p]))))
      .catch(() => undefined)
      .finally(() => !cancelled && setMediaSettled(true))
    return () => {
      cancelled = true
    }
  }, [missing, projectId, token])
  const urlFor = (id: string) => mediaById?.get(id)?.url ?? fetchedMedia.get(id)?.url ?? null

  const html = useMemo(
    () => (mediaSettled ? buildPreviewHtml(creative, edits, slide, ratio, urlFor) : null),
    // urlFor reads mediaById/fetchedMedia, which are the real inputs
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [creative, edits, slide, ratio, mediaSettled, mediaById, fetchedMedia],
  )
  const shownHtml = useDebounced(html, 300)

  // ── Save flow ─────────────────────────────────────────────────────────────────────────────────
  async function reviewChanges() {
    setBusy(true)
    setError(null)
    try {
      const [posts, avail] = await Promise.all([
        getAffectedPosts(projectId, creative.id, token, postId),
        getRenderAvailability(projectId, creative.id, token).catch(() => null),
      ])
      setAffected(posts)
      setAvailability(avail)
      setStep('confirm')
    } catch (err) {
      setError(apiErrorMessage(err, 'Could not check which Posts this edit reaches.'))
    } finally {
      setBusy(false)
    }
  }

  async function save() {
    setBusy(true)
    setError(null)
    try {
      const res = await createSlideTextEdit(projectId, creative.id, { version: creative.version, edits, ...(postId ? { postId } : {}) }, token)
      setRevisionId(res.revision.id)
      setStep('rendering')
    } catch (err) {
      setError(describeSaveError(err))
      // A refused save leaves the edit as it was, so the person can fix it and try again.
      setStep('edit')
    } finally {
      setBusy(false)
    }
  }

  // While an edit renders, ask the Creative until it settles.
  const onAppliedRef = useRef(onApplied)
  useEffect(() => {
    onAppliedRef.current = onApplied
  })
  useEffect(() => {
    if (step !== 'rendering') return
    let cancelled = false
    const tick = async () => {
      try {
        const c = await getCreative(projectId, creative.id, token)
        if (cancelled || c.pendingRevision) return
        const latest = c.latestRevision
        if (latest?.status === 'FAILED' && (!revisionId || latest.id === revisionId)) {
          setFailure({ message: latest.error || 'The slides could not be rendered.', checks: asChecks(latest.checks) })
          setStep('failed')
        } else {
          await onAppliedRef.current()
          if (!cancelled) setStep('applied')
        }
      } catch {
        // A missed poll is retried on the next tick.
      }
    }
    const t = setInterval(() => void tick(), pollMs)
    return () => {
      cancelled = true
      clearInterval(t)
    }
  }, [step, projectId, creative.id, token, revisionId, pollMs])

  const previewErrors = (checks ?? []).filter((c) => c.severity === 'error')
  const others = (affected ?? []).filter((p) => p.outcome === 'back-to-review' || p.outcome === 'new-round')
  const updated = (affected ?? []).filter((p) => p.outcome === 'updated')
  const untouched = (affected ?? []).filter((p) => p.outcome === 'unchanged')
  const thisPost = (affected ?? []).find((p) => p.outcome === 'reviewer-fix')

  function resetField(s: number, path: string) {
    setDrafts((d) => {
      const next = { ...d }
      delete next[keyOf(s, path)]
      return next
    })
  }

  function availabilityLine(a: RenderAvailability): { text: string; warn: boolean } {
    if (a.runtime === 'target') return { text: `The slides re-render on ${a.targetName || 'the workspace runtime target'}.`, warn: false }
    if (a.daemonOnline === false)
      return {
        text: 'No runtime target is set up and no daemon is online, so nothing can render this yet. Run `conductor creative render-slides <revision id>` on a machine with the CLI after saving, or start the daemon.',
        warn: true,
      }
    return {
      text: 'No runtime target is set up, so the slides re-render on a self-hosted runner. Keep your daemon running, or run `conductor creative render-slides <revision id>` on a machine with the CLI after saving.',
      warn: false,
    }
  }

  const title = postId ? 'Fix text' : 'Edit text'

  return (
    <Dialog.Root open onOpenChange={(open) => !open && onClose()}>
      <Dialog.Portal>
        <Dialog.Backdrop className="fixed inset-0 z-40 bg-black/60 dark:bg-black/80" />
        <Dialog.Popup
          className="fixed left-1/2 top-1/2 z-50 flex max-h-[92vh] w-[min(1040px,94vw)] -translate-x-1/2 -translate-y-1/2 flex-col overflow-hidden rounded-lg border border-border bg-popover shadow-lg outline-none"
          data-testid="slide-text-editor"
        >
          <div className="flex shrink-0 items-center justify-between gap-3 border-b border-border px-4 py-3">
            <div className="min-w-0">
              <Dialog.Title className="truncate text-sm font-semibold text-foreground">
                {title} · {creative.displayId}
              </Dialog.Title>
              <Dialog.Description className="text-xs text-muted-foreground">
                {postId
                  ? 'Your fix stays in this Post’s review round.'
                  : 'Only the slides whose text changes are re-rendered.'}
              </Dialog.Description>
            </div>
            <Button variant="ghost" size="icon" aria-label="Close" onClick={onClose}>
              <XIcon className="h-4 w-4" aria-hidden />
            </Button>
          </div>

          <div className="min-h-0 flex-1 overflow-y-auto p-4">
            {step === 'edit' && spec && (
              <div className="grid gap-5 md:grid-cols-[minmax(0,1fr)_320px]">
                <div className="space-y-4">
                  <div className="flex items-center gap-2">
                    <Button variant="ghost" size="sm" disabled={slide === 0} onClick={() => setSlide(slide - 1)} aria-label="Previous slide">
                      <ChevronLeftIcon className="h-4 w-4" aria-hidden />
                    </Button>
                    <span className="text-sm font-medium text-foreground" data-testid="slide-position">
                      Slide {slide + 1} of {slideCount}
                    </span>
                    <Button variant="ghost" size="sm" disabled={slide >= slideCount - 1} onClick={() => setSlide(slide + 1)} aria-label="Next slide">
                      <ChevronRightIcon className="h-4 w-4" aria-hidden />
                    </Button>
                  </div>

                  {slideFields.length === 0 ? (
                    <p className="text-sm text-muted-foreground">This slide has no editable text.</p>
                  ) : (
                    slideFields.map((f) => {
                      const k = keyOf(f.slide, f.path)
                      const value = drafts[k] ?? f.text
                      const edited = value !== f.text
                      const id = `slide-text-${f.slide}-${f.path}`
                      return (
                        <div key={k} data-testid={`slide-field-${f.path}`}>
                          <div className="mb-1 flex items-center justify-between gap-2">
                            <Label htmlFor={id} className="mb-0">
                              {fieldLabel(f.path)}
                            </Label>
                            {edited && (
                              <span className="flex items-center gap-2 text-xs text-muted-foreground">
                                Edited
                                <button type="button" onClick={() => resetField(f.slide, f.path)} className="text-primary hover:underline">
                                  Reset
                                </button>
                              </span>
                            )}
                          </div>
                          <Textarea
                            id={id}
                            value={value}
                            maxLength={MAX_TEXT_LENGTH}
                            rows={Math.max(2, value.split('\n').length)}
                            className="min-h-0"
                            onChange={(e) => setDrafts((d) => ({ ...d, [k]: e.target.value }))}
                          />
                        </div>
                      )
                    })
                  )}
                  <p className="text-xs text-muted-foreground">
                    Line breaks are kept as you type them. Text is limited to {MAX_TEXT_LENGTH} characters.
                  </p>
                </div>

                <div className="space-y-3">
                  {ratios.length > 1 && (
                    <Tabs
                      ariaLabel="Preview ratio"
                      value={ratio}
                      onValueChange={(v) => setRatio(v as SlideRatio)}
                      items={ratios.map((r) => ({ value: r, label: RATIO_LABEL[r] ?? r }))}
                    />
                  )}
                  {shownHtml ? (
                    <SlidePreview
                      html={shownHtml}
                      ratio={ratio}
                      showSafeZones={safeZones}
                      onChecks={setChecks}
                      title={`Preview of slide ${slide + 1} at ${RATIO_LABEL[ratio] ?? ratio}`}
                    />
                  ) : (
                    <p className="text-sm text-muted-foreground">Loading the slide’s images…</p>
                  )}
                  {ratio === '9x16' && (
                    <Checkbox checked={safeZones} onCheckedChange={setSafeZones} label="Show safe zones" />
                  )}
                  <div aria-live="polite" data-testid="preview-checks">
                    <h4 className="mb-1.5 text-xs font-medium text-muted-foreground">Checks for this slide</h4>
                    {checks === null ? (
                      <p className="text-sm text-muted-foreground">Measuring…</p>
                    ) : checks.length === 0 ? (
                      <p className="text-sm text-muted-foreground">No problems found.</p>
                    ) : (
                      <SlideChecksList checks={checks} />
                    )}
                  </div>
                </div>
              </div>
            )}

            {step === 'confirm' && (
              <div className="space-y-4" data-testid="slide-text-confirm">
                <section aria-label="What changes" className="space-y-2">
                  <h3 className="text-sm font-medium text-foreground">
                    {changes.length} {changes.length === 1 ? 'change' : 'changes'} on {changedSlides} {changedSlides === 1 ? 'slide' : 'slides'}
                  </h3>
                  <TextChangeRows changes={changes} />
                </section>

                <section aria-label="Posts this reaches" className="space-y-2" data-testid="affected-posts">
                  <h3 className="text-sm font-medium text-foreground">Posts that use this Creative</h3>
                  {thisPost && (
                    <p className="text-sm text-foreground">
                      {thisPost.displayId} {thisPost.title}: {OUTCOME_TEXT['reviewer-fix']}. Approvals already given in this round no longer
                      count, so each reviewer approves again.
                    </p>
                  )}
                  {(affected ?? []).length === 0 ? (
                    <p className="text-sm text-muted-foreground">No Posts use it yet.</p>
                  ) : (
                    <>
                      {others.length > 0 && (
                        <>
                          <p className="text-sm text-muted-foreground">
                            {postId ? 'Other Posts that will go back to review:' : 'These Posts go back to review:'}
                          </p>
                          <ul className="space-y-1">
                            {others.map((p) => (
                              <li key={p.workItemId} className="text-sm" data-testid={`affected-${p.outcome}`}>
                                <span className="font-mono text-xs">{p.displayId}</span> {p.title}{' '}
                                <span className="text-muted-foreground">· {p.statusLabel}, {OUTCOME_TEXT[p.outcome]}</span>
                              </li>
                            ))}
                          </ul>
                        </>
                      )}
                      {[...updated, ...untouched].length > 0 && (
                        <ul className="space-y-1">
                          {[...updated, ...untouched].map((p) => (
                            <li key={p.workItemId} className="text-sm" data-testid={`affected-${p.outcome}`}>
                              <span className="font-mono text-xs">{p.displayId}</span> {p.title}{' '}
                              <span className="text-muted-foreground">· {p.statusLabel}, {OUTCOME_TEXT[p.outcome]}</span>
                            </li>
                          ))}
                        </ul>
                      )}
                    </>
                  )}
                </section>

                {availability && (
                  <Alert variant={availabilityLine(availability).warn ? 'warning' : 'info'} data-testid="render-availability">
                    <p>{availabilityLine(availability).text}</p>
                  </Alert>
                )}
                {previewErrors.length > 0 && (
                  <Alert variant="warning">
                    <p>
                      The preview of the slide you were on shows {previewErrors.length} {previewErrors.length === 1 ? 'error' : 'errors'}. The
                      save is refused if the render finds one.
                    </p>
                  </Alert>
                )}
              </div>
            )}

            {step === 'rendering' && (
              <div className="space-y-2 py-6 text-center" role="status" data-testid="slide-text-rendering">
                <p className="text-sm font-medium text-foreground">Re-rendering the changed slides…</p>
                <p className="text-sm text-muted-foreground">
                  This takes a minute or two. You can close this; the Creative page shows when it is done.
                </p>
              </div>
            )}

            {step === 'applied' && (
              <div className="space-y-2 py-6 text-center" role="status" data-testid="slide-text-applied">
                <p className="text-sm font-medium text-foreground">The text is updated.</p>
                <p className="text-sm text-muted-foreground">The slides were re-rendered and the Posts that use them were updated.</p>
              </div>
            )}

            {step === 'failed' && failure && (
              <div className="space-y-3" data-testid="slide-text-failed">
                <Alert variant="destructive">
                  <p>{failure.message}</p>
                </Alert>
                {failure.checks.length > 0 && <SlideChecksList checks={failure.checks} withLocation />}
                <p className="text-sm text-muted-foreground">Nothing was changed. Adjust the text and save again.</p>
              </div>
            )}

            {error && (
              <Alert variant="destructive" className="mt-3">
                <p>{error}</p>
              </Alert>
            )}
          </div>

          <div className="flex shrink-0 items-center justify-between gap-3 border-t border-border px-4 py-3">
            <span className="text-xs text-muted-foreground" data-testid="slide-text-summary">
              {step === 'edit' || step === 'confirm'
                ? edits.length === 0
                  ? 'No changes yet.'
                  : `${edits.length} ${edits.length === 1 ? 'field' : 'fields'} changed on ${changedSlides} ${changedSlides === 1 ? 'slide' : 'slides'}`
                : ''}
            </span>
            <div className="flex items-center gap-2">
              {step === 'edit' && (
                <>
                  <Button variant="outline" onClick={onClose}>
                    Cancel
                  </Button>
                  <Button onClick={() => void reviewChanges()} disabled={edits.length === 0 || busy}>
                    {busy ? 'Checking…' : 'Review and save'}
                  </Button>
                </>
              )}
              {step === 'confirm' && (
                <>
                  <Button variant="outline" onClick={() => setStep('edit')} disabled={busy}>
                    Back
                  </Button>
                  <Button onClick={() => void save()} disabled={busy}>
                    {busy ? 'Saving…' : 'Save and re-render'}
                  </Button>
                </>
              )}
              {step === 'rendering' && (
                <Button variant="outline" onClick={onClose}>
                  Close
                </Button>
              )}
              {step === 'applied' && <Button onClick={onClose}>Done</Button>}
              {step === 'failed' && (
                <Button
                  onClick={() => {
                    setFailure(null)
                    setStep('edit')
                  }}
                >
                  Back to editing
                </Button>
              )}
            </div>
          </div>
        </Dialog.Popup>
      </Dialog.Portal>
    </Dialog.Root>
  )
}
