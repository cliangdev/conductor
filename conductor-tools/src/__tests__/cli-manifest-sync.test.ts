import { describe, it, expect } from 'vitest'
import * as fs from 'fs'
import * as path from 'path'
import { fileURLToPath } from 'url'

const __filename = fileURLToPath(import.meta.url)
const __dirname = path.dirname(__filename)

/**
 * Parses the live tool registry (src/mcp/index.ts) and diffs it against
 * assets/cli-manifest.json's mcpTools[] in both directions, so a rename or
 * addition in the registry can't silently drift from the published manifest
 * (which drives the Settings -> CLI reference page).
 */
function getRegisteredToolNames(): string[] {
  const srcPath = path.join(__dirname, '..', 'mcp', 'index.ts')
  const src = fs.readFileSync(srcPath, 'utf8')

  // The registry is the TOOLS array: each entry is { name: '<tool_name>', ... }.
  // Extract just that array's source so we don't accidentally match unrelated
  // string literals elsewhere in the file.
  const arrayMatch = src.match(/const TOOLS = \[([\s\S]*?)\n\]\n/)
  if (!arrayMatch) throw new Error('Could not find TOOLS array in mcp/index.ts')
  const arraySrc = arrayMatch[1]!

  const names = [...arraySrc.matchAll(/name:\s*'([a-z_]+)'/g)].map((m) => m[1]!)
  if (names.length === 0) throw new Error('Parsed zero tool names out of TOOLS array — regex likely stale')
  return names
}

function getManifestToolNames(): string[] {
  const manifestPath = path.join(__dirname, '..', '..', 'assets', 'cli-manifest.json')
  const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8')) as {
    mcpTools: { name: string }[]
  }
  return manifest.mcpTools.map((t) => t.name)
}

describe('cli-manifest.json mcpTools sync', () => {
  it('every tool registered in mcp/index.ts is listed in cli-manifest.json', () => {
    const registered = getRegisteredToolNames()
    const manifest = getManifestToolNames()
    const missing = registered.filter((n) => !manifest.includes(n))
    expect(
      missing,
      `Tools registered in src/mcp/index.ts but missing from assets/cli-manifest.json mcpTools[]:\n${missing.map((n) => `  - ${n}`).join('\n')}`
    ).toEqual([])
  })

  it('cli-manifest.json does not reference a tool name absent from mcp/index.ts', () => {
    const registered = getRegisteredToolNames()
    const manifest = getManifestToolNames()
    const stale = manifest.filter((n) => !registered.includes(n))
    expect(
      stale,
      `assets/cli-manifest.json mcpTools[] references tool names no longer registered in src/mcp/index.ts:\n${stale.map((n) => `  - ${n}`).join('\n')}`
    ).toEqual([])
  })
})
