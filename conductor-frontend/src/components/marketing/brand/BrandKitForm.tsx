'use client'

// COND-24 T2: Settings › Brand. One kit shows as a single page; a second kit adds the switcher
// (see wireframes.md). Every field here is data the validator (backend CreativeValidator) and the
// renderer (@cliangdev/creative-render) read — nothing on this page may become a brand constant in
// product code (docs/cli-assets.md's domain-agnostic-guidance principle).

import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { PlusIcon, StarIcon, TrashIcon, UploadCloudIcon, XIcon } from 'lucide-react'
import { checkCreativeCopy } from '@cliangdev/creative-render/copy-rules'
import { mountBoard } from '@cliangdev/creative-render/mount'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { ConfirmModal } from '@/components/ui/confirm-modal'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Select } from '@/components/ui/select'
import { Skeleton } from '@/components/ui/skeleton'
import { Switch } from '@/components/ui/switch'
import { useToast } from '@/components/ui/toast'
import { Can } from '@/components/auth/Can'
import { useCan } from '@/contexts/PermissionsContext'
import { apiErrorMessage, type ApiError } from '@/lib/api'
import { cn } from '@/lib/utils'
import {
  BRAND_IMAGE_SLOTS,
  BRAND_TOKEN_KEYS,
  COPY_RULE_FIELDS,
  brandKitToBrand,
  confirmBrandKitImage,
  createBrandKit,
  deleteBrandKit,
  deleteBrandKitImage,
  listBrandKits,
  mintBrandKitImageUpload,
  patchBrandKit,
  type BrandImageSlot,
  type BrandKit,
  type CopyRule,
  type CopyRuleField,
} from '@/components/marketing/brand/types'
import { getCreativeRegistry, type CreativeRegistry } from '@/components/marketing/creatives/types'
import { putToSignedUrl } from '@/components/workitems/MediaUploadPanel'

interface Draft {
  name: string
  tokens: Record<string, string>
  fontFamily: string
  fontUrl: string
  ctaClaim: string
  accentPhraseRequired: boolean
  copyRules: CopyRule[]
  approvedLines: string[]
  enabledPlacements: string[]
  knowledgePagePath: string
}

function toDraft(kit: BrandKit): Draft {
  return {
    name: kit.name,
    tokens: { ...kit.tokens },
    fontFamily: kit.fontFamily ?? '',
    fontUrl: kit.fontUrl ?? '',
    ctaClaim: kit.ctaClaim ?? '',
    accentPhraseRequired: kit.accentPhraseRequired,
    copyRules: kit.copyRules.map((r) => ({ ...r, fields: [...r.fields] })),
    approvedLines: [...kit.approvedLines],
    enabledPlacements: [...kit.enabledPlacements],
    knowledgePagePath: kit.knowledgePagePath,
  }
}

function fieldError(errors: ApiError | null, field: string): string | undefined {
  return errors?.fieldErrors?.find((e) => e.field === field)?.message
}

let ruleSeq = 0
function newRuleId() {
  ruleSeq += 1
  return `rule-${Date.now()}-${ruleSeq}`
}

/** Lowercase, hyphenated, ascii-only — the slug a new kit's Name derives to, so the user is never
 *  asked for a slug directly. "kit" is the fallback for a name that has no ascii letters or digits
 *  at all (e.g. all emoji). */
function slugify(name: string): string {
  const slug = name
    .normalize('NFKD')
    .replace(/[̀-ͯ]/g, '')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
  return slug || 'kit'
}

function PreviewBoard({ draft, kit }: { draft: Draft; kit: BrandKit | null }) {
  const ref = useRef<HTMLDivElement>(null)
  useEffect(() => {
    if (!ref.current) return
    const brand = brandKitToBrand(kit)
    const handle = mountBoard(ref.current, {
      creative: {
        layout: 'stacked',
        theme: 'dark',
        headline: 'Headline, *emphasized*.',
        body: 'A line of supporting body copy shown here for preview only.',
      },
      brand: {
        ...brand,
        tokens: draft.tokens as ReturnType<typeof brandKitToBrand>['tokens'],
        fontFamily: draft.fontFamily || undefined,
        fontUrl: draft.fontUrl || undefined,
        ctaClaim: draft.ctaClaim || undefined,
      },
      placementKey: '4x5',
    })
    return () => handle.destroy()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [draft.tokens, draft.fontFamily, draft.fontUrl, draft.ctaClaim, kit])

  return <div ref={ref} className="relative aspect-[4/5] w-48 overflow-hidden rounded-md bg-surface-3" />
}

export interface BrandKitFormProps {
  projectId: string
  token: string
}

export function BrandKitForm({ projectId, token }: BrandKitFormProps) {
  const { showToast } = useToast()
  const canManage = useCan('creative.manage')

  const [kits, setKits] = useState<BrandKit[] | null>(null)
  const [selectedKitId, setSelectedKitId] = useState<string | null>(null)
  const [draft, setDraft] = useState<Draft | null>(null)
  const [registry, setRegistry] = useState<CreativeRegistry | null>(null)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<ApiError | null>(null)
  const [creatingKit, setCreatingKit] = useState(false)
  const [newKitName, setNewKitName] = useState('')
  const [creatingKitBusy, setCreatingKitBusy] = useState(false)
  const [deleteConfirmOpen, setDeleteConfirmOpen] = useState(false)
  const [testLine, setTestLine] = useState('')
  const [newLine, setNewLine] = useState('')
  const [uploadingSlot, setUploadingSlot] = useState<BrandImageSlot | null>(null)

  const selectedKit = useMemo(() => kits?.find((k) => k.id === selectedKitId) ?? null, [kits, selectedKitId])

  const reload = useCallback(async () => {
    const rows = await listBrandKits(projectId, token)
    setKits(rows)
    return rows
  }, [projectId, token])

  useEffect(() => {
    reload().then((rows) => {
      const initial = rows.find((k) => k.isDefault) ?? rows[0]
      if (initial) {
        setSelectedKitId(initial.id)
        setDraft(toDraft(initial))
      }
    })
    getCreativeRegistry(projectId, token).then(setRegistry).catch(() => setRegistry(null))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [projectId, token])

  function selectKit(kitId: string) {
    const kit = kits?.find((k) => k.id === kitId)
    if (!kit) return
    setSelectedKitId(kitId)
    setDraft(toDraft(kit))
    setError(null)
  }

  async function handleSave() {
    if (!selectedKit || !draft) return
    setSaving(true)
    setError(null)
    try {
      const updated = await patchBrandKit(
        projectId,
        selectedKit.id,
        {
          name: draft.name,
          tokens: draft.tokens,
          fontFamily: draft.fontFamily || null,
          fontUrl: draft.fontUrl || null,
          ctaClaim: draft.ctaClaim || null,
          accentPhraseRequired: draft.accentPhraseRequired,
          copyRules: draft.copyRules,
          approvedLines: draft.approvedLines,
          enabledPlacements: draft.enabledPlacements,
          knowledgePagePath: draft.knowledgePagePath,
        },
        token,
      )
      setKits((prev) => (prev ?? []).map((k) => (k.id === updated.id ? updated : k)))
      setDraft(toDraft(updated))
      showToast('Brand kit saved')
    } catch (err) {
      setError(err as ApiError)
      showToast(apiErrorMessage(err, 'Could not save the brand kit'), 'error')
    } finally {
      setSaving(false)
    }
  }

  async function handleSetDefault() {
    if (!selectedKit || selectedKit.isDefault) return
    try {
      const updated = await patchBrandKit(projectId, selectedKit.id, { isDefault: true }, token)
      const rows = await reload()
      const refreshed = rows.find((k) => k.id === updated.id) ?? updated
      setDraft(toDraft(refreshed))
      showToast(`${refreshed.name} is now the default kit`)
    } catch (err) {
      showToast(apiErrorMessage(err, 'Could not set the default kit'), 'error')
    }
  }

  async function handleCreateKit() {
    const name = newKitName.trim()
    if (!name) return
    setCreatingKitBusy(true)
    const base = slugify(name)
    // A duplicate slug is the only thing worth silently retrying — anything else (network, 4xx
    // other than a conflict) is shown to the user straight away instead of burning two more calls.
    const candidates = [base, `${base}-2`, `${base}-3`]
    let lastErr: unknown = null
    try {
      for (const slug of candidates) {
        try {
          const created = await createBrandKit(projectId, { slug, name }, token)
          await reload()
          setSelectedKitId(created.id)
          setDraft(toDraft(created))
          setCreatingKit(false)
          setNewKitName('')
          showToast('Brand kit created')
          return
        } catch (err) {
          lastErr = err
          if ((err as ApiError).status !== 409) break
        }
      }
      showToast(apiErrorMessage(lastErr, 'Could not create the brand kit'), 'error')
    } finally {
      setCreatingKitBusy(false)
    }
  }

  async function handleDeleteKit() {
    if (!selectedKit) return
    try {
      await deleteBrandKit(projectId, selectedKit.id, token)
      const rows = await reload()
      const next = rows.find((k) => k.isDefault) ?? rows[0] ?? null
      setSelectedKitId(next?.id ?? null)
      setDraft(next ? toDraft(next) : null)
      setDeleteConfirmOpen(false)
      showToast('Brand kit deleted')
    } catch (err) {
      setDeleteConfirmOpen(false)
      showToast(apiErrorMessage(err, 'Could not delete this kit — it may be the default, or a Creative still uses it.'), 'error')
    }
  }

  async function handleImageUpload(slot: BrandImageSlot, file: File) {
    if (!selectedKit) return
    setUploadingSlot(slot)
    try {
      const ticket = await mintBrandKitImageUpload(projectId, selectedKit.id, slot, {
        contentType: file.type,
        sizeBytes: file.size,
      }, token)
      await putToSignedUrl(ticket.uploadUrl, file, () => {})
      const updated = await confirmBrandKitImage(projectId, selectedKit.id, slot, ticket.gcsPath, token)
      setKits((prev) => (prev ?? []).map((k) => (k.id === updated.id ? updated : k)))
      setDraft(toDraft(updated))
    } catch (err) {
      showToast(apiErrorMessage(err, 'Could not upload the image'), 'error')
    } finally {
      setUploadingSlot(null)
    }
  }

  async function handleImageRemove(slot: BrandImageSlot) {
    if (!selectedKit) return
    try {
      const updated = await deleteBrandKitImage(projectId, selectedKit.id, slot, token)
      setKits((prev) => (prev ?? []).map((k) => (k.id === updated.id ? updated : k)))
      setDraft(toDraft(updated))
    } catch (err) {
      showToast(apiErrorMessage(err, 'Could not remove the image'), 'error')
    }
  }

  function updateDraft<K extends keyof Draft>(key: K, value: Draft[K]) {
    setDraft((prev) => (prev ? { ...prev, [key]: value } : prev))
  }

  function updateRule(index: number, patch: Partial<CopyRule>) {
    setDraft((prev) => {
      if (!prev) return prev
      const copyRules = prev.copyRules.map((r, i) => (i === index ? { ...r, ...patch } : r))
      return { ...prev, copyRules }
    })
  }

  function toggleRuleField(index: number, field: CopyRuleField) {
    setDraft((prev) => {
      if (!prev) return prev
      const rule = prev.copyRules[index]
      const has = rule.fields.includes(field)
      const fields = has ? rule.fields.filter((f) => f !== field) : [...rule.fields, field]
      return { ...prev, copyRules: prev.copyRules.map((r, i) => (i === index ? { ...r, fields } : r)) }
    })
  }

  const testResults = useMemo(() => {
    if (!draft) return []
    return checkCreativeCopy(
      { accentPhraseRequired: draft.accentPhraseRequired, copyRules: draft.copyRules },
      { headline: testLine },
    ) as { id: string; field: string; message: string }[]
  }, [draft, testLine])

  if (kits === null || !draft) {
    return (
      <div className="space-y-4">
        <Skeleton className="h-8 w-64" />
        <Skeleton className="h-64 w-full" />
      </div>
    )
  }

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-center gap-3">
        <Select
          aria-label="Brand kit"
          value={selectedKitId ?? ''}
          onChange={(e) => selectKit(e.target.value)}
          className="w-56"
        >
          {kits.map((k) => (
            <option key={k.id} value={k.id}>
              {k.name}
              {k.isDefault ? ' ★' : ''}
            </option>
          ))}
        </Select>
        <Can do="creative.manage">
          {selectedKit && !selectedKit.isDefault && (
            <Button variant="outline" size="sm" onClick={handleSetDefault}>
              <StarIcon className="mr-1.5 h-3.5 w-3.5" aria-hidden />
              Set as default
            </Button>
          )}
          <Button variant="outline" size="sm" onClick={() => setCreatingKit(true)}>
            <PlusIcon className="mr-1.5 h-3.5 w-3.5" aria-hidden />
            New kit
          </Button>
          {selectedKit && kits.length > 1 && (
            <Button variant="outline" size="sm" onClick={() => setDeleteConfirmOpen(true)}>
              <TrashIcon className="mr-1.5 h-3.5 w-3.5" aria-hidden />
              Delete kit
            </Button>
          )}
          <div className="ml-auto">
            <Button onClick={handleSave} disabled={saving}>
              {saving ? 'Saving…' : 'Save kit'}
            </Button>
          </div>
        </Can>
      </div>

      {creatingKit && canManage && (
        <div className="flex flex-wrap items-end gap-2 rounded-md border border-border p-3">
          <div>
            <Label htmlFor="new-kit-name" className="text-xs">Name</Label>
            <Input id="new-kit-name" value={newKitName} onChange={(e) => setNewKitName(e.target.value)} className="w-56" />
          </div>
          <Button size="sm" onClick={handleCreateKit} disabled={creatingKitBusy || !newKitName.trim()}>
            {creatingKitBusy ? 'Creating…' : 'Create'}
          </Button>
          <Button size="sm" variant="ghost" onClick={() => setCreatingKit(false)} disabled={creatingKitBusy}>
            Cancel
          </Button>
        </div>
      )}

      {error?.detail && !error.fieldErrors?.length && <Alert variant="destructive">{error.detail}</Alert>}

      <fieldset disabled={!canManage} className="m-0 grid min-w-0 gap-6 border-0 p-0 lg:grid-cols-2">
        <legend className="sr-only">Brand kit details</legend>
        <div className="space-y-6">
          <section className="space-y-3 rounded-lg border border-border p-4">
            <h3 className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">Identity</h3>
            <div>
              <Label htmlFor="kit-name">Name</Label>
              <Input id="kit-name" value={draft.name} onChange={(e) => updateDraft('name', e.target.value)} />
              {fieldError(error, 'name') && <p className="mt-1 text-xs text-destructive">{fieldError(error, 'name')}</p>}
            </div>
            <div className="grid grid-cols-2 gap-3">
              <div>
                <Label htmlFor="kit-font-family">Font family</Label>
                <Input id="kit-font-family" value={draft.fontFamily} onChange={(e) => updateDraft('fontFamily', e.target.value)} placeholder="Inter" />
              </div>
              <div>
                <Label htmlFor="kit-font-url">Font URL</Label>
                <Input id="kit-font-url" value={draft.fontUrl} onChange={(e) => updateDraft('fontUrl', e.target.value)} placeholder="https://fonts.googleapis.com/…" />
                <p className="mt-1 text-[11px] text-muted-foreground">
                  The accent phrase renders in italic — include the italic face (Google Fonts:
                  the <code className="font-mono">ital</code> axis, e.g. <code className="font-mono break-all">family=Poppins:ital,wght@0,400;0,800;1,800</code>)
                  or the browser fakes the slant and the space after it closes up.
                </p>
              </div>
            </div>
            <div>
              <Label>Logos</Label>
              <div className="grid grid-cols-4 gap-2">
                {BRAND_IMAGE_SLOTS.map(({ slot, label, hint }) => {
                  const urlKey = ({
                    mark: 'markUrl',
                    wordmark_dark: 'wordmarkDarkUrl',
                    wordmark_light: 'wordmarkLightUrl',
                    badge: 'badgeUrl',
                  } as const)[slot]
                  const url = selectedKit?.[urlKey]
                  return (
                    <div key={slot} className="space-y-1">
                      <div className="flex aspect-square items-center justify-center overflow-hidden rounded-md border border-dashed border-border bg-surface-3">
                        {url ? (
                          // eslint-disable-next-line @next/next/no-img-element
                          <img src={url} alt={label} className="max-h-full max-w-full object-contain" />
                        ) : (
                          <UploadCloudIcon className="h-4 w-4 text-muted-foreground" aria-hidden />
                        )}
                      </div>
                      <p className="text-center text-[11px] text-muted-foreground">{label}</p>
                      {hint && <p className="text-center text-[10px] text-muted-foreground/70">{hint}</p>}
                      <input
                        type="file"
                        accept="image/png,image/jpeg,image/webp,image/svg+xml"
                        id={`logo-input-${slot}`}
                        className="sr-only"
                        onChange={(e) => {
                          const file = e.target.files?.[0]
                          if (file) void handleImageUpload(slot, file)
                          e.target.value = ''
                        }}
                      />
                      <div className="flex justify-center gap-2">
                        <button
                          type="button"
                          disabled={uploadingSlot === slot}
                          onClick={() => document.getElementById(`logo-input-${slot}`)?.click()}
                          className="rounded text-[11px] text-muted-foreground hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-1 disabled:cursor-not-allowed disabled:opacity-50"
                        >
                          {uploadingSlot === slot ? 'Uploading…' : url ? 'Replace' : 'Upload'}
                        </button>
                        {url && (
                          <button
                            type="button"
                            className="rounded text-[11px] text-muted-foreground hover:text-destructive focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-1"
                            onClick={() => handleImageRemove(slot)}
                          >
                            Remove
                          </button>
                        )}
                      </div>
                    </div>
                  )
                })}
              </div>
            </div>
          </section>

          <section className="space-y-3 rounded-lg border border-border p-4">
            <h3 className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">Tokens</h3>
            <div className="grid grid-cols-2 gap-3">
              {BRAND_TOKEN_KEYS.map((key) => (
                <div key={key} className="space-y-1">
                  <Label htmlFor={`token-${key}`} className="text-xs">{key}</Label>
                  <div className="flex items-center gap-1.5">
                    <input
                      type="color"
                      aria-label={`${key} colour`}
                      value={/^#[0-9a-fA-F]{6}$/.test(draft.tokens[key] ?? '') ? draft.tokens[key] : '#888888'}
                      onChange={(e) => updateDraft('tokens', { ...draft.tokens, [key]: e.target.value })}
                      className="h-7 w-7 shrink-0 cursor-pointer rounded border border-border-strong bg-transparent p-0"
                    />
                    <Input
                      id={`token-${key}`}
                      value={draft.tokens[key] ?? ''}
                      onChange={(e) => updateDraft('tokens', { ...draft.tokens, [key]: e.target.value })}
                      placeholder="#RRGGBB"
                      className="min-w-[6.5rem] font-mono text-xs tabular-nums"
                    />
                  </div>
                </div>
              ))}
            </div>
          </section>

          <section className="space-y-3 rounded-lg border border-border p-4">
            <h3 className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">Placements</h3>
            <div className="flex flex-wrap gap-x-4 gap-y-2">
              {(registry?.placements ?? []).map((p) => (
                <Checkbox
                  key={p.key}
                  id={`placement-${p.key}`}
                  checked={draft.enabledPlacements.includes(p.key)}
                  onCheckedChange={(checked) =>
                    updateDraft(
                      'enabledPlacements',
                      checked
                        ? [...draft.enabledPlacements, p.key]
                        : draft.enabledPlacements.filter((k) => k !== p.key),
                    )
                  }
                  label={p.label}
                />
              ))}
            </div>
            <div>
              <Label htmlFor="kit-knowledge-page">Knowledge page</Label>
              <Input
                id="kit-knowledge-page"
                value={draft.knowledgePagePath}
                onChange={(e) => updateDraft('knowledgePagePath', e.target.value)}
                placeholder="marketing/brand.md"
              />
            </div>
          </section>

          <section className="space-y-2 rounded-lg border border-border p-4">
            <h3 className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">Preview</h3>
            <PreviewBoard draft={draft} kit={selectedKit} />
          </section>
        </div>

        <div className="space-y-6">
          <section className="space-y-3 rounded-lg border border-border p-4">
            <h3 className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">Copy</h3>
            <div>
              <Label htmlFor="kit-cta-claim">CTA claim</Label>
              <Input id="kit-cta-claim" value={draft.ctaClaim} onChange={(e) => updateDraft('ctaClaim', e.target.value)} />
            </div>
            <div className="flex items-center gap-2">
              <Switch
                id="kit-accent-phrase-required"
                checked={draft.accentPhraseRequired}
                onCheckedChange={(checked) => updateDraft('accentPhraseRequired', checked)}
              />
              <Label htmlFor="kit-accent-phrase-required" className="mb-0">
                Headline must carry exactly one accent phrase
              </Label>
            </div>

            <div className="space-y-2">
              <Label>Rules</Label>
              <div className="space-y-2" data-testid="copy-rules-table">
                {draft.copyRules.map((rule, i) => (
                  <div key={rule.id} className="space-y-1.5 rounded-md border border-border p-2">
                    <div className="flex items-center gap-1.5">
                      <Input
                        aria-label="Pattern"
                        value={rule.pattern}
                        onChange={(e) => updateRule(i, { pattern: e.target.value })}
                        placeholder="pattern"
                        className="font-mono text-xs"
                      />
                      <Input
                        aria-label="Flags"
                        value={rule.flags ?? ''}
                        onChange={(e) => updateRule(i, { flags: e.target.value })}
                        placeholder="i"
                        className="w-14 text-xs"
                      />
                      <button
                        type="button"
                        aria-label={`Remove rule ${rule.id}`}
                        onClick={() =>
                          updateDraft('copyRules', draft.copyRules.filter((_, idx) => idx !== i))
                        }
                        className="shrink-0 text-muted-foreground hover:text-destructive"
                      >
                        <XIcon className="h-3.5 w-3.5" aria-hidden />
                      </button>
                    </div>
                    <Input
                      aria-label="Message"
                      value={rule.message}
                      onChange={(e) => updateRule(i, { message: e.target.value })}
                      placeholder="Shown when this rule fails"
                      className="text-xs"
                    />
                    <Input
                      aria-label="Except pattern"
                      value={rule.exceptPattern ?? ''}
                      onChange={(e) => updateRule(i, { exceptPattern: e.target.value })}
                      placeholder="Exception pattern (optional)"
                      className="text-xs"
                    />
                    <div className="flex gap-3">
                      {COPY_RULE_FIELDS.map((f) => (
                        <Checkbox
                          key={f}
                          id={`rule-${rule.id}-${f}`}
                          checked={rule.fields.includes(f)}
                          onCheckedChange={() => toggleRuleField(i, f)}
                          label={f}
                        />
                      ))}
                    </div>
                  </div>
                ))}
              </div>
              <Button
                variant="outline"
                size="sm"
                onClick={() =>
                  updateDraft('copyRules', [
                    ...draft.copyRules,
                    { id: newRuleId(), pattern: '', message: '', fields: ['headline', 'body'] },
                  ])
                }
              >
                <PlusIcon className="mr-1.5 h-3.5 w-3.5" aria-hidden />
                Rule
              </Button>
            </div>

            <div>
              <Label htmlFor="kit-test-line">Test a line</Label>
              <Input
                id="kit-test-line"
                value={testLine}
                onChange={(e) => setTestLine(e.target.value)}
                placeholder="Type a headline to test, *like this*"
              />
              {testLine && (
                <p className={cn('mt-1 text-xs', testResults.length > 0 ? 'text-destructive' : 'text-status-approved')}>
                  {testResults.length > 0 ? testResults.map((r) => r.message).join(' ') : 'No issues.'}
                </p>
              )}
            </div>

            <div>
              <Label>Approved lines</Label>
              <ul className="space-y-1">
                {draft.approvedLines.map((line, i) => (
                  <li key={i} className="flex items-center gap-2 text-sm">
                    <span className="flex-1">{line}</span>
                    <button
                      type="button"
                      aria-label={`Remove line: ${line}`}
                      onClick={() => updateDraft('approvedLines', draft.approvedLines.filter((_, idx) => idx !== i))}
                      className="text-muted-foreground hover:text-destructive"
                    >
                      <XIcon className="h-3.5 w-3.5" aria-hidden />
                    </button>
                  </li>
                ))}
              </ul>
              <div className="mt-1 flex gap-2">
                <Input
                  aria-label="New approved line"
                  value={newLine}
                  onChange={(e) => setNewLine(e.target.value)}
                  placeholder="Add an approved line"
                />
                <Button
                  variant="outline"
                  size="sm"
                  onClick={() => {
                    if (!newLine.trim()) return
                    updateDraft('approvedLines', [...draft.approvedLines, newLine.trim()])
                    setNewLine('')
                  }}
                >
                  Add
                </Button>
              </div>
            </div>
          </section>
        </div>
      </fieldset>

      <ConfirmModal
        open={deleteConfirmOpen}
        title="Delete brand kit"
        description={`Delete "${selectedKit?.name}"? This cannot be undone.`}
        confirmLabel="Delete"
        busyLabel="Deleting…"
        onConfirm={handleDeleteKit}
        onCancel={() => setDeleteConfirmOpen(false)}
      />
    </div>
  )
}
