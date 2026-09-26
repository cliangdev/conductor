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
  version: number
  createdBy?: string | null
  createdAt: string
  updatedAt: string
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

const creativesBase = (projectId: string) => `/api/v2/projects/${projectId}/marketing/creatives`
const photosBase = (projectId: string) => `/api/v2/projects/${projectId}/marketing/photos`

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
    headline: creative.headline ?? '',
    body: creative.body ?? undefined,
    caption: creative.caption ?? undefined,
    photoUrl: creative.photoUrl ?? photo?.url ?? undefined,
    focal: photo?.focal,
    focalOverride: creative.focalOverride ?? undefined,
    placements: creative.placements,
    typeOverrides: creative.typeOverrides as RenderCreative['typeOverrides'],
    sequenceKind: creative.sequenceKind ?? undefined,
    sequence: creative.sequence?.map((beat) => ({
      headline: beat.headline ?? undefined,
      body: beat.body ?? undefined,
      cta: beat.cta ?? undefined,
    })),
  }
}
