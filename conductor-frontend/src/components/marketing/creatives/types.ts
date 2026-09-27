// Wire shapes and API calls for the Creative library (openapi-v2 tag `creatives`): photos with
// provenance, the layout/placement registry, and Creatives themselves (COND-24 T2).
//
// A Creative's shape mirrors @cliangdev/creative-render's `Creative` type closely on purpose — see
// conductor-creative/README.md's "The creative shape" — so `creativeToRenderCreative` below is a
// near pass-through, not a real transform.

import { apiDelete, apiGet, apiPatch, apiPost } from '@/lib/api'
import type { RenderCreative } from './renderTypes'

export type CreativeState = 'DRAFT' | 'READY' | 'ARCHIVED'
export type CreativeTheme = 'dark' | 'light'
export type SequenceKind = 'story' | 'carousel'
export type CreativeLockup = 'plain' | 'chip'

/** Per-placement overrides of layout-derived numbers, in pixels — an absent placement key falls
 * back to the layout's own default. See conductor-creative/README.md's "the creative shape". */
export interface CreativeLayoutOverrides {
  band?: Record<string, number> | null
  padBottom?: Record<string, number> | null
}

export interface SequenceBeat {
  headline?: string | null
  body?: string | null
  photoId?: string | null
  cta?: boolean | null
}

export interface Creative {
  id: string
  projectId: string
  brandKitId: string
  number: number
  variantLetter: string
  displayId: string
  parentCreativeId?: string | null
  name?: string | null
  state: CreativeState
  layout: string
  theme: CreativeTheme
  photoId?: string | null
  photoUrl?: string | null
  focalOverride?: Record<string, string> | null
  headline?: string | null
  body?: string | null
  caption?: string | null
  altText?: string | null
  placements: string[]
  sequenceKind?: SequenceKind | null
  sequence: SequenceBeat[]
  carouselRatio?: string | null
  typeOverrides: Record<string, number[]>
  layoutOverrides?: CreativeLayoutOverrides | null
  lockup: CreativeLockup
  version: number
  createdBy?: string | null
  createdAt: string
  updatedAt: string
  /** The last SUCCEEDED render's id, if any — carried on both list rows and the detail response. */
  latestRenderId?: string | null
  /** The last SUCCEEDED render's 4x5 frame (else its first frame) — the library grid's thumbnail. */
  latestRenderThumbnailUrl?: string | null
  /** Full last-SUCCEEDED-render detail (with frames) — GET detail only, absent from list rows. */
  latestRender?: CreativeRender | null
  /** The family's RUNNING hook experiment, if any (COND-24 T5). Same value on every letter in the family. */
  activeExperimentId?: string | null
}

export interface CreateCreativeRequest {
  brandKitId?: string | null
  name?: string | null
  state?: CreativeState
  layout?: string | null
  theme?: CreativeTheme
  photoId?: string | null
  focalOverride?: Record<string, string> | null
  headline?: string | null
  body?: string | null
  caption?: string | null
  altText?: string | null
  placements?: string[]
  sequenceKind?: SequenceKind | null
  sequence?: SequenceBeat[]
  carouselRatio?: string | null
  lockup?: CreativeLockup
  layoutOverrides?: CreativeLayoutOverrides | null
}

export interface PatchCreativeRequest {
  version: number
  brandKitId?: string
  name?: string
  state?: CreativeState
  layout?: string
  theme?: CreativeTheme
  photoId?: string | null
  focalOverride?: Record<string, string> | null
  headline?: string | null
  body?: string | null
  caption?: string | null
  altText?: string | null
  placements?: string[]
  sequenceKind?: SequenceKind | null
  sequence?: SequenceBeat[]
  carouselRatio?: string | null
  typeOverrides?: Record<string, number[]> | null
  lockup?: CreativeLockup
  layoutOverrides?: CreativeLayoutOverrides | null
}

export interface CreateCreativeVariantRequest {
  headline?: string | null
  name?: string | null
}

export interface CreativeReadinessItem {
  key: string
  ok: boolean
  blocking: boolean
  message: string
}

export interface CreativeReadiness {
  ready: boolean
  items: CreativeReadinessItem[]
}

export interface CreativePhoto {
  id: string
  projectId: string
  label?: string | null
  contentType: string
  sizeBytes: number
  width?: number | null
  height?: number | null
  source?: string | null
  licence?: string | null
  aiGenerated: boolean
  checked: boolean
  blocked: boolean
  blockedReason?: string | null
  focal: Record<string, string>
  uploadStatus: 'PENDING' | 'UPLOADED'
  url?: string | null
  uploadUrl?: string | null
  warnings: string[]
  createdBy?: string | null
  createdAt: string
}

export interface CreateCreativePhotoRequest {
  label?: string | null
  contentType: 'image/jpeg' | 'image/png' | 'image/webp'
  sizeBytes: number
  width: number
  height: number
  source?: string | null
  licence?: string | null
  aiGenerated?: boolean
}

export interface PatchCreativePhotoRequest {
  label?: string
  source?: string
  licence?: string
  aiGenerated?: boolean
  checked?: boolean
  blocked?: boolean
  blockedReason?: string
  focal?: Record<string, string> | null
}

export interface CreativeRegistryLayout {
  themes: string[]
}

export interface CreativeRegistryPlacement {
  key: string
  label: string
  platform?: string | null
  width: number
  height: number
  default: boolean
}

export interface CreativeRegistry {
  layouts: Record<string, CreativeRegistryLayout>
  placements: CreativeRegistryPlacement[]
}

// ── Renders (COND-24 T3) ─────────────────────────────────────────────────────
//
// Rendering itself happens locally — Claude Code/Desktop's `render_creative` MCP tool or
// `conductor creative render <id>` on the CLI, both driving @cliangdev/creative-render's job core
// with Playwright against the user's own machine. There is no server-launched render and no render
// button on the web; the frontend only ever reads render state and, once a render SUCCEEDED, attaches
// its frames to a Post. See the T3 contract for the full endpoint list.

export type CreativeRenderState = 'RUNNING' | 'SUCCEEDED' | 'FAILED'

export interface CreativeRenderFrame {
  id: string
  placementKey: string
  platform?: string | null
  sequenceIndex?: number | null
  /** Signed GET URL, valid long enough to load a thumbnail and drive a download link. */
  url: string
  width: number
  height: number
  sizeBytes: number
  warnings: string[]
}

export interface CreativeRender {
  id: string
  state: CreativeRenderState
  previewOnly: boolean
  renderer?: string | null
  workflowRunId?: string | null
  /** The Creative's `version` at request time — compared against its current version to flag staleness. */
  creativeVersion: number
  requestedAt: string
  finishedAt?: string | null
  error?: string | null
  frames: CreativeRenderFrame[]
}

export interface AttachCreativeRequest {
  renderId: string
  workItemId: string
}

export interface AttachedRenderAsset {
  assetId: string
  frameId: string
  placementKey: string
  sequenceIndex?: number | null
}

export interface AttachTargetUpdated {
  targetId: string
  platform: string
  assetIds: string[]
}

export interface AttachTargetSkipped {
  targetId: string
  platform: string
  reason: string
}

export interface AttachCreativeResult {
  assets: AttachedRenderAsset[]
  targetsUpdated: AttachTargetUpdated[]
  targetsSkipped: AttachTargetSkipped[]
}

/** Latest-first, capped at 20 by the server. */
export function listCreativeRenders(
  projectId: string,
  creativeId: string,
  token: string,
): Promise<CreativeRender[]> {
  return apiGet<CreativeRender[]>(`${creativesBase(projectId)}/${creativeId}/renders`, token)
}

/**
 * Attaches a SUCCEEDED render's frames to a Post: server-side copies each non-`sheet` frame into the
 * Post's asset storage and, for targets without custom media, assigns them by placement. Returns what
 * changed so the caller can show it — never silently.
 */
export function attachCreativeRender(
  projectId: string,
  creativeId: string,
  body: AttachCreativeRequest,
  token: string,
): Promise<AttachCreativeResult> {
  return apiPost<AttachCreativeResult>(`${creativesBase(projectId)}/${creativeId}/attach`, body, token)
}

const creativesBase = (projectId: string) => `/api/v2/projects/${projectId}/marketing/creatives`
const photosBase = (projectId: string) => `/api/v2/projects/${projectId}/marketing/photos`
const experimentsBase = (projectId: string) => `/api/v2/projects/${projectId}/marketing/experiments`

// ── Performance and hook experiments (COND-24 T5) ────────────────────────────────────────────────
//
// See docs/creatives.md's "Performance and experiments": attribution follows a rendered frame to
// every published destination whose media still includes it, rolled up per lettered variant. A hook
// experiment is a human-started A/B on one Creative family's variants, decided automatically once
// every variant has window data, with a human confirming a winner's headline into the brand kit.

/** One platform's slice of one Creative variant's numbers. */
export interface CreativePerformancePlatform {
  platform: string
  posts: number
  views: number
  engagementRate?: number | null
}

/** One Creative variant's attributed performance — published destinations whose media traces back
 * to one of its rendered frames. */
export interface CreativePerformanceEntry {
  creativeId: string
  /** The variant's display id, e.g. "12b". */
  label: string
  headline?: string | null
  posts: number
  views: number
  engagementRate?: number | null
  avgViewPct?: number | null
  views72h?: number | null
  byPlatform: CreativePerformancePlatform[]
}

export interface CreativePerformanceResponse {
  creativeId: string
  /** Every lettered variant of this Creative's family, oldest letter first. */
  family: CreativePerformanceEntry[]
}

export function getCreativePerformance(
  projectId: string,
  creativeId: string,
  token: string,
): Promise<CreativePerformanceResponse> {
  return apiGet<CreativePerformanceResponse>(`${creativesBase(projectId)}/${creativeId}/performance`, token)
}

export type CreativeExperimentMetric = 'views' | 'engagement_rate' | 'avg_view_pct'
export type CreativeExperimentState = 'RUNNING' | 'DECIDED' | 'INCONCLUSIVE'

/** One variant's row inside a decided (or gave-up) experiment's `summary.variants`. */
export interface CreativeExperimentSummaryVariant {
  creativeId: string
  label: string
  headline?: string | null
  hasData: boolean
  views?: number | null
  engagementRate?: number | null
  avgViewPct?: number | null
}

/** Shape of `CreativeExperimentResponse.summary`, written once by `decide()` and never recomputed
 * after `decidedAt` — absent (null) while still RUNNING. `reason` is `"tie"` (an exact tie among the
 * leaders) or `"insufficient_data"` (the seven-day grace period lapsed with a variant still missing
 * window data); absent on a clean decision. */
export interface CreativeExperimentSummary {
  comparisonMetric?: CreativeExperimentMetric
  reason?: 'tie' | 'insufficient_data'
  variants: CreativeExperimentSummaryVariant[]
}

export interface CreateCreativeExperimentRequest {
  /** Any Creative in the family (a lettered variant or the root) — resolved to the family's root automatically. */
  creativeId: string
  metric?: CreativeExperimentMetric
  /** Defaults to 72 on the server. */
  windowHours?: number
}

export interface CreativeExperimentResponse {
  id: string
  projectId: string
  /** The family's root Creative id (variant letter "a"). */
  parentCreativeId: string
  metric: CreativeExperimentMetric
  windowHours: number
  state: CreativeExperimentState
  winnerCreativeId?: string | null
  decidedAt?: string | null
  summary?: CreativeExperimentSummary | null
  winnerLineConfirmedAt?: string | null
  winnerLineConfirmedBy?: string | null
  createdBy?: string | null
  createdAt: string
}

/** Newest first. */
export function listCreativeExperiments(
  projectId: string,
  token: string,
  query: { creativeId?: string; state?: CreativeExperimentState } = {},
): Promise<CreativeExperimentResponse[]> {
  const params = new URLSearchParams()
  if (query.creativeId) params.set('creativeId', query.creativeId)
  if (query.state) params.set('state', query.state)
  const qs = params.toString()
  return apiGet<CreativeExperimentResponse[]>(`${experimentsBase(projectId)}${qs ? `?${qs}` : ''}`, token)
}

/** Opens one RUNNING experiment on a Creative family — needs ≥ 2 variants and no RUNNING experiment
 * already (409). */
export function createCreativeExperiment(
  projectId: string,
  body: CreateCreativeExperimentRequest,
  token: string,
): Promise<CreativeExperimentResponse> {
  return apiPost<CreativeExperimentResponse>(experimentsBase(projectId), body, token)
}

export function getCreativeExperiment(
  projectId: string,
  experimentId: string,
  token: string,
): Promise<CreativeExperimentResponse> {
  return apiGet<CreativeExperimentResponse>(`${experimentsBase(projectId)}/${experimentId}`, token)
}

/** Attempts to settle a RUNNING experiment; a safe no-op (returns it unchanged) while any variant is
 * still short of its own window deadline. */
export function decideCreativeExperiment(
  projectId: string,
  experimentId: string,
  token: string,
): Promise<CreativeExperimentResponse> {
  return apiPost<CreativeExperimentResponse>(`${experimentsBase(projectId)}/${experimentId}/decide`, {}, token)
}

/** The one human action that lets a decided winner's headline join the brand kit's approved lines —
 * never automatic, and `decide` never calls it. Idempotent. */
export function confirmCreativeExperimentWinner(
  projectId: string,
  experimentId: string,
  token: string,
): Promise<CreativeExperimentResponse> {
  return apiPost<CreativeExperimentResponse>(`${experimentsBase(projectId)}/${experimentId}/confirm-winner`, {}, token)
}

export function getCreativeRegistry(projectId: string, token: string): Promise<CreativeRegistry> {
  return apiGet<CreativeRegistry>(`/api/v2/projects/${projectId}/marketing/creative-registry`, token)
}

export function listCreatives(
  projectId: string,
  token: string,
  query: { state?: CreativeState; brandKitId?: string } = {},
): Promise<Creative[]> {
  const params = new URLSearchParams()
  if (query.state) params.set('state', query.state)
  if (query.brandKitId) params.set('brandKitId', query.brandKitId)
  const qs = params.toString()
  return apiGet<Creative[]>(`${creativesBase(projectId)}${qs ? `?${qs}` : ''}`, token)
}

export function createCreative(
  projectId: string,
  body: CreateCreativeRequest,
  token: string,
): Promise<Creative> {
  return apiPost<Creative>(creativesBase(projectId), body, token)
}

export function getCreative(projectId: string, creativeId: string, token: string): Promise<Creative> {
  return apiGet<Creative>(`${creativesBase(projectId)}/${creativeId}`, token)
}

export function patchCreative(
  projectId: string,
  creativeId: string,
  body: PatchCreativeRequest,
  token: string,
): Promise<Creative> {
  return apiPatch<Creative>(`${creativesBase(projectId)}/${creativeId}`, body, token) as Promise<Creative>
}

export function createCreativeVariant(
  projectId: string,
  creativeId: string,
  body: CreateCreativeVariantRequest,
  token: string,
): Promise<Creative> {
  return apiPost<Creative>(`${creativesBase(projectId)}/${creativeId}/variants`, body, token)
}

export function getCreativeReadiness(
  projectId: string,
  creativeId: string,
  token: string,
): Promise<CreativeReadiness> {
  return apiGet<CreativeReadiness>(`${creativesBase(projectId)}/${creativeId}/readiness`, token)
}

export function listCreativePhotos(
  projectId: string,
  token: string,
  includeBlocked = false,
): Promise<CreativePhoto[]> {
  return apiGet<CreativePhoto[]>(
    `${photosBase(projectId)}?includeBlocked=${includeBlocked}`,
    token,
  )
}

export function getCreativePhoto(
  projectId: string,
  photoId: string,
  token: string,
): Promise<CreativePhoto> {
  return apiGet<CreativePhoto>(`${photosBase(projectId)}/${photoId}`, token)
}

export function createCreativePhoto(
  projectId: string,
  body: CreateCreativePhotoRequest,
  token: string,
): Promise<CreativePhoto> {
  return apiPost<CreativePhoto>(photosBase(projectId), body, token)
}

export function confirmCreativePhoto(
  projectId: string,
  photoId: string,
  sizeBytes: number | undefined,
  token: string,
): Promise<CreativePhoto> {
  return apiPost<CreativePhoto>(
    `${photosBase(projectId)}/${photoId}/confirm`,
    sizeBytes != null ? { sizeBytes } : {},
    token,
  )
}

export function patchCreativePhoto(
  projectId: string,
  photoId: string,
  body: PatchCreativePhotoRequest,
  token: string,
): Promise<CreativePhoto> {
  return apiPatch<CreativePhoto>(`${photosBase(projectId)}/${photoId}`, body, token) as Promise<CreativePhoto>
}

/** displayId, e.g. "12a" — number + variantLetter, matching the backend's own join. */
export function displayId(number: number, variantLetter: string): string {
  return `${number}${variantLetter}`
}

/**
 * Maps a backend Creative + its photo's signed URL onto @cliangdev/creative-render's `Creative`
 * shape for `mountBoard`. `photo` is optional — a Creative with no photo yet still renders (no
 * background image), which is what the live-preview panel should show while a marketer is mid-edit.
 */
export function creativeToRenderCreative(
  creative: Creative,
  photo?: CreativePhoto | null,
): RenderCreative {
  return {
    layout: creative.layout as RenderCreative['layout'],
    theme: creative.theme,
    lockup: creative.lockup,
    headline: creative.headline ?? '',
    body: creative.body ?? undefined,
    caption: creative.caption ?? undefined,
    photoUrl: creative.photoUrl ?? photo?.url ?? undefined,
    focal: photo?.focal,
    focalOverride: creative.focalOverride ?? undefined,
    placements: creative.placements,
    layoutOverrides: creative.layoutOverrides ?? undefined,
    typeOverrides: creative.typeOverrides as RenderCreative['typeOverrides'],
    sequenceKind: creative.sequenceKind ?? undefined,
    sequence: creative.sequence?.map((beat) => ({
      headline: beat.headline ?? undefined,
      body: beat.body ?? undefined,
      cta: beat.cta ?? undefined,
    })),
  }
}
