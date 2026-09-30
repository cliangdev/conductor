import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import * as fs from 'fs'
import * as path from 'path'
import * as os from 'os'
import * as crypto from 'crypto'
import { execSync } from 'child_process'
import {
  getAssetSrcDir,
  installPluginAssets,
  writePluginVersionMarker,
  readPluginVersionMarker,
  computeInstalledFileHashes,
  refreshInstalledPluginAssets,
  PLUGIN_VERSION_MARKER,
} from '../lib/plugin-assets.js'
import { readConfig } from '../lib/config.js'

vi.mock('../lib/config.js', () => ({
  readConfig: vi.fn(),
}))

const PRD_REL_PATH = path.join('commands', 'conductor', 'prd.md')

function sha256(content: string): string {
  return crypto.createHash('sha256').update(content, 'utf8').digest('hex')
}

describe('refreshInstalledPluginAssets', () => {
  let assetSrcDir: string
  let globalDir: string
  let localDir: string
  let logs: string[]
  const log = (message: string) => logs.push(message)

  beforeEach(() => {
    assetSrcDir = getAssetSrcDir()
    globalDir = fs.mkdtempSync(path.join(os.tmpdir(), 'conductor-plugin-global-'))
    localDir = fs.mkdtempSync(path.join(os.tmpdir(), 'conductor-plugin-local-'))
    logs = []
  })

  afterEach(() => {
    fs.rmSync(globalDir, { recursive: true, force: true })
    fs.rmSync(localDir, { recursive: true, force: true })
  })

  function opts(currentVersion: string) {
    return { assetSrcDir, globalClaudeDir: globalDir, localClaudeDir: localDir, currentVersion, log }
  }

  it('does nothing when no install exists at either location', () => {
    refreshInstalledPluginAssets(opts('1.0.0'))

    expect(logs).toEqual([])
    expect(fs.existsSync(path.join(localDir, 'commands'))).toBe(false)
    expect(fs.existsSync(path.join(globalDir, 'commands'))).toBe(false)
  })

  it('does nothing when the running version equals the marker', () => {
    installPluginAssets(localDir, assetSrcDir)
    writePluginVersionMarker(localDir, '1.2.3', computeInstalledFileHashes(localDir))

    refreshInstalledPluginAssets(opts('1.2.3'))

    expect(logs).toEqual([])
    expect(readPluginVersionMarker(localDir)?.version).toBe('1.2.3')
  })

  it('does nothing when the running version is older than the marker', () => {
    installPluginAssets(localDir, assetSrcDir)
    writePluginVersionMarker(localDir, '9.9.9', computeInstalledFileHashes(localDir))

    refreshInstalledPluginAssets(opts('1.0.0'))

    expect(logs).toEqual([])
    expect(readPluginVersionMarker(localDir)?.version).toBe('9.9.9')
  })

  it('refreshes both installs when each exists and the running version is newer', () => {
    installPluginAssets(globalDir, assetSrcDir)
    writePluginVersionMarker(globalDir, '0.1.0', computeInstalledFileHashes(globalDir))
    installPluginAssets(localDir, assetSrcDir)
    writePluginVersionMarker(localDir, '0.1.0', computeInstalledFileHashes(localDir))

    refreshInstalledPluginAssets(opts('9.9.9'))

    expect(logs).toEqual(
      expect.arrayContaining([
        'conductor: refreshed Claude plugin assets (global)',
        'conductor: refreshed Claude plugin assets (local)',
      ])
    )
    expect(readPluginVersionMarker(globalDir)?.version).toBe('9.9.9')
    expect(readPluginVersionMarker(localDir)?.version).toBe('9.9.9')
  })

  it('preserves a file the user edited, updates one that matches its recorded hash, and restores one that went missing', () => {
    installPluginAssets(localDir, assetSrcDir)
    const hashes = computeInstalledFileHashes(localDir)
    writePluginVersionMarker(localDir, '0.1.0', hashes)

    const editedPath = path.join(localDir, PRD_REL_PATH)
    fs.writeFileSync(editedPath, 'my own hand-edited notes', 'utf8')

    const untouchedRelPath = path.join('commands', 'conductor', 'implement.md')
    const untouchedPath = path.join(localDir, untouchedRelPath)
    const bundledUntouched = fs.readFileSync(path.join(assetSrcDir, untouchedRelPath), 'utf8')

    const missingRelPath = path.join('agents', 'conductor-researcher.md')
    const missingPath = path.join(localDir, missingRelPath)
    fs.rmSync(missingPath)

    refreshInstalledPluginAssets(opts('9.9.9'))

    // Edited file is left exactly as the user left it.
    expect(fs.readFileSync(editedPath, 'utf8')).toBe('my own hand-edited notes')
    // Untouched file is still whatever the bundled asset says (no-op update since it matched already,
    // but this proves the selective path doesn't skip legitimately unedited files).
    expect(fs.readFileSync(untouchedPath, 'utf8')).toBe(bundledUntouched)
    // Missing file is restored.
    expect(fs.existsSync(missingPath)).toBe(true)
    expect(fs.readFileSync(missingPath, 'utf8')).toBe(fs.readFileSync(path.join(assetSrcDir, missingRelPath), 'utf8'))

    // The new marker keeps the *original* recorded hash for the edited file (not a hash of the user's
    // edited content) — so a future refresh still sees disk-content != recorded-hash and continues to
    // recognize it as user-edited, rather than treating "matches its own last-recorded hash" as clean.
    const newMarker = readPluginVersionMarker(localDir)
    expect(newMarker?.version).toBe('9.9.9')
    expect(newMarker?.files?.[PRD_REL_PATH]).toBe(hashes[PRD_REL_PATH])
    expect(newMarker?.files?.[PRD_REL_PATH]).not.toBe(sha256('my own hand-edited notes'))
  })

  it('keeps an edited file kept across a second refresh at a still-newer version', () => {
    installPluginAssets(localDir, assetSrcDir)
    writePluginVersionMarker(localDir, '0.1.0', computeInstalledFileHashes(localDir))

    const editedPath = path.join(localDir, PRD_REL_PATH)
    fs.writeFileSync(editedPath, 'my own hand-edited notes', 'utf8')

    // First refresh: edited file is left alone.
    refreshInstalledPluginAssets(opts('2.0.0'))
    expect(fs.readFileSync(editedPath, 'utf8')).toBe('my own hand-edited notes')

    // Second refresh, at a newer version still: must remain untouched — this is the case the
    // bug allowed to regress, because a naive "recompute hashes from disk" would have made the
    // edited file look clean again after the first refresh.
    refreshInstalledPluginAssets(opts('3.0.0'))
    expect(fs.readFileSync(editedPath, 'utf8')).toBe('my own hand-edited notes')
    expect(readPluginVersionMarker(localDir)?.version).toBe('3.0.0')
  })

  it('migrates a legacy plain-string marker: overwrites our files unconditionally and starts recording hashes', () => {
    installPluginAssets(localDir, assetSrcDir)
    // Simulate the old marker format: a bare version string, not JSON.
    fs.writeFileSync(path.join(localDir, PLUGIN_VERSION_MARKER), '0.1.0', 'utf8')
    const prdPath = path.join(localDir, PRD_REL_PATH)
    fs.writeFileSync(prdPath, 'stale content from an old release', 'utf8')

    refreshInstalledPluginAssets(opts('9.9.9'))

    expect(logs).toEqual(['conductor: refreshed Claude plugin assets (local)'])
    const bundled = fs.readFileSync(path.join(assetSrcDir, PRD_REL_PATH), 'utf8')
    expect(fs.readFileSync(prdPath, 'utf8')).toBe(bundled)
    const marker = readPluginVersionMarker(localDir)
    expect(marker?.version).toBe('9.9.9')
    expect(marker?.files?.[PRD_REL_PATH]).toBe(sha256(bundled))
  })

  it('treats a pre-existing install with no marker at all the same as a legacy marker', () => {
    installPluginAssets(localDir, assetSrcDir)
    expect(readPluginVersionMarker(localDir)).toBeUndefined()

    refreshInstalledPluginAssets(opts('9.9.9'))

    const marker = readPluginVersionMarker(localDir)
    expect(marker?.version).toBe('9.9.9')
    expect(marker?.files).toBeDefined()
  })

  it('never touches settings.json', () => {
    installPluginAssets(localDir, assetSrcDir)
    writePluginVersionMarker(localDir, '0.1.0', computeInstalledFileHashes(localDir))
    const settingsPath = path.join(localDir, 'settings.json')
    fs.writeFileSync(settingsPath, JSON.stringify({ theme: 'dark' }), 'utf8')
    const before = fs.readFileSync(settingsPath, 'utf8')

    refreshInstalledPluginAssets(opts('9.9.9'))

    expect(fs.readFileSync(settingsPath, 'utf8')).toBe(before)
  })

  it('never writes anything outside the plugin\'s own paths (no stray files in the install dir)', () => {
    installPluginAssets(localDir, assetSrcDir)
    writePluginVersionMarker(localDir, '0.1.0', computeInstalledFileHashes(localDir))
    fs.writeFileSync(path.join(localDir, 'unrelated-user-file.txt'), 'do not touch me', 'utf8')

    refreshInstalledPluginAssets(opts('9.9.9'))

    expect(fs.readFileSync(path.join(localDir, 'unrelated-user-file.txt'), 'utf8')).toBe('do not touch me')
  })

  it('exposes the marker filename as a stable constant', () => {
    expect(PLUGIN_VERSION_MARKER).toBe('.conductor-plugin-version')
  })

  it('never throws — swallows errors and reports them through log', () => {
    expect(() =>
      refreshInstalledPluginAssets({
        assetSrcDir,
        globalClaudeDir: globalDir,
        localClaudeDir: localDir,
        currentVersion: 'not-a-real-version',
        log,
      })
    ).not.toThrow()
  })
})

describe('resolveLocalClaudeDir (via refreshInstalledPluginAssets cwd resolution)', () => {
  let assetSrcDir: string
  let gitRepoParent: string
  let nestedProjectDir: string
  let globalDir: string
  let logs: string[]
  const log = (message: string) => logs.push(message)

  beforeEach(() => {
    assetSrcDir = getAssetSrcDir()
    gitRepoParent = fs.mkdtempSync(path.join(os.tmpdir(), 'conductor-plugin-gitparent-'))
    execSync('git init', { cwd: gitRepoParent, stdio: 'ignore' })
    nestedProjectDir = path.join(gitRepoParent, 'nested-project')
    fs.mkdirSync(nestedProjectDir, { recursive: true })
    globalDir = fs.mkdtempSync(path.join(os.tmpdir(), 'conductor-plugin-global-'))
    logs = []
    vi.mocked(readConfig).mockReset()
  })

  afterEach(() => {
    fs.rmSync(gitRepoParent, { recursive: true, force: true })
    fs.rmSync(globalDir, { recursive: true, force: true })
  })

  it('uses the configured project\'s localPath when cwd resolves it (source "cwd")', () => {
    // Install the plugin both under the configured project root's .claude and under the (different)
    // git root's .claude, so we can tell which one the refresh actually touched.
    const nestedProjectClaudeDir = path.join(nestedProjectDir, '.claude')
    const gitRootClaudeDir = path.join(gitRepoParent, '.claude')
    installPluginAssets(nestedProjectClaudeDir, assetSrcDir)
    writePluginVersionMarker(nestedProjectClaudeDir, '0.1.0', computeInstalledFileHashes(nestedProjectClaudeDir))
    installPluginAssets(gitRootClaudeDir, assetSrcDir)
    writePluginVersionMarker(gitRootClaudeDir, '0.1.0', computeInstalledFileHashes(gitRootClaudeDir))

    vi.mocked(readConfig).mockReturnValue({
      apiKey: 'k', projectId: 'proj1', projectName: 'Proj 1', email: 'e', apiUrl: 'u',
      projects: { proj1: { localPath: nestedProjectDir, projectName: 'Proj 1' } },
    })

    refreshInstalledPluginAssets({
      assetSrcDir,
      globalClaudeDir: globalDir,
      cwd: nestedProjectDir, // exact match against the projects map -> resolveProject source 'cwd'
      currentVersion: '9.9.9',
      log,
    })

    expect(logs).toEqual(['conductor: refreshed Claude plugin assets (local)'])
    expect(readPluginVersionMarker(nestedProjectClaudeDir)?.version).toBe('9.9.9')
    // The git root's own (different, unrelated) install must be left untouched.
    expect(readPluginVersionMarker(gitRootClaudeDir)?.version).toBe('0.1.0')
  })

  it('falls back to the git root — not a stale configured localPath — when cwd matches no project (source "fallback")', () => {
    const staleConfiguredPath = fs.mkdtempSync(path.join(os.tmpdir(), 'conductor-plugin-stale-'))
    try {
      const staleClaudeDir = path.join(staleConfiguredPath, '.claude')
      const gitRootClaudeDir = path.join(gitRepoParent, '.claude')
      installPluginAssets(staleClaudeDir, assetSrcDir)
      writePluginVersionMarker(staleClaudeDir, '0.1.0', computeInstalledFileHashes(staleClaudeDir))
      installPluginAssets(gitRootClaudeDir, assetSrcDir)
      writePluginVersionMarker(gitRootClaudeDir, '0.1.0', computeInstalledFileHashes(gitRootClaudeDir))

      // The projects map has an entry, but its localPath has nothing to do with cwd — cwd matches
      // no project, so resolveProject falls back to the active projectId (source 'fallback').
      vi.mocked(readConfig).mockReturnValue({
        apiKey: 'k', projectId: 'proj1', projectName: 'Proj 1', email: 'e', apiUrl: 'u',
        projects: { proj1: { localPath: staleConfiguredPath, projectName: 'Proj 1' } },
      })

      refreshInstalledPluginAssets({
        assetSrcDir,
        globalClaudeDir: globalDir,
        cwd: gitRepoParent, // inside a git repo that matches no configured project
        currentVersion: '9.9.9',
        log,
      })

      expect(logs).toEqual(['conductor: refreshed Claude plugin assets (local)'])
      // The git root's install was refreshed...
      expect(readPluginVersionMarker(gitRootClaudeDir)?.version).toBe('9.9.9')
      // ...and the stale configured (but unrelated) project path was never touched.
      expect(readPluginVersionMarker(staleClaudeDir)?.version).toBe('0.1.0')
    } finally {
      fs.rmSync(staleConfiguredPath, { recursive: true, force: true })
    }
  })
})

describe('writePluginVersionMarker / readPluginVersionMarker', () => {
  let dir: string

  beforeEach(() => {
    dir = fs.mkdtempSync(path.join(os.tmpdir(), 'conductor-plugin-marker-'))
  })

  afterEach(() => {
    fs.rmSync(dir, { recursive: true, force: true })
  })

  it('round-trips version and files through JSON', () => {
    writePluginVersionMarker(dir, '1.0.0', { [PRD_REL_PATH]: 'abc123' })
    const marker = readPluginVersionMarker(dir)
    expect(marker).toEqual({ version: '1.0.0', files: { [PRD_REL_PATH]: 'abc123' } })
  })

  it('reads a legacy bare-string marker with files left undefined', () => {
    fs.writeFileSync(path.join(dir, PLUGIN_VERSION_MARKER), '2.3.4', 'utf8')
    expect(readPluginVersionMarker(dir)).toEqual({ version: '2.3.4' })
  })

  it('returns undefined when no marker file exists', () => {
    expect(readPluginVersionMarker(dir)).toBeUndefined()
  })
})
