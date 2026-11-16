import { spawn } from 'child_process'
import { Command } from 'commander'
import {
  buildUpdateMessage,
  cachedUpdateStatus,
  cacheIsFresh,
  checkForUpdate,
  isUpdateCheckDisabled,
} from './update-check.js'

/** Commands that never get the trailing notice: mcp (stdout is a protocol stream; it has its own path),
 * doctor (has a dedicated check), upgrade (is the fix), and the hidden refresher. */
const SKIP = new Set(['mcp', 'doctor', 'upgrade', '__update-check'])

export interface NoticeEnv {
  isTTY: boolean
  write: (line: string) => void
  /** Starts a detached refresh of the cache; must not block or keep this process alive. */
  refreshInBackground: () => void
}

export function maybePrintNotice(
  commandName: string,
  opts: { json?: boolean },
  current: string,
  env: NoticeEnv,
  cacheDeps?: Parameters<typeof cachedUpdateStatus>[1],
): void {
  try {
    if (SKIP.has(commandName) || isUpdateCheckDisabled(cacheDeps?.env ?? process.env)) return
    if (!cacheIsFresh(cacheDeps)) env.refreshInBackground()
    if (opts.json || !env.isTTY) return
    const status = cachedUpdateStatus(current, cacheDeps)
    if (status?.outdated) env.write(buildUpdateMessage(status.current, status.latest))
  } catch { /* a notice must never break a command */ }
}

export function registerUpdateNotice(program: Command, current: string): void {
  // Hidden worker: fetch + write the cache, exit. Run detached so commands never wait on the network.
  program
    .command('__update-check', { hidden: true })
    .action(async () => {
      await checkForUpdate(current)
    })

  program.hook('postAction', (_thisCommand, actionCommand) => {
    const opts = actionCommand.opts() as { json?: boolean }
    maybePrintNotice(actionCommand.name(), opts, current, {
      isTTY: Boolean(process.stderr.isTTY),
      write: (line) => process.stderr.write(`\n${line}\n`),
      refreshInBackground: () => {
        const child = spawn(process.execPath, [process.argv[1]!, '__update-check'], { detached: true, stdio: 'ignore' })
        child.on('error', () => {})
        child.unref()
      },
    })
  })
}
