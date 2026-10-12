import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { EventEmitter } from 'node:events'
import * as fs from 'node:fs'
import * as os from 'node:os'
import * as path from 'node:path'

class FakeChild extends EventEmitter {
  stdout = new EventEmitter()
  stderr = new EventEmitter()
  stdin = { written: '', end: (s?: string) => void (this.stdin.written += s ?? ''), on: () => {} }
  kill = vi.fn()
}

const spawnMock = vi.fn()
vi.mock('node:child_process', () => ({ spawn: (...args: unknown[]) => spawnMock(...args) }))

const PNG = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGP4z8DwHwAFAAH/q842iQAAAABJRU5ErkJggg==',
  'base64'
)

type Handler = (child: FakeChild, args: string[]) => void
let handlers: { version: Handler; login: Handler; exec: Handler }

const done = (child: FakeChild, code: number, out = '') =>
  setImmediate(() => {
    if (out) child.stdout.emit('data', out)
    child.emit('exit', code)
  })

let tmp: string
let codexHome: string
let lib: typeof import('../lib/codex-image.js')

beforeEach(async () => {
  vi.resetModules()
  spawnMock.mockReset()
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'codex-img-'))
  codexHome = path.join(tmp, 'home')
  fs.mkdirSync(codexHome)
  process.env['CODEX_HOME'] = codexHome
  delete process.env['CONDUCTOR_CODEX_PATH']
  for (const k of ['MODEL', 'REASONING', 'QUALITY']) delete process.env[`CONDUCTOR_CODEX_IMAGE_${k}`]
  handlers = {
    version: (c) => done(c, 0, 'codex-cli 0.162.1\n'),
    login: (c) => done(c, 0, 'Logged in using ChatGPT\n'),
    exec: (c) => done(c, 0),
  }
  spawnMock.mockImplementation((cmd: string, args: string[]) => {
    const child = new FakeChild()
    if (cmd !== 'codex') done(child, 1) // ffmpeg: unavailable -> PNG preview fallback
    else if (args[0] === '--version') handlers.version(child, args)
    else if (args[0] === 'login') handlers.login(child, args)
    else setImmediate(() => handlers.exec(child, args))
    return child
  })
  lib = await import('../lib/codex-image.js')
})

afterEach(() => {
  delete process.env['CODEX_HOME']
  fs.rmSync(tmp, { recursive: true, force: true })
})

const outDirOf = (args: string[]) => args[args.indexOf('-C') + 1]!
const lastOf = (args: string[]) => args[args.indexOf('-o') + 1]!

describe('generateImages', () => {
  it('uses files reported by the agent and writes sidecars', async () => {
    handlers.exec = (c, args) => {
      const img = path.join(outDirOf(args), 'image-1.png')
      fs.writeFileSync(img, PNG)
      fs.writeFileSync(lastOf(args), JSON.stringify({ files: [img], revisedPrompt: 'a revised prompt' }))
      done(c, 0)
    }
    const out = path.join(tmp, 'out')
    const progress: string[] = []
    const res = await lib.generateImages({ prompt: 'a kitchen', outDir: out, onProgress: (m) => progress.push(m) })
    expect(res.files).toEqual([{ path: path.join(out, 'image-1.png'), width: 1, height: 1 }])
    expect(res.revisedPrompt).toBe('a revised prompt')
    expect(res.preview?.mimeType).toBe('image/png')
    const sidecar = JSON.parse(fs.readFileSync(path.join(out, 'image-1.png.provenance.json'), 'utf8'))
    expect(sidecar).toMatchObject({ licence: 'Generated, house use', aiGenerated: true, prompt: 'a kitchen', revisedPrompt: 'a revised prompt' })
    expect(sidecar.source).toMatch(/^Generated with Codex image_gen on \d{4}-\d{2}-\d{2}$/)
    expect(progress.length).toBeGreaterThan(0)
  })

  it('passes the documented flags, references and the prompt on stdin', async () => {
    let seen: string[] = []
    let stdin = ''
    spawnMock.mockImplementation((cmd: string, args: string[]) => {
      const child = new FakeChild()
      if (cmd !== 'codex') done(child, 1)
      else if (args[0] === '--version') handlers.version(child, args)
      else if (args[0] === 'login') handlers.login(child, args)
      else {
        seen = args
        const img = path.join(outDirOf(args), 'image-1.png')
        fs.writeFileSync(img, PNG)
        fs.writeFileSync(lastOf(args), JSON.stringify({ files: [img], revisedPrompt: 'r' }))
        done(child, 0)
        setImmediate(() => (stdin = child.stdin.written))
      }
      return child
    })
    await lib.generateImages({ prompt: 'a kitchen', aspect: 'landscape', referenceImagePaths: ['/ref/a.png'], outDir: path.join(tmp, 'o') })
    expect(seen).toEqual(expect.arrayContaining(['exec', '--ephemeral', '--skip-git-repo-check', '-s', 'workspace-write', '--json', '-i', '/ref/a.png']))
    expect(seen[seen.length - 1]).toBe('-')
    await new Promise((r) => setImmediate(r))
    expect(stdin).toContain('a kitchen')
    expect(stdin).toContain('1536x1024')
    expect(stdin).toMatch(/NO text/)
    expect(stdin).toContain('high quality')
  })

  it('falls back to new files in generated_images', async () => {
    const gen = path.join(codexHome, 'generated_images', 'sess')
    fs.mkdirSync(gen, { recursive: true })
    fs.writeFileSync(path.join(gen, 'old.png'), PNG)
    handlers.exec = (c) => {
      fs.writeFileSync(path.join(gen, 'new.png'), PNG)
      done(c, 0)
    }
    const out = path.join(tmp, 'out')
    const res = await lib.generateImages({ prompt: 'p', outDir: out })
    expect(res.files).toHaveLength(1)
    expect(res.files[0]!.path).toBe(path.join(out, 'image-1.png'))
    expect(fs.existsSync(path.join(out, 'image-1.png.provenance.json'))).toBe(true)
  })

  it('rejects output that is not an image', async () => {
    handlers.exec = (c, args) => {
      const img = path.join(outDirOf(args), 'image-1.png')
      fs.writeFileSync(img, 'not an image')
      fs.writeFileSync(lastOf(args), JSON.stringify({ files: [img], revisedPrompt: 'r' }))
      c.stdout.emit('data', JSON.stringify({ type: 'item.completed', item: { type: 'agent_message', text: 'I could not' } }) + '\n')
      done(c, 0)
    }
    await expect(lib.generateImages({ prompt: 'p', outDir: path.join(tmp, 'o') })).rejects.toThrow(/no usable image.*I could not/)
  })

  it('ignores reported files outside outDir', async () => {
    const elsewhere = path.join(tmp, 'elsewhere.png')
    fs.writeFileSync(elsewhere, PNG)
    handlers.exec = (c, args) => {
      fs.writeFileSync(lastOf(args), JSON.stringify({ files: [elsewhere], revisedPrompt: 'r' }))
      done(c, 0)
    }
    await expect(lib.generateImages({ prompt: 'p', outDir: path.join(tmp, 'o') })).rejects.toThrow(/no usable image/)
  })

  it('reports Codex not installed', async () => {
    spawnMock.mockImplementation(() => {
      const child = new FakeChild()
      setImmediate(() => child.emit('error', Object.assign(new Error('spawn ENOENT'), { code: 'ENOENT' })))
      return child
    })
    await expect(lib.generateImages({ prompt: 'p', outDir: path.join(tmp, 'o') })).rejects.toThrow(/Codex CLI not found/)
  })

  it('fails fast when not logged in, without running exec', async () => {
    handlers.login = (c) => done(c, 1, 'Not logged in\n')
    await expect(lib.generateImages({ prompt: 'p', outDir: path.join(tmp, 'o') })).rejects.toThrow(/not logged in — run `codex login`/)
    expect(spawnMock.mock.calls.some((c) => (c[1] as string[])[0] === 'exec')).toBe(false)
  })

  it('surfaces the last error on a non-zero exit', async () => {
    handlers.exec = (c) => {
      c.stdout.emit('data', JSON.stringify({ type: 'error', message: 'quota exceeded' }) + '\n')
      done(c, 1)
    }
    await expect(lib.generateImages({ prompt: 'p', outDir: path.join(tmp, 'o') })).rejects.toThrow('Codex failed: quota exceeded')
  })

  it('times out, killing the child', async () => {
    let child: FakeChild | undefined
    handlers.exec = (c) => void (child = c)
    await expect(lib.generateImages({ prompt: 'p', outDir: path.join(tmp, 'o'), timeoutMs: 20 })).rejects.toThrow(/timed out after/)
    expect(child?.kill).toHaveBeenCalled()
  })
})

describe('cost settings', () => {
  const okExec: Handler = (c, args) => {
    const img = path.join(outDirOf(args), 'image-1.png')
    fs.writeFileSync(img, PNG)
    fs.writeFileSync(lastOf(args), JSON.stringify({ files: [img], revisedPrompt: 'r' }))
    done(c, 0)
  }
  const execCalls = () => spawnMock.mock.calls.filter((c) => (c[1] as string[])[0] === 'exec').map((c) => c[1] as string[])
  const flag = (args: string[], f: string) => args[args.indexOf(f) + 1]

  it('buildPrompt carries the quality, defaulting to high', () => {
    expect(lib.buildPrompt('s', 'square', 1, false)).toContain('1024x1024, high quality.')
    expect(lib.buildPrompt('s', 'square', 2, false, 'low')).toContain('2 images at size 1024x1024, low quality.')
    expect(lib.buildPrompt('s', 'square', 1, false)).not.toContain('low quality')
  })

  it('defaults to gpt-6-luna, low reasoning and high quality, recorded in the sidecar', async () => {
    handlers.exec = okExec
    const out = path.join(tmp, 'o')
    const res = await lib.generateImages({ prompt: 'p', outDir: out })
    const [args] = execCalls()
    expect(flag(args!, '-m')).toBe('gpt-6-luna')
    expect(args).toContain('model_reasoning_effort="low"')
    expect(res).toMatchObject({ model: 'gpt-6-luna', quality: 'high' })
    const sidecar = JSON.parse(fs.readFileSync(path.join(out, 'image-1.png.provenance.json'), 'utf8'))
    expect(sidecar).toMatchObject({ model: 'gpt-6-luna', quality: 'high', aiGenerated: true })
  })

  it('honours env overrides and an explicit quality over the env', async () => {
    handlers.exec = okExec
    process.env['CONDUCTOR_CODEX_IMAGE_MODEL'] = 'gpt-x'
    process.env['CONDUCTOR_CODEX_IMAGE_REASONING'] = 'medium'
    process.env['CONDUCTOR_CODEX_IMAGE_QUALITY'] = 'medium'
    let res = await lib.generateImages({ prompt: 'p', outDir: path.join(tmp, 'a') })
    let [args] = execCalls()
    expect(flag(args!, '-m')).toBe('gpt-x')
    expect(args).toContain('model_reasoning_effort="medium"')
    expect(res.quality).toBe('medium')
    res = await lib.generateImages({ prompt: 'p', outDir: path.join(tmp, 'b'), quality: 'low' })
    expect(res.quality).toBe('low')
    expect(lib.resolveQuality()).toBe('medium')
    process.env['CONDUCTOR_CODEX_IMAGE_QUALITY'] = 'bogus'
    expect(lib.resolveQuality()).toBe('high')
    expect(() => lib.resolveQuality('ultra')).toThrow(/quality must be/)
    ;[args] = execCalls()
  })

  it('retries once without -m when the model is unavailable', async () => {
    let n = 0
    handlers.exec = (c, args) => {
      n += 1
      if (args.includes('-m')) {
        c.stdout.emit('data', JSON.stringify({ type: 'error', message: 'The model `gpt-6-luna` is not supported for this account' }) + '\n')
        done(c, 1)
      } else okExec(c, args)
    }
    const progress: string[] = []
    const res = await lib.generateImages({ prompt: 'p', outDir: path.join(tmp, 'o'), onProgress: (m) => progress.push(m) })
    const calls = execCalls()
    expect(n).toBe(2)
    expect(calls[0]).toContain('-m')
    expect(calls[1]).not.toContain('-m')
    expect(res.model).toBe('default')
    expect(progress.some((m) => /retrying with the default model/.test(m))).toBe(true)
  })

  it('does not retry unrelated failures', async () => {
    handlers.exec = (c) => {
      c.stdout.emit('data', JSON.stringify({ type: 'error', message: 'quota exceeded' }) + '\n')
      done(c, 1)
    }
    await expect(lib.generateImages({ prompt: 'p', outDir: path.join(tmp, 'o') })).rejects.toThrow('quota exceeded')
    expect(execCalls()).toHaveLength(1)
  })
})

describe('checkCodexCli', () => {
  it('reports ready, logged-out and missing', async () => {
    expect(await lib.checkCodexCli()).toEqual({ state: 'ready', version: 'codex-cli 0.162.1' })
    handlers.login = (c) => done(c, 1, 'Not logged in')
    expect((await lib.checkCodexCli()).state).toBe('logged-out')
    lib.resetCodexPathCache()
    spawnMock.mockImplementation(() => {
      const child = new FakeChild()
      setImmediate(() => child.emit('error', Object.assign(new Error('x'), { code: 'ENOENT' })))
      return child
    })
    expect(await lib.checkCodexCli()).toEqual({ state: 'missing' })
  })
})
