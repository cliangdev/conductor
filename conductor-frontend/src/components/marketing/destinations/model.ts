// One row per destination, built once from everything the page knows about it — the project's options,
// the Post's selection, the draft being edited, the preflight's findings, the metrics feed and the TikTok
// consent — so the panel, the rows and their details render one model instead of each re-deriving it.
// Pure: no React, no fetches.

import type { StatusHue } from '@/components/ui/status-badge'
import type { PublishFinding, PublishPreflight } from '@/components/marketing/PublishReadinessCard'
import type { PublishMetricsResponse, PublishMetricsTarget } from './DestinationMetrics'
import type { PostFormat } from '@/components/marketing/PostFormatSelector'
import type { InstagramPublishOptionValues } from '@/components/marketing/InstagramPublishOptions'
import type { YouTubePublishOptionValues } from '@/components/marketing/YouTubePublishOptions'
import {
  EMPTY_TIKTOK_OPTIONS,
  tiktokOptionsProblem,
  type TikTokPublishOptionValues,
} from '@/components/marketing/TikTokPublishOptions'
import { INHERITED_CONTENT, type TargetContent } from '@/components/marketing/TargetContentEditor'
import type { PublishConsentState } from '@/components/marketing/TikTokConsentStep'
import type { MediaAsset } from '@/components/workitems/MediaUploadPanel'
import { timeAgo } from '@/lib/format'
import {
  PLATFORM_ORDER,
  hasAccount,
  isManual,
  isSettled,
  isUnhealthy,
  stateHue,
  stateLabelFor,
  targetKey,
  withTikTokDefault,
} from './publishState'
import { effectiveAssetsFor, type DestinationDraft } from './selectionState'
import type { PublishTargetOption, SelectedPublishTarget } from './types'

/** Which way the rows read: a list to pick from, or a list of outcomes. */
export type DestinationMode = 'pick' | 'outcome'

/**
 * What a destination needs, in the order the list shows them: the ones waiting on the reader first,
 * then the ones a machine is busy with, then the done ones, then the ones with nothing to say yet.
 */
export type Attention = 'needs-you' | 'in-flight' | 'published' | 'idle'

const ATTENTION_RANK: Record<Attention, number> = { 'needs-you': 0, 'in-flight': 1, published: 2, idle: 3 }

export function attentionOf(state: string | undefined): Attention {
  switch (state) {
    case 'AWAITING_MANUAL':
    case 'FAILED':
      return 'needs-you'
    case 'HANDED_OFF':
    case 'PUBLISHING':
      return 'in-flight'
    case 'PUBLISHED':
      return 'published'
    default:
      return 'idle'
  }
}

/** The one thing a row offers to do, when it offers anything. */
export type RowAction = 'retry' | 'mark-published' | 'review-consent' | null

export interface RowConsent {
  given: boolean
  verdict?: PublishConsentState['verdict']
  consentedAt?: string | null
  consentedByName?: string | null
  /** Why this account isn't postable yet (the options problem), null when it is. */
  problem: string | null
}

export interface DestinationRowModel {
  key: string
  option: PublishTargetOption
  /** The persisted row, once the Post has one. */
  target?: SelectedPublishTarget
  checked: boolean
  /** A by-hand row for a platform that has an account: offered only once it is already on the Post. */
  hidden: boolean
  /** The connection behind an already-selected target has gone away. */
  unavailable: boolean
  unhealthy: boolean
  manual: boolean
  /** Shows its outcome (chip, permalink, error, actions) rather than its editors — see `isSettled`. */
  settled: boolean
  attention: Attention
  hue: StatusHue | null
  stateLabel: string | null
  /** "goes out Fri 9:00 AM", "due 2 hours ago", "2 hours ago" — from the target's fire time. */
  timeText: string | null
  format: PostFormat
  content: TargetContent
  customized: boolean
  tiktokOptions: TikTokPublishOptionValues
  instagramOptions: InstagramPublishOptionValues
  youtubeOptions: YouTubePublishOptionValues
  /** What actually goes out here: the chosen subset in its order, or the whole Post's media. */
  effectiveAssets: MediaAsset[]
  blockers: PublishFinding[]
  warnings: PublishFinding[]
  metrics?: PublishMetricsTarget
  /** Only on a non-manual TikTok row that is picked. */
  consent?: RowConsent
  /** The one sentence under the label about the account itself, if any. */
  note: string | null
  noteTone: 'muted' | 'amber'
  noteDetail?: string | null
  action: RowAction
}

export interface BuildRowsArgs {
  options: PublishTargetOption[]
  selected: SelectedPublishTarget[]
  draft: DestinationDraft
  preflight: PublishPreflight | null
  metrics: PublishMetricsResponse | null
  consent: { state: PublishConsentState | null; given: boolean } | null
  approvedOrLater: boolean
  mode: DestinationMode
  assets: MediaAsset[]
}

/** A fire time as a person reads it on the row. Relative once it is behind us, a short calendar form ahead. */
export function describeFireTime(iso: string, now: Date = new Date()): string {
  const at = new Date(iso)
  if (Number.isNaN(at.getTime())) return ''
  if (at.getTime() <= now.getTime()) return timeAgo(at)
  const withinWeek = at.getTime() - now.getTime() < 6 * 24 * 60 * 60 * 1000
  try {
    return new Intl.DateTimeFormat(undefined, {
      ...(withinWeek ? { weekday: 'short' } : { month: 'short', day: 'numeric' }),
      hour: 'numeric',
      minute: '2-digit',
    }).format(at)
  } catch {
    return at.toLocaleString()
  }
}

function timeTextFor(target: SelectedPublishTarget | undefined, now: Date): string | null {
  if (!target?.fireTime) return null
  const when = describeFireTime(target.fireTime, now)
  if (!when) return null
  switch (target.state) {
    case 'PENDING':
    case 'HANDED_OFF':
    case 'PUBLISHING':
      return new Date(target.fireTime).getTime() > now.getTime() ? `goes out ${when}` : when
    case 'AWAITING_MANUAL':
      return `due ${when}`
    default:
      return when
  }
}

function noteFor(
  option: PublishTargetOption,
  unavailable: boolean,
  unhealthy: boolean,
  settled: boolean
): { note: string | null; tone: 'muted' | 'amber' } {
  if (unavailable) {
    return {
      note: 'This account is no longer connected — it will be removed when you change the selection.',
      tone: 'amber',
    }
  }
  if (unhealthy) {
    return {
      note: option.healthMessage ?? 'This account needs to be reconnected before it can publish.',
      tone: 'amber',
    }
  }
  if (isManual(option) && !settled) {
    return {
      note: "Conductor won't post this one. It still goes through review and onto the calendar; when it's due you'll be asked to post it yourself and paste the link back.",
      tone: 'muted',
    }
  }
  return { note: null, tone: 'muted' }
}

export function buildRows(args: BuildRowsArgs, now: Date = new Date()): DestinationRowModel[] {
  const { options, selected, draft, preflight, metrics, consent, approvedOrLater, mode, assets } = args
  const availableKeys = new Set(options.map((o) => targetKey(o.platform, o.connectionId)))
  const selectedByKey = new Map(selected.map((t) => [targetKey(t.platform, t.connectionId), t]))
  // Any still-selected target whose connection has since disappeared stays visible, so it can be seen
  // and unchecked instead of vanishing.
  const orphans: PublishTargetOption[] = selected
    .filter((t) => !availableKeys.has(targetKey(t.platform, t.connectionId)))
    .map((t) => ({
      platform: t.platform,
      connectorId: t.connectorId,
      connectionId: t.connectionId,
      label: t.label ?? t.connectionId ?? 'Manual',
      lane: t.lane,
    }))
  const all = [...options, ...orphans]
  const byPlatform = new Map<string, PublishTargetOption[]>()
  for (const o of all) byPlatform.set(o.platform, [...(byPlatform.get(o.platform) ?? []), o])

  const findingsFor = (target: SelectedPublishTarget | undefined, list: PublishFinding[] | undefined) =>
    target ? (list ?? []).filter((f) => f.targetId === target.id) : []
  const metricsByTarget = new Map((metrics?.targets ?? []).map((m) => [m.targetId, m]))

  const rows = all.map((option): DestinationRowModel => {
    const key = targetKey(option.platform, option.connectionId)
    const target = selectedByKey.get(key)
    const checked = draft.keys.has(key)
    const unavailable = !availableKeys.has(key)
    const unhealthy = isUnhealthy(option)
    const manual = isManual(option)
    const settled = checked && isSettled(target, approvedOrLater)
    const content = draft.content[key] ?? INHERITED_CONTENT
    const tiktokOptions = withTikTokDefault(option, draft.tiktok[key] ?? EMPTY_TIKTOK_OPTIONS)
    const { note, tone } = noteFor(option, unavailable, unhealthy, settled)
    const rowConsent: RowConsent | undefined =
      option.platform === 'tiktok' && !manual && checked && consent
        ? {
            given: consent.given,
            verdict: consent.state?.verdict,
            consentedAt: consent.state?.consentedAt,
            consentedByName: consent.state?.consentedByName,
            problem: tiktokOptionsProblem(tiktokOptions),
          }
        : undefined
    const action: RowAction =
      target?.state === 'FAILED' && settled
        ? 'retry'
        : target?.state === 'AWAITING_MANUAL' && settled
          ? 'mark-published'
          : rowConsent && !rowConsent.given && mode === 'pick'
            ? 'review-consent'
            : null
    return {
      key,
      option,
      target,
      checked,
      hidden: manual && !checked && hasAccount(byPlatform.get(option.platform) ?? []),
      unavailable,
      unhealthy,
      manual,
      settled,
      attention: settled ? attentionOf(target?.state) : 'idle',
      hue: settled && target ? stateHue(target.state) : null,
      stateLabel: settled && target ? stateLabelFor(target) : null,
      timeText: settled ? timeTextFor(target, now) : null,
      format: draft.formats[key] ?? 'feed',
      content,
      customized: content.captionOverride !== null || content.assetIds !== null,
      tiktokOptions,
      instagramOptions: draft.instagram[key] ?? {},
      youtubeOptions: draft.youtube[key] ?? {},
      effectiveAssets: effectiveAssetsFor(content, assets),
      blockers: findingsFor(target, preflight?.blockers),
      warnings: findingsFor(target, preflight?.warnings),
      metrics: target ? metricsByTarget.get(target.id) : undefined,
      consent: rowConsent,
      note,
      noteTone: tone,
      noteDetail: unhealthy ? option.healthDetail : undefined,
      action,
    }
  })
  return sortRows(rows, mode)
}

/**
 * Outcome mode reads by attention — what needs you first — then platform; pick mode keeps platform
 * order so the list holds still while boxes are ticked.
 */
export function sortRows(rows: DestinationRowModel[], mode: DestinationMode): DestinationRowModel[] {
  const platformRank = (p: string) => {
    const i = (PLATFORM_ORDER as string[]).indexOf(p)
    return i === -1 ? PLATFORM_ORDER.length : i
  }
  return [...rows].sort((a, b) => {
    if (mode === 'outcome') {
      const d = ATTENTION_RANK[a.attention] - ATTENTION_RANK[b.attention]
      if (d !== 0) return d
    }
    const p = platformRank(a.option.platform) - platformRank(b.option.platform)
    if (p !== 0) return p
    return a.option.label.localeCompare(b.option.label)
  })
}

export interface DestinationSummary {
  text: string
  total: number
  published: number
  needsYou: number
  failed: number
  awaiting: number
}

/** "3 selected" while picking; "2 of 4 published · 2 need you" once things have gone out. */
export function summarize(rows: DestinationRowModel[], mode: DestinationMode): DestinationSummary {
  const picked = rows.filter((r) => r.checked && !r.hidden)
  const total = picked.length
  const published = picked.filter((r) => r.target?.state === 'PUBLISHED').length
  const needsYou = picked.filter((r) => r.attention === 'needs-you').length
  const failed = picked.filter((r) => r.target?.state === 'FAILED').length
  const awaiting = picked.filter((r) => r.target?.state === 'AWAITING_MANUAL').length
  let text: string
  if (total === 0) {
    text = 'No destinations yet'
  } else if (mode === 'pick') {
    text = `${total} selected`
  } else if (published === 0 && needsYou === 0) {
    text = `${total} destination${total === 1 ? '' : 's'}`
  } else {
    text = `${published} of ${total} published`
    if (needsYou > 0) text += ` · ${needsYou} need${needsYou === 1 ? 's' : ''} you`
  }
  return { text, total, published, needsYou, failed, awaiting }
}

/** Everything the panel does on a person's behalf. The compose flow implements the same set locally. */
export interface DestinationActions {
  toggle: (row: DestinationRowModel) => void
  setFormat: (row: DestinationRowModel, next: PostFormat) => void
  setTikTokOptions: (row: DestinationRowModel, next: TikTokPublishOptionValues) => void
  setInstagramOptions: (row: DestinationRowModel, next: InstagramPublishOptionValues) => void
  setYouTubeOptions: (row: DestinationRowModel, next: YouTubePublishOptionValues) => void
  setContent: (row: DestinationRowModel, next: TargetContent) => void
  /** Re-sends every failed destination; the server has no per-target retry. */
  retry: () => Promise<void>
  completeManual: (row: DestinationRowModel, permalink: string, publishedAt: string | null) => Promise<void>
  /** Records the creator's TikTok consent for the whole Post. */
  setConsent: (next: boolean) => Promise<void>
  /** Reopens the checkboxes on an approved Post; the next save sends it back for review. */
  editDestinations: () => void
}

/** What the panel renders from. `usePostDestinations` produces it for a Post; compose produces it locally. */
export interface DestinationsState {
  mode: DestinationMode
  rows: DestinationRowModel[]
  summary: DestinationSummary
  loading: boolean
  loadError: string | null
  saving: boolean
  retrying: boolean
  /** IN_REVIEW: nothing here may change until it is sent back. */
  locked: boolean
  /** Approved or later: editing is allowed but sends the Post back for review. */
  revertsOnEdit: boolean
  /** The person has asked to edit an approved Post's destinations; rows show checkboxes again. */
  editing: boolean
  postLevel: {
    blockers: PublishFinding[]
    warnings: PublishFinding[]
  }
  totals: PublishMetricsResponse['totals'] | null
  observedAt: string | null
  unreportedNote: string | null
  /** Consent trouble for the whole Post: the consent fetch or write failed. */
  consentError: string | null
  consentSaving: boolean
  actions: DestinationActions
}
