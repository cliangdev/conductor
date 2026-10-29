import { describe, it, expect } from 'vitest'
import { prepareHtmlDocument, HTML_DOCUMENT_CSP } from './htmlDocument'

const BRIDGE = 'window.__bridge = 1;'
const BRIDGE_TAG = `<script>${BRIDGE}</script>`
const CSP = `<meta http-equiv="Content-Security-Policy" content="${HTML_DOCUMENT_CSP}">`

const prep = (html: string) => prepareHtmlDocument(html, { bridge: BRIDGE })

describe('prepareHtmlDocument', () => {
  it('puts the CSP first inside <head> and the bridge before </body>', () => {
    const out = prep('<!doctype html><html><head><title>T</title></head><body><p>Hi</p></body></html>')
    expect(out).toBe(
      `<!doctype html><html><head>${CSP}<title>T</title></head><body><p>Hi</p>${BRIDGE_TAG}</body></html>`
    )
  })

  it('handles an uppercase <HEAD> with attributes and an uppercase </BODY>', () => {
    const out = prep('<!DOCTYPE html><HTML><HEAD lang="en" data-x="1"><TITLE>T</TITLE></HEAD><BODY>x</BODY ></HTML>')
    expect(out).toContain(`<HEAD lang="en" data-x="1">${CSP}<TITLE>`)
    expect(out).toContain(`x${BRIDGE_TAG}</BODY >`)
  })

  it('does not mistake <header> for <head>', () => {
    const out = prep('<!doctype html><body><header>H</header></body>')
    expect(out.indexOf(CSP)).toBe('<!doctype html>'.length)
    expect(out).toContain('<header>H</header>')
  })

  it('puts the CSP right after the doctype when there is no <head>', () => {
    const out = prep('<!DOCTYPE html>\n<body><p>Hi</p></body>')
    expect(out).toBe(`<!DOCTYPE html>${CSP}\n<body><p>Hi</p>${BRIDGE_TAG}</body>`)
  })

  it('puts the CSP at the very start when there is no doctype either', () => {
    const out = prep('<p>Hi</p>')
    expect(out).toBe(`${CSP}<p>Hi</p>${BRIDGE_TAG}`)
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

  it('ignores a <head> inside a comment', () => {
    const out = prep('<!doctype html><!-- <head> --><html><head></head><body></body></html>')
    expect(out).toContain(`<html><head>${CSP}</head>`)
    expect(out).toContain('<!-- <head> -->')
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
