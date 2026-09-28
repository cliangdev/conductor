import { describe, it, expect, beforeAll } from 'vitest'
import { existsSync, mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import { createServer } from 'node:http'
import { spawn } from 'node:child_process'

/**
 * End-to-end: spawns the built `conductor creative render` CLI against a fake external v2
 * `/marketing/creatives/{creativeId}/renders` backend (a real node:http server) and a real local
 * browser, exactly the path a user or a self-hosted Workflow runner takes. Needs both `npm run
 * build` (dist/index.js and dist/creative/job/*) and a launchable Chromium — skips cleanly on
 * either being unavailable rather than failing a machine that hasn't run either.
 */

const __dirname = dirname(fileURLToPath(import.meta.url))
const REPO_ROOT = join(__dirname, '..', '..')
const CLI_ENTRY = join(REPO_ROOT, 'dist', 'index.js')
const RENDER_CORE = join(REPO_ROOT, 'dist', 'creative', 'job', 'render.mjs')

async function browserAvailable(): Promise<boolean> {
  try {
    const { chromium } = await import('playwright-core')
    for (const options of [{ channel: 'chrome' }, { channel: 'msedge' }, {}]) {
      try {
        const browser = await chromium.launch(options)
        await browser.close()
        return true
      } catch {
        continue
      }
    }
    return false
  } catch {
    return false
  }
}

// A fixture proven to render successfully by conductor-creative/test/job.test.mjs: no photo, just
// a headline/body on the `stacked` layout.
const RENDER_SPEC = {
  renderId: 'r1',
  previewOnly: false,
  creative: { layout: 'stacked', theme: 'dark', headline: 'Plan the week in *one sentence*.', body: 'Body copy.' },
  brand: { tokens: { accent: '#FF5A5F', darkBg: '#1C1C1E', darkInk: '#FFFFFF' } },
  placements: ['4x5'],
}

function startFakeBackend() {
  const putFrameCalls: string[] = []
  const server = createServer((req, res) => {
    const url = new URL(req.url!, 'http://localhost')
    const pathname = url.pathname
    if (req.method === 'GET' && /\/creatives\/cr1$/.test(pathname)) {
      // renderCreative() checks the creative's kind first — a plain STILL creative here.
      res.writeHead(200, { 'content-type': 'application/json' })
      res.end(JSON.stringify({ id: 'cr1', kind: 'STILL' }))
      return
    }
    if (req.method === 'POST' && pathname.endsWith('/renders')) {
      let body = ''
      req.on('data', (c) => (body += c))
      req.on('end', () => {
        res.writeHead(201, { 'content-type': 'application/json' })
        res.end(JSON.stringify({ id: 'r1', state: 'RUNNING', spec: RENDER_SPEC }))
      })
      return
    }
    if (req.method === 'PUT' && pathname.includes('/frames/')) {
      putFrameCalls.push(pathname)
      const chunks: Buffer[] = []
      req.on('data', (c) => chunks.push(c))
      req.on('end', () => res.writeHead(204).end())
      return
    }
    if (req.method === 'POST' && pathname.endsWith('/complete')) {
      let body = ''
      req.on('data', (c) => (body += c))
      req.on('end', () => res.writeHead(200, { 'content-type': 'application/json' }).end(JSON.stringify({ id: 'r1', state: 'SUCCEEDED' })))
      return
    }
    if (req.method === 'GET' && /\/renders\/r1$/.test(pathname)) {
      res.writeHead(200, { 'content-type': 'application/json' })
      res.end(
        JSON.stringify({
          id: 'r1',
          state: 'SUCCEEDED',
          error: null,
          frames: [{ id: 'f1', placementKey: '4x5', url: 'https://example.test/4x5.png', width: 2160, height: 2700, sizeBytes: 12345 }],
        })
      )
      return
    }
    res.writeHead(404).end('not found')
  })
  return new Promise<{ server: import('node:http').Server; origin: string; putFrameCalls: string[] }>((resolve) => {
    server.listen(0, '127.0.0.1', () => {
      const address = server.address()
      const port = typeof address === 'object' && address ? address.port : 0
      resolve({ server, origin: `http://127.0.0.1:${port}`, putFrameCalls })
    })
  })
}

function runCli(args: string[], env: Record<string, string>): Promise<{ code: number | null; stdout: string; stderr: string }> {
  return new Promise((resolve) => {
    const child = spawn(process.execPath, [CLI_ENTRY, ...args], { env: { ...process.env, ...env } })
    let stdout = ''
    let stderr = ''
    child.stdout.on('data', (c) => (stdout += c))
    child.stderr.on('data', (c) => (stderr += c))
    child.on('close', (code) => resolve({ code, stdout, stderr }))
  })
}

describe('conductor creative render (integration)', () => {
  let skip = false

  beforeAll(async () => {
    if (!existsSync(CLI_ENTRY) || !existsSync(RENDER_CORE)) {
      skip = true
      return
    }
    if (!(await browserAvailable())) {
      skip = true
    }
  }, 30_000)

  it('renders a Creative against a fake API server end to end', async () => {
    if (skip) {
      console.warn('skipping creative render integration test: run `npm run build` and ensure a local Chromium is installed')
      return
    }

    const { server, origin, putFrameCalls } = await startFakeBackend()
    const fakeHome = mkdtempSync(join(tmpdir(), 'conductor-cli-home-'))
    try {
      const result = await runCli(['creative', 'render', 'cr1'], {
        HOME: fakeHome,
        CONDUCTOR_API_URL: origin,
        CONDUCTOR_API_KEY: 'test-key',
        CONDUCTOR_PROJECT_ID: 'proj1',
      })

      expect(result.stderr).toBe('')
      expect(result.code).toBe(0)
      expect(result.stdout).toContain('render r1: SUCCEEDED')
      expect(result.stdout).toContain('4x5')
      expect(result.stdout).toContain('https://example.test/4x5.png')
      expect(putFrameCalls.some((p) => p.includes('/creatives/cr1/renders/r1/frames/4x5'))).toBe(true)
    } finally {
      server.close()
      rmSync(fakeHome, { recursive: true, force: true })
    }
  }, 60_000)
})
