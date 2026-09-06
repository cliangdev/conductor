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
 * Two deliberate omissions. There is no tool that records a creator's TikTok consent: TikTok's audit
 * requirement is that the *creator* sees the preview and the destination account and consents, so that
 * step stays a human action in the Conductor UI and the backend enforces it at the approval gate — an
 * agent consenting on a human's behalf would defeat the whole point. And nothing here takes a
 * credential: an account is named by its connection id, and the backend resolves the token behind it.
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
  const targets = await apiPut<unknown[]>(
    `${workItemBase(config, params.issueId)}/publish-targets`,
    { targets: params.targets },
    config
  )
  return { issueId: params.issueId, targets }
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
    type?: string
    label?: string
    width?: number
    height?: number
    durationSeconds?: number
  },
  config: Config
): Promise<Record<string, unknown>> {
  if (!params.filePath && !params.url) {
    throw new Error('Pass filePath (a file on this machine) or url (a public http(s) URL).')
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
