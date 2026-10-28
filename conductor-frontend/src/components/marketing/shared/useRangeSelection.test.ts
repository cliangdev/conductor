import { act, renderHook } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { useRangeSelection } from './useRangeSelection'

const IDS = ['a', 'b', 'c', 'd', 'e']

describe('useRangeSelection', () => {
  it('toggles an id on and off', () => {
    const { result } = renderHook(() => useRangeSelection(IDS))
    act(() => result.current.select('b'))
    expect(result.current.selected).toEqual(['b'])
    act(() => result.current.select('b'))
    expect(result.current.selected).toEqual([])
  })

  it('selects the range from the last plain click to the shift click', () => {
    const { result } = renderHook(() => useRangeSelection(IDS))
    act(() => result.current.select('b'))
    act(() => result.current.select('d', true))
    expect(result.current.selected).toEqual(['b', 'c', 'd'])
  })

  it('ranges backwards and never duplicates', () => {
    const { result } = renderHook(() => useRangeSelection(IDS))
    act(() => result.current.select('d'))
    act(() => result.current.select('b', true))
    act(() => result.current.select('c', true))
    expect(result.current.selected.sort()).toEqual(['b', 'c', 'd'])
  })

  it('treats a shift click with no anchor as a plain toggle', () => {
    const { result } = renderHook(() => useRangeSelection(IDS))
    act(() => result.current.select('c', true))
    expect(result.current.selected).toEqual(['c'])
  })

  it('ranges only over visible ids when a filter hides items', () => {
    const { result, rerender } = renderHook(({ ids }) => useRangeSelection(ids), {
      initialProps: { ids: IDS },
    })
    act(() => result.current.select('a'))
    rerender({ ids: ['a', 'c', 'e'] })
    act(() => result.current.select('e', true))
    expect(result.current.selected).toEqual(['a', 'c', 'e'])
    expect(result.current.hiddenCount).toBe(0)
  })

  it('keeps hidden selections but excludes them from selectedVisible', () => {
    const { result, rerender } = renderHook(({ ids }) => useRangeSelection(ids), {
      initialProps: { ids: IDS },
    })
    act(() => result.current.select('a'))
    act(() => result.current.select('b'))
    rerender({ ids: ['b', 'c'] })
    expect(result.current.selected).toEqual(['a', 'b'])
    expect(result.current.selectedVisible).toEqual(['b'])
    expect(result.current.hiddenCount).toBe(1)
    rerender({ ids: IDS })
    expect(result.current.hiddenCount).toBe(0)
  })

  it('drops the anchor when it is hidden, so a shift click toggles instead', () => {
    const { result, rerender } = renderHook(({ ids }) => useRangeSelection(ids), {
      initialProps: { ids: IDS },
    })
    act(() => result.current.select('a'))
    rerender({ ids: ['c', 'd'] })
    act(() => result.current.select('d', true))
    expect(result.current.selected).toEqual(['a', 'd'])
  })

  it('clear empties the selection and the anchor', () => {
    const { result } = renderHook(() => useRangeSelection(IDS))
    act(() => result.current.select('b'))
    act(() => result.current.clear())
    expect(result.current.selected).toEqual([])
    act(() => result.current.select('d', true))
    expect(result.current.selected).toEqual(['d'])
  })

  it('add merges ids and remove drops one', () => {
    const { result } = renderHook(() => useRangeSelection(IDS))
    act(() => result.current.select('a'))
    act(() => result.current.add(['a', 'c']))
    expect(result.current.selected).toEqual(['a', 'c'])
    act(() => result.current.remove('a'))
    expect(result.current.selected).toEqual(['c'])
  })

  it('prune drops ids that no longer exist', () => {
    const { result } = renderHook(() => useRangeSelection(IDS))
    act(() => result.current.add(['a', 'b', 'c']))
    act(() => result.current.prune(['b', 'c', 'z']))
    expect(result.current.selected).toEqual(['b', 'c'])
  })
})
