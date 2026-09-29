import { describe, it, expect, beforeAll } from 'vitest'
import { existsSync, mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import { createServer } from 'node:http'
import { spawn, execFileSync } from 'node:child_process'

/**
 * End-to-end: spawns the built `conductor creative render` CLI against a fake external v2
 * `/marketing/creatives/{creativeId}/renders` backend (a real node:http server) for a MOTION
 * Creative, driving a real local browser AND a real local ffmpeg — the two binaries
 * `renderMotionPlacement` (conductor-creative/job/render.mjs) actually shells out to. Mirrors
 * creative-render-integration.test.ts's STILL coverage; needs `npm run build` (dist/index.js and
 * dist/creative/{motion.js,job/*}), a launchable Chromium, AND ffmpeg/ffprobe on PATH — skips
 * cleanly when any of those is missing rather than failing a machine that hasn't set one up.
 */

const __dirname = dirname(fileURLToPath(import.meta.url))
const REPO_ROOT = join(__dirname, '..', '..')
const CLI_ENTRY = join(REPO_ROOT, 'dist', 'index.js')
const RENDER_CORE = join(REPO_ROOT, 'dist', 'creative', 'job', 'render.mjs')
const MOTION_JS = join(REPO_ROOT, 'dist', 'creative', 'motion.js')

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

function ffmpegAvailable(): boolean {
  try {
    execFileSync('ffmpeg', ['-version'], { stdio: 'ignore' })
    return true
  } catch {
    return false
  }
}

// A minimal 1x1 transparent PNG data URI — decodes fine in Chromium, no network fetch needed (same
// fixture conductor-creative/test/job-motion.test.mjs uses).
const TINY_PHOTO =
  'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII='

// A 2s, one-placement MOTION spec at the fixed 30fps conductor-tools always passes — 60 frames,
// no audio (creative.audio is unset, so renderMotionPlacement's audioSourceUrl returns null and
// no ffmpeg audio input/download happens), no clip background (photo only) — the smallest MOTION
// render that still exercises the real ffmpeg encode path end to end.
const RENDER_SPEC = {
  renderId: 'r1',
  previewOnly: false,
  creative: {
    kind: 'MOTION',
    layout: 'stacked',
    theme: 'dark',
    headline: 'Plan the week in *one sentence*.',
    body: 'Body copy.',
    photoUrl: TINY_PHOTO,
    motion: { durationSec: 2, preset: 'fade-up', background: { source: 'photo', motion: 'none' }, endCard: true },
  },
  brand: { tokens: { accent: '#FF5A5F', darkBg: '#1C1C1E', darkInk: '#FFFFFF' } },
  placements: ['4x5'],
}

function startFakeBackend() {
  const putFrameCalls: Array<{ path: string; contentType: string | undefined }> = []
  const putPosterCalls: Array<{ path: string; contentType: string | undefined }> = []
  const server = createServer((req, res) => {
    const url = new URL(req.url!, 'http://localhost')
    const pathname = url.pathname
    if (req.method === 'GET' && /\/creatives\/cr1$/.test(pathname)) {
      // renderCreative() checks the creative's kind first — a MOTION creative here.
      res.writeHead(200, { 'content-type': 'application/json' })
      res.end(JSON.stringify({ id: 'cr1', kind: 'MOTION' }))
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
    if (req.method === 'PUT' && pathname.endsWith('/poster')) {
      putPosterCalls.push({ path: pathname, contentType: req.headers['content-type'] })
      const chunks: Buffer[] = []
      req.on('data', (c) => chunks.push(c))
      req.on('end', () => res.writeHead(204).end())
      return
    }
    if (req.method === 'PUT' && pathname.includes('/frames/')) {
      putFrameCalls.push({ path: pathname, contentType: req.headers['content-type'] })
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
    if (req.method === 'POST' && pathname.endsWith('/fail')) {
      let body = ''
      req.on('data', (c) => (body += c))
      req.on('end', () => {
        console.error('render job reported failure:', body)
        res.writeHead(200, { 'content-type': 'application/json' }).end(JSON.stringify({ id: 'r1', state: 'FAILED' }))
      })
      return
    }
    if (req.method === 'GET' && /\/renders\/r1$/.test(pathname)) {
      res.writeHead(200, { 'content-type': 'application/json' })
      res.end(
        JSON.stringify({
          id: 'r1',
          state: 'SUCCEEDED',
          error: null,
          frames: [
            {
              id: 'f1',
              placementKey: '4x5',
              url: 'https://example.test/4x5.mp4',
              width: 1080,
              height: 1350,
              sizeBytes: 12345,
              durationSeconds: 2,
              hasAudio: false,
              posterUrl: 'https://example.test/4x5.jpg',
              contentType: 'video/mp4',
            },
          ],
        })
      )
      return
    }
    res.writeHead(404).end('not found')
  })
  return new Promise<{ server: import('node:http').Server; origin: string; putFrameCalls: typeof putFrameCalls; putPosterCalls: typeof putPosterCalls }>(
    (resolve) => {
      server.listen(0, '127.0.0.1', () => {
        const address = server.address()
        const port = typeof address === 'object' && address ? address.port : 0
        resolve({ server, origin: `http://127.0.0.1:${port}`, putFrameCalls, putPosterCalls })
      })
    }
  )
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

describe('conductor creative render (MOTION integration)', () => {
  let skip = false

  beforeAll(async () => {
    if (!existsSync(CLI_ENTRY) || !existsSync(RENDER_CORE) || !existsSync(MOTION_JS)) {
      skip = true
      return
    }
    if (!ffmpegAvailable()) {
      skip = true
      return
    }
    if (!(await browserAvailable())) {
      skip = true
    }
  }, 30_000)

  it('renders a MOTION Creative to an MP4 + poster against a fake API server end to end', async () => {
    if (skip) {
      console.warn(
        'skipping MOTION render integration test: run `npm run build` and ensure a local Chromium and ffmpeg are installed'
      )
      return
    }

    const { server, origin, putFrameCalls, putPosterCalls } = await startFakeBackend()
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
      expect(result.stdout).toContain('2s')
      expect(result.stdout).toContain('https://example.test/4x5.mp4')
      // Progress lines from job/render.mjs's own log() calls (e.g. "rendering 1 MOTION placement(s)
      // at 30fps") should have streamed to the CLI's console as the render ran.
      expect(result.stdout).toMatch(/MOTION placement/)

      const framePut = putFrameCalls.find((c) => c.path.includes('/creatives/cr1/renders/r1/frames/4x5'))
      expect(framePut).toBeDefined()
      expect(framePut!.contentType).toBe('video/mp4')

      const posterPut = putPosterCalls.find((c) => c.path.includes('/creatives/cr1/renders/r1/frames/4x5/poster'))
      expect(posterPut).toBeDefined()
      expect(posterPut!.contentType).toBe('image/jpeg')
    } finally {
      server.close()
      rmSync(fakeHome, { recursive: true, force: true })
    }
  }, 90_000)
})
