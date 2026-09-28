import { describe, it, expect, afterEach } from 'vitest'
import * as fs from 'fs'
import * as path from 'path'
import * as os from 'os'
import { copyCreativeRuntime, CREATIVE_RUNTIME_FILES, SOURCE_ROOT } from '../../scripts/build-creative.mjs'

describe('build-creative copy script', () => {
  let tmpDir: string | undefined

  afterEach(() => {
    if (tmpDir) fs.rmSync(tmpDir, { recursive: true, force: true })
    tmpDir = undefined
  })

  it('finds every listed file in the real conductor-creative/ sibling', () => {
    // Guards against the list drifting from what actually exists on disk (a rename in
    // conductor-creative/ would otherwise only surface as a runtime failure much later).
    for (const rel of CREATIVE_RUNTIME_FILES) {
      expect(fs.existsSync(path.join(SOURCE_ROOT, rel)), `missing ${rel} under ${SOURCE_ROOT}`).toBe(true)
    }
  })

  it('copies every listed file to the same relative path under the destination root', () => {
    tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'conductor-creative-copy-'))
    const written = copyCreativeRuntime(SOURCE_ROOT, tmpDir)

    expect(written).toHaveLength(CREATIVE_RUNTIME_FILES.length)
    for (const rel of CREATIVE_RUNTIME_FILES) {
      const destPath = path.join(tmpDir, rel)
      expect(fs.existsSync(destPath), `${rel} was not copied`).toBe(true)
      const srcContent = fs.readFileSync(path.join(SOURCE_ROOT, rel))
      const destContent = fs.readFileSync(destPath)
      expect(destContent.equals(srcContent)).toBe(true)
    }
  })

  it('preserves the job/render.mjs -> ../ package-root relationship the render core relies on', () => {
    tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'conductor-creative-copy-'))
    copyCreativeRuntime(SOURCE_ROOT, tmpDir)

    // job/render.mjs resolves its static-file root as `join(dirname(thisFile), '..')`; the frame/sheet
    // HTML pages it serves must exist one level up from job/ in the copy, exactly as in the source.
    expect(fs.existsSync(path.join(tmpDir, 'job', 'render.mjs'))).toBe(true)
    expect(fs.existsSync(path.join(tmpDir, 'frame.html'))).toBe(true)
    expect(fs.existsSync(path.join(tmpDir, 'sheet.html'))).toBe(true)
  })

  it('throws a clear error when a listed file is missing from the source', () => {
    tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'conductor-creative-copy-'))
    const emptySource = fs.mkdtempSync(path.join(os.tmpdir(), 'conductor-creative-empty-'))
    try {
      expect(() => copyCreativeRuntime(emptySource, tmpDir!)).toThrow(/expected file\(s\) missing/)
    } finally {
      fs.rmSync(emptySource, { recursive: true, force: true })
    }
  })

  it('does not include editor-only or test/dependency files', () => {
    expect(CREATIVE_RUNTIME_FILES).not.toContain('mount.js')
    expect(CREATIVE_RUNTIME_FILES).not.toContain('copy-rules.js')
    expect(CREATIVE_RUNTIME_FILES.some((f) => f.startsWith('test/'))).toBe(false)
    expect(CREATIVE_RUNTIME_FILES.some((f) => f.includes('node_modules'))).toBe(false)
    expect(CREATIVE_RUNTIME_FILES).not.toContain('job/package.json')
  })
})
