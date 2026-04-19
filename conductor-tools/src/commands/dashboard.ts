import { Command } from 'commander'
import chalk from 'chalk'
import { readDaemonState, ActiveRun } from '../daemon/state.js'
import { isDaemonRunning, getQueueCount, formatRelativeTime } from './status.js'
import { readConfig } from '../lib/config.js'

const REFRESH_INTERVAL_MS = 5000
const ANSI_RE = /\x1B\[[0-9;]*m/g

export function visLen(s: string): number {
  return s.replace(ANSI_RE, '').length
}

export function rpad(colored: string, width: number): string {
  return colored + ' '.repeat(Math.max(0, width - visLen(colored)))
}

export function formatUptime(startedAt: string): string {
  const diffMs = Date.now() - new Date(startedAt).getTime()
  const diffSec = Math.floor(diffMs / 1000)
  if (diffSec < 3600) {
    const m = Math.floor(diffSec / 60)
    const s = diffSec % 60
    return `${m}m ${s}s`
  }
  const h = Math.floor(diffSec / 3600)
  const m = Math.floor((diffSec % 3600) / 60)
  return `${h}h ${m}m`
}

export function formatRunDuration(startedAt: string): string {
  const diffMs = Date.now() - new Date(startedAt).getTime()
  const diffSec = Math.floor(diffMs / 1000)
  if (diffSec < 60) return `${diffSec}s`
  if (diffSec < 3600) return `${Math.floor(diffSec / 60)}m ${diffSec % 60}s`
  return `${Math.floor(diffSec / 3600)}h ${Math.floor((diffSec % 3600) / 60)}m`
}

export function truncate(str: string, len: number): string {
  if (str.length <= len) return str
  return str.slice(0, len - 1) + '…'
}

// Two-column row: left is padded to COL1 visible chars, right follows
const COL1 = 42
function twoCol(left: string, right: string = ''): string {
  return '  ' + rpad(left, COL1) + right
}

export function buildLines(isLive: boolean): string[] {
  const config = readConfig()
  const daemonRunning = isDaemonRunning()
  const queueCount = getQueueCount()
  const state = daemonRunning ? readDaemonState() : null
  const lines: string[] = []

  // ── Header ──────────────────────────────────────────────────────────────────
  const projectPart = config
    ? `${chalk.bold(config.projectName)}  ·  ${chalk.gray(config.email)}`
    : chalk.gray('not configured — run conductor init')
  lines.push(`${chalk.bold('conductor')}  ·  ${projectPart}`)
  lines.push('')

  if (!daemonRunning) {
    // ── Not running ───────────────────────────────────────────────────────────
    lines.push('')
    lines.push(`  ${chalk.bold('DAEMON')}`)
    lines.push(`  ${'─'.repeat(28)}`)
    lines.push(`  Status    ${chalk.red('○ not running')}`)
    lines.push('')
    lines.push(`  Run ${chalk.cyan('conductor start')} to begin syncing.`)
    lines.push('')
  } else {
    // ── DAEMON + SYNC QUEUE (two-column) ─────────────────────────────────────
    lines.push('')
    lines.push(twoCol(chalk.bold('DAEMON'), chalk.bold('SYNC QUEUE')))
    lines.push(twoCol('─'.repeat(28), '─'.repeat(22)))

    const statusLeft = `Status    ${chalk.green('● running')}`
    const queueRight = `Pending   ${queueCount > 0 ? chalk.yellow(String(queueCount)) : chalk.gray('0')} items`
    lines.push(twoCol(statusLeft, queueRight))

    if (state) {
      lines.push(twoCol(`PID       ${chalk.white(String(state.pid))}`))
      lines.push(twoCol(`Uptime    ${chalk.white(formatUptime(state.startedAt))}`))
    } else {
      lines.push(twoCol(`PID       ${chalk.gray('unknown')}`))
    }

    lines.push('')
    lines.push('')

    // ── POLL ─────────────────────────────────────────────────────────────────
    if (state) {
      const pollInterval = state.pollMode === 'active' ? '5s' : '60s'
      const lastPoll = state.lastPollAt ? formatRelativeTime(state.lastPollAt) : 'never'
      lines.push(`  ${chalk.bold('POLL')}`)
      lines.push(`  ${'─'.repeat(28)}`)
      lines.push(`  Mode      ${chalk.white(`${state.pollMode}  (${pollInterval} interval)`)}`)
      lines.push(`  Last      ${chalk.white(lastPoll)}`)
      lines.push(`  Events    ${chalk.white(`${state.eventsThisSession} this session`)}`)
      lines.push(`  Errors    ${state.consecutiveErrors > 0 ? chalk.red(`${state.consecutiveErrors} consecutive`) : chalk.gray('0 consecutive')}`)
      lines.push('')
      lines.push('')
    }

    // ── WORKFLOW RUNS ────────────────────────────────────────────────────────
    lines.push(`  ${chalk.bold('WORKFLOW RUNS')}`)
    lines.push(`  ${'─'.repeat(60)}`)
    const runs: ActiveRun[] = state?.activeRuns ?? []
    if (runs.length === 0) {
      lines.push(`  ${chalk.gray('No active runs')}`)
    } else {
      const ID_W = 10, TITLE_W = 30, STATUS_W = 12
      lines.push(
        `  ${chalk.dim(rpad('RUN ID', ID_W))}  ${chalk.dim(rpad('ISSUE TITLE', TITLE_W))}  ${chalk.dim(rpad('STATUS', STATUS_W))}  ${chalk.dim('DURATION')}`
      )
      for (const run of runs) {
        const runId = rpad(run.runId.slice(0, 8), ID_W)
        const title = rpad(truncate(run.issueTitle, TITLE_W), TITLE_W)
        const statusStr =
          run.status === 'completed' ? chalk.green(rpad('✓ done', STATUS_W))
          : run.status === 'failed'  ? chalk.red(rpad('✗ failed', STATUS_W))
          :                            chalk.yellow(rpad('● running', STATUS_W))
        const duration = formatRunDuration(run.startedAt)
        lines.push(`  ${runId}  ${title}  ${statusStr}  ${duration}`)
      }
    }
    lines.push('')
  }

  // ── Footer ────────────────────────────────────────────────────────────────
  if (isLive) {
    const now = new Date().toLocaleTimeString()
    lines.push(`${'─'.repeat(46)}  ${chalk.gray(now)}  ${chalk.dim('Ctrl+C to exit')}`)
  }

  return lines
}

let lastLineCount = 0

function render(isLive: boolean): void {
  const lines = buildLines(isLive)

  if (lastLineCount > 0 && isLive) {
    // Move cursor up to start of last render, erase to end — no flicker
    process.stdout.write(`\x1B[${lastLineCount}A\x1B[0J`)
  }

  process.stdout.write(lines.join('\n') + '\n')
  lastLineCount = lines.length
}

export function registerDashboard(program: Command): void {
  program
    .command('dashboard')
    .description('Show Conductor status (live in terminal, one-shot when piped)')
    .addHelpText('after', `
Examples:
  conductor dashboard
  conductor dashboard | cat`)
    .action(() => {
      const isLive = Boolean(process.stdout.isTTY)

      if (isLive) {
        process.stdout.write('\x1B[?25l') // hide cursor
        const cleanup = () => {
          process.stdout.write('\x1B[?25h') // restore cursor
          process.exit(0)
        }
        process.on('SIGINT', cleanup)
      }

      render(isLive)

      if (isLive) {
        setInterval(() => render(isLive), REFRESH_INTERVAL_MS)
      }
    })
}
