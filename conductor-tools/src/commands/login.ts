import * as readline from 'readline'
import { Command } from 'commander'
import chalk from 'chalk'
import ora from 'ora'
import { readConfig, writeConfig, type Config } from '../lib/config.js'
import { findAvailablePort, waitForOAuthCallback } from '../lib/oauth-server.js'

const CONDUCTOR_API_URL =
  process.env['CONDUCTOR_API_URL'] ?? 'https://conductor-backend-199707291514.us-central1.run.app'

const CONDUCTOR_FRONTEND_URL =
  process.env['CONDUCTOR_FRONTEND_URL'] ?? 'https://conductor-frontend-199707291514.us-central1.run.app'

const CLI_KEY_LABEL = 'CLI key'

interface AuthResult {
  apiKey: string
  email: string
  projectId: string
  projectName: string
  apiUrl: string
  frontendUrl?: string
}

/**
 * Build the config to persist after authenticating.
 *
 * `conductor login` refreshes credentials; it must NOT switch the active project
 * or drop the multi-project map. On re-auth we keep the existing
 * projectId/projectName/projects/localPath and only update the credential. The
 * project the browser/local flow happened to return is adopted only on first login.
 */
export function buildRefreshedConfig(existing: Config | null, auth: AuthResult): Config {
  if (existing) {
    return {
      ...existing,
      apiKey: auth.apiKey,
      email: auth.email,
      apiUrl: auth.apiUrl,
      ...(auth.frontendUrl ? { frontendUrl: auth.frontendUrl } : {}),
    }
  }
  return {
    apiKey: auth.apiKey,
    projectId: auth.projectId,
    projectName: auth.projectName,
    email: auth.email,
    apiUrl: auth.apiUrl,
    ...(auth.frontendUrl ? { frontendUrl: auth.frontendUrl } : {}),
  }
}

/**
 * Exchange a short-lived app JWT for a durable user API key (`uk_…`). The JWT
 * expires (default 24h) — storing it as the CLI credential makes the daemon's
 * writes start 401ing once it lapses. Mirrors the browser cli-login flow.
 * Falls back to the JWT if key issuance is unavailable, so login never hard-fails.
 */
export async function ensureDurableApiKey(apiUrl: string, accessToken: string): Promise<string> {
  const auth = { Authorization: `Bearer ${accessToken}` }
  try {
    const listRes = await fetch(`${apiUrl}/api/v1/api-keys`, { headers: auth })
    if (listRes.ok) {
      const keys = (await listRes.json()) as Array<{ key: string | null; label: string }>
      const reusable = keys.find((k) => k.label === CLI_KEY_LABEL && k.key)
      if (reusable?.key) return reusable.key
    }
    const createRes = await fetch(`${apiUrl}/api/v1/api-keys`, {
      method: 'POST',
      headers: { ...auth, 'Content-Type': 'application/json' },
      body: JSON.stringify({ label: CLI_KEY_LABEL }),
    })
    if (createRes.ok) {
      const created = (await createRes.json()) as { key: string }
      if (created.key) return created.key
    }
  } catch {
    // Network/endpoint issue — fall through to the JWT below.
  }
  return accessToken
}

function resolveApiUrl(): string {
  const cfg = readConfig()
  return cfg?.apiUrl ?? CONDUCTOR_API_URL
}

function resolveFrontendUrl(): string {
  const cfg = readConfig()
  return cfg?.frontendUrl ?? CONDUCTOR_FRONTEND_URL
}

function prompt(question: string, hidden = false): Promise<string> {
  return new Promise((resolve) => {
    const rl = readline.createInterface({ input: process.stdin, output: process.stdout })
    if (hidden) {
      process.stdout.write(question)
      process.stdin.setRawMode?.(true)
      let input = ''
      process.stdin.resume()
      process.stdin.setEncoding('utf8')
      const onData = (ch: string) => {
        if (ch === '\n' || ch === '\r' || ch === '\u0003') {
          process.stdin.setRawMode?.(false)
          process.stdin.pause()
          process.stdin.removeListener('data', onData)
          process.stdout.write('\n')
          rl.close()
          resolve(input)
        } else if (ch === '\u007f') {
          input = input.slice(0, -1)
        } else {
          input += ch
        }
      }
      process.stdin.on('data', onData)
    } else {
      rl.question(question, (answer) => {
        rl.close()
        resolve(answer)
      })
    }
  })
}

async function loginLocal(apiUrl: string): Promise<void> {
  const email = await prompt('Email: ')
  const password = await prompt('Password: ', true)

  const spinner = ora('Authenticating...').start()

  const res = await fetch(`${apiUrl}/api/v1/auth/local`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email, password }),
  })

  if (!res.ok) {
    spinner.fail(chalk.red('Authentication failed — check your email and password.'))
    process.exit(1)
  }

  const { accessToken, user } = await res.json() as { accessToken: string; user: { email: string } }

  // Fetch first available project
  const projectsRes = await fetch(`${apiUrl}/api/v1/projects`, {
    headers: { Authorization: `Bearer ${accessToken}` },
  })

  if (!projectsRes.ok) {
    spinner.fail(chalk.red('Authenticated but failed to fetch projects.'))
    process.exit(1)
  }

  const projects = await projectsRes.json() as Array<{ id: string; name: string }>

  if (projects.length === 0) {
    spinner.fail(chalk.red('No projects found. Create a project in the Conductor UI first.'))
    process.exit(1)
  }

  const project = projects[0]!
  // Store a durable user API key, not the 24h JWT we just received.
  const apiKey = await ensureDurableApiKey(apiUrl, accessToken)
  const config = buildRefreshedConfig(readConfig(), {
    apiKey,
    email: user?.email ?? email,
    projectId: project.id,
    projectName: project.name,
    apiUrl,
  })
  writeConfig(config)

  spinner.succeed(chalk.green(`Logged in as ${email} (project: ${config.projectName})`))
}

async function isKeyValid(apiUrl: string, apiKey: string): Promise<boolean> {
  try {
    const res = await fetch(`${apiUrl}/api/v1/projects`, {
      headers: { Authorization: `Bearer ${apiKey}` },
    })
    return res.status === 200
  } catch {
    return false
  }
}

export function registerLogin(program: Command): void {
  program
    .command('login')
    .description('Authenticate with Conductor via browser')
    .option('--force', 'Re-authenticate even if already logged in')
    .option('--local', 'Use email/password login (local dev only)')
    .addHelpText('after', `
Examples:
  conductor login
  conductor login --force
  conductor login --local`)
    .action(async (options: { force?: boolean; local?: boolean }) => {
      const existing = readConfig()

      if (existing && existing.apiKey && !options.force) {
        const apiUrl = resolveApiUrl()
        const valid = await isKeyValid(apiUrl, existing.apiKey)
        if (valid) {
          console.log(
            `Already logged in as ${existing.email}. Use --force to re-authenticate.`
          )
          process.exit(0)
          return
        }
        console.log('Stored credentials are invalid — re-authenticating...')
      }

      const apiUrl = resolveApiUrl()

      if (options.local) {
        await loginLocal(apiUrl)
        process.exit(0)
        return
      }

      let port: number
      try {
        port = await findAvailablePort()
      } catch (err) {
        console.error(chalk.red((err as Error).message))
        process.exit(1)
        return
      }

      const spinner = ora('Opening browser for authentication...').start()

      try {
        const { default: open } = await import('open')
        const frontendUrl = resolveFrontendUrl()
        const loginUrl = `${frontendUrl}/auth/cli-login?port=${port}`
        await open(loginUrl)

        const payload = await waitForOAuthCallback(port, spinner)
        const config = buildRefreshedConfig(readConfig(), {
          apiKey: payload.apiKey,
          email: payload.email,
          projectId: payload.projectId,
          projectName: payload.projectName,
          apiUrl,
          frontendUrl,
        })
        writeConfig(config)
        spinner.succeed(
          chalk.green(`Logged in as ${config.email} (project: ${config.projectName})`)
        )
        process.exit(0)
      } catch (err) {
        spinner.fail(chalk.red((err as Error).message))
        process.exit(1)
      }
    })
}
