import { describe, it, expect, vi } from 'vitest'
import { runUpgrade } from '../commands/upgrade.js'

function run(spawnResult: { status: number | null; error?: Error }, installed: boolean) {
  const out: string[] = []
  const err: string[] = []
  const clear = vi.fn()
  const spawn = vi.fn(() => spawnResult)
  const code = runUpgrade({
    spawn: spawn as never,
    log: (m) => out.push(m),
    errLog: (m) => err.push(m),
    clearCache: clear,
    pluginInstalled: () => installed,
    cwd: '/repo',
    home: '/home',
  })
  return { code, out: out.join('\n'), err: err.join('\n'), clear, spawn }
}

describe('conductor upgrade', () => {
  it('installs @latest with inherited stdio, clears the cache, and points at restart when the plugin is installed', () => {
    const r = run({ status: 0 }, true)
    expect(r.code).toBe(0)
    expect(r.spawn).toHaveBeenCalledWith(expect.stringMatching(/^npm/), ['i', '-g', '@cliangdev/conductor@latest'], { stdio: 'inherit' })
    expect(r.clear).toHaveBeenCalled()
    expect(r.out).toContain('Restart Claude Code')
  })

  it('tells the user to run init per repo when no plugin install is detected', () => {
    const r = run({ status: 0 }, false)
    expect(r.code).toBe(0)
    expect(r.out).toContain('Run `conductor init` in each repo that uses Conductor to update its Claude commands.')
  })

  it('explains EACCES without suggesting sudo', () => {
    const r = run({ status: null, error: Object.assign(new Error('x'), { code: 'EACCES' }) }, true)
    expect(r.code).toBe(1)
    expect(r.err).toContain('Permission denied')
    expect(r.err).toMatch(/nvm|prefix/)
    expect(r.err).toContain('without sudo')
    expect(r.clear).not.toHaveBeenCalled()
  })

  it('explains a non-zero npm exit', () => {
    const r = run({ status: 243 }, true)
    expect(r.code).toBe(243)
    expect(r.err).toContain('EACCES')
    expect(r.clear).not.toHaveBeenCalled()
  })

  it('explains npm missing', () => {
    const r = run({ status: null, error: Object.assign(new Error('x'), { code: 'ENOENT' }) }, true)
    expect(r.code).toBe(1)
    expect(r.err).toContain('npm was not found')
  })
})
