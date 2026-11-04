import { describe, it, expect } from 'vitest'
import { wordDiff } from './wordDiff'

const rejoin = (pieces: ReturnType<typeof wordDiff>, side: 'old' | 'new') =>
  pieces.filter((p) => p.kind === 'same' || p.kind === (side === 'old' ? 'del' : 'add')).map((p) => p.text).join('')

describe('wordDiff', () => {
  it('returns one unchanged piece for equal text, and none for empty text', () => {
    expect(wordDiff('same words', 'same words')).toEqual([{ text: 'same words', kind: 'same' }])
    expect(wordDiff('', '')).toEqual([])
  })

  it('marks only the replaced word', () => {
    expect(wordDiff('Save time on dinner', 'Save money on dinner')).toEqual([
      { text: 'Save ', kind: 'same' },
      { text: 'time', kind: 'del' },
      { text: 'money', kind: 'add' },
      { text: ' on dinner', kind: 'same' },
    ])
  })

  it('shows an insertion and a deletion', () => {
    const added = wordDiff('one three', 'one two three')
    expect(added.filter((p) => p.kind === 'add').map((p) => p.text.trim())).toEqual(['two'])
    expect(added.some((p) => p.kind === 'del')).toBe(false)
    const removed = wordDiff('one two three', 'one three')
    expect(removed.filter((p) => p.kind === 'del').map((p) => p.text.trim())).toEqual(['two'])
  })

  it('keeps line breaks, and the sides rejoin to the original texts', () => {
    const a = 'Hook line\nsecond line'
    const b = 'Hook line\nthird line'
    const d = wordDiff(a, b)
    expect(rejoin(d, 'old')).toBe(a)
    expect(rejoin(d, 'new')).toBe(b)
    expect(d.find((p) => p.kind === 'same')!.text).toContain('\n')
  })

  it('treats everything as replaced when one side is empty', () => {
    expect(wordDiff('', 'new text')).toEqual([{ text: 'new text', kind: 'add' }])
    expect(wordDiff('old text', '')).toEqual([{ text: 'old text', kind: 'del' }])
  })

  it('falls back to a whole-text replacement for very long inputs', () => {
    const long = Array.from({ length: 700 }, (_, i) => `w${i}`).join(' ')
    expect(wordDiff(long, `${long} more`)).toEqual([
      { text: long, kind: 'del' },
      { text: `${long} more`, kind: 'add' },
    ])
  })
})
