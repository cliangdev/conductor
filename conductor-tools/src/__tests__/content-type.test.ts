import { describe, it, expect } from 'vitest'
import { contentTypeFor } from '../lib/content-type.js'

describe('contentTypeFor', () => {
  it.each([
    ['prd.html', 'text/html'],
    ['page.htm', 'text/html'],
    ['PRD.HTML', 'text/html'],
    ['prd.md', 'text/markdown'],
    ['README.MD', 'text/markdown'],
    ['tasks.json', 'application/json'],
    ['notes.txt', 'text/plain'],
    ['noextension', 'text/plain'],
    ['archive.tar.gz', 'text/plain'],
    ['mockup.v2.html', 'text/html'],
  ])('%s -> %s', (name, expected) => {
    expect(contentTypeFor(name)).toBe(expected)
  })
})
