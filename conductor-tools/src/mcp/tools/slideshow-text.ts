import type { Config } from '../config.js'
import { apiGet, apiPost, ApiError } from '../api.js'
import { withCreativeErrors } from './creatives.js'

/**
 * update_creative_slide_text: edit the text of a CAROUSEL Creative's slides. The backend stores a PENDING revision
 * and starts the render Workflow (`conductor creative render-slides`) that re-composes only the changed slides; this
 * returns at once (dispatch-status pattern, docs/mcp-tool-guidelines.md). The outcome is read with get_creative, whose
 * `latestRevision` / `pendingRevision` carry the status and any failing layout checks.
 */

const creativesBase = (config: Config): string => `/api/v2/projects/${config.projectId}/marketing/creatives`

export interface SlideTextEditInput {
  slide: number
  /** The `path` of one of get_creative's textFields, e.g. `texts[0].t`. */
  field: string
  text: string
}

export interface UpdateCreativeSlideTextParams {
  creativeId: string
  edits: SlideTextEditInput[]
  /** Optional: when omitted, the Creative's current version is read first. */
  version?: number
}

interface TextDiffEntry {
  slide: number
  path: string
  old: string | null
  new: string | null
}

interface RevisionView {
  id: string
  revisionNo?: number
  status?: string
  textDiff?: TextDiffEntry[]
}

interface RenderRunView {
  runId?: string
  status?: string
  runtime?: string
  targetName?: string | null
  error?: string | null
}

const MAX_EDITS = 50
/** Diff entries shown in the result; the rest are counted. */
const DIFF_SHOWN = 20
const clip = (text: string | null, max = 120): string | null => (text !== null && text.length > max ? `${text.slice(0, max - 1)}…` : text)

export async function updateCreativeSlideText(params: UpdateCreativeSlideTextParams, config: Config): Promise<Record<string, unknown>> {
  const { creativeId, edits } = params
  if (!Array.isArray(edits) || edits.length === 0) throw new Error('edits is required: a list of { slide, field, text }, taking field from get_creative textFields[].path.')
  if (edits.length > MAX_EDITS) throw new Error(`At most ${MAX_EDITS} edits per call; send the rest after this one finishes rendering.`)
  edits.forEach((e, i) => {
    // `path` is what textFields calls it; accept it where a model copies that name.
    const field = e?.field ?? (e as unknown as { path?: string })?.path
    if (!Number.isInteger(e?.slide) || e.slide < 1) throw new Error(`edits[${i}].slide must be a whole number from 1 (slide numbers match get_creative textFields).`)
    if (typeof field !== 'string' || !field) throw new Error(`edits[${i}].field is required: the path of a text field, such as texts[0].t.`)
    if (typeof e.text !== 'string') throw new Error(`edits[${i}].text must be a string.`)
  })

  const url = creativesBase(config)
  let version = params.version
  if (version === undefined || version === null) {
    version = (await apiGet<{ version?: number }>(`${url}/${creativeId}`, config)).version
  }
  const body = {
    version,
    edits: edits.map((e) => ({ slide: e.slide, path: e.field ?? (e as unknown as { path: string }).path, text: e.text })),
  }

  let response: { revision: RevisionView; renderRun?: RenderRunView | null }
  try {
    response = await withCreativeErrors(() => apiPost(`${url}/${creativeId}/slide-text-edits`, body, config))
  } catch (err) {
    if (err instanceof ApiError && err.status === 409) {
      if (/changed since you read/i.test(err.message)) throw new Error('This Creative changed since you read it — get_creative and retry')
      throw new Error(`${err.message} A text edit or render for this Creative is still running; get_creative shows it as pendingRevision. Retry when it has finished.`)
    }
    throw err
  }

  const { revision, renderRun } = response
  const diff = revision.textDiff ?? []
  const changed = [...new Set(diff.map((d) => d.slide))].sort((a, b) => a - b)
  const where = renderRun
    ? renderRun.runtime === 'target'
      ? `on the workspace's runtime target${renderRun.targetName ? ` (${renderRun.targetName})` : ''}`
      : "on this workspace's self-hosted daemon"
    : null
  const lines = [
    `Revision${revision.revisionNo ? ` ${revision.revisionNo}` : ''} is rendering slide${changed.length === 1 ? '' : 's'} ${changed.join(', ')}${where ? ` ${where}` : ''}. Nothing on the Creative changes until it finishes.`,
    'Call get_creative: latestRevision.status is APPLIED when done, or FAILED with the layout checks that stopped it (nothing is changed then).',
  ]
  if (!renderRun) {
    lines.push(`No render run was started. Run it on this machine: conductor creative render-slides ${revision.id} --creative ${creativeId}`)
  } else if (renderRun.runtime !== 'target') {
    lines.push(`No daemon running (conductor start)? Run it on this machine instead: conductor creative render-slides ${revision.id} --creative ${creativeId}`)
  }
  return {
    ok: true,
    creativeId,
    revisionId: revision.id,
    status: revision.status ?? 'PENDING',
    changedSlides: changed,
    diff: diff.slice(0, DIFF_SHOWN).map((d) => ({ slide: d.slide, path: d.path, old: clip(d.old), new: clip(d.new) })),
    ...(diff.length > DIFF_SHOWN ? { diffOmitted: diff.length - DIFF_SHOWN } : {}),
    ...(renderRun ? { render: { runtime: renderRun.runtime ?? 'self-hosted', ...(renderRun.targetName ? { targetName: renderRun.targetName } : {}), runId: renderRun.runId, status: renderRun.status } } : {}),
    nextStep: lines.join(' '),
  }
}
