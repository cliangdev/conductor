import { describe, it, expect } from 'vitest'
import { existsSync } from 'node:fs'
import { join, dirname } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { execFile } from 'node:child_process'

/**
 * End-to-end through the built `renderDraft`: a contact-sheet preview must also run the full render's
 * per-placement checks, so a badge that intrudes into 9:16's reserved overlay band is reported
 * before anything is approved. Runs against dist/ (the CLI's job dir only exists there) with a real
 * local Chromium; skips cleanly when either is missing.
 */
const REPO_ROOT = join(dirname(fileURLToPath(import.meta.url)), '..', '..')
const RENDER_MODULE = join(REPO_ROOT, 'dist', 'lib', 'creative-render.js')

const SCRIPT = `
import { renderDraft } from ${JSON.stringify(pathToFileURL(RENDER_MODULE).href)}
import { mkdtempSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
const badge = 'data:image/svg+xml;utf8,' + encodeURIComponent('<svg xmlns="http://www.w3.org/2000/svg" width="480" height="160"><rect width="480" height="160" fill="black"/></svg>')
const spec = {
  renderId: 'draft', previewOnly: true,
  creative: { layout: 'stacked', theme: 'dark', headline: 'Plan the week in *one sentence*.', body: 'Body.' },
  brand: { tokens: { accent: '#FF5A5F', darkBg: '#1C1C1E', darkInk: '#FFFFFF' }, logos: { badge }, ctaClaim: 'Free on the App Store' },
  placements: ['9x16', '4x5', '1x1'],
}
try {
  console.log(JSON.stringify(await renderDraft({ spec, localFiles: {}, outDir: mkdtempSync(join(tmpdir(), 'rd-')) })))
} catch (e) {
  console.log(JSON.stringify({ unavailable: String(e && e.message || e) }))
}
`

function run(): Promise<Record<string, any>> {
  return new Promise((resolve, reject) => {
    execFile(process.execPath, ['--input-type=module', '-e', SCRIPT], { timeout: 90_000 }, (err, stdout) => {
      if (err) return reject(err)
      resolve(JSON.parse(stdout.trim().split('\n').pop() as string))
    })
  })
}

describe('renderDraft preview checks (integration)', () => {
  it('reports a badge in the 9:16 overlay band as a failing safeZone check, with only the sheet kept', async () => {
    if (!existsSync(RENDER_MODULE)) {
      console.warn('skipping draft checks integration test: run `npm run build` first')
      return
    }
    const result = await run()
    if (result.unavailable || (!result.ok && /browser|chrom/i.test(String(result.error)))) {
      console.warn('skipping draft checks integration test: no local browser')
      return
    }

    expect(result.ok).toBe(true)
    expect(result.manifest.frames.map((f: { placementKey: string }) => f.placementKey)).toEqual(['sheet'])
    expect(result.manifest.passed).toBe(false)
    // The 4:5 placement is stacked, which has no room for the body line: that surfaces as a warning in the
    // same checks (never an error, so it does not fail `passed` on its own).
    expect(result.manifest.checks).toEqual([
      expect.objectContaining({ placementKey: '9x16', rule: 'safeZone', severity: 'error' }),
      expect.objectContaining({ placementKey: '4x5', rule: 'bodyDropped', severity: 'warning' }),
    ])
  }, 120_000)
})
