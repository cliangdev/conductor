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
 * `bridgeSource` is built with `Function.prototype.toString`, so `bridgeMain` and the `lib/textQuote.ts`
 * functions it is given (`buildQuote`, `findQuote`, `flattenText`, `rangeFor`) must stay self-contained: no imports, no closed-over variables, no helpers the build
 * could inject (spread, classes, async). `htmlBridge.test.ts` evaluates the result in jsdom.
 */

import { buildQuote, findQuote, flattenText, rangeFor } from '@/lib/textQuote'

// Other modules import these from here.
export { buildQuote, findQuote }
export type { QuoteParts } from '@/lib/textQuote'

/** Runs inside the frame. Receives the two quote helpers as arguments so it closes over nothing. */
function bridgeMain(buildQuote: any, findQuote: any, flattenText: any, rangeFor: any): void {
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

  // Highlight colours live in the frame's own document, so they cannot use the app's theme tokens: the
  // app sends them in a `theme` message (these are the fallback until it does).
  const style = document.createElement('style')
  function applyColors(base: string, active: string): void {
    style.textContent =
      '::highlight(conductor-comment){background-color:' + base + ';color:inherit}' +
      '::highlight(conductor-comment-active){background-color:' + active + ';color:inherit}' +
      '::highlight(conductor-comment-flash){background-color:' + active + ';color:inherit}'
  }
  applyColors('rgba(255,214,10,.38)', 'rgba(255,159,10,.6)')
  ;(document.head || document.documentElement).appendChild(style)
  let activeId: string | null = null
  let layoutQueued = false

  /** One `layout` message per animation frame: where each highlight is, so the app can line its cards up. */
  function postLayout(): void {
    layoutQueued = false
    const tops: any[] = []
    const ids = Object.keys(ranges)
    const sy = w.scrollY || 0
    for (let i = 0; i < ids.length; i++) {
      tops.push({ id: ids[i], top: Math.round(ranges[ids[i]].getBoundingClientRect().top + sy) })
    }
    post({ type: 'layout', scrollY: Math.round(sy), viewportH: w.innerHeight || 0, tops: tops })
  }

  function scheduleLayout(): void {
    if (layoutQueued) return
    layoutQueued = true
    w.requestAnimationFrame(postLayout)
  }

  function paintActive(): void {
    const reg = w.CSS && w.CSS.highlights
    if (!reg || typeof w.Highlight === 'undefined') return
    if (activeId && ranges[activeId]) {
      const h = new w.Highlight(ranges[activeId])
      h.priority = 1
      reg.set('conductor-comment-active', h)
    } else {
      reg.delete('conductor-comment-active')
    }
  }

  function flatten(): any {
    return flattenText(document.body || document.documentElement)
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
    paintActive()
    scheduleLayout()
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
    h.priority = 2
    reg.set('conductor-comment-flash', h)
    if (activeTimer) clearTimeout(activeTimer)
    activeTimer = setTimeout(function () {
      reg.delete('conductor-comment-flash')
    }, 1400)
  }

  function scrollToComment(id: string): void {
    const r = ranges[id]
    if (!r) return
    // Only move the page when the words are not comfortably on screen already.
    const b = r.getBoundingClientRect()
    if (b.top < 60 || b.bottom > (w.innerHeight || 0) - 60) {
      const node = r.startContainer
      const target = node.nodeType === 1 ? (node as Element) : node.parentElement
      if (target && target.scrollIntoView) target.scrollIntoView({ block: 'center', behavior: 'smooth' })
    }
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
  // Keys typed while the frame has focus never reach the app's own listeners, so the thread-navigation
  // keys are relayed (the app ignores them when it is typing; here, when the page is).
  document.addEventListener('keydown', function (e: KeyboardEvent) {
    if (e.metaKey || e.ctrlKey || e.altKey) return
    const t: any = e.target
    if (t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA' || t.tagName === 'SELECT' || t.isContentEditable)) return
    if (e.key === 'j' || e.key === 'k' || e.key === 'Escape') post({ type: 'key', key: e.key })
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
    scheduleLayout()
    if (!hasSelection || scrollQueued) return
    scrollQueued = true
    w.requestAnimationFrame(function () {
      scrollQueued = false
      reportSelection()
    })
  })
  w.addEventListener('resize', scheduleLayout)

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
    } else if (d.type === 'active') {
      activeId = typeof d.id === 'string' ? d.id : null
      paintActive()
    } else if (d.type === 'theme') {
      // Only plain colour syntax goes into the stylesheet; the app validates too, this is the backstop.
      const ok = /^[#a-zA-Z0-9(),.%\s\/-]{1,64}$/
      if (typeof d.highlight === 'string' && typeof d.active === 'string' && ok.test(d.highlight) && ok.test(d.active)) {
        applyColors(d.highlight, d.active)
      }
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
  '(' +
  bridgeMain.toString() +
  ')(' +
  [buildQuote, findQuote, flattenText, rangeFor].map((f) => f.toString()).join(',') +
  ');'
