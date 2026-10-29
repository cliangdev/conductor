/**
 * Prepares a stored HTML Work Item document for the sandboxed viewer frame.
 *
 * Two things are added at render time and never stored: a Content-Security-Policy `<meta>` that
 * limits what the page can load or send, and the comment bridge script (see `htmlBridge.ts`).
 * The page keeps running its own inline scripts and loading fonts and pinned CDN libraries; it just
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

/** First match of `tag` that is not inside an HTML comment (a commented-out `<head>` is not the head). */
function findOutsideComments(html: string, tag: RegExp): RegExpExecArray | null {
  const re = new RegExp(`<!--[\\s\\S]*?-->|${tag.source}`, 'gi')
  let m: RegExpExecArray | null
  while ((m = re.exec(html)) !== null) {
    if (!m[0].startsWith('<!--')) return m
  }
  return null
}

function insertCsp(html: string): string {
  const head = findOutsideComments(html, /<head(?=[\s/>])[^>]*>/)
  if (head) {
    const at = head.index + head[0].length
    return html.slice(0, at) + CSP_META + html.slice(at)
  }
  const doctype = findOutsideComments(html, /<!doctype[^>]*>/)
  if (doctype) {
    const at = doctype.index + doctype[0].length
    return html.slice(0, at) + CSP_META + html.slice(at)
  }
  return CSP_META + html
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
