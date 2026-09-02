import { readFile } from 'node:fs/promises'
import { basename, extname } from 'node:path'
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
  publishOptions?: Record<string, unknown>
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
  const available = await apiGet<unknown[]>(`${V2_PROJECT(config)}/publish-targets`, config)
  if (!params.issueId) {
    return { available }
  }
  const selected = await apiGet<unknown[]>(
    `${workItemBase(config, params.issueId)}/publish-targets`,
    config
  )
  return { available, selected }
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
    filePath: string
    type: string
    label?: string
    width?: number
    height?: number
    durationSeconds?: number
  },
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
  const contentType = mediaTypeFor(filename)
  const base = workItemBase(config, params.issueId)

  const ticket = await apiPost<UploadTicket>(
    `${base}/assets/uploads`,
    {
      type: params.type,
      label: params.label,
      filename,
      contentType,
      sizeBytes: bytes.byteLength,
      width: params.width,
      height: params.height,
      durationSeconds: params.durationSeconds,
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
