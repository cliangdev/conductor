#!/usr/bin/env node
/* build-creative.mjs — copies the runtime files of the sibling
 * `conductor-creative/` package into this package's `dist/creative/`, run by
 * `npm run build` alongside `tsc`.
 *
 * Why a copy rather than a dependency: conductor-tools is published to npm
 * (`@cliangdev/conductor`), so it cannot `file:`-depend on a sibling
 * directory that only exists in this monorepo checkout at runtime — the
 * published package has to carry what it needs itself. `conductor-creative/`
 * has no build step of its own (plain ESM, no bundler), so "build" here just
 * means "select the files the render job actually needs and lay them out at
 * the same relative paths dist/creative/job/render.mjs's own
 * `PACKAGE_ROOT = join(HERE, '..')` expects".
 *
 * Deliberately excluded: `mount.js`/`copy-rules.js` (editor-only, not
 * imported by frame.js/sheet.js/job/render.mjs — the render core never
 * reaches for them; `motion.js`, by contrast, IS imported by all three
 * (frame.js/sheet.js apply a MOTION creative's timeline in-page, and
 * job/render.mjs imports `motionKeyTimes` for a previewOnly sheet spec) so
 * it is included below), `test/`, `node_modules/`, `job/package.json` (its
 * `playwright` dependency
 * is for running the job directly out of that repo; conductor-tools drives
 * the same `run()` with its own `playwright-core`-based browser discovery —
 * see src/lib/creative-browser.ts), and `README.md`.
 *
 * Kept in one explicit list (rather than "copy everything except...") so a
 * new file added to conductor-creative/ has to be a deliberate decision here
 * too, and `src/__tests__/build-creative.test.ts` asserts every listed file
 * actually lands in dist/creative/ after this runs.
 */
import { existsSync, mkdirSync, copyFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const __dirname = dirname(fileURLToPath(import.meta.url))
const REPO_ROOT = join(__dirname, '..', '..')
export const SOURCE_ROOT = join(REPO_ROOT, 'conductor-creative')
export const DEST_ROOT = join(__dirname, '..', 'dist', 'creative')

/** Relative paths (from conductor-creative/) this build needs. Order doesn't matter. */
export const CREATIVE_RUNTIME_FILES = [
  'render.js',
  'motion.js',
  'assertions.js',
  'placements.js',
  'placements.json',
  'styles.css',
  'tokens.css',
  'frame.css',
  'frame.html',
  'font.js',
  'frame.js',
  'sheet.html',
  'sheet.js',
  'layouts/index.js',
  'layouts/index.json',
  'layouts/stacked/layout.json',
  'layouts/stacked/layout.css',
  'layouts/bleed/layout.json',
  'layouts/bleed/layout.css',
  'layouts/card/layout.json',
  'layouts/card/layout.css',
  'layouts/split/layout.json',
  'layouts/split/layout.css',
  'job/render.mjs',
  'job/server.mjs',
  'job/transport.mjs',
  'job/file-transport.mjs',
]

export function copyCreativeRuntime(sourceRoot = SOURCE_ROOT, destRoot = DEST_ROOT) {
  const missing = []
  for (const rel of CREATIVE_RUNTIME_FILES) {
    const src = join(sourceRoot, rel)
    if (!existsSync(src)) {
      missing.push(rel)
      continue
    }
    const dest = join(destRoot, rel)
    mkdirSync(dirname(dest), { recursive: true })
    copyFileSync(src, dest)
  }
  if (missing.length > 0) {
    throw new Error(
      `build-creative.mjs: expected file(s) missing from ${sourceRoot}:\n` +
        missing.map((f) => `  - ${f}`).join('\n')
    )
  }
  return CREATIVE_RUNTIME_FILES.map((rel) => join(destRoot, rel))
}

function isMain() {
  return process.argv[1] && import.meta.url === `file://${process.argv[1]}`
}

if (isMain()) {
  const written = copyCreativeRuntime()
  console.log(`build-creative: copied ${written.length} file(s) into ${DEST_ROOT}`)
}
