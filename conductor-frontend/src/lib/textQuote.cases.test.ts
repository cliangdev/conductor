import { describe, it, expect, beforeAll, vi } from 'vitest'
import { createElement } from 'react'
import { render } from '@testing-library/react'
import { readFileSync } from 'fs'
import { resolve } from 'path'

// MermaidDiagram transitively pulls in mermaid + react-zoom-pan-pinch, neither of which survives jsdom.
vi.mock('@/components/markdown/MermaidDiagram', () => ({ MermaidDiagram: () => null }))
vi.mock('@/components/markdown/SignedImage', () => ({ SignedImage: () => null }))

import { bridgeSource, findQuote } from '@/components/markdown/htmlBridge'
import { MarkdownRenderer } from '@/components/markdown/MarkdownRenderer'

/**
 * The shared quote cases (repo-root testdata/text-quote-cases.json), also run by the backend's
 * TextQuoteCasesTest through TextProjection + QuoteMatcher. Here the flattened text is the browser's own: the
 * document is put in a jsdom page, the real comment bridge reports the text of a select-all (its `selection`
 * message carries it, flattened), and `findQuote` is run against that. The bridge itself also resolves each
 * quote, which covers visibility (hidden text is in the flattened text but never a match).
 * Markdown cases are rendered with the app's MarkdownRenderer first.
 */

interface Case {
  name: string
  doc: string
  docType: 'html' | 'markdown'
  quote: { exact: string; prefix: string; suffix: string }
  expected: { found: boolean; start?: number; end?: number }
}

const cases: Case[] = JSON.parse(
  readFileSync(resolve(__dirname, '../../../testdata/text-quote-cases.json'), 'utf-8')
)

const posted: Array<Record<string, unknown>> = []

function sendFromParent(data: unknown) {
  window.dispatchEvent(new MessageEvent('message', { data, source: window }))
}

/** The page's flattened text, as the bridge produces it for a selection of the whole body. */
async function flattenedText(): Promise<string> {
  posted.length = 0
  const range = document.createRange()
  range.selectNodeContents(document.body)
  window.getSelection()!.removeAllRanges()
  window.getSelection()!.addRange(range)
  document.dispatchEvent(new MouseEvent('mouseup', { bubbles: true }))
  const m = await vi.waitFor(() => {
    const found = posted.find((p) => p.type === 'selection')
    expect(found).toBeDefined()
    return found!
  })
  window.getSelection()!.removeAllRanges()
  return m.exact as string
}

async function bridgeFinds(c: Case): Promise<boolean> {
  posted.length = 0
  sendFromParent({
    type: 'anchors',
    anchors: [{ id: 'q', exact: c.quote.exact, prefix: c.quote.prefix, suffix: c.quote.suffix }],
  })
  const m = await vi.waitFor(() => {
    const found = posted.find((p) => p.type === 'anchored')
    expect(found).toBeDefined()
    return found!
  })
  return (m.results as Array<{ found: boolean }>)[0].found
}

function load(c: Case) {
  if (c.docType === 'html') {
    document.body.innerHTML = c.doc
  } else {
    const { container } = render(createElement(MarkdownRenderer, { content: c.doc }))
    const html = container.innerHTML
    document.body.innerHTML = html
  }
}

describe('shared text-quote cases', () => {
  beforeAll(() => {
    // jsdom has no layout: a range has a box unless its text sits under a hidden element.
    const zero = { top: 0, left: 0, right: 0, bottom: 0, width: 0, height: 0, x: 0, y: 0, toJSON: () => ({}) }
    Range.prototype.getBoundingClientRect = () => zero as DOMRect
    const box = { ...zero, right: 10, bottom: 10, width: 10, height: 10 }
    Range.prototype.getClientRects = function (this: Range) {
      const el = this.startContainer.nodeType === 1 ? (this.startContainer as Element) : this.startContainer.parentElement
      return (el?.closest('[hidden]') ? [] : [box]) as unknown as DOMRectList
    }
    window.addEventListener('message', (e: MessageEvent) => {
      const d = e.data as Record<string, unknown> | null
      if (d && d.source === 'conductor-bridge') posted.push(d)
    })
    new Function(bridgeSource)()
  })

  it('has the cases the contract calls for', () => {
    expect(cases.length).toBeGreaterThanOrEqual(10)
    expect(cases.some((c) => c.docType === 'markdown')).toBe(true)
  })

  it.each(cases.map((c) => [c.name, c] as const))('%s', async (_name, c) => {
    load(c)
    const text = await flattenedText()
    const hit = findQuote(text, c.quote)

    // Hidden text is part of the browser's flattened text (visibility is checked per match), so a case
    // that depends on it has no offsets to compare; for the rest the offsets must agree exactly.
    if (c.expected.start !== undefined) {
      expect(hit).toEqual({ start: c.expected.start, end: c.expected.end })
    }
    // Whether the quote is found at all, with visibility applied, is what the user sees.
    expect(await bridgeFinds(c)).toBe(c.expected.found)
  })
})
