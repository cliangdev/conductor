import * as fs from 'fs'
import * as path from 'path'
import * as os from 'os'
import { execSync } from 'child_process'
import { createHash } from 'crypto'
import { fileURLToPath } from 'url'
import { createRequire } from 'module'
import { readConfig as readCliConfig } from './config.js'
import { resolveProject } from '../mcp/config.js'

const PLUGIN_FILES = [
  'commands/conductor/prd.md',
  'commands/conductor/implement.md',
  'commands/conductor/fix.md',
  'commands/conductor/workflow.md',
  'commands/conductor/creative.md',
  'commands/conductor/content.md',
  'agents/conductor-researcher.md',
  'agents/conductor-coder.md',
  'agents/conductor-content-strategist.md',
  'agents/conductor-content-ideator.md',
  'agents/conductor-content-creative-director.md',
  'agents/conductor-content-scriptwriter.md',
  'agents/conductor-content-art-director.md',
  'skills/conductor-ux-ui-design/SKILL.md',
  'skills/conductor-ux-ui-design/references/design-tokens.md',
  'skills/conductor-coder/SKILL.md',
  'skills/conductor-publisher/SKILL.md',
  'skills/conductor-creative/SKILL.md',
  'skills/conductor-creative/references/brand-kit-setup.md',
  'skills/conductor-content-studio/SKILL.md',
  'skills/conductor-content-studio/references/rubric.md',
  'skills/conductor-content-studio/references/capabilities.md',
  'skills/conductor-content-studio/references/platform-specs.md',
  'skills/conductor-content-studio/references/idea-template.md',
  'skills/conductor-content-studio/references/script-template.md',
  'skills/conductor-content-studio/references/shotlist-template.md',
]

// Paths removed in previous versions — deleted on `conductor init` (and a refresh) to clean up stale installs.
const LEGACY_PATHS = [
  'agents/researcher.md',
  'skills/ux-ui-design/SKILL.md',
  'skills/ux-ui-design/references/design-tokens.md',
]

const CONDUCTOR_PERMISSIONS = ['mcp__conductor__*']

/** Anchor file used to decide whether a `.claude/` dir has the plugin installed at all. */
const PLUGIN_ANCHOR = path.join('commands', 'conductor', 'prd.md')

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

/** Writes `content` to `destPath` via a temp-file-then-rename, so a reader (or a concurrently
 * starting daemon/MCP server) never observes a partially-written file. */
function atomicWriteFileSync(destPath: string, content: string): void {
  const dir = path.dirname(destPath)
  fs.mkdirSync(dir, { recursive: true })
  const tmpPath = path.join(dir, `.${path.basename(destPath)}.tmp-${process.pid}-${Date.now()}-${Math.random().toString(36).slice(2)}`)
  fs.writeFileSync(tmpPath, content, 'utf8')
  fs.renameSync(tmpPath, destPath)
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

  atomicWriteFileSync(settingsPath, JSON.stringify(settings, null, 2) + '\n')
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

  atomicWriteFileSync(mcpPath, JSON.stringify(mcp, null, 2) + '\n')
}

export type InstallStatus = 'installed' | 'updated' | 'current'

/** Writes every PLUGIN_FILES entry into targetDir (unconditionally overwriting anything already
 * there) and removes LEGACY_PATHS — the file-level half of installing/refreshing the plugin, with no
 * opinion on settings.json or .mcp.json. Shared by {@link installPluginAssets} (which also merges
 * those) and the legacy-marker migration path in {@link refreshInstalledPluginAssets} (which
 * deliberately does not). */
function writePluginFiles(targetDir: string, assetSrcDir: string): InstallStatus {
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
      atomicWriteFileSync(destPath, srcContent)
    } else if (destContent !== srcContent) {
      anyUpdated = true
      atomicWriteFileSync(destPath, srcContent)
    }
  }

  for (const legacy of LEGACY_PATHS) {
    try { fs.rmSync(path.join(targetDir, legacy)) } catch { /* already gone */ }
  }

  if (anyNew) return 'installed'
  if (anyUpdated) return 'updated'
  return 'current'
}

/**
 * Install or update conductor Claude plugin assets into targetDir. Used only by `conductor init` —
 * this is the one place allowed to touch settings.json/.mcp.json; a background refresh
 * ({@link refreshInstalledPluginAssets}) never calls this.
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
  const status = writePluginFiles(targetDir, assetSrcDir)

  mergeSettingsJson(path.join(targetDir, 'settings.json'))
  if (mcpJsonPath) {
    mergeMcpJson(mcpJsonPath)
  }

  return status
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

/** Records which package version last (re)installed the plugin assets into targetDir, and — from this
 * version on — a sha256 per installed file, so a later refresh can tell whether the user edited a file
 * (hash mismatch, leave it alone) or it's still exactly what was shipped (safe to overwrite). */
export const PLUGIN_VERSION_MARKER = '.conductor-plugin-version'

export interface PluginVersionMarker {
  version: string
  /** relPath (one of PLUGIN_FILES) -> sha256 hex of the content this package wrote there. Present
   * only for the current JSON marker format; absent for a legacy plain-version-string marker (or when
   * no marker exists at all), which carries no hash baseline to check edits against. */
  files?: Record<string, string>
}

function sha256Hex(content: string): string {
  return createHash('sha256').update(content, 'utf8').digest('hex')
}

/** The sha256 of every currently-installed PLUGIN_FILES entry under targetDir, for stamping into the
 * marker right after a write (install, refresh, or migration) so the next refresh has a baseline. */
export function computeInstalledFileHashes(targetDir: string): Record<string, string> {
  const hashes: Record<string, string> = {}
  for (const file of PLUGIN_FILES) {
    try {
      hashes[file] = sha256Hex(fs.readFileSync(path.join(targetDir, file), 'utf8'))
    } catch { /* not installed */ }
  }
  return hashes
}

export function writePluginVersionMarker(targetDir: string, version: string, files?: Record<string, string>): void {
  const marker: PluginVersionMarker = files ? { version, files } : { version }
  atomicWriteFileSync(path.join(targetDir, PLUGIN_VERSION_MARKER), JSON.stringify(marker))
}

/** Reads the marker, transparently handling the legacy format (a bare version string, no JSON) that
 * predates per-file hashes — returned with `files` left undefined so callers can tell the two apart. */
export function readPluginVersionMarker(targetDir: string): PluginVersionMarker | undefined {
  let raw: string
  try {
    raw = fs.readFileSync(path.join(targetDir, PLUGIN_VERSION_MARKER), 'utf8').trim()
  } catch {
    return undefined
  }
  if (!raw) return undefined

  try {
    const parsed = JSON.parse(raw) as Partial<PluginVersionMarker>
    if (parsed && typeof parsed.version === 'string') {
      const files = parsed.files && typeof parsed.files === 'object' ? parsed.files : undefined
      return { version: parsed.version, files }
    }
  } catch {
    // Not JSON — a legacy marker written as a bare version string.
  }
  return { version: raw }
}

function parseSemverTriple(v: string): [number, number, number] {
  const m = /^(\d+)\.(\d+)\.(\d+)/.exec(v.trim())
  if (!m) return [0, 0, 0]
  return [Number(m[1]), Number(m[2]), Number(m[3])]
}

/** True only when `a` is strictly newer than `b` (major.minor.patch; pre-release/build tags are
 * ignored — this package only ever compares its own release versions). Equal or older returns false,
 * which is what makes a refresh a no-op instead of silently re-running on every startup. */
function isNewerVersion(a: string, b: string): boolean {
  const [aMaj, aMin, aPatch] = parseSemverTriple(a)
  const [bMaj, bMin, bPatch] = parseSemverTriple(b)
  if (aMaj !== bMaj) return aMaj > bMaj
  if (aMin !== bMin) return aMin > bMin
  return aPatch > bPatch
}

/** Overwrites a plugin file only when it's missing or its current content still hashes to what was
 * recorded at the last install/refresh — i.e. the user hasn't touched it. A file with no recorded hash
 * that still exists is left alone too (unknown provenance); a brand-new PLUGIN_FILES entry with no
 * recorded hash is written because it's simply missing. Never touches settings.json or .mcp.json.
 *
 * Returns the new hash for every file this call actually wrote (or found already matching the bundled
 * content). Files it left alone because the user edited them are NOT included — the caller must keep
 * their previously recorded hash rather than recompute one from the edited content on disk, or the next
 * refresh would see disk-matches-recorded-hash and silently adopt the edit as "clean", overwriting it. */
function overwriteUneditedFiles(targetDir: string, assetSrcDir: string, recordedHashes: Record<string, string>): Record<string, string> {
  const writtenHashes: Record<string, string> = {}

  for (const file of PLUGIN_FILES) {
    const srcPath = path.join(assetSrcDir, file)
    let srcContent: string
    try {
      srcContent = fs.readFileSync(srcPath, 'utf8')
    } catch {
      continue // no longer shipped
    }

    const destPath = path.join(targetDir, file)
    let destContent: string | null = null
    try {
      destContent = fs.readFileSync(destPath, 'utf8')
    } catch { /* missing */ }

    if (destContent === null) {
      atomicWriteFileSync(destPath, srcContent)
      writtenHashes[file] = sha256Hex(srcContent)
      continue
    }

    const recordedHash = recordedHashes[file]
    if (recordedHash !== undefined && sha256Hex(destContent) === recordedHash) {
      if (destContent !== srcContent) {
        atomicWriteFileSync(destPath, srcContent)
      }
      writtenHashes[file] = sha256Hex(srcContent)
    }
    // else: edited by the user (or no baseline to trust) — leave it exactly as it is, and leave its
    // hash out of writtenHashes so the caller keeps the previously recorded one.
  }

  for (const legacy of LEGACY_PATHS) {
    try { fs.rmSync(path.join(targetDir, legacy)) } catch { /* already gone */ }
  }

  return writtenHashes
}

/**
 * Refreshes one install location in place, if the running version is newer than what's recorded there.
 * A JSON marker (with `files`) does the selective, edit-preserving overwrite; a legacy marker (a bare
 * version string) or no marker at all has no hash baseline, so this migrates it the same way
 * `installPluginAssets` would — overwrite our own files unconditionally — and starts recording hashes
 * from here on. Never touches settings.json or .mcp.json. Returns whether anything actually changed.
 */
function refreshOneLocation(targetDir: string, assetSrcDir: string, currentVersion: string): boolean {
  const marker = readPluginVersionMarker(targetDir)
  if (marker && !isNewerVersion(currentVersion, marker.version)) return false

  let newHashes: Record<string, string>
  if (marker?.files) {
    const written = overwriteUneditedFiles(targetDir, assetSrcDir, marker.files)
    // Files that were skipped (user-edited) keep their previously recorded hash so they're
    // still recognized as edited — and thus left alone — by every future refresh.
    newHashes = { ...marker.files, ...written }
  } else {
    writePluginFiles(targetDir, assetSrcDir)
    newHashes = computeInstalledFileHashes(targetDir)
  }
  writePluginVersionMarker(targetDir, currentVersion, newHashes)
  return true
}

/** True if `dir` is inside a git work-tree; returns its root, or undefined outside one (or if `git`
 * itself is unavailable — never throws). */
function findGitRoot(startDir: string): string | undefined {
  try {
    const root = execSync('git rev-parse --show-toplevel', {
      cwd: startDir,
      encoding: 'utf8',
      stdio: ['pipe', 'pipe', 'pipe'],
    }).trim()
    return root || undefined
  } catch {
    return undefined
  }
}

/**
 * Finds the project `.claude/` that `cwd` belongs to — never bare `cwd` itself, which for the MCP
 * server or the daemon is rarely the project root. Prefers the localPath `conductor init` recorded for
 * whichever project this cwd resolves to (the same `projects` map + cwd-matching `resolveProject` uses
 * for MCP tool calls), but only when `resolveProject` actually derived that project *from* this cwd
 * (source `cwd` or `env`) — since that survives cwd being a subdirectory or symlink. A `fallback`
 * source (cwd matched no configured project, or there's no map at all) means the resolved project's
 * localPath has nothing to do with this cwd — using it would silently point the refresh at some other,
 * unrelated project's `.claude/`. In that case (and whenever there's no on-disk config to consult, e.g.
 * an ephemeral container) fall back to walking up to the enclosing git repo's root instead.
 */
function resolveLocalClaudeDir(cwd: string): string | undefined {
  try {
    const config = readCliConfig()
    if (config) {
      const resolution = resolveProject(config, cwd)
      if (resolution.source === 'cwd' || resolution.source === 'env') {
        const localPath = config.projects?.[resolution.projectId]?.localPath
        if (localPath) return path.join(localPath, '.claude')
      }
    }
  } catch { /* fall through to git */ }

  const gitRoot = findGitRoot(cwd)
  return gitRoot ? path.join(gitRoot, '.claude') : undefined
}

export interface RefreshOptions {
  /** Called once per location actually refreshed, and once if the whole thing fails; defaults to a
   * no-op so callers that don't care about logging don't have to pass anything. */
  log?: (message: string) => void
  assetSrcDir?: string
  currentVersion?: string
  /** Override for tests; production callers omit this and get `~/.claude`. */
  globalClaudeDir?: string
  /** Override for tests; production callers omit this and let it resolve from `cwd`. */
  localClaudeDir?: string
  cwd?: string
}

/**
 * Refreshes every existing Claude plugin install — the global one (`~/.claude`) and the project-local
 * one for whichever project `cwd` belongs to — in place, but only the ones that actually exist and
 * only when the running package version is newer than what each install's marker records (equal or
 * older is a no-op, per install). Never touches settings.json or .mcp.json — `conductor init` alone
 * manages those. Writes are atomic (temp file + rename). Swallows every error so a broken refresh can
 * never block the MCP server or the daemon from starting; logs through `log`.
 *
 * Single implementation shared by the MCP server (`mcp/index.ts`) and `conductor start`
 * (`commands/start.ts`) startup — call this instead of duplicating the check.
 */
export function refreshInstalledPluginAssets(opts: RefreshOptions = {}): void {
  const log = opts.log ?? (() => {})
  try {
    const assetSrcDir = opts.assetSrcDir ?? getAssetSrcDir()
    const currentVersion = opts.currentVersion ?? getPackageVersion()
    const cwd = opts.cwd ?? process.cwd()
    const globalDir = opts.globalClaudeDir ?? path.join(os.homedir(), '.claude')
    const localDir = opts.localClaudeDir ?? resolveLocalClaudeDir(cwd)

    const targets: Array<{ dir: string; location: 'global' | 'local' }> = []
    if (fs.existsSync(path.join(globalDir, PLUGIN_ANCHOR))) {
      targets.push({ dir: globalDir, location: 'global' })
    }
    if (localDir && path.resolve(localDir) !== path.resolve(globalDir) && fs.existsSync(path.join(localDir, PLUGIN_ANCHOR))) {
      targets.push({ dir: localDir, location: 'local' })
    }

    for (const { dir, location } of targets) {
      if (refreshOneLocation(dir, assetSrcDir, currentVersion)) {
        log(`conductor: refreshed Claude plugin assets (${location})`)
      }
    }
  } catch (err) {
    log(`conductor: could not refresh Claude plugin assets: ${err instanceof Error ? err.message : String(err)}`)
  }
}
