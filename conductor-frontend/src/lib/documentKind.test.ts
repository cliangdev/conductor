import { describe, it, expect } from 'vitest'
import { isHtmlDocument, isMarkdownDocument, isPrdDocument, withPrdFirst } from './documentKind'

describe('isHtmlDocument', () => {
  it('treats a PRD.HTML file stored as text/markdown as HTML (extension wins)', () => {
    const doc = { filename: 'PRD.HTML', contentType: 'text/markdown' }
    expect(isHtmlDocument(doc)).toBe(true)
    expect(isMarkdownDocument(doc)).toBe(false)
  })

  it('treats a .md file stored as text/html as HTML (media type wins)', () => {
    const doc = { filename: 'notes.md', contentType: 'text/html' }
    expect(isHtmlDocument(doc)).toBe(true)
    expect(isMarkdownDocument(doc)).toBe(false)
  })

  it('ignores content type parameters and case', () => {
    expect(isHtmlDocument({ filename: 'page', contentType: 'text/html; charset=utf-8' })).toBe(true)
    expect(isHtmlDocument({ filename: 'page', contentType: 'TEXT/HTML ;charset=UTF-8' })).toBe(true)
    expect(isHtmlDocument({ filename: 'a.htm', contentType: 'application/octet-stream' })).toBe(true)
  })

  it('is false for markdown, plain text and binaries', () => {
    expect(isHtmlDocument({ filename: 'a.md', contentType: 'text/markdown' })).toBe(false)
    expect(isHtmlDocument({ filename: 'a.png', contentType: 'image/png' })).toBe(false)
    expect(isHtmlDocument({ filename: 'a.html.txt', contentType: 'text/plain' })).toBe(false)
  })
})

describe('isMarkdownDocument', () => {
  it('accepts markdown and plain text by media type, parameters ignored, or a .md name', () => {
    expect(isMarkdownDocument({ filename: 'a', contentType: 'text/markdown; charset=utf-8' })).toBe(true)
    expect(isMarkdownDocument({ filename: 'a', contentType: 'text/plain' })).toBe(true)
    expect(isMarkdownDocument({ filename: 'README.MD', contentType: 'application/octet-stream' })).toBe(true)
  })

  it('rejects binaries', () => {
    expect(isMarkdownDocument({ filename: 'a.png', contentType: 'image/png' })).toBe(false)
  })
})

describe('withPrdFirst', () => {
  const names = (...files: string[]) => withPrdFirst(files.map((filename) => ({ filename }))).map((d) => d.filename)

  it('puts the PRD first and keeps the order of the rest', () => {
    expect(names('architecture.md', 'mockup.html', 'prd.html')).toEqual(['prd.html', 'architecture.md', 'mockup.html'])
  })

  it('puts prd.html before an older prd.md', () => {
    expect(names('prd.md', 'architecture.md', 'prd.html')).toEqual(['prd.html', 'prd.md', 'architecture.md'])
  })

  it('leaves a Work Item without a PRD in its own order', () => {
    expect(names('notes.md', 'plan.html', 'prd-notes.md')).toEqual(['notes.md', 'plan.html', 'prd-notes.md'])
    expect(isPrdDocument({ filename: 'prd-notes.md' })).toBe(false)
  })
})
