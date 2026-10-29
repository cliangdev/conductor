import { describe, it, expect, beforeAll, beforeEach, afterEach, vi } from 'vitest'
import { buildQuote, findQuote, bridgeSource } from './htmlBridge'

describe('buildQuote', () => {
  const text = 'The quick brown fox jumps over the lazy dog. Pack my box with five dozen liquor jugs.'

  it('returns the exact text with 32 characters of context either side', () => {
    const start = text.indexOf('jumps')
    const q = buildQuote(text, start, start + 'jumps over'.length)
    expect(q.exact).toBe('jumps over')
    expect(q.prefix).toBe('The quick brown fox '.slice(-32))
    expect(q.suffix).toBe(' the lazy dog. Pack my box with ')
    expect(q.suffix).toHaveLength(32)
  })

  it('keeps short context at the document edges', () => {
    const q = buildQuote('Hello world', 0, 5)
    expect(q).toEqual({ exact: 'Hello', prefix: '', suffix: ' world' })
  })

  it('collapses whitespace and trims the selection', () => {
    const q = buildQuote('a  b\n\n c    d   e', 3, 14)
    expect(q.exact).toBe('b c d')
    expect(q.prefix).toBe('a ')
    expect(q.suffix).toBe(' e')
  })

  it('reads a quote across a block boundary with one space', () => {
    const text = 'Goals Ship it today'
    expect(buildQuote(text, 0, 'Goals Ship it'.length).exact).toBe('Goals Ship it')
  })

  it('caps the quote at 2000 characters', () => {
    const long = 'word '.repeat(1000)
    const q = buildQuote(long, 0, long.length)
    expect(q.exact.length).toBeLessThanOrEqual(2000)
    expect(q.exact.length).toBeGreaterThan(1990)
  })
})

describe('findQuote', () => {
  it('finds a unique quote', () => {
    const text = 'alpha beta gamma'
    expect(findQuote(text, { exact: 'beta' })).toEqual({ start: 6, end: 10 })
  })

  it('decides between repeated text by the prefix and suffix', () => {
    const text = 'Total: 20 of 26 users. Later: 20 of 26 signups. Last: 20 of 26 posts.'
    const second = text.indexOf('20 of 26', 20)
    const q = buildQuote(text, second, second + 8)
    expect(findQuote(text, q)).toEqual({ start: second, end: second + 8 })
    const third = text.lastIndexOf('20 of 26')
    expect(findQuote(text, buildQuote(text, third, third + 8))).toEqual({ start: third, end: third + 8 })
  })

  it('weighs the suffix when the prefix is the same', () => {
    const text = 'see item one. see item two.'
    const q = { exact: 'item', prefix: 'see ', suffix: ' two.' }
    expect(findQuote(text, q)).toEqual({ start: text.indexOf('item', 10), end: text.indexOf('item', 10) + 4 })
  })

  it('takes the first exact match when neither side agrees', () => {
    const text = 'foo bar foo baz foo'
    expect(findQuote(text, { exact: 'foo', prefix: 'zzz', suffix: 'qqq' })).toEqual({ start: 0, end: 3 })
  })

  it('matches across differently spaced input', () => {
    expect(findQuote('one two three', { exact: 'two\n  three' })).toEqual({ start: 4, end: 13 })
  })

  it('skips matches the caller rejects, and is null when every match is rejected', () => {
    const text = 'foo bar foo'
    expect(findQuote(text, { exact: 'foo' }, (start) => start > 0)).toEqual({ start: 8, end: 11 })
    expect(findQuote(text, { exact: 'foo' }, () => false)).toBeNull()
  })

  it('returns null when the text is not there', () => {
    expect(findQuote('alpha beta', { exact: 'delta', prefix: 'alpha ' })).toBeNull()
    expect(findQuote('alpha beta', { exact: '   ' })).toBeNull()
  })
})

describe('bridgeSource', () => {
  it('is self-contained: no import/export, no stray helper references', () => {
    expect(bridgeSource).not.toMatch(/\bimport\b\s*[\w{*(]/)
    expect(bridgeSource).not.toMatch(/\bexport\b/)
    expect(bridgeSource).not.toMatch(/__name|_object_spread|_to_consumable|_class_call_check/)
    expect(bridgeSource).not.toMatch(/<\/script/i)
  })

  it('parses and runs in a bare function scope', () => {
    // A fresh Function has no access to this module's scope, so a closed-over helper would throw here.
    expect(() => new Function(bridgeSource)).not.toThrow()
  })

  describe('inside a document', () => {
    const posted: Array<Record<string, unknown>> = []
    const onMessage = (e: MessageEvent) => {
      const d = e.data as Record<string, unknown> | null
      if (d && d.source === 'conductor-bridge') posted.push(d)
    }

    beforeAll(() => {
      // jsdom has no layout: give Range the geometry methods the bridge reads.
      const zero = { top: 0, left: 0, right: 0, bottom: 0, width: 0, height: 0, x: 0, y: 0, toJSON: () => ({}) }
      Range.prototype.getBoundingClientRect = () => zero as DOMRect
      // A range has a box unless its text sits under an element marked data-hidden (display:none).
      const box = { ...zero, right: 10, bottom: 10, width: 10, height: 10 }
      Range.prototype.getClientRects = function (this: Range) {
        const el = this.startContainer.nodeType === 1 ? (this.startContainer as Element) : this.startContainer.parentElement
        return (el?.closest('[data-hidden]') ? [] : [box]) as unknown as DOMRectList
      }
      // jsdom's top-level window is its own parent, so the bridge posts to itself and we listen here.
      new Function(bridgeSource)()
    })

    beforeEach(() => {
      posted.length = 0
      window.addEventListener('message', onMessage)
      document.body.innerHTML = `
        <h1>Report</h1>
        <section id="funnel"><h2>The funnel</h2>
          <p>Reached <b>20</b>  of 26 users
            in total.</p><script>var hidden = "do not index"</script>
          <p>Another line.</p>
        </section>`
    })

    afterEach(() => {
      window.removeEventListener('message', onMessage)
      window.getSelection()?.removeAllRanges()
    })

    const waitForPost = (type: string) =>
      vi.waitFor(() => {
        const m = posted.find((p) => p.type === type)
        expect(m).toBeDefined()
        return m!
      })

    const sendFromParent = (data: unknown) =>
      window.dispatchEvent(new MessageEvent('message', { data, source: window }))

    it('reports a selection with its quote and section', async () => {
      const b = document.querySelector('#funnel p b')!.firstChild as Text
      const p2 = document.querySelectorAll('#funnel p')[0].lastChild as Text
      const range = document.createRange()
      range.setStart(b, 0)
      range.setEnd(p2, p2.data.indexOf('.'))
      window.getSelection()!.addRange(range)
      document.dispatchEvent(new MouseEvent('mouseup', { bubbles: true }))

      const m = await waitForPost('selection')
      expect(m.exact).toBe('20 of 26 users in total')
      expect(m.prefix).toBe('Report The funnel Reached ')
      expect(m.suffix).toBe('. Another line. ')
      expect(m.sectionId).toBe('funnel')
      expect(m.sectionTitle).toBe('The funnel')
      expect(m.rect).toBeTruthy()
    })

    it('reports when the selection is cleared', async () => {
      const t = document.querySelector('h1')!.firstChild as Text
      const range = document.createRange()
      range.setStart(t, 0)
      range.setEnd(t, 3)
      window.getSelection()!.addRange(range)
      document.dispatchEvent(new MouseEvent('mouseup', { bubbles: true }))
      await waitForPost('selection')

      window.getSelection()!.removeAllRanges()
      document.dispatchEvent(new Event('selectionchange'))
      await waitForPost('selection-cleared')
    })

    it('resolves anchors, skipping script text, and ignores messages from elsewhere', async () => {
      // Not from window.parent: ignored.
      window.dispatchEvent(
        new MessageEvent('message', { data: { type: 'anchors', anchors: [{ id: 'x', exact: 'Report' }] }, source: null })
      )
      expect(posted.find((p) => p.type === 'anchored')).toBeUndefined()

      sendFromParent({
        type: 'anchors',
        anchors: [
          { id: 'a', exact: '20 of 26 users', prefix: 'Reached ', suffix: ' in total.' },
          { id: 'b', exact: 'do not index' },
          { id: 'c', exact: 'Another line.' },
        ],
      })
      const m = await waitForPost('anchored')
      const results = m.results as Array<{ id: string; found: boolean; top: number }>
      expect(results.map((r) => [r.id, r.found])).toEqual([
        ['a', true],
        ['b', false],
        ['c', true],
      ])
    })

    it('puts a space between adjacent blocks and at <br>, but not between inline elements', async () => {
      document.body.innerHTML =
        '<h2>Goals</h2><p>Ship it</p><ul><li>one</li><li>two</li></ul><p>line<br>break and <b>bold</b>text</p>'
      sendFromParent({
        type: 'anchors',
        anchors: [
          { id: 'blocks', exact: 'Goals Ship it' },
          { id: 'glued', exact: 'GoalsShip it' },
          { id: 'items', exact: 'one two' },
          { id: 'br', exact: 'line break' },
          { id: 'inline', exact: 'bold' + 'text' },
        ],
      })
      const m = await waitForPost('anchored')
      const results = m.results as Array<{ id: string; found: boolean }>
      expect(Object.fromEntries(results.map((r) => [r.id, r.found]))).toEqual({
        blocks: true,
        glued: false,
        items: true,
        br: true,
        inline: true,
      })
    })

    it('reports a selection across blocks with the block space in the quote', async () => {
      document.body.innerHTML = '<h2>Goals</h2><p>Ship it today</p>'
      const a = document.querySelector('h2')!.firstChild as Text
      const b = document.querySelector('p')!.firstChild as Text
      const range = document.createRange()
      range.setStart(a, 0)
      range.setEnd(b, 'Ship it'.length)
      window.getSelection()!.addRange(range)
      document.dispatchEvent(new MouseEvent('mouseup', { bubbles: true }))
      const m = await waitForPost('selection')
      expect(m.exact).toBe('Goals Ship it')
      expect(m.suffix).toBe(' today')
    })

    it('anchors to the visible copy of a quote, and reports a quote that only exists hidden as not found', async () => {
      document.body.innerHTML =
        '<div data-hidden><p>Revenue is up</p></div><p>Revenue is up</p><div data-hidden><p>Only hidden</p></div>'
      sendFromParent({
        type: 'anchors',
        anchors: [
          { id: 'both', exact: 'Revenue is up' },
          { id: 'hiddenOnly', exact: 'Only hidden' },
        ],
      })
      const m = await waitForPost('anchored')
      const results = m.results as Array<{ id: string; found: boolean }>
      expect(results.map((r) => [r.id, r.found])).toEqual([
        ['both', true],
        ['hiddenOnly', false],
      ])
    })

    it('takes over every link click: hash links scroll, web links go to the app, nothing navigates', async () => {
      document.body.innerHTML = `
        <h2 id="goals">Goals</h2>
        <a id="hash" href="#goals">jump</a>
        <a id="web" href="https://example.com/x" target="_blank">web</a>
        <a id="mail" href="mailto:a@b.co">mail</a>
        <a id="rel" href="other.html">rel</a>
        <a id="js" href="javascript:alert(1)">js</a>`
      const scrollIntoView = vi.fn()
      Element.prototype.scrollIntoView = scrollIntoView

      const click = (id: string) => {
        const ev = new MouseEvent('click', { bubbles: true, cancelable: true })
        document.getElementById(id)!.dispatchEvent(ev)
        return ev.defaultPrevented
      }

      expect(click('hash')).toBe(true)
      expect(scrollIntoView).toHaveBeenCalled()

      expect(click('web')).toBe(true)
      expect(click('mail')).toBe(true)
      expect(click('rel')).toBe(true)
      expect(click('js')).toBe(true)
      await vi.waitFor(() => {
        const links = posted.filter((p) => p.type === 'openLink').map((p) => p.href)
        expect(links).toEqual(['https://example.com/x', 'mailto:a@b.co'])
      })
    })

    it('re-resolves when scripts add text later', async () => {
      sendFromParent({ type: 'anchors', anchors: [{ id: 'late', exact: 'Rendered later' }] })
      const first = await waitForPost('anchored')
      expect((first.results as Array<{ found: boolean }>)[0].found).toBe(false)

      posted.length = 0
      const p = document.createElement('p')
      p.textContent = 'Rendered later by a script'
      document.body.appendChild(p)
      const second = await vi.waitFor(
        () => {
          const m = posted.find((x) => x.type === 'anchored')
          expect(m).toBeDefined()
          return m!
        },
        { timeout: 2000 }
      )
      expect((second.results as Array<{ found: boolean }>)[0].found).toBe(true)
    })
  })
})
