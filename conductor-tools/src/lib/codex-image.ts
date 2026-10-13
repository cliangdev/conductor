/**
 * Photo generation through the person's own Codex CLI (`codex exec` with its built-in `image_gen`
 * tool). Conductor never holds an image-model key: Claude writes the scene, Codex makes the image on
 * this machine under the person's ChatGPT/Codex login, and the file lands in a project-local folder
 * that drops straight into `photoPath`. Each image gets a `<file>.provenance.json` sidecar.
 *
 * The agent is asked to copy the generated files into the working directory and report them through
 * `--output-schema`; when it doesn't, new files under `$CODEX_HOME/generated_images` are picked up
 * and copied instead.
 */
import { spawn } from 'node:child_process'
import { copyFile, mkdir, readdir, readFile, stat, writeFile } from 'node:fs/promises'
import { homedir } from 'node:os'
import { extname, join, resolve, sep } from 'node:path'
import { type Captured, type GeneratedImage, type GenerateImagesParams, type GenerateImagesResult, type ImageAspect, ASPECT_SIZES, jpegPreview, runCapture, sceneRules, validImage } from './image-common.js'
import { binaryAvailable } from './media-probe.js'
import { writeProvenanceSidecar } from './provenance-sidecar.js'

export { ASPECT_SIZES, type ImageAspect, type GenerateImagesParams, type GeneratedImage, type GenerateImagesResult }

export const NO_CODEX_HINT =
  'Codex CLI not found — install it (npm i -g @openai/codex) and run `codex login`, or set CONDUCTOR_CODEX_PATH.'
export const NOT_LOGGED_IN_HINT = 'Codex CLI is not logged in — run `codex login` (see `conductor doctor`).'

const IMAGE_EXTS = new Set(['.png', '.jpg', '.jpeg', '.webp'])

const OUTPUT_SCHEMA = {
  type: 'object',
  properties: {
    files: { type: 'array', items: { type: 'string' } },
    revisedPrompt: { type: 'string' },
  },
  required: ['files', 'revisedPrompt'],
  additionalProperties: false,
}

let cachedCodexPath: string | undefined

/** `CONDUCTOR_CODEX_PATH`, else `codex` on PATH. Cached for the process lifetime. */
export async function resolveCodexPath(): Promise<string> {
  if (cachedCodexPath) return cachedCodexPath
  const candidate = process.env['CONDUCTOR_CODEX_PATH']?.trim() || 'codex'
  if (await binaryAvailable(candidate, ['--version'])) {
    cachedCodexPath = candidate
    return candidate
  }
  throw new Error(NO_CODEX_HINT)
}

/** Test-only: the resolved path is cached, so tests that vary availability reset it. */
export function resetCodexPathCache(): void {
  cachedCodexPath = undefined
}

async function assertLoggedIn(codex: string): Promise<void> {
  let res: Captured
  try {
    res = await runCapture(codex, ['login', 'status'])
  } catch {
    throw new Error(NO_CODEX_HINT)
  }
  if (res.code !== 0 || /not logged in/i.test(res.output)) throw new Error(NOT_LOGGED_IN_HINT)
}

export type CodexStatus =
  | { state: 'ready'; version: string }
  | { state: 'logged-out'; version: string }
  | { state: 'missing' }

/** For `conductor doctor`: never throws. */
export async function checkCodexCli(): Promise<CodexStatus> {
  let codex: string
  try {
    codex = await resolveCodexPath()
  } catch {
    return { state: 'missing' }
  }
  let version = 'unknown version'
  try {
    const v = await runCapture(codex, ['--version'])
    version = v.output.trim().split('\n')[0] || version
  } catch {
    /* keep the default */
  }
  try {
    await assertLoggedIn(codex)
    return { state: 'ready', version }
  } catch {
    return { state: 'logged-out', version }
  }
}

export function buildPrompt(scene: string, aspect: ImageAspect, count: number, hasReferences: boolean): string {
  return [
    sceneRules(scene, aspect, hasReferences),
    `- Use the image_gen tool to create exactly ${count} image${count === 1 ? '' : 's'} at size ${ASPECT_SIZES[aspect]}, high quality.`,
    `- Then copy each generated file into the current directory as ${Array.from({ length: Math.min(count, 2) }, (_, i) => `image-${i + 1}.png`).join(', ')}${count > 2 ? '…' : ''}.`,
    '- Reply with JSON: {"files": [absolute paths of the copies], "revisedPrompt": "the prompt the image tool actually used"}.',
  ].join('\n')
}

async function listImages(root: string): Promise<Array<{ path: string; mtimeMs: number }>> {
  const out: Array<{ path: string; mtimeMs: number }> = []
  const walk = async (dir: string): Promise<void> => {
    let entries
    try {
      entries = await readdir(dir, { withFileTypes: true })
    } catch {
      return
    }
    for (const e of entries) {
      const p = join(dir, e.name)
      if (e.isDirectory()) await walk(p)
      else if (IMAGE_EXTS.has(extname(e.name).toLowerCase())) {
        try {
          out.push({ path: p, mtimeMs: (await stat(p)).mtimeMs })
        } catch {
          /* gone */
        }
      }
    }
  }
  await walk(root)
  return out
}

const codexHome = (): string => process.env['CODEX_HOME'] || join(homedir(), '.codex')

interface ExecOutcome {
  lastError?: string
  lastMessage?: string
}

function runCodexExec(codex: string, args: string[], prompt: string, timeoutMs: number, onProgress?: (m: string) => void): Promise<ExecOutcome> {
  return new Promise((resolvePromise, reject) => {
    const child = spawn(codex, args, { stdio: ['pipe', 'pipe', 'pipe'] })
    const outcome: ExecOutcome = {}
    let settled = false
    let buffer = ''
    let stderr = ''
    const finish = (err?: Error) => {
      if (settled) return
      settled = true
      clearTimeout(timer)
      if (err) reject(err)
      else resolvePromise(outcome)
    }
    const timer = setTimeout(() => {
      try {
        child.kill()
      } catch {
        /* already gone */
      }
      finish(new Error(`Codex image generation timed out after ${Math.round(timeoutMs / 1000)} s`))
    }, timeoutMs)
    timer.unref?.()

    const handleLine = (line: string) => {
      if (!line.trim()) return
      let ev: Record<string, unknown>
      try {
        ev = JSON.parse(line) as Record<string, unknown>
      } catch {
        return
      }
      const type = String(ev['type'] ?? '')
      if (type === 'error') {
        const message = String(ev['message'] ?? 'unknown error')
        outcome.lastError = message
        onProgress?.(`Codex: ${message}`)
      } else if (type.startsWith('item.')) {
        const item = (ev['item'] ?? {}) as Record<string, unknown>
        const itemType = String(item['type'] ?? 'item')
        if (itemType === 'agent_message' && typeof item['text'] === 'string') outcome.lastMessage = item['text']
        onProgress?.(`Codex: ${itemType.replace(/_/g, ' ')} ${type.slice(5)}`)
      }
    }

    child.stdout?.on('data', (d) => {
      buffer += d
      let nl: number
      while ((nl = buffer.indexOf('\n')) >= 0) {
        handleLine(buffer.slice(0, nl))
        buffer = buffer.slice(nl + 1)
      }
    })
    child.stderr?.on('data', (d) => (stderr += d))
    child.on('error', (err) => finish((err as NodeJS.ErrnoException).code === 'ENOENT' ? new Error(NO_CODEX_HINT) : err))
    child.on('exit', (code) => {
      handleLine(buffer)
      if (code === 0) finish()
      else {
        const detail = outcome.lastError ?? (stderr.trim().split('\n').pop() || `exit ${code}`)
        finish(new Error(`Codex failed: ${detail}`))
      }
    })
    child.stdin?.on?.('error', () => {})
    child.stdin?.end(prompt)
  })
}

export async function generateImagesWithCodex(params: GenerateImagesParams): Promise<GenerateImagesResult> {
  const { outDir, onProgress } = params
  const aspect = params.aspect ?? 'portrait'
  const count = params.count ?? 1
  const refs = params.referenceImagePaths ?? []
  const timeoutMs = params.timeoutMs ?? 300_000

  const codex = await resolveCodexPath()
  await assertLoggedIn(codex)

  await mkdir(outDir, { recursive: true })
  const outRoot = resolve(outDir)
  const schemaPath = join(outRoot, '.codex-schema.json')
  const lastPath = join(outRoot, '.codex-last.json')
  await writeFile(schemaPath, JSON.stringify(OUTPUT_SCHEMA))

  const genRoot = join(codexHome(), 'generated_images')
  const before = new Set((await listImages(genRoot)).map((f) => f.path))
  const startedAt = Date.now() - 1000

  const args = [
    'exec', '--ephemeral', '--skip-git-repo-check', '-s', 'workspace-write',
    '-C', outRoot, '--output-schema', schemaPath, '-o', lastPath, '--json',
    ...refs.flatMap((r) => ['-i', r]),
    '-',
  ]
  onProgress?.('Starting Codex image generation')
  const outcome = await runCodexExec(codex, args, buildPrompt(params.prompt, aspect, count, refs.length > 0), timeoutMs, onProgress)

  let revisedPrompt = ''
  const found: GeneratedImage[] = []

  // (a) files the agent copied into outDir and reported.
  try {
    const last = JSON.parse(await readFile(lastPath, 'utf8')) as { files?: unknown; revisedPrompt?: unknown }
    if (typeof last.revisedPrompt === 'string') revisedPrompt = last.revisedPrompt
    for (const f of Array.isArray(last.files) ? last.files : []) {
      if (found.length >= count || typeof f !== 'string') continue
      const abs = resolve(outRoot, f)
      if (!abs.startsWith(outRoot + sep)) continue
      const img = await validImage(abs)
      if (img && !found.some((x) => x.path === img.path)) found.push(img)
    }
  } catch {
    /* no usable report — try the generated_images folder */
  }

  // (b) fallback: new files under $CODEX_HOME/generated_images, newest first.
  if (found.length === 0) {
    const fresh = (await listImages(genRoot))
      .filter((f) => !before.has(f.path) && f.mtimeMs >= startedAt)
      .sort((a, b) => b.mtimeMs - a.mtimeMs)
    for (const f of fresh) {
      if (found.length >= count) break
      const dest = join(outRoot, `image-${found.length + 1}${extname(f.path).toLowerCase()}`)
      await copyFile(f.path, dest)
      const img = await validImage(dest)
      if (img) found.push(img)
    }
  }

  if (found.length === 0) {
    const why = outcome.lastMessage ? ` Codex said: ${outcome.lastMessage.slice(0, 500)}` : ''
    // A ChatGPT plan without image generation (e.g. Free) hides image_gen from `codex exec` entirely,
    // and the run still exits 0 — so this is the error that person sees.
    throw new Error(
      `Codex finished but produced no usable image.${why} ` +
        "Codex's image generation needs a ChatGPT plan that includes it (Plus or higher) on the account `codex login` used."
    )
  }

  const date = new Date().toISOString().slice(0, 10)
  const provenance = { source: `Generated with Codex image_gen on ${date}`, licence: 'Generated, house use', aiGenerated: true as const }
  for (const img of found) {
    await writeProvenanceSidecar(img.path, { ...provenance, prompt: params.prompt, revisedPrompt })
  }

  const preview = await jpegPreview(found[0]!.path, outRoot)
  return { files: found, provenance, revisedPrompt, outDir: outRoot, ...(preview ? { preview } : {}) }
}
