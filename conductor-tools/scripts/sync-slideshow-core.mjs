#!/usr/bin/env node
/* sync-slideshow-core.mjs — emits the pure slideshow modules of src/lib/slideshow/ as plain ESM (+ .d.ts)
 * into the sibling `conductor-creative/slideshow/`, and copies the bundled fonts to
 * `conductor-creative/slideshow/fonts/`. Run by `npm run build`; the output is committed, and
 * src/__tests__/sync-slideshow-core.test.ts fails when the committed copy differs from a fresh generation.
 *
 * Why: the web editor's live preview must build the very same slide HTML (and run the same in-page CHECK_SCRIPT)
 * the composer and the render job do, but the web app cannot import conductor-tools (a published npm package with
 * Node dependencies). These five modules have no Node imports, so they are compiled on their own. compose.ts and
 * spec-transport.ts import node:fs / playwright and are deliberately not emitted.
 *
 * The web app imports them as `@cliangdev/creative-render/slideshow/html.js` etc. through its `file:` dependency
 * and copies the fonts into `public/slideshow-fonts/` (conductor-frontend/scripts/copy-slideshow-fonts.mjs).
 */
import { mkdirSync, readdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import ts from 'typescript'

const __dirname = dirname(fileURLToPath(import.meta.url))
const TOOLS_ROOT = join(__dirname, '..')
const REPO_ROOT = join(TOOLS_ROOT, '..')
export const SRC_DIR = join(TOOLS_ROOT, 'src', 'lib', 'slideshow')
export const OUT_DIR = join(REPO_ROOT, 'conductor-creative', 'slideshow')
export const FONTS_SRC = join(TOOLS_ROOT, 'assets', 'fonts')

/** The modules that are safe to run in a browser (no Node imports), without the extension. */
export const CORE_MODULES = ['spec', 'style', 'css', 'html', 'text-fields']

const HEADER =
  '// GENERATED from conductor-tools/src/lib/slideshow/%NAME%.ts by conductor-tools/scripts/sync-slideshow-core.mjs.\n' +
  '// Do not edit: change the TypeScript source and run `npm run build` in conductor-tools.\n'

/** Returns { [relative path]: content } for the .js and .d.ts of every core module. Throws on a type error or a Node import. */
export function generateSlideshowCore(srcDir = SRC_DIR) {
  const rootNames = CORE_MODULES.map((m) => join(srcDir, `${m}.ts`))
  const options = {
    target: ts.ScriptTarget.ES2020,
    module: ts.ModuleKind.ES2020,
    moduleResolution: ts.ModuleResolutionKind.Bundler,
    lib: ['lib.es2022.d.ts', 'lib.dom.d.ts'],
    types: [],
    strict: true,
    skipLibCheck: true,
    declaration: true,
    rootDir: srcDir,
    newLine: ts.NewLineKind.LineFeed,
    removeComments: false,
  }
  const program = ts.createProgram(rootNames, options)
  const problems = ts.getPreEmitDiagnostics(program).filter((d) => d.category === ts.DiagnosticCategory.Error)
  if (problems.length > 0) {
    throw new Error(
      'sync-slideshow-core: the core modules do not compile on their own (a Node import?):\n' +
        problems.map((d) => ts.flattenDiagnosticMessageText(d.messageText, '\n')).join('\n')
    )
  }
  const out = {}
  const emit = program.emit(undefined, (fileName, text) => {
    const rel = fileName.slice(srcDir.length + 1)
    const name = rel.replace(/\.d\.ts$|\.js$/, '')
    out[rel] = HEADER.replace('%NAME%', name) + text
  })
  if (emit.emitSkipped) throw new Error('sync-slideshow-core: emit was skipped')
  for (const [rel, text] of Object.entries(out)) {
    if (/(from|import)\s*\(?\s*['"]node:/.test(text)) throw new Error(`sync-slideshow-core: ${rel} imports a Node module`)
  }
  return out
}

/** Every file sync writes under OUT_DIR, as { relative path: Buffer | string }. */
export function expectedOutput(srcDir = SRC_DIR, fontsSrc = FONTS_SRC) {
  const files = generateSlideshowCore(srcDir)
  const all = { ...files }
  for (const f of readdirSync(fontsSrc).sort()) all[`fonts/${f}`] = readFileSync(join(fontsSrc, f))
  return all
}

export function syncSlideshowCore(outDir = OUT_DIR) {
  const files = expectedOutput()
  rmSync(outDir, { recursive: true, force: true })
  mkdirSync(join(outDir, 'fonts'), { recursive: true })
  for (const [rel, content] of Object.entries(files)) writeFileSync(join(outDir, rel), content)
  return Object.keys(files).map((rel) => join(outDir, rel))
}

function isMain() {
  return process.argv[1] && import.meta.url === `file://${process.argv[1]}`
}

if (isMain()) {
  const written = syncSlideshowCore()
  console.log(`sync-slideshow-core: wrote ${written.length} file(s) into ${OUT_DIR}`)
}
