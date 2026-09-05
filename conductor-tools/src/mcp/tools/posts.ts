import { Config } from '../config.js'
import { apiGet, apiPost, isClientError } from '../api.js'
import { createWorkItem, updateWorkItem, listWorkItems } from './issues.js'
import { listWorkflows, transitionWorkItem } from './workflows.js'
import { listPublishTargets, setPublishTargets, uploadAsset, PublishTargetSelection } from './marketing.js'

/**
 * The publishing pipeline the way an agent wants to drive it: one call to create, schedule and submit a
 * Post; one call to ask where it stands; one call to hand it to a reviewer; one to approve it.
 *
 * Everything here composes the lower-level tools (create_work_item, upload_asset, set_publish_targets,
 * transition_work_item) and the backend's publish preflight — nothing is decided client-side that the
 * server also decides. The three rules this file exists to enforce on the agent's behalf:
 *
 * - discover, never guess: accounts are resolved by name from the project's own list, the Workflow from
 *   what publishes, the asset type from the Workflow, the fire time from the server's `earliestFireTime`;
 * - validate before moving: the preflight runs before any status change, and its blockers come back in
 *   the same response instead of surfacing as a 422 four calls later;
 * - end with the confirmation table: what was created, where it goes, when, and what happens next.
 */

const V2_PROJECT = (config: Config): string => `/api/v2/projects/${config.projectId}`

const workItemBase = (config: Config, workItemId: string): string =>
  `${V2_PROJECT(config)}/work-items/${workItemId}`

export interface MediaInput {
  /** A file on this machine. */
  path?: string
  /** A public URL — fetched here, on the machine the server runs on, then uploaded like a local file. */
  url?: string
  label?: string
  width?: number
  height?: number
  durationSeconds?: number
}

export interface TargetInput {
  platform: string
  /** The connected account's label as list_publish_targets shows it, or its connectionId. Omitted = manual. */
  account?: string
  captionOverride?: string
  /** Indexes into `media` (0-based) or asset ids. Order is content. */
  assetIds?: Array<string | number>
  options?: Record<string, unknown>
}

export interface CreatePostParams {
  text: string
  title?: string
  media?: MediaInput[]
  targets: TargetInput[]
  scheduledFor?: string
  timezone?: string
  submit?: boolean
  reviewers?: string[]
  workflow?: string
}

interface AccountOption {
  platform: string
  connectionId: string | null
  label: string
  lane: string
  healthStatus?: string | null
  optionKeys?: string[]
}

interface Finding {
  code: string
  message: string
  targetId?: string | null
}

interface Preflight {
  publishing: boolean
  ready: boolean
  blockers: Finding[]
  warnings: Finding[]
  nextTransition?: { to: string; label?: string | null; requiresReview: boolean } | null
  consent: { required: boolean; verdict: string }
  review: { gated: boolean; assignedReviewers: number; satisfied: boolean; reviewerRole?: string | null }
  earliestFireTime?: string | null
}

interface WorkItemRow {
  id: string
  displayId?: string
  title?: string
  status?: string
  workflow?: string
  scheduledFor?: string | null
  scheduleTimezone?: string | null
  description?: string | null
}

interface TargetRow {
  id: string
  platform: string
  label?: string | null
  lane?: string
  state?: string
  permalink?: string | null
  errorMessage?: string | null
  fireTime?: string | null
}

interface Member {
  userId: string
  name?: string | null
  email?: string | null
  role: string
}

interface WorkflowSummary {
  slug: string
  noun?: string
  types: string[]
  assetTypes: string[]
  statuses: unknown[]
}

export async function getPreflight(config: Config, postId: string): Promise<Preflight> {
  return apiGet<Preflight>(`${workItemBase(config, postId)}/publish-preflight`, config)
}

async function listAccounts(config: Config): Promise<AccountOption[]> {
  const result = await listPublishTargets({}, config)
  return (result['accounts'] as AccountOption[] | undefined) ?? []
}

/**
 * The Workflow whose items publish: the one whose asset types name a platform the project can publish
 * to. With one such Workflow it is chosen silently; with several the caller has to say, because a Post
 * on the wrong lifecycle is not a small mistake.
 */
export async function pickPublishingWorkflow(
  config: Config,
  accounts: AccountOption[],
  slug?: string
): Promise<WorkflowSummary> {
  const workflows = (await listWorkflows({ kind: 'LIFECYCLE' }, config)) as WorkflowSummary[]
  const platforms = new Set(accounts.map((a) => String(a.platform).toLowerCase()))
  const publishing = workflows.filter((w) =>
    (w.assetTypes ?? []).some((t) => platforms.has(String(t).toLowerCase().split('_')[0] ?? ''))
  )
  if (slug) {
    const chosen = workflows.find((w) => w.slug === slug)
    if (!chosen) throw new Error(`No Workflow with slug "${slug}". Call list_workflows({kind:"LIFECYCLE"}).`)
    return chosen
  }
  if (publishing.length === 1) return publishing[0]!
  if (publishing.length === 0) {
    throw new Error(
      'No Workflow in this project publishes (none declares an asset type named for a platform). ' +
        'Create one with create_workflow, e.g. from the MARKETING example, or pass `workflow` explicitly.'
    )
  }
  throw new Error(
    `Several Workflows publish here: ${publishing.map((w) => w.slug).join(', ')}. Pass \`workflow\` to choose.`
  )
}

/** An account by its label (case-insensitive) or connection id; omitted or "manual" means the manual lane. */
function resolveAccount(accounts: AccountOption[], target: TargetInput): AccountOption {
  const platform = target.platform.trim().toLowerCase()
  const onPlatform = accounts.filter((a) => String(a.platform).toLowerCase() === platform)
  if (onPlatform.length === 0) {
    throw new Error(
      `"${target.platform}" is not a platform this project can publish to. ` +
        `Known: ${[...new Set(accounts.map((a) => a.platform))].join(', ')}.`
    )
  }
  const wanted = target.account?.trim()
  if (!wanted || wanted.toLowerCase() === 'manual') {
    const manual = onPlatform.find((a) => a.lane === 'MANUAL')
    if (!manual) throw new Error(`No manual destination is offered for ${target.platform}.`)
    return manual
  }
  const automated = onPlatform.filter((a) => a.lane !== 'MANUAL')
  const byId = automated.find((a) => a.connectionId === wanted)
  if (byId) return byId
  const lower = wanted.toLowerCase()
  const byLabel = automated.filter((a) => String(a.label).toLowerCase() === lower)
  if (byLabel.length === 1) return byLabel[0]!
  const byPrefix = automated.filter((a) => String(a.label).toLowerCase().includes(lower))
  if (byLabel.length === 0 && byPrefix.length === 1) return byPrefix[0]!
  const names = automated.map((a) => `"${a.label}" (${a.connectionId})`).join(', ')
  throw new Error(
    (byLabel.length > 1 || byPrefix.length > 1 ? `"${wanted}" matches more than one ` : `No `) +
      `${target.platform} account ${byLabel.length > 1 || byPrefix.length > 1 ? '' : `named "${wanted}" `}` +
      `— connected accounts: ${names || 'none'}. Omit \`account\` for the manual destination.`
  )
}

/** The next quarter-hour at or after `earliest`, so "no time given" lands on a tidy calendar slot. */
export function nextQuarterHour(earliest: Date): Date {
  const slot = new Date(earliest.getTime())
  slot.setSeconds(0, 0)
  const minutes = slot.getMinutes()
  const rounded = Math.ceil(minutes / 15) * 15
  slot.setMinutes(rounded)
  if (slot.getTime() < earliest.getTime()) slot.setMinutes(slot.getMinutes() + 15)
  return slot
}

function localTimezone(): string {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC'
  } catch {
    return 'UTC'
  }
}

function errorText(err: unknown): string {
  return err instanceof Error ? err.message : String(err)
}

/**
 * Assigns reviewers by name, email or id. Only members holding the REVIEWER role can be assigned — the
 * backend refuses anyone else — so a wrong role is reported by name rather than as a bare 4xx.
 */
export async function assignReviewers(
  config: Config,
  postId: string,
  reviewers: string[]
): Promise<{ assigned: Array<{ userId: string; name: string | null }>; problems: string[] }> {
  const members = await apiGet<Member[]>(`/api/v1/projects/${config.projectId}/members`, config)
  const assigned: Array<{ userId: string; name: string | null }> = []
  const problems: string[] = []
  for (const wanted of reviewers) {
    const lower = wanted.trim().toLowerCase()
    const match = members.find(
      (m) =>
        m.userId === wanted ||
        (m.email ?? '').toLowerCase() === lower ||
        (m.name ?? '').toLowerCase() === lower
    )
    if (!match) {
      problems.push(`No project member matches "${wanted}".`)
      continue
    }
    if (match.role !== 'REVIEWER') {
      problems.push(
        `${match.name ?? match.userId} holds the ${match.role} role; only REVIEWER-role members can be assigned.`
      )
      continue
    }
    try {
      await apiPost<unknown>(`${workItemBase(config, postId)}/reviewers`, { userId: match.userId }, config)
      assigned.push({ userId: match.userId, name: match.name ?? null })
    } catch (err) {
      problems.push(`Could not assign ${match.name ?? match.userId}: ${errorText(err)}`)
    }
  }
  return { assigned, problems }
}

/** Takes the move the preflight names, when the Post is ready. Returns what happened, never throws on a refusal. */
async function takeNextMove(
  config: Config,
  postId: string,
  preflight: Preflight
): Promise<{ moved: boolean; toStatus?: string; refusal?: string }> {
  if (!preflight.ready) return { moved: false }
  const next = preflight.nextTransition
  if (!next) return { moved: false }
  const result = await transitionWorkItem({ issueId: postId, toStatus: next.to }, config)
  if (result['error']) return { moved: false, refusal: String(result['error']) }
  return { moved: true, toStatus: next.to }
}

function nextStep(status: string | undefined, preflight: Preflight, submitted: boolean): string {
  if (!preflight.publishing) return 'This Work Item is not on a publishing Workflow.'
  if (!preflight.ready) {
    return 'Fix the blockers, then call submit_post({postId}). Nothing has been submitted.'
  }
  if (!submitted) return 'Ready but not submitted. Call submit_post({postId}) to hand it to review.'
  if (preflight.nextTransition == null) {
    return 'Scheduled. Poll get_post_status for each destination’s outcome and permalink.'
  }
  if (preflight.review.gated && !preflight.review.satisfied) {
    const who =
      preflight.review.assignedReviewers > 0
        ? `${preflight.review.assignedReviewers} reviewer(s) assigned`
        : 'no reviewer assigned yet — call submit_post with `reviewers`'
    const consent = preflight.consent.required
      ? ' TikTok consent is recorded by the creator in the Conductor UI; no tool can do it.'
      : ''
    return `Waiting for a reviewer to approve (${who}). Approval schedules it automatically.${consent}`
  }
  return `Ready for "${preflight.nextTransition.label ?? preflight.nextTransition.to}"; a status move will take it.`
}

/**
 * The one call. Creates the Post, uploads its media (from paths or URLs, measuring video here), chooses
 * its destinations by account name, schedules it at the server's earliest acceptable time when none is
 * given, and — unless told not to — submits it for review with the named reviewers. Returns the
 * confirmation table plus whatever the gate still wants.
 */
export async function createPost(params: CreatePostParams, config: Config): Promise<Record<string, unknown>> {
  if (!params.text || !params.text.trim()) throw new Error('text is required — the caption that goes out.')
  if (!Array.isArray(params.targets) || params.targets.length === 0) {
    throw new Error('targets is required — at least one {platform, account?}. Call list_publish_targets to see accounts.')
  }

  const accounts = await listAccounts(config)
  const workflow = await pickPublishingWorkflow(config, accounts, params.workflow)
  // Resolve every destination before creating anything, so a typo in an account name costs no Post.
  const resolved = params.targets.map((t) => ({ input: t, account: resolveAccount(accounts, t) }))

  const type = workflow.types.includes('POST') ? 'POST' : (workflow.types[0] ?? 'POST')
  const title = params.title?.trim() || firstLine(params.text)
  const created = await createWorkItem(
    { workflow: workflow.slug, type, title, description: params.text },
    config
  )
  if (created['error']) return created
  const postId = String(created['issueId'])
  const warnings: string[] = []

  // Media, in the order given, so index-style assetIds line up.
  const assets: Array<Record<string, unknown>> = []
  for (const [index, media] of (params.media ?? []).entries()) {
    const stored = await uploadAsset(
      {
        issueId: postId,
        filePath: media.path,
        url: media.url,
        type: workflow.assetTypes[0],
        label: media.label,
        width: media.width,
        height: media.height,
        durationSeconds: media.durationSeconds,
      },
      config
    )
    if (typeof stored['warning'] === 'string') warnings.push(`media[${index}]: ${stored['warning']}`)
    assets.push(stored)
  }

  const selection: PublishTargetSelection[] = resolved.map(({ input, account }) => ({
    platform: account.platform,
    connectionId: account.lane === 'MANUAL' ? null : account.connectionId,
    captionOverride: input.captionOverride ?? null,
    assetIds: (input.assetIds ?? []).map((ref) => {
      if (typeof ref === 'number') {
        const asset = assets[ref]
        if (!asset) throw new Error(`assetIds refers to media[${ref}], but only ${assets.length} media were given.`)
        return String(asset['id'])
      }
      return ref
    }),
    publishOptions: input.options,
  }))
  await setPublishTargets({ issueId: postId, targets: selection }, config)

  // Schedule: the server knows what the chosen destinations can accept; nothing here guesses a lead time.
  let preflight = await getPreflight(config, postId)
  const timezone = params.timezone ?? localTimezone()
  let scheduledFor = params.scheduledFor
  if (!scheduledFor) {
    const earliest = preflight.earliestFireTime ? new Date(preflight.earliestFireTime) : new Date(Date.now() + 15 * 60_000)
    scheduledFor = nextQuarterHour(earliest).toISOString()
  }
  const scheduled = await updateWorkItem({ issueId: postId, scheduledFor, scheduleTimezone: timezone }, config)
  if (scheduled['error']) return { ...scheduled, postId }

  preflight = await getPreflight(config, postId)
  const submit = params.submit !== false
  let submitted = false
  let refusal: string | undefined
  let reviewerReport: Awaited<ReturnType<typeof assignReviewers>> | undefined
  if (submit && preflight.ready) {
    if (params.reviewers && params.reviewers.length > 0) {
      reviewerReport = await assignReviewers(config, postId, params.reviewers)
      warnings.push(...reviewerReport.problems)
    }
    const move = await takeNextMove(config, postId, preflight)
    submitted = move.moved
    refusal = move.refusal
    if (submitted) preflight = await getPreflight(config, postId)
  }

  return confirmation(config, postId, preflight, {
    submitted,
    refusal,
    warnings,
    reviewers: reviewerReport?.assigned,
    assets,
  })
}

async function confirmation(
  config: Config,
  postId: string,
  preflight: Preflight,
  extra: {
    submitted: boolean
    refusal?: string
    warnings?: string[]
    reviewers?: Array<{ userId: string; name: string | null }>
    assets?: Array<Record<string, unknown>>
  }
): Promise<Record<string, unknown>> {
  const item = await apiGet<WorkItemRow>(workItemBase(config, postId), config)
  const selected = await apiGet<TargetRow[]>(`${workItemBase(config, postId)}/publish-targets`, config)
  const assets =
    extra.assets ?? (await apiGet<Array<Record<string, unknown>>>(`${workItemBase(config, postId)}/assets`, config))
  const result: Record<string, unknown> = {
    postId,
    displayId: item.displayId,
    status: item.status,
    scheduledFor: item.scheduledFor,
    timezone: item.scheduleTimezone,
    targets: (selected ?? []).map((t) => ({
      targetId: t.id,
      platform: t.platform,
      account: t.label ?? (t.lane === 'MANUAL' ? 'manual' : null),
      lane: t.lane,
      state: t.state,
      permalink: t.permalink ?? null,
      errorMessage: t.errorMessage ?? null,
    })),
    assets: assets.map((a) => ({ id: a['id'], label: a['label'], contentType: a['contentType'] })),
    blockers: preflight.blockers,
    warnings: [...preflight.warnings.map((w) => w.message), ...(extra.warnings ?? [])],
    review: preflight.review,
    consent: preflight.consent,
    nextStep: extra.refusal
      ? `The move was refused: ${extra.refusal}`
      : nextStep(item.status, preflight, extra.submitted),
  }
  if (extra.reviewers && extra.reviewers.length > 0) result['reviewers'] = extra.reviewers
  return result
}

function firstLine(text: string): string {
  const line = text.split(/\r?\n/).find((l) => l.trim().length > 0) ?? text
  return line.trim().length > 80 ? line.trim().slice(0, 77) + '…' : line.trim()
}

/** Where a Post stands: status, every destination's outcome, and what the gate still wants. Always live. */
export async function getPostStatus(params: { postId: string }, config: Config): Promise<Record<string, unknown>> {
  const preflight = await getPreflight(config, params.postId)
  return confirmation(config, params.postId, preflight, { submitted: true })
}

/** Hands a Post to review: assigns the named reviewers, then takes the move the preflight names. */
export async function submitPost(
  params: { postId: string; reviewers?: string[] },
  config: Config
): Promise<Record<string, unknown>> {
  let preflight = await getPreflight(config, params.postId)
  const warnings: string[] = []
  let reviewerReport: Awaited<ReturnType<typeof assignReviewers>> | undefined
  if (params.reviewers && params.reviewers.length > 0) {
    reviewerReport = await assignReviewers(config, params.postId, params.reviewers)
    warnings.push(...reviewerReport.problems)
  }
  const move = await takeNextMove(config, params.postId, preflight)
  if (move.moved) preflight = await getPreflight(config, params.postId)
  return confirmation(config, params.postId, preflight, {
    submitted: move.moved,
    refusal: move.refusal,
    warnings,
    reviewers: reviewerReport?.assigned,
  })
}

/** Posts across every publishing Workflow, with each one's destinations, newest schedule first. */
export async function listPosts(
  params: { status?: string; since?: string; until?: string; platform?: string; limit?: number },
  config: Config
): Promise<Record<string, unknown>> {
  const accounts = await listAccounts(config)
  const workflows = (await listWorkflows({ kind: 'LIFECYCLE' }, config)) as WorkflowSummary[]
  const platforms = new Set(accounts.map((a) => String(a.platform).toLowerCase()))
  const publishing = workflows.filter((w) =>
    (w.assetTypes ?? []).some((t) => platforms.has(String(t).toLowerCase().split('_')[0] ?? ''))
  )
  const since = params.since ? new Date(params.since).getTime() : null
  const until = params.until ? new Date(params.until).getTime() : null
  const rows: WorkItemRow[] = []
  for (const workflow of publishing) {
    const items = (await listWorkItems({ workflow: workflow.slug, status: params.status }, config)) as WorkItemRow[]
    for (const item of items) {
      const at = item.scheduledFor ? new Date(item.scheduledFor).getTime() : null
      if (since !== null && (at === null || at < since)) continue
      if (until !== null && (at === null || at > until)) continue
      rows.push(item)
    }
  }
  rows.sort((a, b) => (b.scheduledFor ?? '').localeCompare(a.scheduledFor ?? ''))
  const limit = Math.max(1, Math.min(params.limit ?? 50, 50))
  const posts: Array<Record<string, unknown>> = []
  for (const row of rows.slice(0, limit)) {
    const selected = (await apiGet<TargetRow[]>(`${workItemBase(config, row.id)}/publish-targets`, config)) ?? []
    const targets = selected
      .filter((t) => !params.platform || String(t.platform).toLowerCase() === params.platform.toLowerCase())
      .map((t) => ({ platform: t.platform, account: t.label ?? null, state: t.state, permalink: t.permalink ?? null }))
    if (params.platform && targets.length === 0) continue
    posts.push({
      postId: row.id,
      displayId: row.displayId,
      title: row.title,
      status: row.status,
      scheduledFor: row.scheduledFor ?? null,
      timezone: row.scheduleTimezone ?? null,
      targets,
    })
  }
  return { posts, count: posts.length, truncated: rows.length > limit }
}

/**
 * Records a review verdict as the caller's user. The backend requires an assigned reviewer holding the
 * REVIEWER role (or an ADMIN who was assigned) — a project API key cannot review, and a user key without
 * the role is refused; both come back as the server's own message. Approval on MARKETING schedules the
 * Post in the same request; `autoTransition` says how far it got.
 */
export async function submitReview(
  params: { postId: string; verdict: 'approve' | 'request_changes' | string; summary?: string },
  config: Config
): Promise<Record<string, unknown>> {
  const verdict = normalizeVerdict(params.verdict)
  try {
    const review = await apiPost<Record<string, unknown>>(
      `${workItemBase(config, params.postId)}/reviews`,
      { verdict, body: params.summary },
      config
    )
    const preflight = await getPreflight(config, params.postId)
    const status = await confirmation(config, params.postId, preflight, { submitted: true })
    return { verdict, autoTransition: review['autoTransition'] ?? null, ...status }
  } catch (err) {
    if (isClientError(err)) {
      return { error: `Review not recorded: ${errorText(err)}` }
    }
    throw err
  }
}

function normalizeVerdict(raw: string): string {
  const v = String(raw ?? '').trim().toLowerCase().replace(/[\s-]+/g, '_')
  if (v === 'approve' || v === 'approved') return 'APPROVED'
  if (v === 'request_changes' || v === 'changes_requested' || v === 'reject') return 'CHANGES_REQUESTED'
  if (v === 'comment' || v === 'commented') return 'COMMENTED'
  throw new Error(`verdict must be "approve" or "request_changes", not "${raw}".`)
}

/** Every Asset on a Work Item — the ids set_publish_targets.assetIds wants. */
export async function listAssets(params: { issueId: string }, config: Config): Promise<Record<string, unknown>> {
  const assets = await apiGet<Array<Record<string, unknown>>>(`${workItemBase(config, params.issueId)}/assets`, config)
  return { issueId: params.issueId, assets }
}
