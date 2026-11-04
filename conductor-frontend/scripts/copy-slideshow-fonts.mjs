#!/usr/bin/env node
/* Copies the slideshow fonts (TikTok Sans, Inter, Source Serif 4, Courier Prime) that the Creative page's live
 * slide preview needs from @cliangdev/creative-render (conductor-creative/slideshow/fonts) into
 * public/slideshow-fonts/, where the preview iframe loads them from (assetBase "/slideshow-fonts/").
 * Run by `predev` and `prebuild`; the copy is gitignored. */
import { cpSync, existsSync, mkdirSync, realpathSync, rmSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = join(dirname(fileURLToPath(import.meta.url)), '..')
const source = join(root, 'node_modules', '@cliangdev', 'creative-render', 'slideshow', 'fonts')
const dest = join(root, 'public', 'slideshow-fonts')

if (!existsSync(source)) {
  console.error(`copy-slideshow-fonts: ${source} not found. Run \`npm ci\`, and \`npm run build\` in conductor-tools if conductor-creative/slideshow is missing.`)
  process.exit(1)
}
rmSync(dest, { recursive: true, force: true })
mkdirSync(dest, { recursive: true })
cpSync(realpathSync(source), dest, { recursive: true })
console.log(`copy-slideshow-fonts: copied into ${dest}`)
