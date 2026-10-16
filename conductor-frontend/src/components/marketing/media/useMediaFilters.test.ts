import { describe, it, expect } from 'vitest'
import { act, renderHook } from '@testing-library/react'
import { useMediaFilters } from './useMediaFilters'
import { photo } from './testFixtures'

const items = [
  photo('a', 'slide 10', 1, 1, { createdAt: '2026-01-01T00:00:00Z', checked: true }),
  photo('b', 'slide 2', 1, 1, { createdAt: '2026-01-03T00:00:00Z', postCount: 2 }),
  photo('c', 'Beach clip', 1, 1, { createdAt: '2026-01-02T00:00:00Z', mediaKind: 'VIDEO', aiGenerated: true, creativeCount: 1 }),
  photo('d', 'hidden', 1, 1, { blocked: true }),
]
const ids = (r: { current: { visible: { id: string }[] } }) => r.current.visible.map((i) => i.id)

describe('useMediaFilters', () => {
  it('sorts by name naturally by default and newest on request, hiding blocked', () => {
    const { result } = renderHook(() => useMediaFilters(items))
    expect(ids(result)).toEqual(['c', 'b', 'a'])
    act(() => result.current.setSort('newest'))
    expect(ids(result)).toEqual(['b', 'c', 'a'])
  })

  it('searches labels by substring, case-insensitively', () => {
    const { result } = renderHook(() => useMediaFilters(items))
    act(() => result.current.setQuery('SLIDE'))
    expect(ids(result)).toEqual(['b', 'a'])
    expect(result.current.filtering).toBe(true)
  })

  it('narrows by kind tab', () => {
    const { result } = renderHook(() => useMediaFilters(items))
    act(() => result.current.setKind('VIDEO'))
    expect(ids(result)).toEqual(['c'])
  })

  it('applies the chips', () => {
    const { result } = renderHook(() => useMediaFilters(items))
    act(() => result.current.toggleChip('onPosts'))
    expect(ids(result)).toEqual(['b'])
    act(() => result.current.toggleChip('onPosts'))
    act(() => result.current.toggleChip('ai'))
    expect(ids(result)).toEqual(['c'])
    act(() => result.current.toggleChip('ai'))
    act(() => result.current.toggleChip('unused'))
    expect(ids(result)).toEqual(['a'])
    act(() => result.current.toggleChip('unused'))
    act(() => result.current.toggleChip('unchecked'))
    expect(ids(result)).toEqual(['c', 'b'])
  })

  it('shows blocked items only when the Blocked chip is on', () => {
    const { result } = renderHook(() => useMediaFilters(items))
    act(() => result.current.toggleChip('blocked'))
    expect(ids(result)).toContain('d')
  })

  it('is empty while items are still loading', () => {
    const { result } = renderHook(() => useMediaFilters(null))
    expect(result.current.visible).toEqual([])
  })
})
