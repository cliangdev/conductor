import * as fs from 'fs'
import * as path from 'path'
import * as os from 'os'
import { Command } from 'commander'
import chalk from 'chalk'
import { readConfig, CONFIG_PATH } from '../lib/config.js'
import { getAssetSrcDir, getPluginInstallStatus } from '../lib/plugin-assets.js'
import { checkCodexCli, type CodexStatus } from '../lib/codex-image.js'
import { resolveImageSettings, resolveOpenAiKey } from '../lib/image-generation.js'
import { resolveGeminiKey } from '../lib/gemini-omni.js'
import { getVertexToken } from '../lib/vertex-veo.js'
import { pickVideoRoute, videoCaps } from '../lib/video-generation.js'
import { spentToday } from '../lib/video-spend.js'
import { printNextSteps } from '../lib/next-steps.js'

export async function checkApiHealth(apiUrl: string): Promise<boolean> {
  try {
    const response = await fetch(`${apiUrl}/api/v1/health`)
    return response.ok
  } catch {
    return false
  }
}

export function checkMcpJson(workingDir: string): boolean {
  return fs.existsSync(path.join(workingDir, '.mcp.json'))
}

export function checkConfigFile(): boolean {
  return fs.existsSync(CONFIG_PATH)
}

const CODEX_CHECK_NAME = 'Image generation: Codex (the skills\' default)'
const API_KEY_HINT = 'an OpenAI API key (`conductor config set-openai-api-key`) is the fallback when Codex fails'

export function describeCodexStatus(status: CodexStatus): { status: 'pass' | 'warn'; message: string } {
  if (status.state === 'ready') return { status: 'pass', message: `${CODEX_CHECK_NAME}: ${status.version}, logged in` }
  if (status.state === 'logged-out') {
    return { status: 'warn', message: `${CODEX_CHECK_NAME}: ${status.version} installed but not logged in — run \`codex login\`` }
  }
  return { status: 'warn', message: `${CODEX_CHECK_NAME}: not found — npm i -g @openai/codex, then \`codex login\` (optional; only needed to generate photos)` }
}

/** Both routes: the skills try Codex first and fall back to the API key, so either one working passes. */
export async function checkImageGeneration(): Promise<{ status: 'pass' | 'warn'; message: string }> {
  const codex = describeCodexStatus(await checkCodexCli())
  const found = resolveOpenAiKey()
  if (!found) return { status: codex.status, message: `${codex.message}; ${API_KEY_HINT}` }
  const api = await checkOpenAiRoute(found)
  return { status: codex.status === 'pass' || api.status === 'pass' ? 'pass' : 'warn', message: `${codex.message}; fallback: ${api.message}` }
}

async function checkOpenAiRoute(found: { key: string; source: string }): Promise<{ status: 'pass' | 'warn'; message: string }> {
  const { model, quality } = resolveImageSettings()
  const label = `Image generation: OpenAI API (${model}, ${quality}), key from ${found.source}`
  try {
    const res = await fetch(`https://api.openai.com/v1/models/${encodeURIComponent(model)}`, {
      headers: { Authorization: `Bearer ${found.key}` },
      signal: AbortSignal.timeout(5000),
    })
    if (res.ok) return { status: 'pass', message: label }
    if (res.status === 401) return { status: 'warn', message: `${label} — key rejected by OpenAI` }
    if (res.status === 404) return { status: 'warn', message: `${label} — model not available to this key` }
    return { status: 'warn', message: `${label} — OpenAI answered ${res.status}` }
  } catch {
    return { status: 'warn', message: `${label} — OpenAI unreachable` }
  }
}

/** Video is optional: not configured is a warning with the setup commands. Never prints a key or token. */
export async function checkVideoGeneration(): Promise<{ status: 'pass' | 'warn'; message: string }> {
  const choice = pickVideoRoute()
  const caps = videoCaps()
  const spend = `spent today $${spentToday().toFixed(2)} of $${caps.dailyUsd.toFixed(2)} cap`
  if (!choice) {
    return {
      status: 'warn',
      message: 'Video generation: not configured (optional) — run `conductor config set-vertex-project <id>` and `gcloud auth login` for Veo, or `conductor config set-gemini-api-key <key>` for Gemini Omni',
    }
  }
  if (choice.route === 'vertex') {
    let token = true
    try {
      await getVertexToken()
    } catch {
      token = false
    }
    const base = `Video generation: Veo on Vertex AI, project ${choice.project}, region ${choice.region}`
    return token
      ? { status: 'pass', message: `${base}, access token available; ${spend}` }
      : { status: 'warn', message: `${base} — no access token: run \`gcloud auth login\` (or set CONDUCTOR_VERTEX_ACCESS_TOKEN); ${spend}` }
  }
  const source = resolveGeminiKey()?.source ?? 'config'
  return { status: 'pass', message: `Video generation: Gemini Omni, key set (from ${source}); ${spend}` }
}

export function registerDoctor(program: Command): void {
  program
    .command('doctor')
    .description('Run health checks on your Conductor setup')
    .option('--json', 'Output results as JSON')
    .addHelpText('after', `
Examples:
  conductor doctor
  conductor doctor --json`)
    .action(async (options: { json?: boolean }) => {
      if (options.json) {
        const checks: Array<{ name: string; status: 'pass' | 'fail' | 'warn'; message: string }> = []

        const configExists = checkConfigFile()
        checks.push({
          name: 'config',
          status: configExists ? 'pass' : 'fail',
          message: configExists ? 'Config file found (~/.conductor/config.json)' : 'Config file not found — run conductor login',
        })

        const config = readConfig()

        if (config) {
          const apiReachable = await checkApiHealth(config.apiUrl)
          checks.push({
            name: 'api',
            status: apiReachable ? 'pass' : 'fail',
            message: apiReachable ? `API reachable (GET /api/v1/health → 200)` : 'API not reachable',
          })
        } else {
          checks.push({
            name: 'api',
            status: 'fail',
            message: 'API reachable (no config — skipped)',
          })
        }

        const mcpExists = checkMcpJson(process.cwd())
        checks.push({
          name: 'mcp',
          status: mcpExists ? 'pass' : 'fail',
          message: mcpExists ? '.mcp.json found in current directory' : '.mcp.json not found in current directory',
        })

        const globalClaudeDir = path.join(os.homedir(), '.claude')
        const localClaudeDir = path.join(process.cwd(), '.claude')
        const { location, outdated } = getPluginInstallStatus(getAssetSrcDir(), globalClaudeDir, localClaudeDir)

        if (location === 'none') {
          checks.push({ name: 'plugin', status: 'fail', message: 'Claude plugin not installed — run conductor init' })
        } else {
          checks.push({ name: 'plugin', status: 'pass', message: `Claude plugin installed (${location})` })
        }

        if (location !== 'none' && outdated) {
          checks.push({ name: 'plugin-version', status: 'warn', message: 'Claude plugin outdated — re-run conductor init to update' })
        }

        const images = await checkImageGeneration()
        checks.push({ name: 'image-generation', status: images.status, message: images.message })

        const video = await checkVideoGeneration()
        checks.push({ name: 'video-generation', status: video.status, message: video.message })

        const allPass = checks.every(c => c.status !== 'fail')
        process.stdout.write(JSON.stringify({ checks }, null, 2) + '\n')
        process.exit(allPass ? 0 : 1)
        return
      }

      let failures = 0
      let warnings = 0

      const configExists = checkConfigFile()
      const configMark = configExists ? chalk.green('✓') : chalk.red('✗')
      console.log(`${configMark} Config file found (~/.conductor/config.json)`)
      if (!configExists) failures++

      const config = readConfig()

      if (config) {
        const apiReachable = await checkApiHealth(config.apiUrl)
        const apiMark = apiReachable ? chalk.green('✓') : chalk.red('✗')
        console.log(`${apiMark} API reachable (GET /api/v1/health → ${apiReachable ? '200' : 'failed'})`)
        if (!apiReachable) failures++
      } else {
        console.log(`${chalk.red('✗')} API reachable (no config — skipped)`)
        failures++
      }

      const mcpExists = checkMcpJson(process.cwd())
      const mcpMark = mcpExists ? chalk.green('✓') : chalk.red('✗')
      console.log(`${mcpMark} .mcp.json ${mcpExists ? 'found' : 'not found'} in current directory`)
      if (!mcpExists) failures++

      const globalClaudeDir = path.join(os.homedir(), '.claude')
      const localClaudeDir = path.join(process.cwd(), '.claude')
      const { location, outdated } = getPluginInstallStatus(getAssetSrcDir(), globalClaudeDir, localClaudeDir)

      if (location === 'global') {
        console.log(`${chalk.green('✓')} Claude plugin: global`)
      } else if (location === 'local') {
        console.log(`${chalk.green('✓')} Claude plugin: local`)
      } else {
        console.log(`${chalk.red('✗')} Claude plugin: not installed — run conductor init`)
        failures++
      }

      if (location !== 'none' && outdated) {
        console.log(`${chalk.yellow('⚠')} Claude plugin outdated — re-run conductor init to update`)
        warnings++
      }

      const images = await checkImageGeneration()
      if (images.status === 'pass') {
        console.log(`${chalk.green('✓')} ${images.message}`)
      } else {
        console.log(`${chalk.yellow('⚠')} ${images.message}`)
        warnings++
      }

      const video = await checkVideoGeneration()
      if (video.status === 'pass') {
        console.log(`${chalk.green('✓')} ${video.message}`)
      } else {
        console.log(`${chalk.yellow('⚠')} ${video.message}`)
        warnings++
      }

      if (failures === 0 && warnings === 0) {
        printNextSteps()
      }
    })
}
