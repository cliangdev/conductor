/**
 * Text-quote anchoring shared by every commentable viewer: flatten a rendered document to one
 * whitespace-collapsed string, build a quote from a span of it, and find a stored quote in it again.
 *
 * The HTML bridge is serialised with `Function.prototype.toString` and evaluated inside the sandboxed
 * frame, so every function here must stay self-contained: no imports, no closed-over module variables
 * and no helpers the build could inject (spread, classes, async). Constants live inside the functions.
 */

export interface QuoteParts {
  exact: string
  prefix: string
  suffix: string
}

/** One text node's place in the flattened text. */
export interface FlatNode {
  node: Text
  /** Flat index of each of the node's characters, -1 where whitespace collapsed away. */
  chars: number[]
  begin: number
  end: number
}

export interface FlatText {
  text: string
  nodes: FlatNode[]
  /** Per flat character: index into `nodes`, or -1 for a block-boundary space that belongs to no node. */
  fn: number[]
  /** Per flat character: offset inside that node. */
  fo: number[]
}

/**
 * The quote for `text[start, end)`: the selected text with whitespace collapsed and trimmed, plus up to
 * 32 characters of context either side. `text` is the flattened, whitespace-collapsed page text, so the
 * quote can be found again with `findQuote`.
 */
export function buildQuote(text: string, start: number, end: number): QuoteParts {
  let s = Math.max(0, start)
  let e = Math.min(text.length, end)
  while (s < e && /\s/.test(text.charAt(s))) s++
  while (e > s && /\s/.test(text.charAt(e - 1))) e--
  // The API caps a quote at 2000 characters.
  if (e - s > 2000) {
    e = s + 2000
    while (e > s && /\s/.test(text.charAt(e - 1))) e--
  }
  return {
    exact: text.slice(s, e).replace(/\s+/g, ' '),
    prefix: text.slice(Math.max(0, s - 32), s).replace(/\s+/g, ' '),
    suffix: text.slice(e, e + 32).replace(/\s+/g, ' '),
  }
}

/**
 * Where a quote sits in `text`. Among all exact matches, the one whose surrounding text agrees most with
 * the stored prefix and suffix wins; with no agreement anywhere, the first match. Null when the exact
 * text is not there at all, or when `accept` (given each match's start and end) rejects every match.
 */
export function findQuote(
  text: string,
  quote: { exact: string; prefix?: string; suffix?: string },
  accept?: (start: number, end: number) => boolean
): { start: number; end: number } | null {
  const exact = (quote.exact || '').replace(/\s+/g, ' ').trim()
  if (!exact) return null
  const prefix = (quote.prefix || '').replace(/\s+/g, ' ')
  const suffix = (quote.suffix || '').replace(/\s+/g, ' ')
  let best = -1
  let bestScore = -1
  let from = 0
  for (;;) {
    const i = text.indexOf(exact, from)
    if (i < 0) break
    from = i + 1
    // A match the caller rejects (text that is not visible) is not a candidate at all.
    if (accept && !accept(i, i + exact.length)) continue
    let score = 0
    const before = text.slice(Math.max(0, i - prefix.length), i)
    for (let k = 1; k <= before.length && k <= prefix.length; k++) {
      if (before.charAt(before.length - k) === prefix.charAt(prefix.length - k)) score++
      else break
    }
    const after = text.slice(i + exact.length, i + exact.length + suffix.length)
    for (let k = 0; k < after.length && k < suffix.length; k++) {
      if (after.charAt(k) === suffix.charAt(k)) score++
      else break
    }
    if (score > bestScore) {
      best = i
      bestScore = score
    }
  }
  return best < 0 ? null : { start: best, end: best + exact.length }
}

/**
 * The text under `root` as one string: whitespace runs collapsed to one space, and a space at every
 * block boundary (and `br`) so "<h2>Goals</h2><p>Ship it</p>" reads "Goals Ship it". Those spaces
 * belong to no node; the map back to nodes (`fn`/`fo`, one entry per character) holds -1 for them.
 * Each text node's `chars` lists the flat index of each of its characters, -1 where collapsed away.
 */
export function flattenText(root: Element): FlatText {
  const SKIP = 'script,style,noscript,template'
  const BLOCKS =
    'address,article,aside,blockquote,dd,div,dl,dt,figcaption,figure,footer,form,h1,h2,h3,h4,h5,h6,' +
    'header,hr,li,main,nav,ol,p,pre,section,table,tbody,td,th,thead,tr,ul'
  const nodes: FlatNode[] = []
  const fn: number[] = []
  const fo: number[] = []
  const parts: string[] = []
  let len = 0
  let lastSpace = true
  let pendingBreak = false
  let prevBlock: Element | null = null
  // 5 = NodeFilter.SHOW_ELEMENT | NodeFilter.SHOW_TEXT, spelled out so no global is needed.
  const walker = root.ownerDocument.createTreeWalker(root, 5)
  let n: Node | null
  while ((n = walker.nextNode())) {
    if (n.nodeType === 1) {
      if ((n as Element).tagName === 'BR') pendingBreak = true
      continue
    }
    const p = n.parentElement
    if (!p || p.closest(SKIP)) continue
    const block = p.closest(BLOCKS) || root
    if (prevBlock && block !== prevBlock) pendingBreak = true
    prevBlock = block
    if (pendingBreak && !lastSpace) {
      parts.push(' ')
      fn.push(-1)
      fo.push(-1)
      len++
      lastSpace = true
    }
    pendingBreak = false
    const s = n.nodeValue || ''
    const chars: number[] = []
    const info: FlatNode = { node: n as Text, chars: chars, begin: len, end: 0 }
    for (let i = 0; i < s.length; i++) {
      let c = s.charAt(i)
      if (/\s/.test(c)) {
        if (lastSpace) {
          chars.push(-1)
          continue
        }
        c = ' '
        lastSpace = true
      } else {
        lastSpace = false
      }
      chars.push(len)
      fn.push(nodes.length)
      fo.push(i)
      parts.push(c)
      len++
    }
    info.end = len
    nodes.push(info)
  }
  return { text: parts.join(''), nodes: nodes, fn: fn, fo: fo }
}

/** The DOM range covering `flat.text[start, end)`. Both ends must fall on characters that belong to a node. */
export function rangeFor(flat: FlatText, start: number, end: number): Range {
  const first = flat.nodes[flat.fn[start]].node
  const r = first.ownerDocument.createRange()
  r.setStart(first, flat.fo[start])
  r.setEnd(flat.nodes[flat.fn[end - 1]].node, flat.fo[end - 1] + 1)
  return r
}
