import * as http from 'http'
import * as net from 'net'
import type { Ora } from 'ora'

const PORT_MIN = 3131
const PORT_MAX = 3199
const DEFAULT_TIMEOUT_MS = 120_000

export interface OAuthCallbackPayload {
  apiKey: string
  projectId: string
  projectName: string
  email: string
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

export async function findAvailablePort(): Promise<number> {
  for (let port = PORT_MIN; port <= PORT_MAX; port++) {
    const available = await isPortAvailable(port)
    if (available) return port
  }
  throw new Error(`No available port found in range ${PORT_MIN}-${PORT_MAX}`)
}

export async function waitForOAuthCallback(
  port: number,
  spinner: Ora,
  timeoutMs: number = DEFAULT_TIMEOUT_MS
): Promise<OAuthCallbackPayload> {
  return new Promise((resolve, reject) => {
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
        cleanup()
        reject(new Error('OAuth callback missing required parameters'))
        return
      }

      res.writeHead(200, { 'Content-Type': 'text/html' })
      res.end(
        '<html><body><h1>Authentication successful!</h1><p>You can close this tab and return to the terminal.</p></body></html>'
      )

      cleanup()
      resolve({ apiKey, projectId, projectName, email })
    })

    server.listen(port)

    const timeout = setTimeout(() => {
      cleanup()
      reject(new Error('Authentication timed out after 120 seconds'))
    }, timeoutMs)

    const sigintHandler = () => {
      cleanup()
      spinner.fail('Cancelled')
      process.exit(1)
    }

    process.on('SIGINT', sigintHandler)

    function cleanup() {
      clearTimeout(timeout)
      process.off('SIGINT', sigintHandler)
      server.close()
    }
  })
}
