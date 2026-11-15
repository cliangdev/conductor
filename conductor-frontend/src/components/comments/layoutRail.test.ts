import { describe, it, expect } from 'vitest'
import { layoutRail, RAIL_GAP, type RailLayoutItem } from './layoutRail'

function noOverlap(items: RailLayoutItem[], positions: Record<string, number>, gap = RAIL_GAP) {
  const sorted = items.slice().sort((a, b) => positions[a.id] - positions[b.id])
  for (let i = 1; i < sorted.length; i++) {
    const prev = sorted[i - 1]
    expect(positions[sorted[i].id]).toBeGreaterThanOrEqual(positions[prev.id] + prev.height + gap)
  }
}

describe('layoutRail', () => {
  it('leaves well-spaced cards at their anchors', () => {
    const items = [
      { id: 'a', top: 0, height: 50 },
      { id: 'b', top: 200, height: 50 },
    ]
    const { positions, totalHeight } = layoutRail(items, null)
    expect(positions).toEqual({ a: 0, b: 200 })
    expect(totalHeight).toBe(250)
  })

  it('never overlaps cards, whichever is active', () => {
    const items = [
      { id: 'a', top: 100, height: 80 },
      { id: 'b', top: 110, height: 120 },
      { id: 'c', top: 120, height: 60 },
      { id: 'd', top: 400, height: 40 },
    ]
    for (const active of [null, 'a', 'b', 'c', 'd']) {
      noOverlap(items, layoutRail(items, active).positions)
    }
  })

  it('pins the active card exactly at its anchor and pushes the others away', () => {
    const items = [
      { id: 'a', top: 100, height: 80 },
      { id: 'b', top: 110, height: 80 },
      { id: 'c', top: 120, height: 80 },
    ]
    const { positions } = layoutRail(items, 'b')
    expect(positions.b).toBe(110)
    expect(positions.c).toBe(110 + 80 + RAIL_GAP)
    expect(positions.a).toBe(110 - 80 - RAIL_GAP)
  })

  it('keeps an 8px gap by default and honours a custom one', () => {
    const items = [
      { id: 'a', top: 0, height: 40 },
      { id: 'b', top: 0, height: 40 },
    ]
    expect(layoutRail(items, 'a').positions.b).toBe(48)
    expect(layoutRail(items, 'a', 16).positions.b).toBe(56)
  })

  it('does not push cards above the floor', () => {
    const items = [
      { id: 'a', top: 10, height: 100 },
      { id: 'b', top: 20, height: 100 },
      { id: 'c', top: 30, height: 100 },
    ]
    // Pinning c would push a and b to negative tops; the floor stacks them down from 0 instead.
    const { positions } = layoutRail(items, 'c')
    expect(Math.min(...Object.values(positions))).toBeGreaterThanOrEqual(0)
    noOverlap(items, positions)
    expect(positions.a).toBe(0)
    // A custom floor.
    expect(layoutRail(items, null, RAIL_GAP, 40).positions.a).toBe(40)
  })

  it('orders by anchor, not by input order', () => {
    const items = [
      { id: 'late', top: 300, height: 50 },
      { id: 'early', top: 10, height: 50 },
    ]
    const { positions } = layoutRail(items, null)
    expect(positions.early).toBeLessThan(positions.late)
  })

  it('treats an unknown active id as no active card', () => {
    const items = [{ id: 'a', top: 5, height: 20 }]
    expect(layoutRail(items, 'zzz').positions.a).toBe(5)
  })

  it('handles an empty rail', () => {
    expect(layoutRail([], null)).toEqual({ positions: {}, totalHeight: 0 })
  })
})
