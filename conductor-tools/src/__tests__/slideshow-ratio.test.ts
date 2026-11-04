import { describe, it, expect } from 'vitest'
import { ratioOfSize } from '../lib/slideshow/index.js'

describe('ratioOfSize', () => {
  it('names the shapes within 2%, not only the exact pixel sizes', () => {
    expect(ratioOfSize(1080, 1920)).toBe('9x16')
    expect(ratioOfSize(720, 1280)).toBe('9x16')
    expect(ratioOfSize(1080, 1350)).toBe('4x5')
    expect(ratioOfSize(1080, 1340)).toBe('4x5')
    expect(ratioOfSize(1080, 1080)).toBe('1x1')
  })
  it('has no answer for other or unknown shapes', () => {
    expect(ratioOfSize(1080, 1200)).toBeUndefined()
    expect(ratioOfSize(1920, 1080)).toBeUndefined()
    expect(ratioOfSize(null, undefined)).toBeUndefined()
    expect(ratioOfSize(0, 0)).toBeUndefined()
  })
})
