import { describe, it, expect } from 'vitest'
import { selection, tiktokOption } from '@/components/marketing/test-fixtures'
import { EMPTY_DRAFT, buildSelectionPayload, draftFromTargets } from './selectionState'
import { buildRows } from './model'
import type { SelectedPublishTarget } from './types'

const tiktok = tiktokOption('c1')
const KEY = 'tiktok c1'
const target = (publishOptions: SelectedPublishTarget['publishOptions']): SelectedPublishTarget => ({
  ...selection(tiktok, 'w1'),
  publishOptions,
})

describe('carouselAs in the destination draft', () => {
  it('is seeded only for a target whose row carries it', () => {
    expect(draftFromTargets([target({ carouselAs: 'video' })]).carouselAs).toEqual({ [KEY]: 'video' })
    expect(draftFromTargets([target({ privacyLevel: 'SELF_ONLY' })]).carouselAs).toEqual({})
  })

  it('rides along in publishOptions when saved, next to the platform options', () => {
    const draft = { ...draftFromTargets([target({ carouselAs: 'video' })]), carouselAs: { [KEY]: 'photos' as const } }
    const [sent] = buildSelectionPayload([tiktok], draft)
    expect(sent.publishOptions).toMatchObject({ carouselAs: 'photos', disableComment: true })
  })

  it('is never sent for a target without a choice', () => {
    const draft = { ...EMPTY_DRAFT, keys: new Set([KEY]) }
    const [sent] = buildSelectionPayload([tiktok], draft)
    expect(sent.publishOptions).not.toHaveProperty('carouselAs')
  })

  it('shows up on the row model', () => {
    const t = target({ carouselAs: 'video' })
    const rows = buildRows({
      options: [tiktok],
      selected: [t],
      draft: draftFromTargets([t]),
      preflight: null,
      metrics: null,
      consent: null,
      approvedOrLater: false,
      mode: 'pick',
      assets: [],
    })
    expect(rows[0].carouselAs).toBe('video')
  })
})
