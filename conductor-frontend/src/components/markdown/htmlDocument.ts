/**
 * Prepares a stored HTML Work Item document for the sandboxed viewer frame.
 *
 * Two things are added at render time and never stored: a Content-Security-Policy `<meta>`, placed
 * before any of the page's own markup, that limits what the page can load or send, and the comment
 * bridge script (see `htmlBridge.ts`). The page keeps running its own inline scripts and loading fonts and pinned CDN libraries; it just
 * cannot make network requests (`connect-src 'none'`), submit forms, or rebase its URLs.
 */

const CDN_SCRIPTS = [
  'https://cdnjs.cloudflare.com',
  'https://cdn.jsdelivr.net',
  'https://unpkg.com',
  'https://cdn.tailwindcss.com',
  'https://code.jquery.com',
]

export const HTML_DOCUMENT_CSP = [
  "default-src 'none'",
  `script-src 'unsafe-inline' ${CDN_SCRIPTS.join(' ')}`,
  "style-src 'unsafe-inline' https://fonts.googleapis.com https://cdn.jsdelivr.net https://unpkg.com",
  'font-src https://fonts.gstatic.com data:',
  'img-src data: blob: https:',
  'media-src data: blob: https:',
  "connect-src 'none'",
  "form-action 'none'",
  "base-uri 'none'",
].join('; ')

const CSP_META = `<meta http-equiv="Content-Security-Policy" content="${HTML_DOCUMENT_CSP}">`

/**
 * A doctype at the very start of the document (after an optional BOM, whitespace and comments).
 * Only a leading doctype counts: one further down is page content, not the document type.
 */
const LEADING_DOCTYPE = /^\uFEFF?\s*(?:<!--[\s\S]*?-->\s*)*<!doctype[^>]*>/i

/**
 * The policy goes right after the doctype, or at offset 0 without one — before anything else in the
 * document, whatever it is. It is deliberately not searched for a `<head>`: a `<script>` placed before
 * `<head>` would run before the policy existed. The parser hoists a `<meta>` found before `<html>` or
 * `<head>` into the head it implies, so the policy is still the first thing in it.
 */
function insertCsp(html: string): string {
  const doctype = LEADING_DOCTYPE.exec(html)
  const at = doctype ? doctype[0].length : 0
  return html.slice(0, at) + CSP_META + html.slice(at)
}

function insertBridge(html: string, bridge: string): string {
  // "</script" inside the bridge would end the element early; the escaped form is equivalent in JS.
  const script = `<script>${bridge.replace(/<\/script/gi, '<\\/script')}</script>`
  const re = /<\/body\s*>/gi
  let last: RegExpExecArray | null = null
  let m: RegExpExecArray | null
  while ((m = re.exec(html)) !== null) last = m
  if (!last) return html + script
  return html.slice(0, last.index) + script + html.slice(last.index)
}

export function prepareHtmlDocument(html: string, opts: { bridge: string }): string {
  return insertBridge(insertCsp(html), opts.bridge)
}
