import { describe, it, expect } from 'vitest'
import { blocksByLine, snapToRendered } from './lineBlocks'

const lines = (...ns: number[]) => new Map(ns.map((n) => [n, true]))

describe('snapToRendered', () => {
  it('returns the line unchanged when it rendered something', () => {
    expect(snapToRendered(3, lines(1, 3, 5))).toBe(3)
  })
  it('snaps a blank line back to the preceding block', () => {
    expect(snapToRendered(4, lines(1, 3, 5))).toBe(3)
  })
  it('breaks an exact tie toward the preceding block', () => {
    expect(snapToRendered(5, lines(4, 6))).toBe(4)
  })
  it('snaps forward when there is nothing above', () => {
    expect(snapToRendered(1, lines(7, 9))).toBe(7)
  })
  it('snaps back to the last block for a line past the end', () => {
    expect(snapToRendered(99, lines(2, 8))).toBe(8)
  })
  it('returns the line itself when nothing rendered', () => {
    expect(snapToRendered(4, new Map())).toBe(4)
  })
})

describe('blocksByLine', () => {
  it('maps each line to the narrowest block covering it', () => {
    const root = document.createElement('div')
    root.innerHTML =
      '<ul data-line-start="1" data-line-end="3"><li data-line-start="2" data-line-end="2">x</li></ul><p data-line-start="5" data-line-end="5">y</p>'
    const map = blocksByLine(root)
    expect(map.get(1)?.tagName).toBe('UL')
    expect(map.get(2)?.tagName).toBe('LI')
    expect(map.get(5)?.tagName).toBe('P')
    expect(map.has(4)).toBe(false)
  })
})
