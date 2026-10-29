/* eslint-disable @typescript-eslint/no-explicit-any */
/**
 * The comment bridge: a script that is added to an HTML document when it is rendered (never stored)
 * and runs inside the sandboxed frame, after the page's own scripts. It reports text selections to the
 * app, finds and highlights the text existing comments are anchored to, relays clicks on those
 * highlights, and takes over link clicks (the frame is sandboxed with `allow-scripts` only, so it cannot
 * open windows or navigate) — all through `postMessage`. The wire format is in `htmlBridgeProtocol.ts`.
 *
 * Quotes are matched against the page's text with whitespace collapsed and one space at every block
 * boundary, and only against text that is visible (it has a box and is not `visibility:hidden`).
 *
 * `bridgeSource` is built with `Function.prototype.toString`, so `bridgeMain`, `buildQuote` and
 * `findQuote` must stay self-contained: no imports, no closed-over variables, no helpers the build
 * could inject (spread, classes, async). `htmlBridge.test.ts` evaluates the result in jsdom.
 */

export interface QuoteParts {
  exact: string
  prefix: string
  suffix: string
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

/** Runs inside the frame. Receives the two quote helpers as arguments so it closes over nothing. */
function bridgeMain(buildQuote: any, findQuote: any): void {
  const SKIP = 'script,style,noscript,template'
  const BLOCKS =
    'address,article,aside,blockquote,dd,div,dl,dt,figcaption,figure,footer,form,h1,h2,h3,h4,h5,h6,' +
    'header,hr,li,main,nav,ol,p,pre,section,table,tbody,td,th,thead,tr,ul'
  const w: any = window
  let anchors: any[] = []
  let ranges: Record<string, Range> = {}
  let lastResults = ''
  let hasSelection = false
  let activeTimer: any = null

  function post(msg: any): void {
    msg.source = 'conductor-bridge'
    try {
      w.parent.postMessage(msg, '*')
    } catch {
      // The parent is gone; nothing to tell.
    }
  }

  // Highlight colours live in the frame's own document, so they cannot use the app's theme tokens.
  const style = document.createElement('style')
  style.textContent =
    '::highlight(conductor-comment){background-color:rgba(255,214,10,.38);color:inherit}' +
    '::highlight(conductor-comment-active){background-color:rgba(255,159,10,.6);color:inherit}'
  ;(document.head || document.documentElement).appendChild(style)

  /**
   * The text under body as one string: whitespace runs collapsed to one space, and a space at every
   * block boundary (and `br`) so "<h2>Goals</h2><p>Ship it</p>" reads "Goals Ship it". Those spaces
   * belong to no node; the map back to nodes (`fn`/`fo`, one entry per character) holds -1 for them.
   * Each text node's `chars` lists the flat index of each of its characters, -1 where collapsed away.
   */
  function flatten(): any {
    const root = document.body || document.documentElement
    const nodes: any[] = []
    const fn: number[] = []
    const fo: number[] = []
    const parts: string[] = []
    let len = 0
    let lastSpace = true
    let pendingBreak = false
    let prevBlock: Element | null = null
    const walker = document.createTreeWalker(root, NodeFilter.SHOW_ELEMENT | NodeFilter.SHOW_TEXT)
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
      const info = { node: n, chars: chars, begin: len, end: 0 }
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

  /** Whether the text of `r` is actually shown: it has a box, and is not `visibility:hidden`. */
  function isVisible(r: Range): boolean {
    const rects = r.getClientRects()
    let shown = false
    for (let i = 0; i < rects.length; i++) {
      if (rects[i].width > 0 || rects[i].height > 0) {
        shown = true
        break
      }
    }
    if (!shown) return false
    const el = r.startContainer.nodeType === 1 ? (r.startContainer as Element) : r.startContainer.parentElement
    return !el || w.getComputedStyle(el).visibility !== 'hidden'
  }

  function rangeFor(flat: any, start: number, end: number): Range {
    const r = document.createRange()
    r.setStart(flat.nodes[flat.fn[start]].node, flat.fo[start])
    r.setEnd(flat.nodes[flat.fn[end - 1]].node, flat.fo[end - 1] + 1)
    return r
  }

  function paint(): void {
    const reg = w.CSS && w.CSS.highlights
    if (!reg || typeof w.Highlight === 'undefined') return
    const ids = Object.keys(ranges)
    if (ids.length === 0) {
      reg.delete('conductor-comment')
      return
    }
    const h = new w.Highlight()
    for (let i = 0; i < ids.length; i++) h.add(ranges[ids[i]])
    reg.set('conductor-comment', h)
  }

  function resolveAll(force: boolean): void {
    const flat = flatten()
    const found: Record<string, Range> = {}
    const results: any[] = []
    for (let i = 0; i < anchors.length; i++) {
      const a = anchors[i]
      // Only visible text counts: a hidden copy of the quote (a collapsed panel, a print-only block)
      // must not take the comment, so a quote that is only there hidden is reported not found.
      const hit = findQuote(flat.text, a, function (s: number, e: number) {
        return isVisible(rangeFor(flat, s, e))
      })
      if (hit) {
        const r = rangeFor(flat, hit.start, hit.end)
        found[a.id] = r
        results.push({
          id: a.id,
          found: true,
          top: Math.round(r.getBoundingClientRect().top + (w.scrollY || 0)),
        })
      } else {
        results.push({ id: a.id, found: false, top: 0 })
      }
    }
    ranges = found
    paint()
    const serialized = JSON.stringify(results)
    if (force || serialized !== lastResults) {
      lastResults = serialized
      post({ type: 'anchored', results: results })
    }
  }

  function clearSelection(): void {
    if (!hasSelection) return
    hasSelection = false
    post({ type: 'selection-cleared' })
  }

  function collapse(t: string): string {
    return t.replace(/\s+/g, ' ').trim()
  }

  function reportSelection(): void {
    const sel = w.getSelection()
    if (!sel || sel.rangeCount === 0 || sel.isCollapsed) {
      clearSelection()
      return
    }
    const r: Range = sel.getRangeAt(0)
    const flat = flatten()
    let s = -1
    let e = -1
    for (let i = 0; i < flat.nodes.length; i++) {
      const info = flat.nodes[i]
      if (!r.intersectsNode(info.node)) continue
      const so = r.startContainer === info.node ? r.startOffset : 0
      const eo = r.endContainer === info.node ? r.endOffset : info.chars.length
      if (s < 0) {
        let v = -1
        for (let j = so; j < info.chars.length; j++) {
          if (info.chars[j] >= 0) {
            v = info.chars[j]
            break
          }
        }
        s = v >= 0 ? v : info.end
      }
      for (let j = eo - 1; j >= 0; j--) {
        if (info.chars[j] >= 0) {
          e = info.chars[j] + 1
          break
        }
      }
    }
    if (s < 0 || e <= s) {
      clearSelection()
      return
    }
    const q = buildQuote(flat.text, s, e)
    if (!q.exact) {
      clearSelection()
      return
    }
    const c: Node = r.commonAncestorContainer
    const el: Element | null = c.nodeType === 1 ? (c as Element) : c.parentElement
    const sec = el ? el.closest('[id]') : null
    let sectionId: string | undefined
    let sectionTitle: string | undefined
    if (sec && sec.id) {
      sectionId = sec.id.slice(0, 255)
      const heading = /^H[1-6]$/.test(sec.tagName) ? sec : sec.querySelector('h1,h2,h3,h4,h5,h6')
      const title = heading ? collapse(heading.textContent || '') : ''
      if (title) sectionTitle = title.slice(0, 500)
    }
    const b = r.getBoundingClientRect()
    hasSelection = true
    post({
      type: 'selection',
      exact: q.exact,
      prefix: q.prefix,
      suffix: q.suffix,
      sectionId: sectionId,
      sectionTitle: sectionTitle,
      rect: { top: b.top, left: b.left, right: b.right, bottom: b.bottom, width: b.width, height: b.height },
    })
  }

  function hitTest(x: number, y: number): string | null {
    let bestId: string | null = null
    let bestArea = Infinity
    const ids = Object.keys(ranges)
    for (let i = 0; i < ids.length; i++) {
      const rects = ranges[ids[i]].getClientRects()
      for (let j = 0; j < rects.length; j++) {
        const rc = rects[j]
        if (x >= rc.left && x <= rc.right && y >= rc.top && y <= rc.bottom) {
          const area = rc.width * rc.height
          if (area < bestArea) {
            bestArea = area
            bestId = ids[i]
          }
        }
      }
    }
    return bestId
  }

  function flash(id: string): void {
    const reg = w.CSS && w.CSS.highlights
    if (!reg || typeof w.Highlight === 'undefined' || !ranges[id]) return
    const h = new w.Highlight(ranges[id])
    h.priority = 1
    reg.set('conductor-comment-active', h)
    if (activeTimer) clearTimeout(activeTimer)
    activeTimer = setTimeout(function () {
      reg.delete('conductor-comment-active')
    }, 1400)
  }

  function scrollToComment(id: string): void {
    const r = ranges[id]
    if (!r) return
    const node = r.startContainer
    const target = node.nodeType === 1 ? (node as Element) : node.parentElement
    if (target && target.scrollIntoView) target.scrollIntoView({ block: 'center', behavior: 'smooth' })
    flash(id)
  }

  /**
   * The frame is sandboxed with `allow-scripts` only, so it can neither open windows nor navigate
   * anywhere. Every link click is therefore taken over here: `#hash` links scroll inside the frame;
   * http(s), mailto and tel links are handed to the app, which checks them and opens them; anything
   * else is dropped. Nothing is ever left to the browser's default.
   */
  function handleLink(e: MouseEvent, a: Element): void {
    const href = (a.getAttribute('href') || '').trim()
    e.preventDefault()
    if (href.charAt(0) === '#') {
      let id = href.slice(1)
      try {
        id = decodeURIComponent(id)
      } catch {
        // keep the raw fragment
      }
      const t = id ? document.getElementById(id) || document.getElementsByName(id)[0] : null
      if (t) t.scrollIntoView()
      else if (!id) w.scrollTo(0, 0)
      return
    }
    if (/^(https?|mailto|tel):/i.test(href)) post({ type: 'openLink', href: href })
  }

  document.addEventListener('mouseup', function () {
    setTimeout(reportSelection, 0)
  })
  document.addEventListener('keyup', function () {
    setTimeout(reportSelection, 0)
  })
  document.addEventListener('selectionchange', function () {
    const sel = w.getSelection()
    if (!sel || sel.isCollapsed) clearSelection()
  })

  let scrollQueued = false
  w.addEventListener('scroll', function () {
    if (!hasSelection || scrollQueued) return
    scrollQueued = true
    w.requestAnimationFrame(function () {
      scrollQueued = false
      reportSelection()
    })
  })

  document.addEventListener('click', function (e: MouseEvent) {
    if (e.defaultPrevented) return
    const t = e.target as Element | null
    const a = t && t.closest ? t.closest('a[href]') : null
    if (a) {
      handleLink(e, a)
      return
    }
    const sel = w.getSelection()
    if (sel && !sel.isCollapsed) return
    const id = hitTest(e.clientX, e.clientY)
    if (id) post({ type: 'open', id: id })
  })

  w.addEventListener('message', function (e: MessageEvent) {
    if (e.source !== w.parent) return
    const d: any = e.data
    if (!d || typeof d !== 'object') return
    if (d.type === 'anchors' && Array.isArray(d.anchors)) {
      anchors = d.anchors.filter(function (a: any) {
        return a && typeof a.id === 'string' && typeof a.exact === 'string'
      })
      resolveAll(true)
    } else if (d.type === 'scrollTo' && typeof d.id === 'string') {
      scrollToComment(d.id)
    }
  })

  // Content that scripts render after load becomes anchorable once the DOM settles.
  let mutationTimer: any = null
  new MutationObserver(function () {
    if (mutationTimer) clearTimeout(mutationTimer)
    mutationTimer = setTimeout(function () {
      if (anchors.length > 0) resolveAll(false)
    }, 300)
  }).observe(document.body || document.documentElement, {
    childList: true,
    subtree: true,
    characterData: true,
  })

  if (document.readyState === 'complete') post({ type: 'ready' })
  else w.addEventListener('load', function () {
    post({ type: 'ready' })
  })
}

export const bridgeSource: string =
  '(' + bridgeMain.toString() + ')(' + buildQuote.toString() + ',' + findQuote.toString() + ');'
