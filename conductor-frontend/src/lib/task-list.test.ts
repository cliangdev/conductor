import { describe, it, expect } from 'vitest'
import { toggleTaskLine } from './task-list'

describe('toggleTaskLine', () => {
  it('checks an unchecked item', () => {
    expect(toggleTaskLine('- [ ] alpha', 1, true)).toBe('- [x] alpha')
  })

  it('unchecks a checked item', () => {
    expect(toggleTaskLine('- [x] alpha', 1, false)).toBe('- [ ] alpha')
  })

  it('accepts an already-uppercase X', () => {
    expect(toggleTaskLine('- [X] alpha', 1, false)).toBe('- [ ] alpha')
  })

  it('is a no-op when the item is already in the requested state', () => {
    expect(toggleTaskLine('- [x] alpha', 1, true)).toBe('- [x] alpha')
  })

  it('only touches the addressed line', () => {
    const content = '- [ ] alpha\n- [ ] beta\n- [ ] gamma'
    expect(toggleTaskLine(content, 2, true)).toBe('- [ ] alpha\n- [x] beta\n- [ ] gamma')
  })

  it.each([
    ['dash', '- [ ] x', '- [x] x'],
    ['asterisk', '* [ ] x', '* [x] x'],
    ['plus', '+ [ ] x', '+ [x] x'],
    ['ordered with period', '1. [ ] x', '1. [x] x'],
    ['ordered with paren', '1) [ ] x', '1) [x] x'],
  ])('handles a %s marker', (_label, input, expected) => {
    expect(toggleTaskLine(input, 1, true)).toBe(expected)
  })

  it('preserves indentation on nested items', () => {
    expect(toggleTaskLine('    - [ ] nested', 1, true)).toBe('    - [x] nested')
  })

  it('handles blockquoted items', () => {
    expect(toggleTaskLine('> - [ ] quoted', 1, true)).toBe('> - [x] quoted')
  })

  it('preserves a trailing carriage return on CRLF content', () => {
    const content = '- [ ] alpha\r\n- [ ] beta\r\n'
    expect(toggleTaskLine(content, 1, true)).toBe('- [x] alpha\r\n- [ ] beta\r\n')
  })

  it('preserves a trailing newline', () => {
    expect(toggleTaskLine('- [ ] alpha\n', 1, true)).toBe('- [x] alpha\n')
  })

  it('preserves trailing text after the checkbox', () => {
    expect(toggleTaskLine('- [ ] alpha **bold** `code`', 1, true)).toBe(
      '- [x] alpha **bold** `code`'
    )
  })

  it('returns null when the line is not a task list item', () => {
    expect(toggleTaskLine('just a paragraph', 1, true)).toBeNull()
  })

  it('returns null for a plain bullet with no checkbox', () => {
    expect(toggleTaskLine('- alpha', 1, true)).toBeNull()
  })

  it('returns null when the line number is out of range', () => {
    expect(toggleTaskLine('- [ ] alpha', 5, true)).toBeNull()
    expect(toggleTaskLine('- [ ] alpha', 0, true)).toBeNull()
  })
})
