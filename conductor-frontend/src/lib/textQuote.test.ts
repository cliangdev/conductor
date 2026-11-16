import { describe, it, expect } from 'vitest'
import { buildQuote, findQuote, flattenText, rangeFor } from './textQuote'

function dom(html: string): HTMLElement {
  const root = document.createElement('div')
  root.innerHTML = html
  document.body.appendChild(root)
  return root
}

describe('flattenText', () => {
  it('collapses whitespace and puts one space at each block boundary', () => {
    const root = dom('<h2>Goals</h2><p>Ship   it\n today</p><ul><li>one</li><li>two</li></ul>')
    expect(flattenText(root).text).toBe('Goals Ship it today one two')
  })

  it('treats br as a boundary and skips script, style and template text', () => {
    const root = dom('<p>a<br>b</p><script>var x = 1</script><style>p{}</style><template>hidden</template>')
    expect(flattenText(root).text).toBe('a b')
  })

  it('maps every flat character back to its node and offset', () => {
    const root = dom('<p>Hello <b>big</b> world</p>')
    const flat = flattenText(root)
    expect(flat.text).toBe('Hello big world')
    const i = flat.text.indexOf('big')
    expect(flat.nodes[flat.fn[i]].node.nodeValue).toBe('big')
    expect(flat.fo[i]).toBe(0)
    expect(flat.fn.length).toBe(flat.text.length)
  })
})

describe('rangeFor', () => {
  it('covers exactly the flat span, across elements', () => {
    const root = dom('<p>Hello <b>big</b> world</p><p>next</p>')
    const flat = flattenText(root)
    const start = flat.text.indexOf('lo big')
    expect(rangeFor(flat, start, start + 'lo big wor'.length).toString()).toBe('lo big wor')
    const q = flat.text.indexOf('world next')
    expect(rangeFor(flat, q, q + 'world'.length).toString()).toBe('world')
  })

  it('round-trips a quote built from a span', () => {
    const root = dom('<h2>Funnel</h2><p>Reached 20 of 26 users</p>')
    const flat = flattenText(root)
    const s = flat.text.indexOf('20 of 26')
    const quote = buildQuote(flat.text, s, s + 8)
    const hit = findQuote(flat.text, quote)!
    expect(rangeFor(flat, hit.start, hit.end).toString()).toBe('20 of 26')
  })
})

describe('buildQuote', () => {
  const text ='The quick brown fox jumps over the lazy dog. Pack my box with five dozen liquor jugs.'

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
