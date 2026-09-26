'use client'

// COND-24 T2: the Creative editor — form on the left, every enabled placement rendering live on the
// right via @cliangdev/creative-render. No network call happens on a keystroke (AC-P0-2.2): the
// preview boards are re-rendered locally from the in-memory form, and the copy-rule feedback runs
// the same brand kit rules the backend's CreativeValidator enforces at save time.

import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useRouter } from 'next/navigation'
import { ChevronLeftIcon, ChevronRightIcon, PlusIcon, XIcon } from 'lucide-react'
import { attachFocalDrag, enabledPlacements, mountBoard } from '@cliangdev/creative-render/mount'
import { checkCreativeCopy } from '@cliangdev/creative-render/copy-rules'
import { placements as renderPlacements } from '@cliangdev/creative-render/placements'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Checkbox } from '@/components/ui/checkbox'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Modal } from '@/components/ui/modal'
import { Select } from '@/components/ui/select'
import { Skeleton } from '@/components/ui/skeleton'
import { StatusBadge } from '@/components/ui/status-badge'
import { Textarea } from '@/components/ui/textarea'
import { useToast } from '@/components/ui/toast'
import { apiErrorMessage, type ApiError } from '@/lib/api'
import { cn } from '@/lib/utils'
import { brandKitToBrand, listBrandKits, type BrandKit } from '@/components/marketing/brand/types'
import { PhotoPicker } from '@/components/marketing/creatives/PhotoPicker'
import {
  createCreativeVariant,
  getCreative,
  getCreativePhoto,
  getCreativeReadiness,
  getCreativeRegistry,
  listCreativePhotos,
  patchCreative,
  type Creative,
  type CreativePhoto,
  type CreativeRegistry,
  type CreativeReadiness,
  type CreativeState,
  type CreativeTheme,
  type SequenceBeat,
  type SequenceKind,
} from '@/components/marketing/creatives/types'
import type { RenderCreative } from '@/components/marketing/creatives/renderTypes'

interface FormState {
  brandKitId: string
  name: string
  state: CreativeState
  layout: string
  theme: CreativeTheme
  photoId: string | null
  focalOverride: Record<string, string>
  headline: string
  body: string
  caption: string
  altText: string
  placements: string[]
  sequenceKind: SequenceKind | null
  sequence: SequenceBeat[]
  typeOverrides: Record<string, number[]>
}

function toForm(creative: Creative): FormState {
  return {
    brandKitId: creative.brandKitId,
    name: creative.name ?? '',
    state: creative.state,
    layout: creative.layout,
    theme: creative.theme,
    photoId: creative.photoId ?? null,
    focalOverride: { ...(creative.focalOverride ?? {}) },
    headline: creative.headline ?? '',
    body: creative.body ?? '',
    caption: creative.caption ?? '',
    altText: creative.altText ?? '',
    placements: [...creative.placements],
    sequenceKind: creative.sequenceKind ?? null,
    sequence: creative.sequence.map((b) => ({ ...b })),
    typeOverrides: { ...creative.typeOverrides },
  }
}

function violationsFor(err: ApiError | null, field: string): string[] {
  return (err?.violations ?? []).filter((v) => v.field === field).map((v) => v.message)
}

const STATE_HUE: Record<CreativeState, 'gray' | 'teal' | 'slate'> = {
  DRAFT: 'gray',
  READY: 'teal',
  ARCHIVED: 'slate',
}

function PlacementBoard({
  placementKey,
  creative,
  brand,
  sequenceIndex,
  onFocalChange,
}: {
  placementKey: string
  creative: RenderCreative
  brand: ReturnType<typeof brandKitToBrand>
  sequenceIndex: number
  onFocalChange: (placementKey: string, value: string) => void
}) {
  const containerRef = useRef<HTMLDivElement>(null)
  const handleRef = useRef<ReturnType<typeof mountBoard> | null>(null)
  const onFocalChangeRef = useRef(onFocalChange)
  useEffect(() => {
    onFocalChangeRef.current = onFocalChange
  }, [onFocalChange])

  useEffect(() => {
    if (!containerRef.current) return
    const handle = mountBoard(containerRef.current, { creative, brand, placementKey, sequenceIndex })
    handleRef.current = handle
    const drag = attachFocalDrag(handle, (value: string) => onFocalChangeRef.current(placementKey, value))
    return () => {
      drag.detach()
      handle.destroy()
      handleRef.current = null
    }
    // Mount once per placement; updates below patch the live board instead of re-mounting it.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [placementKey])

  useEffect(() => {
    handleRef.current?.update({ creative, brand, sequenceIndex })
  }, [creative, brand, sequenceIndex])

  const dims = renderPlacements[placementKey as keyof typeof renderPlacements] as { w: number; h: number; label: string } | undefined
  const height = 260
  const width = dims ? Math.round((height * dims.w) / dims.h) : height

  return (
    <div className="shrink-0 space-y-1">
      <div
        ref={containerRef}
        data-testid={`placement-board-${placementKey}`}
        className="relative overflow-hidden rounded-md bg-surface-3"
        style={{ width, height }}
      />
      <p className="text-center text-[11px] text-muted-foreground">{dims?.label ?? placementKey}</p>
    </div>
  )
}

export interface CreativeEditorProps {
  projectId: string
  creativeId: string
  token: string
}

export function CreativeEditor({ projectId, creativeId, token }: CreativeEditorProps) {
  const router = useRouter()
  const { showToast } = useToast()

  const [creative, setCreative] = useState<Creative | null>(null)
  const [form, setForm] = useState<FormState | null>(null)
  const [kits, setKits] = useState<BrandKit[] | null>(null)
  const [registry, setRegistry] = useState<CreativeRegistry | null>(null)
  const [photo, setPhoto] = useState<CreativePhoto | null>(null)
  const [photos, setPhotos] = useState<CreativePhoto[]>([])
  const [readiness, setReadiness] = useState<CreativeReadiness | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [saveError, setSaveError] = useState<ApiError | null>(null)
  const [conflict, setConflict] = useState(false)
  const [saving, setSaving] = useState(false)
  const [photoPickerOpen, setPhotoPickerOpen] = useState(false)
  const [variantOpen, setVariantOpen] = useState(false)
  const [variantHeadline, setVariantHeadline] = useState('')
  const [variantBusy, setVariantBusy] = useState(false)
  const [sequenceIndex, setSequenceIndex] = useState(0)

  const load = useCallback(async () => {
    const [loaded, kitRows, reg] = await Promise.all([
      getCreative(projectId, creativeId, token),
      listBrandKits(projectId, token),
      getCreativeRegistry(projectId, token),
    ])
    setCreative(loaded)
    setForm(toForm(loaded))
    setKits(kitRows)
    setRegistry(reg)
    setSequenceIndex(0)
    if (loaded.photoId) {
      getCreativePhoto(projectId, loaded.photoId, token).then(setPhoto).catch(() => setPhoto(null))
    } else {
      setPhoto(null)
    }
    getCreativeReadiness(projectId, creativeId, token).then(setReadiness).catch(() => setReadiness(null))
  }, [projectId, creativeId, token])

  useEffect(() => {
    load().catch((err) => setLoadError(apiErrorMessage(err, 'Could not load this Creative.')))
  }, [load])

  useEffect(() => {
    listCreativePhotos(projectId, token, true).then(setPhotos).catch(() => setPhotos([]))
  }, [projectId, token])

  useEffect(() => {
    if (!form?.photoId) {
      setPhoto(null)
      return
    }
    if (photo?.id === form.photoId) return
    let cancelled = false
    getCreativePhoto(projectId, form.photoId, token).then((p) => {
      if (!cancelled) setPhoto(p)
    })
    return () => {
      cancelled = true
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [form?.photoId, projectId, token])

  const selectedKit = useMemo(() => kits?.find((k) => k.id === form?.brandKitId) ?? null, [kits, form?.brandKitId])
  const brand = useMemo(() => brandKitToBrand(selectedKit), [selectedKit])

  const renderCreative: RenderCreative | null = useMemo(() => {
    if (!form) return null
    return {
      layout: form.layout as RenderCreative['layout'],
      theme: form.theme,
      headline: form.headline,
      body: form.body || undefined,
      caption: form.caption || undefined,
      photoUrl: photo?.url ?? undefined,
      focal: photo?.focal,
      focalOverride: Object.keys(form.focalOverride).length ? form.focalOverride : undefined,
      placements: form.placements,
      typeOverrides: form.typeOverrides,
      sequenceKind: form.sequenceKind ?? undefined,
      sequence: form.sequence.map((b) => ({
        headline: b.headline ?? undefined,
        body: b.body ?? undefined,
        cta: b.cta ?? undefined,
      })),
    }
  }, [form, photo])

  const enabledKeys = useMemo(() => {
    if (!renderCreative) return []
    return enabledPlacements(renderCreative, brand.enabledPlacements ?? [], renderPlacements) as string[]
  }, [renderCreative, brand])

  const copyIssues = useMemo(() => {
    if (!form || !selectedKit) return []
    return checkCreativeCopy(
      { accentPhraseRequired: selectedKit.accentPhraseRequired, copyRules: selectedKit.copyRules },
      { headline: form.headline, body: form.body, caption: form.caption },
    ) as { id: string; field: string; message: string }[]
  }, [form, selectedKit])

  function issuesFor(field: string): string[] {
    return copyIssues.filter((i) => i.field === field).map((i) => i.message)
  }

  function update<K extends keyof FormState>(key: K, value: FormState[K]) {
    setForm((prev) => (prev ? { ...prev, [key]: value } : prev))
  }

  function handleFocalChange(placementKey: string, value: string) {
    setForm((prev) =>
      prev ? { ...prev, focalOverride: { ...prev.focalOverride, [placementKey]: value } } : prev,
    )
  }

  async function handleSave() {
    if (!form || !creative) return
    setSaving(true)
    setSaveError(null)
    setConflict(false)
    try {
      const updated = await patchCreative(
        projectId,
        creativeId,
        {
          version: creative.version,
          brandKitId: form.brandKitId,
          name: form.name || undefined,
          state: form.state,
          layout: form.layout,
          theme: form.theme,
          photoId: form.photoId,
          focalOverride: Object.keys(form.focalOverride).length ? form.focalOverride : null,
          headline: form.headline,
          body: form.body,
          caption: form.caption,
          altText: form.altText,
          placements: form.placements,
          sequenceKind: form.sequenceKind,
          sequence: form.sequence,
          typeOverrides: form.typeOverrides,
        },
        token,
      )
      setCreative(updated)
      setForm(toForm(updated))
      getCreativeReadiness(projectId, creativeId, token).then(setReadiness).catch(() => setReadiness(null))
      showToast('Creative saved')
    } catch (err) {
      const apiErr = err as ApiError
      if (apiErr.status === 409) {
        setConflict(true)
      } else {
        setSaveError(apiErr)
        showToast(apiErrorMessage(err, 'Could not save this Creative'), 'error')
      }
    } finally {
      setSaving(false)
    }
  }

  async function handleReload() {
    setConflict(false)
    setSaveError(null)
    await load()
  }

  async function handleSaveAsVariant() {
    setVariantBusy(true)
    try {
      const variant = await createCreativeVariant(
        projectId,
        creativeId,
        { headline: variantHeadline || undefined },
        token,
      )
      router.push(`/app/projects/${projectId}/marketing/creatives/${variant.id}`)
    } catch (err) {
      showToast(apiErrorMessage(err, 'Could not create the variant'), 'error')
      setVariantBusy(false)
    }
  }

  if (loadError) return <Alert variant="destructive">{loadError}</Alert>
  if (!form || !creative || !kits || !registry) {
    return (
      <div className="space-y-4">
        <Skeleton className="h-8 w-80" />
        <div className="grid gap-6 lg:grid-cols-[360px_1fr]">
          <Skeleton className="h-96" />
          <Skeleton className="h-96" />
        </div>
      </div>
    )
  }

  const layoutOptions = Object.keys(registry.layouts)
  const themeOptions = registry.layouts[form.layout]?.themes ?? ['dark', 'light']
  const hasSequence = form.sequenceKind != null && form.sequence.length > 0

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center gap-3">
        <h1 className="text-lg font-semibold text-foreground">
          {creative.displayId} {form.name && <span className="text-muted-foreground">· {form.name}</span>}
        </h1>
        <StatusBadge status={form.state} hue={STATE_HUE[form.state]} label={form.state} />
        <span className="text-xs text-muted-foreground">
          kit: {selectedKit?.name ?? '—'} · v{creative.version}
        </span>
        <div className="ml-auto flex gap-2">
          <Button
            variant="outline"
            onClick={() => {
              setVariantHeadline(form.headline)
              setVariantOpen(true)
            }}
          >
            Save as variant
          </Button>
          <Button onClick={handleSave} disabled={saving}>
            {saving ? 'Saving…' : 'Save'}
          </Button>
        </div>
      </div>

      {conflict && (
        <Alert variant="warning">
          This Creative changed elsewhere.{' '}
          <button type="button" className="underline" onClick={handleReload}>
            Reload
          </button>{' '}
          to see the latest version before saving again.
        </Alert>
      )}
      {saveError?.detail && !saveError.violations?.length && (
        <Alert variant="destructive">{saveError.detail}</Alert>
      )}

      <div className="grid gap-6 lg:grid-cols-[360px_1fr]">
        <div className="space-y-4">
          <Card className="space-y-3 p-4">
            <div>
              <Label htmlFor="creative-name">Name</Label>
              <Input id="creative-name" value={form.name} onChange={(e) => update('name', e.target.value)} />
            </div>

            <div>
              <Label htmlFor="creative-kit">Brand kit</Label>
              <Select id="creative-kit" value={form.brandKitId} onChange={(e) => update('brandKitId', e.target.value)}>
                {kits.map((k) => (
                  <option key={k.id} value={k.id}>
                    {k.name}
                  </option>
                ))}
              </Select>
              {violationsFor(saveError, 'brandKitId').map((m) => (
                <p key={m} className="mt-1 text-xs text-destructive">{m}</p>
              ))}
            </div>

            <div>
              <Label>Photo</Label>
              <button
                type="button"
                onClick={() => setPhotoPickerOpen(true)}
                className="flex w-full items-center gap-2 rounded-md border border-border-strong px-2 py-1.5 text-left text-sm hover:bg-surface-3"
              >
                {photo?.url ? (
                  // eslint-disable-next-line @next/next/no-img-element
                  <img src={photo.url} alt="" className="h-8 w-8 rounded object-cover" />
                ) : (
                  <span className="h-8 w-8 rounded bg-surface-3" />
                )}
                <span className="truncate text-muted-foreground">
                  {photo ? photo.label || photo.id : 'Choose a photo…'}
                </span>
              </button>
              {violationsFor(saveError, 'photoId').map((m) => (
                <p key={m} className="mt-1 text-xs text-destructive">{m}</p>
              ))}
            </div>

            <div className="grid grid-cols-2 gap-3">
              <div>
                <Label htmlFor="creative-layout">Layout</Label>
                <Select
                  id="creative-layout"
                  value={form.layout}
                  onChange={(e) => {
                    const layout = e.target.value
                    const themes = registry.layouts[layout]?.themes ?? []
                    setForm((prev) =>
                      prev ? { ...prev, layout, theme: (themes.includes(prev.theme) ? prev.theme : (themes[0] as CreativeTheme) ?? prev.theme) } : prev,
                    )
                  }}
                >
                  {layoutOptions.map((l) => (
                    <option key={l} value={l}>
                      {l}
                    </option>
                  ))}
                </Select>
                {violationsFor(saveError, 'layout').map((m) => (
                  <p key={m} className="mt-1 text-xs text-destructive">{m}</p>
                ))}
              </div>
              <div>
                <Label htmlFor="creative-theme">Theme</Label>
                <Select id="creative-theme" value={form.theme} onChange={(e) => update('theme', e.target.value as CreativeTheme)}>
                  {themeOptions.map((t) => (
                    <option key={t} value={t}>
                      {t}
                    </option>
                  ))}
                </Select>
                {violationsFor(saveError, 'theme').map((m) => (
                  <p key={m} className="mt-1 text-xs text-destructive">{m}</p>
                ))}
              </div>
            </div>

            <div>
              <Label htmlFor="creative-headline">Headline</Label>
              <Textarea id="creative-headline" value={form.headline} onChange={(e) => update('headline', e.target.value)} rows={2} />
              {issuesFor('headline').map((m) => (
                <p key={m} className="mt-1 text-xs text-destructive">{m}</p>
              ))}
              {violationsFor(saveError, 'headline').map((m) => (
                <p key={`v-${m}`} className="mt-1 text-xs text-destructive">{m}</p>
              ))}
            </div>

            <div>
              <Label htmlFor="creative-body">Body</Label>
              <Textarea id="creative-body" value={form.body} onChange={(e) => update('body', e.target.value)} rows={3} />
              {issuesFor('body').map((m) => (
                <p key={m} className="mt-1 text-xs text-destructive">{m}</p>
              ))}
              {violationsFor(saveError, 'body').map((m) => (
                <p key={`v-${m}`} className="mt-1 text-xs text-destructive">{m}</p>
              ))}
            </div>

            <div>
              <Label htmlFor="creative-caption">Caption</Label>
              <Textarea id="creative-caption" value={form.caption} onChange={(e) => update('caption', e.target.value)} rows={2} />
              {issuesFor('caption').map((m) => (
                <p key={m} className="mt-1 text-xs text-destructive">{m}</p>
              ))}
            </div>

            <div>
              <Label htmlFor="creative-alt-text">Alt text</Label>
              <Input id="creative-alt-text" value={form.altText} onChange={(e) => update('altText', e.target.value)} />
            </div>

            <div>
              <Label>Extra placements</Label>
              <div className="flex flex-wrap gap-x-3 gap-y-1.5">
                {registry.placements
                  .filter((p) => !(brand.enabledPlacements ?? []).includes(p.key))
                  .map((p) => (
                    <Checkbox
                      key={p.key}
                      id={`extra-placement-${p.key}`}
                      checked={form.placements.includes(p.key)}
                      onCheckedChange={(checked) =>
                        update(
                          'placements',
                          checked ? [...form.placements, p.key] : form.placements.filter((k) => k !== p.key),
                        )
                      }
                      label={p.label}
                    />
                  ))}
              </div>
            </div>

            <div>
              <Label htmlFor="creative-state">State</Label>
              <Select id="creative-state" value={form.state} onChange={(e) => update('state', e.target.value as CreativeState)}>
                <option value="DRAFT">Draft</option>
                <option value="READY">Ready</option>
                <option value="ARCHIVED">Archived</option>
              </Select>
            </div>
          </Card>

          <Card className="space-y-3 p-4">
            <Label htmlFor="creative-sequence-kind">Sequence</Label>
            <Select
              id="creative-sequence-kind"
              value={form.sequenceKind ?? ''}
              onChange={(e) => {
                const v = e.target.value
                update('sequenceKind', v ? (v as SequenceKind) : null)
              }}
            >
              <option value="">None</option>
              <option value="story">Story</option>
              <option value="carousel">Carousel</option>
            </Select>

            {form.sequenceKind && (
              <div className="space-y-2">
                {form.sequence.map((beat, i) => (
                  <div key={i} className="flex items-start gap-2 rounded-md border border-border p-2">
                    <span className="mt-1.5 text-xs text-muted-foreground">{i + 1}</span>
                    <div className="flex-1 space-y-1">
                      <Input
                        aria-label={`Beat ${i + 1} headline`}
                        value={beat.headline ?? ''}
                        onChange={(e) =>
                          update(
                            'sequence',
                            form.sequence.map((b, idx) => (idx === i ? { ...b, headline: e.target.value } : b)),
                          )
                        }
                        placeholder={i === 0 ? form.headline || 'Headline (inherits above)' : 'Headline'}
                      />
                      <Textarea
                        aria-label={`Beat ${i + 1} body`}
                        value={beat.body ?? ''}
                        onChange={(e) =>
                          update(
                            'sequence',
                            form.sequence.map((b, idx) => (idx === i ? { ...b, body: e.target.value } : b)),
                          )
                        }
                        rows={2}
                        placeholder="Body (optional, not inherited)"
                      />
                    </div>
                    <button
                      type="button"
                      aria-label={`Remove beat ${i + 1}`}
                      onClick={() => update('sequence', form.sequence.filter((_, idx) => idx !== i))}
                      className="text-muted-foreground hover:text-destructive"
                    >
                      <XIcon className="h-3.5 w-3.5" aria-hidden />
                    </button>
                  </div>
                ))}
                <Button
                  variant="outline"
                  size="sm"
                  onClick={() => update('sequence', [...form.sequence, {}])}
                >
                  <PlusIcon className="mr-1.5 h-3.5 w-3.5" aria-hidden />
                  Beat
                </Button>
                {violationsFor(saveError, 'sequence').map((m) => (
                  <p key={m} className="text-xs text-destructive">{m}</p>
                ))}
              </div>
            )}
          </Card>
        </div>

        <div className="space-y-4">
          <Card className="space-y-3 p-4">
            <h3 className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">
              Live preview · drag a frame to set its focal point
            </h3>
            {renderCreative && (
              <div className="flex flex-wrap gap-4">
                {enabledKeys.map((key) => (
                  <PlacementBoard
                    key={key}
                    placementKey={key}
                    creative={renderCreative}
                    brand={brand}
                    sequenceIndex={sequenceIndex}
                    onFocalChange={handleFocalChange}
                  />
                ))}
              </div>
            )}
            {hasSequence && (
              <div className="flex items-center gap-2">
                <Button
                  variant="ghost"
                  size="sm"
                  disabled={sequenceIndex === 0}
                  onClick={() => setSequenceIndex((i) => Math.max(0, i - 1))}
                  aria-label="Previous beat"
                >
                  <ChevronLeftIcon className="h-4 w-4" aria-hidden />
                </Button>
                <span className="text-xs text-muted-foreground">
                  Beat {sequenceIndex + 1} of {form.sequence.length}
                </span>
                <Button
                  variant="ghost"
                  size="sm"
                  disabled={sequenceIndex >= form.sequence.length - 1}
                  onClick={() => setSequenceIndex((i) => Math.min(form.sequence.length - 1, i + 1))}
                  aria-label="Next beat"
                >
                  <ChevronRightIcon className="h-4 w-4" aria-hidden />
                </Button>
              </div>
            )}
          </Card>

          {readiness && (
            <Card className="space-y-1.5 p-4">
              <h3 className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">Readiness</h3>
              <ul className="space-y-1">
                {readiness.items.map((item) => (
                  <li
                    key={item.key}
                    className={cn('text-sm', item.ok ? 'text-status-approved' : item.blocking ? 'text-destructive' : 'text-status-progress')}
                  >
                    {item.message}
                  </li>
                ))}
              </ul>
            </Card>
          )}
        </div>
      </div>

      <PhotoPicker
        projectId={projectId}
        token={token}
        open={photoPickerOpen}
        onOpenChange={setPhotoPickerOpen}
        photos={photos}
        onPhotosChanged={() => listCreativePhotos(projectId, token, true).then(setPhotos)}
        onSelect={(p) => {
          setPhoto(p)
          update('photoId', p.id)
          setPhotoPickerOpen(false)
        }}
      />

      <Modal
        open={variantOpen}
        onOpenChange={setVariantOpen}
        title="Save as variant"
        description="Copies the photo, layout and body; give this variant its own headline."
        footer={
          <div className="flex justify-end gap-2">
            <Button variant="outline" onClick={() => setVariantOpen(false)} disabled={variantBusy}>
              Cancel
            </Button>
            <Button onClick={handleSaveAsVariant} disabled={variantBusy}>
              {variantBusy ? 'Creating…' : 'Create variant'}
            </Button>
          </div>
        }
      >
        <Label htmlFor="variant-headline">Headline</Label>
        <Textarea id="variant-headline" value={variantHeadline} onChange={(e) => setVariantHeadline(e.target.value)} rows={2} />
      </Modal>
    </div>
  )
}
