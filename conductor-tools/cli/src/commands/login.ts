import * as http from 'http'
import * as net from 'net'
import { Command } from 'commander'
import chalk from 'chalk'
import ora from 'ora'
import { readConfig, writeConfig, Config } from '../lib/config.js'

const CONDUCTOR_API_URL = process.env['CONDUCTOR_API_URL'] ?? 'http://localhost:8080'
const PORT_MIN = 3131
const PORT_MAX = 3199
const LOGIN_TIMEOUT_MS = 120_000

async function findAvailablePort(): Promise<number> {
  for (let port = PORT_MIN; port <= PORT_MAX; port++) {
    const available = await isPortAvailable(port)
    if (available) return port
  }
  throw new Error(`No available port found in range ${PORT_MIN}-${PORT_MAX}`)
}

function isPortAvailable(port: number): Promise<boolean> {
  return new Promise((resolve) => {
    const server = net.createServer()
    server.once('error', () => resolve(false))
    server.once('listening', () => {
      server.close(() => resolve(true))
    })
    server.listen(port)
  })
}

interface CallbackParams {
  apiKey: string
  projectId: string
  projectName: string
  email: string
}

function startCallbackServer(
  port: number,
  onCallback: (params: CallbackParams) => void,
  onError: (err: Error) => void
): http.Server {
  const server = http.createServer((req, res) => {
    const url = new URL(req.url ?? '/', `http://localhost:${port}`)
    if (url.pathname !== '/oauth/callback') {
      res.writeHead(404)
      res.end('Not found')
      return
    }

    const apiKey = url.searchParams.get('apiKey')
    const projectId = url.searchParams.get('projectId')
    const projectName = url.searchParams.get('projectName')
    const email = url.searchParams.get('email')

    if (!apiKey || !projectId || !projectName || !email) {
      res.writeHead(400)
      res.end('Missing required parameters')
      onError(new Error('OAuth callback missing required parameters'))
      return
    }

    res.writeHead(200, { 'Content-Type': 'text/html' })
    res.end(
      '<html><body><h1>Authentication successful!</h1><p>You can close this tab and return to the terminal.</p></body></html>'
    )

    onCallback({ apiKey, projectId, projectName, email })
  })

  server.listen(port)
  return server
}

export function registerLogin(program: Command): void {
  program
    .command('login')
    .description('Authenticate with Conductor via browser')
    .option('--force', 'Re-authenticate even if already logged in')
    .action(async (options: { force?: boolean }) => {
      const existing = readConfig()

      if (existing && !options.force) {
        console.log(
          `Already logged in as ${existing.email}. Use --force to re-authenticate.`
        )
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

      const callbackPromise = new Promise<Config>((resolve, reject) => {
        const server = startCallbackServer(
          port,
          (params) => {
            server.close()
            resolve({
              ...params,
              apiUrl: CONDUCTOR_API_URL,
            })
          },
          (err) => {
            server.close()
            reject(err)
          }
        )

        const timeout = setTimeout(() => {
          server.close()
          reject(new Error('Authentication timed out after 120 seconds'))
        }, LOGIN_TIMEOUT_MS)

        server.on('close', () => clearTimeout(timeout))

        process.on('SIGINT', () => {
          server.close()
          clearTimeout(timeout)
          spinner.fail('Authentication cancelled')
          process.exit(1)
        })
      })

      try {
        const { default: open } = await import('open')
        const loginUrl = `${CONDUCTOR_API_URL}/auth/cli-login?port=${port}`
        await open(loginUrl)

        const config = await callbackPromise
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
