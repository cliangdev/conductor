import { describe, it, expect } from 'vitest'
import { fallbackBox, snapToRendered, type LineOffsets } from './useSourceLineOffsets'

function offsets(...lines: number[]): LineOffsets {
  return new Map(lines.map((line) => [line, { top: line * 10, height: 10 }]))
}

describe('snapToRendered', () => {
  it('returns the line unchanged when it rendered something', () => {
    expect(snapToRendered(3, offsets(1, 3, 5))).toBe(3)
  })

  it('snaps a blank line back to the preceding block', () => {
    // Line 4 is the blank separator between a block ending at 3 and one starting at 5.
    expect(snapToRendered(4, offsets(1, 3, 5))).toBe(3)
  })

  it('breaks an exact tie toward the preceding block', () => {
    // Line 5 is equidistant from 4 and 6; a comment on a blank line refers to what came before it.
    expect(snapToRendered(5, offsets(4, 6))).toBe(4)
  })

  it('snaps forward when there is nothing above', () => {
    expect(snapToRendered(1, offsets(7, 9))).toBe(7)
  })

  it('snaps back to the last block for a line past the end', () => {
    expect(snapToRendered(99, offsets(2, 8))).toBe(8)
  })

  it('returns the line itself when nothing has been measured', () => {
    expect(snapToRendered(4, new Map())).toBe(4)
  })
})

describe('fallbackBox', () => {
  it('reproduces the uniform 26px stacking used before measurement lands', () => {
    expect(fallbackBox(1)).toEqual({ top: 0, height: 26 })
    expect(fallbackBox(3)).toEqual({ top: 52, height: 26 })
  })
})
