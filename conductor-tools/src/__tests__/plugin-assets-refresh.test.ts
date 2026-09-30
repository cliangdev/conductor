import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import * as fs from 'fs'
import * as path from 'path'
import * as os from 'os'
import {
  getAssetSrcDir,
  installPluginAssets,
  writePluginVersionMarker,
  readPluginVersionMarker,
  refreshPluginAssetsIfOutdated,
  PLUGIN_VERSION_MARKER,
} from '../lib/plugin-assets.js'

describe('refreshPluginAssetsIfOutdated', () => {
  let assetSrcDir: string
  let globalDir: string
  let localDir: string

  beforeEach(() => {
    assetSrcDir = getAssetSrcDir()
    globalDir = fs.mkdtempSync(path.join(os.tmpdir(), 'conductor-plugin-global-'))
    localDir = fs.mkdtempSync(path.join(os.tmpdir(), 'conductor-plugin-local-'))
  })

  afterEach(() => {
    fs.rmSync(globalDir, { recursive: true, force: true })
    fs.rmSync(localDir, { recursive: true, force: true })
  })

  it('does nothing when no install exists at either location', () => {
    const result = refreshPluginAssetsIfOutdated(assetSrcDir, globalDir, localDir, '1.0.0')

    expect(result).toEqual({ refreshed: false })
    expect(fs.existsSync(path.join(localDir, 'commands'))).toBe(false)
    expect(fs.existsSync(path.join(globalDir, 'commands'))).toBe(false)
  })

  it('does nothing when the installed marker already matches the current version', () => {
    installPluginAssets(localDir, assetSrcDir)
    writePluginVersionMarker(localDir, '1.2.3')

    const result = refreshPluginAssetsIfOutdated(assetSrcDir, globalDir, localDir, '1.2.3')

    expect(result).toEqual({ refreshed: false })
    // Marker untouched (still the exact same value, not just equal-by-coincidence).
    expect(readPluginVersionMarker(localDir)).toBe('1.2.3')
  })

  it('rewrites files and bumps the marker when the installed version is older', () => {
    installPluginAssets(localDir, assetSrcDir)
    writePluginVersionMarker(localDir, '0.1.0')
    // Simulate a file that's drifted from the bundled version, the way an older release's content would.
    const prdPath = path.join(localDir, 'commands', 'conductor', 'prd.md')
    fs.writeFileSync(prdPath, 'stale content from an old release', 'utf8')

    const result = refreshPluginAssetsIfOutdated(assetSrcDir, globalDir, localDir, '9.9.9')

    expect(result).toEqual({ refreshed: true, location: 'local' })
    expect(readPluginVersionMarker(localDir)).toBe('9.9.9')
    const bundled = fs.readFileSync(path.join(assetSrcDir, 'commands', 'conductor', 'prd.md'), 'utf8')
    expect(fs.readFileSync(prdPath, 'utf8')).toBe(bundled)
  })

  it('treats a pre-existing install with no marker yet as outdated (writes one)', () => {
    installPluginAssets(localDir, assetSrcDir)
    expect(readPluginVersionMarker(localDir)).toBeUndefined()

    const result = refreshPluginAssetsIfOutdated(assetSrcDir, globalDir, localDir, '9.9.9')

    expect(result).toEqual({ refreshed: true, location: 'local' })
    expect(readPluginVersionMarker(localDir)).toBe('9.9.9')
  })

  it('prefers a global install over a local one when both exist', () => {
    installPluginAssets(globalDir, assetSrcDir)
    writePluginVersionMarker(globalDir, '0.1.0')
    installPluginAssets(localDir, assetSrcDir)
    writePluginVersionMarker(localDir, '9.9.9')

    const result = refreshPluginAssetsIfOutdated(assetSrcDir, globalDir, localDir, '9.9.9')

    expect(result).toEqual({ refreshed: true, location: 'global' })
    expect(readPluginVersionMarker(globalDir)).toBe('9.9.9')
    // Local was already current and untouched by this call.
    expect(readPluginVersionMarker(localDir)).toBe('9.9.9')
  })

  it('never writes anything outside the plugin\'s own paths (no stray files in the install dir)', () => {
    installPluginAssets(localDir, assetSrcDir)
    writePluginVersionMarker(localDir, '0.1.0')
    fs.writeFileSync(path.join(localDir, 'unrelated-user-file.txt'), 'do not touch me', 'utf8')

    refreshPluginAssetsIfOutdated(assetSrcDir, globalDir, localDir, '9.9.9')

    expect(fs.readFileSync(path.join(localDir, 'unrelated-user-file.txt'), 'utf8')).toBe('do not touch me')
  })

  it('exposes the marker filename as a stable constant', () => {
    expect(PLUGIN_VERSION_MARKER).toBe('.conductor-plugin-version')
  })
})
