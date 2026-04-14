import * as fs from 'fs'
import * as path from 'path'
import { fileURLToPath } from 'url'

const PLUGIN_FILES = [
  'commands/conductor/prd.md',
  'commands/conductor/implement.md',
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
