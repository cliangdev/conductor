import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, act } from '@testing-library/react'
import { useEffect, useMemo, useRef } from 'react'
import {
  anchorFromRange,
  useDocumentAnchors,
  HIGHLIGHT_ACTIVE,
  HIGHLIGHT_BASE,
  type AnchorInput,
  type DocumentAnchors,
} from './useDocumentAnchors'

const CONTENT = '# Goals\n\nShip the thing by Friday.\n\nSecond paragraph here.\n'

const holder = { current: null as unknown as DocumentAnchors }
let highlights: Map<string, { ranges: Range[] }>

class FakeHighlight {
  ranges: Range[]
  constructor(...ranges: Range[]) {
    this.ranges = ranges
  }
}

function Harness({
  threads,
  activeId = null,
  html,
  content = CONTENT,
  onClickAnchor,
}: {
  threads: AnchorInput[]
  activeId?: string | null
  html?: string
  content?: string
  onClickAnchor?: (id: string) => void
}) {
  const ref = useRef<HTMLDivElement>(null)
  const result = useDocumentAnchors(ref, threads, { activeId, content, onClickAnchor })
  useEffect(() => {
    holder.current = result
  })
  const inner = useMemo(
    () => ({
      __html:
        html ??
        '<h1 id="goals" data-line-start="1" data-line-end="1">Goals</h1>' +
          '<p data-line-start="3" data-line-end="3">Ship the thing by Friday.</p>' +
          '<p data-line-start="5" data-line-end="5">Second paragraph here.</p>',
    }),
    [html]
  )
  return <div ref={ref} data-testid="doc" dangerouslySetInnerHTML={inner} />
}

function installHighlightApi() {
  highlights = new Map()
  vi.stubGlobal('Highlight', FakeHighlight)
  vi.stubGlobal('CSS', {
    highlights: {
      set: (name: string, h: { ranges: Range[] }) => highlights.set(name, h),
      delete: (name: string) => highlights.delete(name),
    },
  })
}

function paintedText(name: string): string[] {
  return (highlights.get(name)?.ranges ?? []).map((r) => r.toString())
}

describe('useDocumentAnchors', () => {
  beforeEach(() => {
    installHighlightApi()
  })
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('finds a quote, paints it, and reports it as placed', () => {
    render(<Harness threads={[{ id: 'c1', anchor: { exact: 'Ship the thing' } }]} />)
    expect(holder.current.placements.c1.found).toBe(true)
    expect(paintedText(HIGHLIGHT_BASE)).toEqual(['Ship the thing'])
  })

  it('paints the active thread with the active highlight only', () => {
    render(
      <Harness
        activeId="c2"
        threads={[
          { id: 'c1', anchor: { exact: 'Ship the thing' } },
          { id: 'c2', anchor: { exact: 'Second paragraph' } },
        ]}
      />
    )
    expect(paintedText(HIGHLIGHT_BASE)).toEqual(['Ship the thing'])
    expect(paintedText(HIGHLIGHT_ACTIVE)).toEqual(['Second paragraph'])
  })

  it('uses prefix and suffix to pick between repeated text', () => {
    render(
      <Harness
        html="<p>a cat and a dog</p><p>a cat and a bird</p>"
        threads={[{ id: 'c1', anchor: { exact: 'a cat', suffix: ' and a bird' } }]}
      />
    )
    const [range] = highlights.get(HIGHLIGHT_BASE)!.ranges
    expect(range.startContainer.parentElement?.textContent).toBe('a cat and a bird')
  })

  it('reports a quote that is not in the page as not found (it goes to Whole document)', () => {
    render(<Harness threads={[{ id: 'c1', anchor: { exact: 'text that was deleted' } }]} />)
    expect(holder.current.placements.c1).toEqual({ found: false, top: 0 })
    expect(highlights.has(HIGHLIGHT_BASE)).toBe(false)
  })

  it('highlights the whole block covering a legacy line comment', () => {
    render(<Harness threads={[{ id: 'l1', lineNumber: 3 }]} />)
    expect(holder.current.placements.l1.found).toBe(true)
    expect(paintedText(HIGHLIGHT_BASE)).toEqual(['Ship the thing by Friday.'])
  })

  it('snaps a legacy comment on a blank line to the block before it', () => {
    render(<Harness threads={[{ id: 'l1', lineNumber: 4 }]} />)
    expect(paintedText(HIGHLIGHT_BASE)).toEqual(['Ship the thing by Friday.'])
  })

  it('does not find a legacy line beyond the end of the document', () => {
    render(<Harness threads={[{ id: 'l1', lineNumber: 40 }]} />)
    expect(holder.current.placements.l1).toEqual({ found: false, top: 0 })
  })

  it('leaves a whole-document thread (no quote, no line) out of the placements', () => {
    render(<Harness threads={[{ id: 'd1' }]} />)
    expect(holder.current.placements.d1).toBeUndefined()
  })

  it('still places threads when the Highlight API is missing, and paints nothing', () => {
    vi.unstubAllGlobals()
    vi.stubGlobal('CSS', {})
    render(<Harness threads={[{ id: 'c1', anchor: { exact: 'Ship the thing' } }]} />)
    expect(holder.current.highlightSupported).toBe(false)
    expect(holder.current.placements.c1.found).toBe(true)
    expect(highlights.size).toBe(0)
  })

  it('re-finds quotes when the content changes', () => {
    const threads = [{ id: 'c1', anchor: { exact: 'brand new words' } }]
    const { rerender } = render(<Harness threads={threads} />)
    expect(holder.current.placements.c1.found).toBe(false)
    rerender(<Harness threads={threads} html="<p>some brand new words</p>" content="some brand new words" />)
    expect(holder.current.placements.c1.found).toBe(true)
  })

  it('reports the thread whose highlight is clicked', () => {
    const onClickAnchor = vi.fn()
    render(
      <Harness
        onClickAnchor={onClickAnchor}
        threads={[
          { id: 'c1', anchor: { exact: 'Ship the thing' } },
          { id: 'c2', anchor: { exact: 'Second paragraph' } },
        ]}
      />
    )
    const para = screen.getByTestId('doc').querySelectorAll('p')[1]
    const text = para.firstChild as Text
    ;(document as unknown as { caretRangeFromPoint: unknown }).caretRangeFromPoint = () => {
      const r = document.createRange()
      r.setStart(text, 3)
      r.collapse(true)
      return r
    }
    fireEvent.click(para)
    expect(onClickAnchor).toHaveBeenCalledWith('c2')

    // A click outside every highlight reports nothing.
    onClickAnchor.mockClear()
    ;(document as unknown as { caretRangeFromPoint: unknown }).caretRangeFromPoint = () => {
      const r = document.createRange()
      r.setStart(text, text.length)
      r.collapse(true)
      return r
    }
    fireEvent.click(para)
    expect(onClickAnchor).not.toHaveBeenCalled()
    delete (document as unknown as { caretRangeFromPoint?: unknown }).caretRangeFromPoint
  })

  it('flashes the thread on scrollToAnchor and clears the flash after a moment', () => {
    vi.useFakeTimers()
    render(<Harness threads={[{ id: 'c1', anchor: { exact: 'Ship the thing' } }]} />)
    act(() => holder.current.scrollToAnchor('c1'))
    expect(paintedText('conductor-comment-flash')).toEqual(['Ship the thing'])
    act(() => {
      vi.advanceTimersByTime(2000)
    })
    expect(highlights.has('conductor-comment-flash')).toBe(false)
    vi.useRealTimers()
  })

  it('removes its highlights on unmount', () => {
    const { unmount } = render(<Harness threads={[{ id: 'c1', anchor: { exact: 'Ship the thing' } }]} />)
    expect(highlights.has(HIGHLIGHT_BASE)).toBe(true)
    unmount()
    expect(highlights.has(HIGHLIGHT_BASE)).toBe(false)
  })
})

describe('anchorFromRange', () => {
  it('builds a quote with context and the nearest heading', () => {
    const root = document.createElement('div')
    root.innerHTML = '<h1 id="goals">Goals</h1><p>Ship the thing by Friday.</p>'
    document.body.appendChild(root)
    const text = root.querySelector('p')!.firstChild as Text
    const range = document.createRange()
    range.setStart(text, 5)
    range.setEnd(text, 14)
    const anchor = anchorFromRange(root, range)
    expect(anchor).toMatchObject({ exact: 'the thing', prefix: 'Goals Ship ', suffix: ' by Friday.' })
    root.remove()
  })

  it('records the section when the selection is inside a heading', () => {
    const root = document.createElement('div')
    root.innerHTML = '<h2 id="scope">Scope of work</h2>'
    document.body.appendChild(root)
    const text = root.querySelector('h2')!.firstChild as Text
    const range = document.createRange()
    range.setStart(text, 0)
    range.setEnd(text, 5)
    expect(anchorFromRange(root, range)).toMatchObject({
      exact: 'Scope',
      sectionId: 'scope',
      sectionTitle: 'Scope of work',
    })
    root.remove()
  })

  it('returns null for a collapsed range or one outside the container', () => {
    const root = document.createElement('div')
    root.innerHTML = '<p>hello</p>'
    document.body.appendChild(root)
    const other = document.createElement('p')
    other.textContent = 'elsewhere'
    document.body.appendChild(other)
    const collapsed = document.createRange()
    collapsed.setStart(root.firstChild!, 0)
    expect(anchorFromRange(root, collapsed)).toBeNull()
    const outside = document.createRange()
    outside.selectNodeContents(other)
    expect(anchorFromRange(root, outside)).toBeNull()
    root.remove()
    other.remove()
  })
})
