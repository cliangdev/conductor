// Wire shapes and API calls for /api/v2/projects/{projectId}/marketing/brand-kits (openapi-v2 tag
// `brand-kits`). A Brand Kit is per-project workspace configuration (COND-24 T2) — colour tokens,
// font, logo slots, CTA claim, copy rules, approved lines, enabled placements and the Knowledge
// page holding the brand's prose context. A project always has at least one ("default"), lazily
// created by the first GET.
//
// Nothing here may hardcode a brand value — see conductor-creative/README.md's domain-agnostic
// rule. A kit's `tokens`/`logos` are handed to @cliangdev/creative-render's `mountBoard` as the
// `brand` object at render time (see brandKitToBrand below).

import { apiDelete, apiGet, apiPatch, apiPost } from '@/lib/api'
import type { RenderBrand } from '@/components/marketing/creatives/renderTypes'

export type BrandImageSlot = 'mark' | 'wordmark_dark' | 'wordmark_light' | 'badge'

export const BRAND_IMAGE_SLOTS: { slot: BrandImageSlot; label: string }[] = [
  { slot: 'mark', label: 'Mark' },
  { slot: 'wordmark_dark', label: 'Wordmark (dark)' },
  { slot: 'wordmark_light', label: 'Wordmark (light)' },
  { slot: 'badge', label: 'Store badge' },
]

export type CopyRuleField = 'headline' | 'body' | 'caption'

export const COPY_RULE_FIELDS: CopyRuleField[] = ['headline', 'body', 'caption']

export interface CopyRule {
  id: string
  pattern: string
  flags?: string | null
  message: string
  fields: CopyRuleField[]
  exceptPattern?: string | null
}

/** The brand token keys @cliangdev/creative-render's tokens.css maps to CSS custom properties. */
export const BRAND_TOKEN_KEYS = [
  'accent',
  'accent2',
  'darkBg',
  'darkInk',
  'lightBg',
  'lightCard',
  'ink',
  'ink2',
] as const

export type BrandTokenKey = (typeof BRAND_TOKEN_KEYS)[number]

export interface BrandKit {
  id: string
  projectId: string
  slug: string
  name: string
  isDefault: boolean
  tokens: Record<string, string>
  fontFamily?: string | null
  fontUrl?: string | null
  markUrl?: string | null
  wordmarkDarkUrl?: string | null
  wordmarkLightUrl?: string | null
  badgeUrl?: string | null
  ctaClaim?: string | null
  accentPhraseRequired: boolean
  copyRules: CopyRule[]
  approvedLines: string[]
  enabledPlacements: string[]
  knowledgePagePath: string
  createdAt: string
  updatedAt: string
}

export interface CreateBrandKitRequest {
  slug: string
  name: string
  tokens?: Record<string, string> | null
  fontFamily?: string | null
  fontUrl?: string | null
  ctaClaim?: string | null
  accentPhraseRequired?: boolean
  copyRules?: CopyRule[] | null
  approvedLines?: string[] | null
  enabledPlacements?: string[] | null
  knowledgePagePath?: string | null
}

export interface PatchBrandKitRequest {
  name?: string
  isDefault?: boolean
  tokens?: Record<string, string> | null
  fontFamily?: string | null
  fontUrl?: string | null
  ctaClaim?: string | null
  accentPhraseRequired?: boolean
  copyRules?: CopyRule[] | null
  approvedLines?: string[] | null
  enabledPlacements?: string[] | null
  knowledgePagePath?: string
}

export interface UploadTicket {
  uploadUrl: string
  gcsPath: string
}

const base = (projectId: string) => `/api/v2/projects/${projectId}/marketing/brand-kits`

export function listBrandKits(projectId: string, token: string): Promise<BrandKit[]> {
  return apiGet<BrandKit[]>(base(projectId), token)
}

export function getBrandKit(projectId: string, kitId: string, token: string): Promise<BrandKit> {
  return apiGet<BrandKit>(`${base(projectId)}/${kitId}`, token)
}

export function createBrandKit(
  projectId: string,
  body: CreateBrandKitRequest,
  token: string,
): Promise<BrandKit> {
  return apiPost<BrandKit>(base(projectId), body, token)
}

export function patchBrandKit(
  projectId: string,
  kitId: string,
  body: PatchBrandKitRequest,
  token: string,
): Promise<BrandKit> {
  return apiPatch<BrandKit>(`${base(projectId)}/${kitId}`, body, token) as Promise<BrandKit>
}

export function deleteBrandKit(projectId: string, kitId: string, token: string): Promise<void> {
  return apiDelete(`${base(projectId)}/${kitId}`, token)
}

export function mintBrandKitImageUpload(
  projectId: string,
  kitId: string,
  slot: BrandImageSlot,
  body: { contentType: string; sizeBytes: number },
  token: string,
): Promise<UploadTicket> {
  return apiPost<UploadTicket>(`${base(projectId)}/${kitId}/images/${slot}`, body, token)
}

export function confirmBrandKitImage(
  projectId: string,
  kitId: string,
  slot: BrandImageSlot,
  gcsPath: string,
  token: string,
): Promise<BrandKit> {
  return apiPost<BrandKit>(`${base(projectId)}/${kitId}/images/${slot}/confirm`, { gcsPath }, token)
}

/**
 * `apiDelete` never parses a response body (most DELETEs are 204s), but this endpoint answers 200
 * with the kit that now has this slot cleared — so, delete, then re-fetch the kit rather than
 * trust a body `apiDelete` throws away.
 */
export async function deleteBrandKitImage(
  projectId: string,
  kitId: string,
  slot: BrandImageSlot,
  token: string,
): Promise<BrandKit> {
  await apiDelete(`${base(projectId)}/${kitId}/images/${slot}`, token)
  return getBrandKit(projectId, kitId, token)
}

/**
 * Maps a Brand Kit onto the `Brand` shape @cliangdev/creative-render's `mountBoard` renders with.
 * A kit with no logo image renders no lockup/badge for that slot — there is no placeholder.
 */
export function brandKitToBrand(kit: BrandKit | null | undefined): RenderBrand {
  if (!kit) return {}
  return {
    tokens: kit.tokens as RenderBrand['tokens'],
    fontFamily: kit.fontFamily ?? undefined,
    fontUrl: kit.fontUrl ?? undefined,
    logos: {
      mark: kit.markUrl ?? undefined,
      wordmarkDark: kit.wordmarkDarkUrl ?? undefined,
      wordmarkLight: kit.wordmarkLightUrl ?? undefined,
      badge: kit.badgeUrl ?? undefined,
    },
    ctaClaim: kit.ctaClaim ?? undefined,
    enabledPlacements: kit.enabledPlacements,
  }
}
