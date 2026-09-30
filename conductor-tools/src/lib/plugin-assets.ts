import * as fs from 'fs'
import * as path from 'path'
import { fileURLToPath } from 'url'
import { createRequire } from 'module'

const PLUGIN_FILES = [
  'commands/conductor/prd.md',
  'commands/conductor/implement.md',
  'commands/conductor/fix.md',
  'commands/conductor/workflow.md',
  'commands/conductor/creative.md',
  'agents/conductor-researcher.md',
  'skills/conductor-ux-ui-design/SKILL.md',
  'skills/conductor-ux-ui-design/references/design-tokens.md',
  'skills/conductor-coder/SKILL.md',
  'skills/conductor-publisher/SKILL.md',
  'skills/conductor-creative/SKILL.md',
]

// Paths removed in previous versions — deleted on `conductor init` to clean up stale installs.
const LEGACY_PATHS = [
  'agents/researcher.md',
  'skills/ux-ui-design/SKILL.md',
  'skills/ux-ui-design/references/design-tokens.md',
]

const CONDUCTOR_PERMISSIONS = ['mcp__conductor__*']

interface SettingsJson {
  permissions?: {
    allow?: string[]
    [key: string]: unknown
  }
  [key: string]: unknown
}

interface McpServerEntry {
  command: string
  args?: string[]
}

interface McpJson {
  mcpServers?: Record<string, McpServerEntry>
  [key: string]: unknown
}

export function getAssetSrcDir(): string {
  const __filename = fileURLToPath(import.meta.url)
  const __dirname = path.dirname(__filename)
  // Compiled to dist/lib/plugin-assets.js; assets/claude/ is at ../../assets/claude
  return path.join(__dirname, '..', '..', 'assets', 'claude')
}

/** The running @cliangdev/conductor package's own version — same resolution path as
 * getAssetSrcDir (dist/lib/plugin-assets.js -> ../../package.json). */
export function getPackageVersion(): string {
  const require = createRequire(import.meta.url)
  const pkg = require('../../package.json') as { version: string }
  return pkg.version
}

function mergeSettingsJson(settingsPath: string): void {
  let settings: SettingsJson = {}
  try {
    settings = JSON.parse(fs.readFileSync(settingsPath, 'utf8')) as SettingsJson
  } catch { /* file doesn't exist or invalid JSON — start fresh */ }

  const existingAllow = settings.permissions?.allow ?? []
  const newAllow = [...new Set([...existingAllow, ...CONDUCTOR_PERMISSIONS])]

  settings.permissions = {
    ...(settings.permissions ?? {}),
    allow: newAllow,
  }

  fs.mkdirSync(path.dirname(settingsPath), { recursive: true })
  fs.writeFileSync(settingsPath, JSON.stringify(settings, null, 2) + '\n', 'utf8')
}

function mergeMcpJson(mcpPath: string): void {
  let mcp: McpJson = {}
  try {
    mcp = JSON.parse(fs.readFileSync(mcpPath, 'utf8')) as McpJson
  } catch { /* file doesn't exist — start fresh */ }

  mcp.mcpServers = {
    ...(mcp.mcpServers ?? {}),
    conductor: { command: 'conductor', args: ['mcp'] },
  }

  fs.mkdirSync(path.dirname(mcpPath), { recursive: true })
  fs.writeFileSync(mcpPath, JSON.stringify(mcp, null, 2) + '\n', 'utf8')
}

export type InstallStatus = 'installed' | 'updated' | 'current'

/**
 * Install or update conductor Claude plugin assets into targetDir.
 *
 * @param targetDir   Destination .claude/ directory (e.g. ~/.claude/ or projectRoot/.claude/)
 * @param assetSrcDir Source assets/claude/ directory from the CLI package
 * @param mcpJsonPath Optional path to .mcp.json to merge; omit if caller handles it separately
 */
export function installPluginAssets(
  targetDir: string,
  assetSrcDir: string,
  mcpJsonPath?: string,
): InstallStatus {
  let anyNew = false
  let anyUpdated = false

  for (const file of PLUGIN_FILES) {
    const srcPath = path.join(assetSrcDir, file)
    const destPath = path.join(targetDir, file)

    let srcContent: string
    try {
      srcContent = fs.readFileSync(srcPath, 'utf8')
    } catch {
      continue
    }

    let destContent: string | null = null
    try {
      destContent = fs.readFileSync(destPath, 'utf8')
    } catch { /* doesn't exist yet */ }

    if (destContent === null) {
      anyNew = true
      fs.mkdirSync(path.dirname(destPath), { recursive: true })
      fs.writeFileSync(destPath, srcContent, 'utf8')
    } else if (destContent !== srcContent) {
      anyUpdated = true
      fs.writeFileSync(destPath, srcContent, 'utf8')
    }
  }

  for (const legacy of LEGACY_PATHS) {
    const legacyPath = path.join(targetDir, legacy)
    try { fs.rmSync(legacyPath) } catch { /* already gone */ }
  }

  mergeSettingsJson(path.join(targetDir, 'settings.json'))

  if (mcpJsonPath) {
    mergeMcpJson(mcpJsonPath)
  }

  if (anyNew) return 'installed'
  if (anyUpdated) return 'updated'
  return 'current'
}

/**
 * Return the plugin install status without writing any files.
 * Used by `conductor doctor`.
 */
export function getPluginInstallStatus(
  assetSrcDir: string,
  globalClaudeDir: string,
  localClaudeDir: string,
): { location: 'global' | 'local' | 'none'; outdated: boolean } {
  const bundledPrdPath = path.join(assetSrcDir, 'commands', 'conductor', 'prd.md')
  const globalPrdPath = path.join(globalClaudeDir, 'commands', 'conductor', 'prd.md')
  const localPrdPath = path.join(localClaudeDir, 'commands', 'conductor', 'prd.md')

  let installedPath: string | null = null
  let location: 'global' | 'local' | 'none' = 'none'

  if (fs.existsSync(globalPrdPath)) {
    installedPath = globalPrdPath
    location = 'global'
  } else if (fs.existsSync(localPrdPath)) {
    installedPath = localPrdPath
    location = 'local'
  }

  let outdated = false
  if (installedPath) {
    try {
      const installed = fs.readFileSync(installedPath, 'utf8')
      const bundled = fs.readFileSync(bundledPrdPath, 'utf8')
      outdated = installed !== bundled
    } catch { /* assume current if can't compare */ }
  }

  return { location, outdated }
}

/** Records which package version last (re)installed the plugin assets into targetDir, so a later
 * startup can tell in one file read whether a refresh is needed — cheaper than diffing every file. */
export const PLUGIN_VERSION_MARKER = '.conductor-plugin-version'

export function writePluginVersionMarker(targetDir: string, version: string): void {
  fs.writeFileSync(path.join(targetDir, PLUGIN_VERSION_MARKER), version, 'utf8')
}

export function readPluginVersionMarker(targetDir: string): string | undefined {
  try {
    return fs.readFileSync(path.join(targetDir, PLUGIN_VERSION_MARKER), 'utf8').trim()
  } catch {
    return undefined
  }
}

export interface RefreshResult {
  refreshed: boolean
  location?: 'global' | 'local'
}

/**
 * Silently re-installs plugin assets — same file-by-file logic `installPluginAssets` (and `conductor
 * init`) uses, touching only PLUGIN_FILES, cleaning LEGACY_PATHS, and merging settings.json
 * permissions, never any file outside those — when an existing install's recorded marker version is
 * older than the package currently running. Checks the global (~/.claude) location first, then local
 * (project/.claude), same precedence as {@link getPluginInstallStatus}, and refreshes whichever one is
 * actually installed. Does nothing when neither location has an install (nothing to refresh), or the
 * marker there already matches the current version. Call at MCP server / daemon startup, not on every
 * tool call — this does a few sync file reads.
 */
export function refreshPluginAssetsIfOutdated(
  assetSrcDir: string,
  globalClaudeDir: string,
  localClaudeDir: string,
  currentVersion: string = getPackageVersion()
): RefreshResult {
  const anchor = path.join('commands', 'conductor', 'prd.md')
  let targetDir: string | undefined
  let location: 'global' | 'local' | undefined
  if (fs.existsSync(path.join(globalClaudeDir, anchor))) {
    targetDir = globalClaudeDir
    location = 'global'
  } else if (fs.existsSync(path.join(localClaudeDir, anchor))) {
    targetDir = localClaudeDir
    location = 'local'
  }
  if (!targetDir) return { refreshed: false }

  const installedVersion = readPluginVersionMarker(targetDir)
  if (installedVersion === currentVersion) return { refreshed: false }

  installPluginAssets(targetDir, assetSrcDir)
  writePluginVersionMarker(targetDir, currentVersion)
  return { refreshed: true, location }
}
