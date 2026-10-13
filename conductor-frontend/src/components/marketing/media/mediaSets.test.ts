import { describe, it, expect } from 'vitest'
import { buildCarouselPlan, inSet, setPrefix, sortNatural } from './mediaSets'
import { photo, slides } from './testFixtures'

describe('mediaSets', () => {
  it('sorts labels naturally', () => {
    const sorted = sortNatural([photo('a', 'slide 10'), photo('b', 'slide 2'), photo('c', 'slide 1')])
    expect(sorted.map((p) => p.label)).toEqual(['slide 1', 'slide 2', 'slide 10'])
  })

  it('derives the set prefix and membership', () => {
    expect(setPrefix('01-import 4x5 slide 01')).toBe('01-import')
    expect(setPrefix('01-import 9x16 slide 01')).toBe('01-import')
    expect(inSet('01-import 9x16 slide 03', '01-import')).toBe(true)
    expect(inSet('01-importer slide 03', '01-import')).toBe(false)
  })

  it('groups 7 + 7 by placement in natural order and names from the common prefix', () => {
    const items = [...slides('9x16', 7), ...slides('4x5', 7)].reverse()
    const plan = buildCarouselPlan(items)
    expect(plan.problems).toEqual([])
    expect(plan.groups.map((g) => g.placement)).toEqual(['4x5', '9x16'])
    expect(plan.groups[0].items.map((i) => i.id)).toEqual(slides('4x5', 7).map((i) => i.id))
    expect(plan.name).toBe('01-import')
  })

  it('refuses a video, an off-aspect image and too many slides', () => {
    expect(buildCarouselPlan([photo('v', 'clip', 1080, 1920, { mediaKind: 'VIDEO' })]).problems[0]).toMatch(/Only images/)
    const off = buildCarouselPlan([photo('x', 'wide', 1920, 1080)])
    expect(off.problems[0]).toMatch(/wide \(1920×1080\)/)
    expect(buildCarouselPlan(slides('4x5', 11)).problems[0]).toMatch(/4:5 has 11 slides — the limit is 10/)
    expect(buildCarouselPlan(slides('9x16', 11)).problems).toEqual([])
  })

  it('refuses blocked and unfinished uploads, as the server does', () => {
    const blocked = buildCarouselPlan([photo('b', 'bad slide', 1080, 1350, { blocked: true })])
    expect(blocked.problems.join(' ')).toMatch(/Blocked: bad slide/)
    const pending = buildCarouselPlan([photo('p', 'half slide', 1080, 1350, { uploadStatus: 'PENDING' })])
    expect(pending.problems.join(' ')).toMatch(/Still uploading: half slide/)
  })
})
