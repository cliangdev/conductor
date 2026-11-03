import { describe, it, expect } from 'vitest'
import { existsSync, readdirSync, readFileSync } from 'node:fs'
import { join } from 'node:path'
// @ts-expect-error -- plain .mjs script, no declarations
import { CORE_MODULES, OUT_DIR, expectedOutput } from '../../scripts/sync-slideshow-core.mjs'
import { buildSlideHtml, type SlideshowSpec } from '../lib/slideshow/index.js'
import { listTextFields, diffText, applyTextEdits } from '../lib/slideshow/text-fields.js'

// conductor-creative/slideshow/ is generated from src/lib/slideshow/ (scripts/sync-slideshow-core.mjs) so the web
// editor builds the same slide HTML as the composer. A stale committed copy would make the live preview lie.

const listFiles = (dir: string, prefix = ''): string[] =>
  readdirSync(dir, { withFileTypes: true }).flatMap((e) => (e.isDirectory() ? listFiles(join(dir, e.name), `${prefix}${e.name}/`) : [`${prefix}${e.name}`]))

describe('conductor-creative/slideshow is the committed output of sync-slideshow-core', () => {
  const expected = expectedOutput() as Record<string, string | Buffer>

  it('has exactly the files a fresh generation writes', () => {
    expect(listFiles(OUT_DIR).sort()).toEqual(Object.keys(expected).sort())
  })

  it('matches a fresh generation byte for byte (run `npm run build` in conductor-tools to refresh)', () => {
    for (const [rel, content] of Object.entries(expected)) {
      const onDisk = readFileSync(join(OUT_DIR, rel))
      expect(Buffer.from(content).equals(onDisk), `${rel} is stale`).toBe(true)
    }
  })

  it('imports nothing from Node, so a browser can load it', () => {
    for (const rel of Object.keys(expected).filter((f) => f.endsWith('.js'))) {
      expect(readFileSync(join(OUT_DIR, rel), 'utf8'), rel).not.toMatch(/from ['"]node:|require\(/)
    }
  })

  it('ships every bundled font the stylesheet names', () => {
    for (const f of ['tiktok-sans-700.ttf', 'inter-400.woff2', 'source-serif-4-400.woff2', 'courier-prime-400.ttf']) {
      expect(existsSync(join(OUT_DIR, 'fonts', f)), f).toBe(true)
    }
  })
})

describe('the generated builder produces the same HTML as the TypeScript one', () => {
  const spec: SlideshowSpec = {
    id: 'parity',
    slides: [
      { type: 'photo', bg: 'https://example.test/a.jpg', dim: true, texts: [{ t: 'Line one\nline two', y: 30 }, { t: 'sub', style: 'sub' }] },
      { type: 'notes', title: 'Plan', date: 'Nov 2', items: ['a', { t: 'b', done: true }], paras: ['para'] },
      { type: 'messages', bubbles: [{ t: 'hi', me: true }, { t: 'hello' }] } as never,
      { type: 'tier', rows: [{ k: 'S', c: '#f00', items: ['x', 'y'] }] },
    ],
  }
  it('for every slide and ratio, and for the text helpers', async () => {
    const gen = await import(join(OUT_DIR, 'html.js'))
    const genText = await import(join(OUT_DIR, 'text-fields.js'))
    for (let i = 0; i < spec.slides.length; i++) {
      for (const ratio of ['9x16', '4x5'] as const) {
        expect(gen.buildSlideHtml(spec, i, ratio, '/slideshow-fonts/')).toBe(buildSlideHtml(spec, i, ratio, '/slideshow-fonts/'))
      }
    }
    expect(genText.listTextFields(spec)).toEqual(listTextFields(spec))
    const edited = applyTextEdits(spec, [{ slide: 1, path: 'texts[0].t', text: 'changed' }])
    expect(genText.diffText(spec, edited)).toEqual(diffText(spec, edited))
  })
})

it('lists the modules the generator emits', () => {
  expect(CORE_MODULES).toEqual(['spec', 'style', 'css', 'html', 'text-fields'])
})
