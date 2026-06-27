import { describe, it, expect } from 'vitest'
import * as fs from 'fs'
import * as path from 'path'
import { fileURLToPath } from 'url'
import { getAssetSrcDir } from '../lib/plugin-assets.js'

const __filename = fileURLToPath(import.meta.url)
const __dirname = path.dirname(__filename)

function walkDir(dir: string, base: string): string[] {
  const results: string[] = []
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name)
    if (entry.isDirectory()) {
      results.push(...walkDir(full, base))
    } else {
      results.push(path.relative(base, full))
    }
  }
  return results
}

// Read PLUGIN_FILES directly from source so the test reflects the live list
async function getPluginFiles(): Promise<string[]> {
  const srcPath = path.join(__dirname, '..', 'lib', 'plugin-assets.ts')
  const src = fs.readFileSync(srcPath, 'utf8')
  const match = src.match(/const PLUGIN_FILES\s*=\s*\[([\s\S]*?)\]/)
  if (!match) throw new Error('Could not find PLUGIN_FILES in plugin-assets.ts')
  return [...match[1]!.matchAll(/'([^']+)'/g)].map(m => m[1]!)
}

describe('PLUGIN_FILES completeness', () => {
  it('every file under assets/claude/ is listed in PLUGIN_FILES', async () => {
    const assetSrcDir = getAssetSrcDir()
    const allFiles = walkDir(assetSrcDir, assetSrcDir)
    const pluginFiles = await getPluginFiles()

    const missing = allFiles.filter(f => !pluginFiles.includes(f))
    expect(missing, `Files in assets/claude/ not in PLUGIN_FILES:\n${missing.map(f => `  - ${f}`).join('\n')}`).toEqual([])
  })
})
