import { describe, it, expect } from 'vitest'
import { contentTypeFor } from '../lib/content-type.js'

describe('contentTypeFor', () => {
  it.each([
    ['prd.html', 'text/html'],
    ['page.htm', 'text/html'],
    ['PRD.HTML', 'text/html'],
    ['mockup.v2.html', 'text/html'],
    ['prd.md', 'text/markdown'],
    ['README.MD', 'text/markdown'],
    ['tasks.json', 'text/markdown'],
    ['notes.txt', 'text/markdown'],
    ['noextension', 'text/markdown'],
    ['page.html.bak', 'text/markdown'],
  ])('%s -> %s', (name, expected) => {
    expect(contentTypeFor(name)).toBe(expected)
  })
})
