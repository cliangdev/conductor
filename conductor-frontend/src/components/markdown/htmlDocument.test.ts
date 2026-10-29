import { describe, it, expect } from 'vitest'
import { prepareHtmlDocument, HTML_DOCUMENT_CSP } from './htmlDocument'

const BRIDGE = 'window.__bridge = 1;'
const BRIDGE_TAG = `<script>${BRIDGE}</script>`
const CSP = `<meta http-equiv="Content-Security-Policy" content="${HTML_DOCUMENT_CSP}">`

const prep = (html: string) => prepareHtmlDocument(html, { bridge: BRIDGE })

describe('prepareHtmlDocument', () => {
  it('puts the CSP right after the doctype and the bridge before </body>', () => {
    const out = prep('<!doctype html><html><head><title>T</title></head><body><p>Hi</p></body></html>')
    expect(out).toBe(
      `<!doctype html>${CSP}<html><head><title>T</title></head><body><p>Hi</p>${BRIDGE_TAG}</body></html>`
    )
  })

  it('puts the CSP at offset 0 when there is no doctype', () => {
    expect(prep('<p>Hi</p>')).toBe(`${CSP}<p>Hi</p>${BRIDGE_TAG}`)
    expect(prep('<html><head></head><body>x</body></html>')).toBe(
      `${CSP}<html><head></head><body>x${BRIDGE_TAG}</body></html>`
    )
  })

  it('puts the policy before a script that comes before <head>', () => {
    const withDoctype = prep('<!DOCTYPE html><script>steal()</script><head></head><body></body>')
    expect(withDoctype.indexOf(CSP)).toBeLessThan(withDoctype.indexOf('steal()'))
    expect(withDoctype.indexOf(CSP)).toBe('<!DOCTYPE html>'.length)

    const bare = prep('<script>steal()</script><head></head><body></body>')
    expect(bare.startsWith(CSP)).toBe(true)
  })

  it('does not look for <head>: uppercase, attributes, <header> and commented heads change nothing', () => {
    const html = '<!DOCTYPE html><!-- <head> --><HTML><HEAD lang="en"><TITLE>T</TITLE></HEAD><BODY><header>H</header>x</BODY ></HTML>'
    const out = prep(html)
    expect(out.indexOf(CSP)).toBe('<!DOCTYPE html>'.length)
    expect(out).toContain('<HEAD lang="en"><TITLE>')
    expect(out).toContain(`x${BRIDGE_TAG}</BODY >`)
  })

  it('only counts a doctype at the start of the document', () => {
    const out = prep('<p>intro</p><script>var d = "<!doctype html>"</script>')
    expect(out.startsWith(CSP)).toBe(true)
  })

  it('skips leading whitespace and comments before the doctype', () => {
    const out = prep('\n<!-- generated -->\n<!DOCTYPE html>\n<body></body>')
    expect(out).toContain(`<!DOCTYPE html>${CSP}\n<body>`)
  })

  it('skips a BOM before the doctype', () => {
    expect(prep('﻿<!doctype html><p>x</p>').startsWith(`﻿<!doctype html>${CSP}`)).toBe(true)
  })

  it('puts the CSP at offset 0 when a leading comment or the doctype never closes', () => {
    expect(prep('<!-- open <!doctype html>').startsWith(CSP)).toBe(true)
    expect(prep('<!doctype html').startsWith(CSP)).toBe(true)
  })

  it('stays fast on a long run of comments', () => {
    const html = '<!--' + '--><!--'.repeat(50_000) + '<p>x</p>'
    const t = performance.now()
    expect(prep(html).startsWith(CSP)).toBe(true)
    expect(performance.now() - t).toBeLessThan(500)
  })

  it('appends the bridge at the end when there is no </body>', () => {
    const out = prep('<html><head></head><body><p>Hi</p>')
    expect(out.endsWith(`<p>Hi</p>${BRIDGE_TAG}`)).toBe(true)
  })

  it('uses the last </body>', () => {
    const out = prep('<body><script>var s = "</body>"</script><p>x</p></body>')
    expect(out.endsWith(`${BRIDGE_TAG}</body>`)).toBe(true)
    expect(out.match(/window\.__bridge/g)).toHaveLength(1)
    expect(out.indexOf(BRIDGE_TAG)).toBeGreaterThan(out.indexOf('<p>x</p>'))
  })

  it('is not confused by $ patterns in the document or the bridge', () => {
    const out = prepareHtmlDocument('<body>$&$1</body>', { bridge: "var a = '$&'; var b = '$`';" })
    expect(out).toContain("var a = '$&'; var b = '$`';")
    expect(out).toContain('$&$1')
  })

  it('cannot be ended early by a </script in the bridge', () => {
    const out = prepareHtmlDocument('<body></body>', { bridge: 'var s = "</script>";' })
    expect(out.match(/<\/script>/g)).toHaveLength(1)
  })

  it('carries the policy the plan specifies', () => {
    expect(HTML_DOCUMENT_CSP).toContain("default-src 'none'")
    expect(HTML_DOCUMENT_CSP).toContain("connect-src 'none'")
    expect(HTML_DOCUMENT_CSP).toContain("form-action 'none'")
    expect(HTML_DOCUMENT_CSP).toContain("base-uri 'none'")
    expect(HTML_DOCUMENT_CSP).toContain('font-src https://fonts.gstatic.com data:')
    expect(HTML_DOCUMENT_CSP).toContain('img-src data: blob: https:')
    expect(HTML_DOCUMENT_CSP).toContain('https://cdnjs.cloudflare.com')
  })
})
