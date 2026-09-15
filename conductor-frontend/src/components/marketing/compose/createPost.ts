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
import type { PublishTargetOption } from '@/components/marketing/destinations/types'
import { nextSlot, wallClockToInstant } from '@/lib/schedule'

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
  options: PublishTargetOption[]
  draft: DestinationDraft
  schedule: { onApproval: boolean; local: string; timeZone: string }
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
    } catch (err) {
      problems.push(`${file.name}: ${apiErrorMessage(err, 'upload failed')}`)
    }
  }

  try {
    onStep?.('Choosing destinations…')
    // The same payload the Post page saves — format, the platform's options, a caption or media of the
    // destination's own — which the modal this replaces could not carry.
    await apiPut(`${base}/publish-targets`, { targets: buildSelectionPayload(input.options, input.draft) }, token)
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

  return { created, problems }
}
