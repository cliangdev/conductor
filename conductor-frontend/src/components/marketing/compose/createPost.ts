// Making a Post from a draft, in the order an agent's create_post uses the same endpoints: create the
// Work Item, upload each file, save the destinations, set the schedule. Anything after the create that
// fails is reported rather than fatal — from then on the Post exists, and its own page says what is
// still missing.

import { apiErrorMessage, apiGet, apiPatch, apiPost, apiPut } from '@/lib/api'
import {
  isVideoContentType,
  measureVideoMetadata,
  putToSignedUrl,
} from '@/components/workitems/MediaUploadPanel'
import { buildSelectionPayload, type DestinationDraft } from '@/components/marketing/destinations/selectionState'
import type { PublishTargetOption, PublishTargetSelectionPayload } from '@/components/marketing/destinations/types'
import { nextSlot, wallClockToInstant } from '@/lib/schedule'
import { attachCreativeRender } from '@/components/marketing/creatives/types'

/** Prefix of the stand-in ids the New Post form gives files that are not uploaded yet. */
export const PENDING_ASSET_PREFIX = 'pending-'

/**
 * Swaps the form's stand-in file ids for the asset ids the uploads produced, in the two places a
 * destination refers to media: its own media list and an Instagram reel's cover. A stand-in whose upload
 * failed (or whose file was removed) is dropped rather than sent, so it can't point at nothing.
 */
export function resolvePendingAssetIds(
  targets: PublishTargetSelectionPayload[],
  uploaded: Record<string, string>
): PublishTargetSelectionPayload[] {
  const resolve = (id: string): string | undefined =>
    id.startsWith(PENDING_ASSET_PREFIX) ? uploaded[id] : id
  return targets.map((target) => {
    const next: PublishTargetSelectionPayload = { ...target }
    if (target.assetIds) {
      next.assetIds = target.assetIds.map(resolve).filter((id): id is string => Boolean(id))
    }
    const cover = (target.publishOptions as Record<string, unknown> | undefined)?.['coverAssetId']
    if (typeof cover === 'string') {
      const options = { ...(target.publishOptions as Record<string, unknown>) }
      const real = resolve(cover)
      if (real) options['coverAssetId'] = real
      else delete options['coverAssetId']
      next.publishOptions = options as PublishTargetSelectionPayload['publishOptions']
    }
    return next
  })
}

export interface CreatedWorkItem {
  id: string
  displayId?: string
}

interface UploadTicket {
  assetId: string
  uploadUrl: string
}

interface PreflightSummary {
  earliestFireTime?: string | null
}

export interface CreatePostInput {
  projectId: string
  token: string
  workflowSlug: string
  /** The Workflow's types; POST when it has one, else its first. */
  types: string[]
  /** The asset type uploads are filed under — the Workflow's first declared one. */
  assetType: string
  noun: string
  title: string
  caption: string
  files: File[]
  /** The form's stand-in id for each file, by index, as destinations referred to it. */
  fileIds?: string[]
  options: PublishTargetOption[]
  draft: DestinationDraft
  schedule: { onApproval: boolean; local: string; timeZone: string }
  /** Set when this Post was started from a Creative's "Use in Post" — attached last, after destinations. */
  attachCreative?: { creativeId: string; renderId: string }
  /** Told what is happening, for the footer's live region. */
  onStep?: (step: string) => void
}

export interface CreatePostResult {
  created: CreatedWorkItem
  /** What failed after the Post existed, in the words the server used. */
  problems: string[]
}

/** The first non-blank line of the caption, cut to fit a title, when no title was given. */
export function titleFromCaption(caption: string): string {
  const firstLine = caption.split(/\r?\n/).find((l) => l.trim().length > 0)?.trim() ?? caption.trim()
  return firstLine.length > 80 ? firstLine.slice(0, 77) + '…' : firstLine
}

/**
 * Creates the Post. Rejects only when the create itself is refused; every later failure is collected
 * into `problems` and the Post is returned anyway.
 */
export async function createPost(input: CreatePostInput): Promise<CreatePostResult> {
  const { projectId, token, onStep } = input
  const type = input.types.includes('POST') ? 'POST' : input.types[0]!
  onStep?.('Creating…')
  const created = await apiPost<CreatedWorkItem>(
    `/api/v2/projects/${projectId}/work-items`,
    {
      type,
      title: input.title.trim() || titleFromCaption(input.caption),
      description: input.caption.trim(),
      workflow: input.workflowSlug,
    },
    token
  )

  const base = `/api/v2/projects/${projectId}/work-items/${created.id}`
  const problems: string[] = []
  const uploaded: Record<string, string> = {}
  for (const [index, file] of input.files.entries()) {
    try {
      onStep?.(`Uploading ${index + 1} of ${input.files.length}…`)
      const measured = isVideoContentType(file.type) ? await measureVideoMetadata(file) : null
      const ticket = await apiPost<UploadTicket>(
        `${base}/assets/uploads`,
        {
          type: input.assetType,
          label: file.name,
          filename: file.name,
          contentType: file.type,
          sizeBytes: file.size,
          ...(measured?.width ? { width: measured.width } : {}),
          ...(measured?.height ? { height: measured.height } : {}),
          ...(measured?.durationSeconds ? { durationSeconds: measured.durationSeconds } : {}),
        },
        token
      )
      await putToSignedUrl(ticket.uploadUrl, file, () => {})
      await apiPost<void>(`${base}/assets/${ticket.assetId}/confirm`, { sizeBytes: file.size }, token)
      const pendingId = input.fileIds?.[index]
      if (pendingId) uploaded[pendingId] = ticket.assetId
    } catch (err) {
      problems.push(`${file.name}: ${apiErrorMessage(err, 'upload failed')}`)
    }
  }

  try {
    onStep?.('Choosing destinations…')
    // The same payload the Post page saves — format, the platform's options, a caption or media of the
    // destination's own — which the modal this replaces could not carry.
    const targets = resolvePendingAssetIds(buildSelectionPayload(input.options, input.draft), uploaded)
    await apiPut(`${base}/publish-targets`, { targets }, token)
  } catch (err) {
    problems.push(apiErrorMessage(err, 'Could not save the destinations'))
  }

  try {
    onStep?.('Scheduling…')
    const { onApproval, local, timeZone } = input.schedule
    if (onApproval) {
      // No date at all: approval puts it on the earliest slot every destination accepts, and the
      // server works that out when the Post enters its scheduled status, not now.
      await apiPatch(base, { publishOnApproval: true, scheduleTimezone: timeZone }, token)
    } else {
      let scheduledFor = wallClockToInstant(local, timeZone)
      if (!scheduledFor) {
        // No time given: the server says the earliest the chosen destinations accept.
        const preflight = await apiGet<PreflightSummary>(`${base}/publish-preflight`, token)
        const earliest = preflight.earliestFireTime
          ? new Date(preflight.earliestFireTime)
          : new Date(Date.now() + 15 * 60_000)
        scheduledFor = nextSlot(earliest).toISOString()
      }
      await apiPatch(base, { scheduledFor, scheduleTimezone: timeZone }, token)
    }
  } catch (err) {
    problems.push(apiErrorMessage(err, 'Could not set the schedule'))
  }

  if (input.attachCreative) {
    try {
      onStep?.('Attaching the creative…')
      // Last, deliberately: the destinations PUT above replaces the whole target selection, which
      // would silently undo an attach that ran before it.
      await attachCreativeRender(
        projectId,
        input.attachCreative.creativeId,
        { renderId: input.attachCreative.renderId, workItemId: created.id },
        token,
      )
    } catch (err) {
      problems.push(apiErrorMessage(err, 'Could not attach the creative'))
    }
  }

  return { created, problems }
}
