import * as readline from 'readline'
import { Command } from 'commander'
import chalk from 'chalk'
import ora from 'ora'
import { readConfig, writeConfig } from '../lib/config.js'
import { findAvailablePort, waitForOAuthCallback } from '../lib/oauth-server.js'

const CONDUCTOR_API_URL = process.env['CONDUCTOR_API_URL'] ?? 'http://localhost:8080'

const CONDUCTOR_FRONTEND_URL = process.env['CONDUCTOR_FRONTEND_URL'] ?? 'http://localhost:3000'

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
  writeConfig({
    apiKey: accessToken,
    projectId: project.id,
    projectName: project.name,
    email: user?.email ?? email,
    apiUrl,
  })

  spinner.succeed(chalk.green(`Logged in as ${email} (project: ${project.name})`))
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
    .action(async (options: { force?: boolean; local?: boolean }) => {
      const existing = readConfig()

      if (existing && !options.force) {
        const apiUrl = resolveApiUrl()
        const valid = await isKeyValid(apiUrl, existing.apiKey ?? '')
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
        const existingConfig = readConfig()
        const config = {
          ...payload,
          apiUrl,
          frontendUrl,
          ...(existingConfig?.localPath ? { localPath: existingConfig.localPath } : {}),
        }
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
