import { describe, expect, it } from 'vitest'
import { manualOption, option, selection, tiktokOption } from '@/components/marketing/test-fixtures'
import type { PublishPreflight } from '@/components/marketing/PublishReadinessCard'
import type { PublishMetricsResponse } from './DestinationMetrics'
import { attentionOf, buildRows, describeFireTime, sortRows, summarize, type BuildRowsArgs } from './model'
import { EMPTY_DRAFT } from './selectionState'
import { targetKey } from './publishState'
import type { SelectedPublishTarget } from './types'

const WORK_ITEM = 'post-1'
const NOW = new Date('2026-09-12T12:00:00Z')

const facebook = option({ platform: 'facebook', connectionId: 'fb', label: 'Rexipe' })
const instagram = option({ platform: 'instagram', connectionId: 'ig', label: '@rexipeio' })
const youtube = option({ platform: 'youtube', connectionId: 'yt', label: 'Rexipe Kitchen' })
const tiktok = tiktokOption('tt')

function picked(...targets: SelectedPublishTarget[]) {
  return { ...EMPTY_DRAFT, keys: new Set(targets.map((t) => targetKey(t.platform, t.connectionId))) }
}

function args(overrides: Partial<BuildRowsArgs>): BuildRowsArgs {
  return {
    options: [facebook, instagram, youtube, tiktok],
    selected: [],
    draft: EMPTY_DRAFT,
    preflight: null,
    metrics: null,
    consent: null,
    approvedOrLater: false,
    mode: 'pick',
    assets: [],
    ...overrides,
  }
}

describe('attentionOf', () => {
  it('puts the states waiting on a person first, machines next, done after, nothing-yet last', () => {
    expect(attentionOf('AWAITING_MANUAL')).toBe('needs-you')
    expect(attentionOf('FAILED')).toBe('needs-you')
    expect(attentionOf('HANDED_OFF')).toBe('in-flight')
    expect(attentionOf('PUBLISHING')).toBe('in-flight')
    expect(attentionOf('PUBLISHED')).toBe('published')
    expect(attentionOf('PENDING')).toBe('idle')
    expect(attentionOf('REVOKED')).toBe('idle')
    expect(attentionOf(undefined)).toBe('idle')
  })
})

describe('buildRows', () => {
  it('sorts outcome rows by what needs you, then platform order; pick rows by platform only', () => {
    const selected = [
      { ...selection(facebook, WORK_ITEM), state: 'PUBLISHED' },
      { ...selection(instagram, WORK_ITEM), state: 'PUBLISHED' },
      { ...selection(youtube, WORK_ITEM), state: 'FAILED' },
      { ...selection(tiktok, WORK_ITEM), state: 'AWAITING_MANUAL' },
    ]
    const outcome = buildRows(args({ selected, draft: picked(...selected), approvedOrLater: true, mode: 'outcome' }), NOW)
    expect(outcome.map((r) => r.option.platform)).toEqual(['youtube', 'tiktok', 'facebook', 'instagram'])

    const pick = buildRows(args({ selected, draft: picked(...selected), mode: 'pick' }), NOW)
    expect(pick.map((r) => r.option.platform)).toEqual(['facebook', 'instagram', 'youtube', 'tiktok'])
  })

  it('routes the preflight findings and the metrics to their rows by target id', () => {
    const fb = { ...selection(facebook, WORK_ITEM, 'fb-target'), state: 'PUBLISHED' }
    const ig = { ...selection(instagram, WORK_ITEM, 'ig-target'), state: 'PENDING' }
    const preflight = {
      publishing: true,
      ready: false,
      blockers: [
        { code: 'TARGET_MEDIA_MISSING', message: 'Instagram (@rexipeio) needs at least one image', targetId: 'ig-target' },
        { code: 'NO_TIMEZONE', message: 'Needs a time zone', targetId: null },
      ],
      warnings: [{ code: 'MEDIA_ADVISORY', message: 'Facebook crops it', targetId: 'fb-target' }],
      consent: { required: false, verdict: 'NOT_REQUIRED' },
      review: { gated: false, assignedReviewers: 0, satisfied: false },
    } as PublishPreflight
    const metrics: PublishMetricsResponse = {
      workItemId: WORK_ITEM,
      targets: [{ targetId: 'fb-target', platform: 'facebook', latest: { observedAt: '2026-09-12T10:00:00Z', likes: 84 }, series: [] }],
    }
    const rows = buildRows(args({ selected: [fb, ig], draft: picked(fb, ig), preflight, metrics, mode: 'outcome' }), NOW)
    const fbRow = rows.find((r) => r.option.platform === 'facebook')!
    const igRow = rows.find((r) => r.option.platform === 'instagram')!
    expect(igRow.blockers.map((b) => b.code)).toEqual(['TARGET_MEDIA_MISSING'])
    expect(fbRow.blockers).toEqual([])
    expect(fbRow.warnings.map((w) => w.code)).toEqual(['MEDIA_ADVISORY'])
    expect(fbRow.metrics?.latest.likes).toBe(84)
    expect(igRow.metrics).toBeUndefined()
  })

  it('hides a by-hand row for a platform with an account unless it is already on the Post', () => {
    const manualIg = manualOption('instagram')
    const manualYt = manualOption('youtube')
    const rows = buildRows(args({ options: [instagram, manualIg, manualYt], selected: [], draft: EMPTY_DRAFT }), NOW)
    expect(rows.find((r) => r.option === manualIg)?.hidden).toBe(true)
    // YouTube has no account here, so its by-hand row is the only way.
    expect(rows.find((r) => r.option === manualYt)?.hidden).toBe(false)

    const onPost = selection(manualIg, WORK_ITEM)
    const kept = buildRows(args({ options: [instagram, manualIg], selected: [onPost], draft: picked(onPost) }), NOW)
    expect(kept.find((r) => r.option === manualIg)?.hidden).toBe(false)
  })

  it('carries consent only on a picked, non-manual TikTok row, with the options problem and the default audience', () => {
    const tt = selection(tiktok, WORK_ITEM)
    const manualTt = manualOption('tiktok')
    const onPostByHand = selection(manualTt, WORK_ITEM)
    const rows = buildRows(
      args({
        options: [tiktok, manualTt, facebook],
        selected: [tt, onPostByHand],
        draft: picked(tt, onPostByHand),
        consent: { state: null, given: false },
      }),
      NOW
    )
    const ttRow = rows.find((r) => r.option === tiktok)!
    expect(ttRow.consent).toEqual({ given: false, verdict: undefined, consentedAt: undefined, consentedByName: undefined, problem: null })
    expect(ttRow.tiktokOptions.privacyLevel).toBe('PUBLIC_TO_EVERYONE')
    expect(ttRow.action).toBe('review-consent')
    expect(rows.find((r) => r.option === manualTt)?.consent).toBeUndefined()
    expect(rows.find((r) => r.option === facebook)?.consent).toBeUndefined()
  })

  it('keeps a retried FAILED→PENDING row settled on an approved Post, and offers Retry / Mark published by state', () => {
    const fb = { ...selection(facebook, WORK_ITEM), state: 'PENDING' }
    const yt = { ...selection(youtube, WORK_ITEM), state: 'FAILED' }
    const tt = { ...selection(tiktok, WORK_ITEM), state: 'AWAITING_MANUAL' }
    const rows = buildRows(args({ selected: [fb, yt, tt], draft: picked(fb, yt, tt), approvedOrLater: true, mode: 'outcome' }), NOW)
    const byPlatform = Object.fromEntries(rows.map((r) => [r.option.platform, r]))
    expect(byPlatform.facebook.settled).toBe(true)
    expect(byPlatform.facebook.stateLabel).toBe('Waiting')
    expect(byPlatform.facebook.action).toBeNull()
    expect(byPlatform.youtube.action).toBe('retry')
    expect(byPlatform.tiktok.action).toBe('mark-published')
    expect(byPlatform.tiktok.hue).toBe('amber')
  })

  it('shows an orphaned selection (its connection gone) so it can be unticked', () => {
    const gone = { ...selection(option({ platform: 'facebook', connectionId: 'old-page', label: 'Old page' }), WORK_ITEM) }
    const rows = buildRows(args({ options: [facebook], selected: [gone], draft: picked(gone) }), NOW)
    const orphan = rows.find((r) => r.option.connectionId === 'old-page')!
    expect(orphan.unavailable).toBe(true)
    expect(orphan.note).toMatch(/no longer connected/)
  })

  it('reads the fire time in the row’s own words', () => {
    const soon = new Date(NOW.getTime() + 2 * 60 * 60 * 1000).toISOString()
    const earlier = new Date(NOW.getTime() - 2 * 60 * 60 * 1000).toISOString()
    const fb = { ...selection(facebook, WORK_ITEM), state: 'PENDING', fireTime: soon }
    const tt = { ...selection(tiktok, WORK_ITEM), state: 'AWAITING_MANUAL', fireTime: earlier }
    const ig = { ...selection(instagram, WORK_ITEM), state: 'PUBLISHED', fireTime: earlier }
    const rows = buildRows(args({ selected: [fb, tt, ig], draft: picked(fb, tt, ig), approvedOrLater: true, mode: 'outcome' }), NOW)
    const text = Object.fromEntries(rows.map((r) => [r.option.platform, r.timeText]))
    expect(text.facebook).toMatch(/^goes out /)
    expect(text.tiktok).toMatch(/^due /)
    expect(text.instagram).not.toMatch(/^(goes out|due) /)
  })
})

describe('describeFireTime', () => {
  it('is relative once behind us and a short calendar form ahead', () => {
    expect(describeFireTime(new Date(NOW.getTime() - 3 * 60 * 60 * 1000).toISOString(), NOW)).toMatch(/ago/)
    expect(describeFireTime(new Date(NOW.getTime() + 24 * 60 * 60 * 1000).toISOString(), NOW)).toMatch(/\d/)
    expect(describeFireTime('garbage', NOW)).toBe('')
  })
})

describe('summarize', () => {
  it('counts what is picked, published and waiting on a person', () => {
    const selected = [
      { ...selection(facebook, WORK_ITEM), state: 'PUBLISHED' },
      { ...selection(instagram, WORK_ITEM), state: 'PUBLISHED' },
      { ...selection(youtube, WORK_ITEM), state: 'FAILED' },
      { ...selection(tiktok, WORK_ITEM), state: 'AWAITING_MANUAL' },
    ]
    const rows = buildRows(args({ selected, draft: picked(...selected), approvedOrLater: true, mode: 'outcome' }), NOW)
    const s = summarize(rows, 'outcome')
    expect(s.text).toBe('2 of 4 published · 2 need you')
    expect(s.failed).toBe(1)
    expect(s.awaiting).toBe(1)
    expect(summarize(rows, 'pick').text).toBe('4 selected')
    expect(summarize([], 'pick').text).toBe('No destinations yet')
  })

  it('says how many destinations there are while nothing has gone out', () => {
    const selected = [{ ...selection(facebook, WORK_ITEM), state: 'PENDING' }]
    const rows = buildRows(args({ selected, draft: picked(...selected), approvedOrLater: true, mode: 'outcome' }), NOW)
    expect(summarize(rows, 'outcome').text).toBe('1 destination')
  })

  it('sortRows is stable for equal attention and platform', () => {
    const a = option({ platform: 'facebook', connectionId: 'a', label: 'Alpha' })
    const b = option({ platform: 'facebook', connectionId: 'b', label: 'Beta' })
    const rows = buildRows(args({ options: [b, a] }), NOW)
    expect(sortRows(rows, 'pick').map((r) => r.option.label)).toEqual(['Alpha', 'Beta'])
  })
})
