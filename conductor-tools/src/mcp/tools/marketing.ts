import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { basename, extname, join } from 'node:path'
import { probeVideo } from '../../lib/mp4-probe.js'
import { Config } from '../config.js'
import { apiGet, apiPost, apiPut, apiDelete, putBytes } from '../api.js'

/**
 * The publishing pipeline for a Post, end to end from an agent: which connected accounts a project can
 * publish to, which of them one Post targets and under what per-platform options, the media attached to
 * it, and where each target landed once it fired.
 *
 * Two deliberate omissions. No tool records a creator's TikTok consent: TikTok's audit requirement is
 * that the *creator* sees the preview and the destination account and consents, so that step is a human
 * action in their own browser and the backend enforces it at the approval gate — an agent consenting on
 * a human's behalf would defeat the whole point. request_tiktok_consent only hands the person a link to
 * do it (and waits to see that they did). And nothing here takes a credential: an account is named by
 * its connection id, and the backend resolves the token behind it.
 *
 * The MANUAL lane runs through the same tools rather than getting its own: a manual destination is
 * selected with set_publish_targets like any other (with no connectionId), and completed with
 * complete_manual_publish. Worth being clear about what that tool is not — an agent recording a link is
 * reporting something a human already did outside Conductor, not publishing anything itself. It stays
 * exempt from the TikTok consent rule for the same reason the lane is: nothing is sent to TikTok.
 */

const V2_PROJECT = (config: Config): string => `/api/v2/projects/${config.projectId}`

const workItemBase = (config: Config, workItemId: string): string =>
  `${V2_PROJECT(config)}/work-items/${workItemId}`

/**
 * Extension → media type, used only to declare the bytes at mint. The server owns the allowlist, so an
 * extension it will refuse (and an unknown one, which falls through to application/octet-stream) is sent
 * as-is and rejected there with its own message — better a real server verdict than a guess here that
 * drifts from it.
 */
const MEDIA_TYPES: Record<string, string> = {
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.gif': 'image/gif',
  '.webp': 'image/webp',
  '.mp4': 'video/mp4',
  '.mov': 'video/quicktime',
  '.m4v': 'video/x-m4v',
  '.webm': 'video/webm',
  '.avi': 'video/x-msvideo',
  '.pdf': 'application/pdf',
  '.svg': 'image/svg+xml',
  '.txt': 'text/plain',
  '.zip': 'application/zip',
}

const DEFAULT_MEDIA_TYPE = 'application/octet-stream'

interface UploadTicket {
  assetId: string
  uploadUrl: string
  gcsPath?: string
  expiresAt?: string
}

interface AssetRow {
  id: string
  [key: string]: unknown
}

export interface PublishTargetSelection {
  platform: string
  /**
   * The connected account to publish through. Omitted or null selects that platform's MANUAL
   * destination — the one a human posts by hand — of which there is exactly one per platform.
   */
  connectionId?: string | null
  /**
   * The surface this destination publishes to: feed (default), reel or story. From that platform's
   * `formats` in list_publish_targets — selecting one it does not offer is refused.
   */
  format?: 'feed' | 'reel' | 'story'
  publishOptions?: Record<string, unknown>
  /**
   * Copy for this destination alone, replacing the Post's caption here. Omitted means the Post's own —
   * and, because this is a set-replace, omitting it also clears an override already stored.
   */
  captionOverride?: string | null
  /**
   * An ordered subset of the Post's uploaded media this destination publishes. Omitted or empty means it
   * inherits the Post's whole set. Order is content: Instagram crops a carousel to its first item.
   */
  assetIds?: string[]
}

function mediaTypeFor(filename: string): string {
  return MEDIA_TYPES[extname(filename).toLowerCase()] ?? DEFAULT_MEDIA_TYPE
}

/**
 * Video width/height/duration cannot be derived server-side (no container parser in the JDK), and the
 * media validator blocks approval without them. Reported rather than invented: a guessed dimension would
 * pass the gate and then fail at the platform.
 */
function missingVideoMeasurements(
  contentType: string,
  asset: Record<string, unknown>
): string[] {
  if (!contentType.startsWith('video/')) return []
  return (['width', 'height', 'durationSeconds'] as const).filter(
    (field) => asset[field] === undefined || asset[field] === null
  )
}

export async function listPublishTargets(
  params: { issueId?: string },
  config: Config
): Promise<Record<string, unknown>> {
  const accounts = await apiGet<unknown[]>(`${V2_PROJECT(config)}/publish-targets`, config)
  if (!params.issueId) {
    return { accounts }
  }
  const selected = await apiGet<unknown[]>(
    `${workItemBase(config, params.issueId)}/publish-targets`,
    config
  )
  return { accounts, selected }
}

/**
 * The asset type a file on this Work Item is recorded under when the caller did not say: the first one
 * its Workflow declares. The UI makes the same choice — on a publishing Workflow the type changes nothing
 * a person can see, and asking an agent to name `instagram_post` for a file bound for Facebook was the
 * kind of magic string that made the pipeline hard to drive.
 */
async function defaultAssetType(config: Config, issueId: string): Promise<string> {
  const item = await apiGet<{ workflow?: string }>(workItemBase(config, issueId), config)
  const slug = item.workflow
  const workflows = await apiGet<Array<Record<string, unknown>>>(
    `/api/v1/projects/${config.projectId}/workflows?lifecycle=true`,
    config
  )
  const bound = (Array.isArray(workflows) ? workflows : []).find((w) => w['slug'] === slug)
  const def = bound?.['definition'] as Record<string, unknown> | undefined
  const types = (def?.['asset_types'] as string[] | undefined) ?? []
  if (types.length === 0) {
    throw new Error(
      `Workflow ${slug ?? '(unknown)'} declares no asset types, so a file cannot be attached to this Work Item.`
    )
  }
  return types[0]!
}

/**
 * Fetches a public URL to a temp file so it can go through the same mint → PUT → confirm path a local file
 * does. The server never fetches a URL itself; this runs on the machine the MCP server runs on.
 */
async function downloadToTemp(url: string): Promise<{ filePath: string; contentType: string | null; cleanup: () => Promise<void> }> {
  let parsed: URL
  try {
    parsed = new URL(url)
  } catch {
    throw new Error(`"${url}" is not a valid URL`)
  }
  if (parsed.protocol !== 'https:' && parsed.protocol !== 'http:') {
    throw new Error(`Only http(s) URLs can be fetched, not ${parsed.protocol}`)
  }
  const response = await fetch(parsed)
  if (!response.ok) {
    throw new Error(`Fetching ${url} failed: HTTP ${response.status}`)
  }
  const dir = await mkdtemp(join(tmpdir(), 'conductor-media-'))
  let name = basename(parsed.pathname) || 'download'
  const headerType = response.headers.get('content-type')?.split(';')[0]?.trim().toLowerCase() ?? null
  if (!extname(name) && headerType) {
    const ext = Object.entries(MEDIA_TYPES).find(([, type]) => type === headerType)?.[0]
    if (ext) name += ext
  }
  const filePath = join(dir, name)
  await writeFile(filePath, new Uint8Array(await response.arrayBuffer()))
  return { filePath, contentType: headerType, cleanup: () => rm(dir, { recursive: true, force: true }) }
}

export async function setPublishTargets(
  params: { issueId: string; targets: PublishTargetSelection[] },
  config: Config
): Promise<Record<string, unknown>> {
  if (!Array.isArray(params.targets)) {
    throw new Error(
      'targets is required — pass the complete selection, or an empty array to clear every target'
    )
  }
  const warnings: string[] = []
  const selection = params.targets.map((target) => {
    const { target: cleaned, warning } = stripHumanOnlyTikTokOptions(target)
    if (warning) warnings.push(warning)
    return cleaned
  })
  const targets = await apiPut<unknown[]>(
    `${workItemBase(config, params.issueId)}/publish-targets`,
    { targets: selection },
    config
  )
  return { issueId: params.issueId, targets, ...(warnings.length > 0 ? { warnings } : {}) }
}

/**
 * TikTok's audit requires the creator to choose these themselves while consenting, so the backend refuses
 * them from a machine credential. Dropped here, with a warning, rather than failing the whole Post. Only
 * for an API (connection-backed) TikTok target: the MANUAL lane has no consent step and keeps its options.
 */
const HUMAN_ONLY_TIKTOK_KEYS = [
  'privacyLevel',
  'disableComment',
  'disableDuet',
  'disableStitch',
  'brandContentToggle',
  'brandOrganicToggle',
] as const

export function stripHumanOnlyTikTokOptions(target: PublishTargetSelection): {
  target: PublishTargetSelection
  warning?: string
} {
  if (target.platform.toLowerCase() !== 'tiktok' || !target.connectionId || !target.publishOptions) {
    return { target }
  }
  const kept: Record<string, unknown> = {}
  const stripped: string[] = []
  for (const [key, value] of Object.entries(target.publishOptions)) {
    if ((HUMAN_ONLY_TIKTOK_KEYS as readonly string[]).includes(key)) stripped.push(key)
    else kept[key] = value
  }
  if (stripped.length === 0) return { target }
  return {
    target: { ...target, publishOptions: kept },
    warning: `tiktok: ignored ${stripped.join(', ')} — the creator chooses these themselves when they consent in their browser (request_tiktok_consent).`,
  }
}

/**
 * The three-call upload (mint → PUT the bytes → confirm) collapsed into one tool call. The MCP server
 * runs locally over stdio, so it reads the file itself rather than making the agent stream bytes through
 * its context.
 *
 * Nothing half-uploaded survives a failure: the server validates type, size and filename before it
 * creates a row, so a refused file leaves none, and a failure after the mint deletes the PENDING row it
 * did create.
 */
export async function uploadAsset(
  params: {
    issueId: string
    filePath?: string
    url?: string
    mediaIds?: string[]
    type?: string
    label?: string
    width?: number
    height?: number
    durationSeconds?: number
  },
  config: Config
): Promise<Record<string, unknown>> {
  const sources = [params.filePath, params.url, params.mediaIds?.length ? params.mediaIds : undefined]
    .filter(Boolean).length
  if (sources !== 1) {
    throw new Error(
      'Pass exactly one of filePath (a file on this machine), url (a public http(s) URL) or mediaIds ' +
        '(library media ids from list_creative_media).'
    )
  }
  if (params.mediaIds?.length) {
    // Links, not copies: the Work Item's file points at the library's bytes.
    const created = await apiPost<Array<Record<string, unknown>>>(
      `${workItemBase(config, params.issueId)}/assets/from-media`,
      { mediaIds: params.mediaIds },
      config
    )
    return {
      issueId: params.issueId,
      assets: created.map((a) => ({
        id: a['id'], mediaId: a['mediaId'], label: a['label'], contentType: a['contentType'],
      })),
    }
  }
  let filePath = params.filePath as string
  let cleanup: (() => Promise<void>) | undefined
  let fetchedType: string | null = null
  if (!params.filePath && params.url) {
    const downloaded = await downloadToTemp(params.url)
    filePath = downloaded.filePath
    cleanup = downloaded.cleanup
    fetchedType = downloaded.contentType
  }
  try {
    return await uploadFile({ ...params, filePath, type: params.type ?? (await defaultAssetType(config, params.issueId)) },
        fetchedType, config)
  } finally {
    if (cleanup) await cleanup()
  }
}

async function uploadFile(
  params: {
    issueId: string
    filePath: string
    type: string
    label?: string
    width?: number
    height?: number
    durationSeconds?: number
  },
  fetchedType: string | null,
  config: Config
): Promise<Record<string, unknown>> {
  let bytes: Uint8Array
  try {
    bytes = new Uint8Array(await readFile(params.filePath))
  } catch (err) {
    throw new Error(
      `Cannot read "${params.filePath}": ${err instanceof Error ? err.message : String(err)}`
    )
  }

  const filename = basename(params.filePath)
  const guessed = mediaTypeFor(filename)
  const contentType = guessed === DEFAULT_MEDIA_TYPE && fetchedType ? fetchedType : guessed
  const base = workItemBase(config, params.issueId)

  // A video's measurements come from the client — the server has no container parser by design — and
  // the approval gate blocks without them. Measure here, the way the browser does, unless the caller
  // already did; a caller's numbers always win.
  let width = params.width
  let height = params.height
  let durationSeconds = params.durationSeconds
  if (contentType.startsWith('video/') && (width == null || height == null || durationSeconds == null)) {
    try {
      const probe = await probeVideo(params.filePath)
      width = width ?? probe.width ?? undefined
      height = height ?? probe.height ?? undefined
      durationSeconds = durationSeconds ?? probe.durationSeconds ?? undefined
    } catch {
      // Unreadable container: fall through and let the read-back warning say what is missing.
    }
  }

  const ticket = await apiPost<UploadTicket>(
    `${base}/assets/uploads`,
    {
      type: params.type,
      label: params.label,
      filename,
      contentType,
      sizeBytes: bytes.byteLength,
      width,
      height,
      durationSeconds,
    },
    config
  )

  try {
    await putBytes(ticket.uploadUrl, contentType, bytes)
    await apiPost<void>(
      `${base}/assets/${ticket.assetId}/confirm`,
      { sizeBytes: bytes.byteLength },
      config
    )
  } catch (err) {
    // Best effort: a PENDING row with no bytes behind it is the one thing this tool must not leave for a
    // human to find, but a failed cleanup must not mask the failure that caused it.
    try {
      await apiDelete(`${base}/assets/${ticket.assetId}`, config)
    } catch {
      // ignored — the original failure below is the one worth reporting
    }
    throw new Error(
      `Upload failed; the pending Asset was removed: ${err instanceof Error ? err.message : String(err)}`
    )
  }

  let stored: AssetRow | undefined
  try {
    stored = (await apiGet<AssetRow[]>(`${base}/assets`, config)).find(
      (asset) => asset.id === ticket.assetId
    )
  } catch {
    // The bytes are stored and confirmed; a failed read-back only costs the caller the stored shape.
  }
  const asset: Record<string, unknown> = stored ?? {
    id: ticket.assetId,
    workItemId: params.issueId,
    type: params.type,
    kind: 'file',
    contentType,
    sizeBytes: bytes.byteLength,
    uploadStatus: 'UPLOADED',
  }

  const missing = missingVideoMeasurements(contentType, asset)
  if (missing.length === 0) {
    return asset
  }
  return {
    ...asset,
    warning:
      `Video uploaded without ${missing.join(', ')}. These cannot be derived server-side, so approval ` +
      'of this Work Item stays blocked until they are known — measure them and upload the file again ' +
      'passing width, height and durationSeconds.',
  }
}

export async function retryFailedPublishTargets(
  params: { issueId: string },
  config: Config
): Promise<Record<string, unknown>> {
  return apiPost<Record<string, unknown>>(
    `${workItemBase(config, params.issueId)}/publish-targets/retry`,
    undefined,
    config
  )
}

/**
 * Records that a manual destination was published by hand, and reads back what it now looks like.
 *
 * The one way a target reaches PUBLISHED without a platform reporting it, so it is narrow by design: the
 * backend refuses any target that is not on the MANUAL lane, because an automated one has a poller that
 * will publish it and report the real outcome, and declaring it published would strand a post still
 * queued to go out. A caller wanting to abandon an automated target should drop it with
 * set_publish_targets instead.
 *
 * The response is the target as the server now holds it — the action and its verification in one call,
 * so an agent never has to guess whether the write landed.
 */
export async function completeManualPublish(
  params: { issueId: string; targetId: string; permalink: string; publishedAt?: string },
  config: Config
): Promise<Record<string, unknown>> {
  if (!params.permalink || !params.permalink.trim()) {
    throw new Error(
      'permalink is required — it is the only record that this destination went out, because there is' +
        ' no platform to ask. Find the target id with list_publish_targets.'
    )
  }
  const target = await apiPost<Record<string, unknown>>(
    `${workItemBase(config, params.issueId)}/publish-targets/${params.targetId}/manual-publish`,
    { permalink: params.permalink.trim(), publishedAt: params.publishedAt ?? null },
    config
  )
  return { issueId: params.issueId, target }
}

// --- request_tiktok_consent -----------------------------------------------------------------------------

const DEFAULT_CONSENT_WAIT_SECONDS = 600
const MAX_CONSENT_WAIT_SECONDS = 900
const CONSENT_POLL_MS = 5_000

interface ConsentItem {
  workItemId: string
  displayId: string
  title?: string
  verdict: string
  consentedAt?: string | null
  consentedByName?: string | null
}

interface ConsentRequestCreated {
  id: string
  consentUrl: string
  expiresAt: string
  items: ConsentItem[]
  skipped?: Array<{ workItemId: string; displayId?: string | null; reason: string }>
  complete: boolean
}

interface ConsentRequestStatus {
  id: string
  expiresAt: string
  expired: boolean
  complete: boolean
  consentUrl?: string
  completedAt?: string | null
  items: ConsentItem[]
}

/** What the MCP server lends the tool: URL elicitation (only when the client supports it) and progress. */
export interface ConsentHost {
  /** Present only when the client declared `elicitation.url`. 'accept' means the person agreed to open it. */
  elicitUrl?: (args: { message: string; url: string; elicitationId: string }) => Promise<'accept' | 'decline' | 'cancel'>
  /** Tells the client the elicitation finished; called only if elicitUrl was used. */
  notifyComplete?: (elicitationId: string) => Promise<void>
  /** Called when the count of consented Posts changes; only wired when the call carries a progress token. */
  onProgress?: (done: number, total: number, message: string) => void
  sleep?: (ms: number) => Promise<void>
  now?: () => number
  pollMs?: number
}

const summarize = (items: ConsentItem[]) =>
  items.map((i) => ({
    postId: i.workItemId,
    displayId: i.displayId,
    verdict: i.verdict,
    ...(i.consentedByName ? { consentedBy: i.consentedByName } : {}),
  }))

/**
 * Hands the person one link where they review and consent to their TikTok Posts in their own browser, then
 * waits for them to do it. It never records consent — the backend refuses that from any machine credential.
 * With a client that supports URL elicitation the link is offered as a prompt; otherwise (common) it comes
 * back as text for the agent to show, and a second call with `requestId` waits.
 */
export async function requestTikTokConsent(
  params: { postIds?: string[]; requestId?: string; waitSeconds?: number },
  config: Config,
  host: ConsentHost = {}
): Promise<Record<string, unknown>> {
  const sleep = host.sleep ?? ((ms: number) => new Promise<void>((r) => setTimeout(r, ms)))
  const now = host.now ?? Date.now
  const pollMs = host.pollMs ?? CONSENT_POLL_MS
  const waitSeconds = Math.min(Math.max(params.waitSeconds ?? DEFAULT_CONSENT_WAIT_SECONDS, 0), MAX_CONSENT_WAIT_SECONDS)
  const base = `${V2_PROJECT(config)}/tiktok-consent-requests`

  let requestId = params.requestId
  let skipped: Array<{ post: string; reason: string }> = []
  let items: ConsentItem[] = []
  let elicited = false

  if (!requestId) {
    // The backend takes Work Item ids or display ids and reports an unknown one as skipped NOT_FOUND.
    const ids = params.postIds && params.postIds.length > 0 ? params.postIds : undefined
    const created = await apiPost<ConsentRequestCreated>(base, ids ? { workItemIds: ids } : {}, config)
    requestId = created.id
    skipped = (created.skipped ?? []).map((k) => ({ post: k.displayId ?? k.workItemId, reason: k.reason }))
    items = created.items
    const skippedNote = skipped.length > 0 ? { skipped } : {}
    if (created.items.length === 0) {
      return { requestId, status: 'nothing_to_consent', ...skippedNote, nextStep: 'No Post needs TikTok consent (skipped lists why).' }
    }
    if (created.complete) {
      return { requestId, status: 'complete', posts: summarize(items), ...skippedNote, nextStep: 'Every Post is VALID. Call submit_post for each.' }
    }
    const pending = items.filter((i) => i.verdict !== 'VALID').length
    const message = `Review and consent to ${pending} TikTok post${pending === 1 ? '' : 's'} in Conductor`
    if (host.elicitUrl) {
      const action = await host.elicitUrl({ message, url: created.consentUrl, elicitationId: created.id })
      if (action !== 'accept') {
        return {
          requestId,
          status: 'declined',
          consentUrl: created.consentUrl,
          posts: summarize(items),
          ...skippedNote,
          nextStep: 'The person declined to open the consent page. Ask whether they want it; the link stays valid for 24 hours.',
        }
      }
      elicited = true
    } else {
      return {
        requestId,
        status: 'awaiting_person',
        consentUrl: created.consentUrl,
        posts: summarize(items),
        ...skippedNote,
        nextStep:
          `Show this link to the person: ${created.consentUrl} — they consent to each Post there in their own browser (it works only for their login). ` +
          `Then call request_tiktok_consent({requestId: "${created.id}"}) to wait until they finish.`,
      }
    }
  }

  const deadline = now() + waitSeconds * 1000
  let lastDone = -1
  for (;;) {
    const status = await apiGet<ConsentRequestStatus>(`${base}/${requestId}`, config)
    items = status.items
    const done = items.filter((i) => i.verdict === 'VALID').length
    if (done !== lastDone) {
      lastDone = done
      host.onProgress?.(done, items.length, `${done} of ${items.length} TikTok posts consented`)
    }
    if (status.complete) {
      if (elicited) await host.notifyComplete?.(requestId).catch(() => {})
      return {
        requestId,
        status: 'complete',
        posts: summarize(items),
        ...(skipped.length > 0 ? { skipped } : {}),
        nextStep: 'Every Post is VALID. Call submit_post for each (or submit_review to approve), then get_post_status.',
      }
    }
    if (status.expired) {
      return {
        requestId,
        status: 'expired',
        posts: summarize(items),
        nextStep: 'The link expired. Call request_tiktok_consent again (without requestId) for a fresh one.',
      }
    }
    if (now() + pollMs > deadline) {
      return {
        requestId,
        status: 'waiting',
        posts: summarize(items),
        ...(status.consentUrl ? { consentUrl: status.consentUrl } : {}),
        nextStep: `${done} of ${items.length} consented so far.${status.consentUrl ? ` Link for the person: ${status.consentUrl}.` : ''} Call request_tiktok_consent({requestId: "${requestId}"}) again to keep waiting, or ask the person to finish.`,
      }
    }
    await sleep(pollMs)
  }
}
