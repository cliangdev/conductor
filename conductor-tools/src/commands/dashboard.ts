import { Command } from 'commander'
import chalk from 'chalk'
import { readDaemonState, ActiveRun } from '../daemon/state.js'
import { isDaemonRunning, getQueueCount, formatRelativeTime } from './status.js'

function formatUptime(startedAt: string): string {
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

function formatRunDuration(startedAt: string): string {
  const diffMs = Date.now() - new Date(startedAt).getTime()
  const diffSec = Math.floor(diffMs / 1000)
  if (diffSec < 60) return `${diffSec}s`
  if (diffSec < 3600) return `${Math.floor(diffSec / 60)}m ${diffSec % 60}s`
  return `${Math.floor(diffSec / 3600)}h ${Math.floor((diffSec % 3600) / 60)}m`
}

function truncate(str: string, len: number): string {
  if (str.length <= len) return str
  return str.slice(0, len - 1) + '…'
}

function renderDashboard(): void {
  process.stdout.write('\x1Bc')

  const now = new Date()
  const timeStr = now.toLocaleTimeString()

  const daemonRunning = isDaemonRunning()
  const queueCount = getQueueCount()

  // Header
  console.log(chalk.cyan('╔══════════════════════════════════════╗'))
  console.log(chalk.cyan('║') + chalk.bold('     CONDUCTOR DASHBOARD              ') + chalk.cyan('║'))
  console.log(chalk.cyan('╚══════════════════════════════════════╝'))
  console.log(`Updated: ${chalk.gray(timeStr)}`)
  console.log()

  if (!daemonRunning) {
    console.log(chalk.yellow('Daemon not running — start with `conductor start`'))
    console.log()
    console.log(chalk.gray('Press Ctrl+C to exit'))
    return
  }

  const state = readDaemonState()

  // DAEMON section
  console.log(chalk.bold('DAEMON'))
  if (state) {
    const pollInterval = state.pollMode === 'active' ? '5s' : '60s'
    console.log(`  PID:       ${chalk.white(state.pid)}`)
    console.log(`  Uptime:    ${chalk.white(formatUptime(state.startedAt))}`)
    console.log(`  Status:    ${chalk.green('Running')}`)
    console.log()

    // POLL section
    console.log(chalk.bold('POLL'))
    console.log(`  Mode:      ${chalk.white(`${state.pollMode} (${pollInterval})`)}`)
    const lastPoll = state.lastPollAt ? formatRelativeTime(state.lastPollAt) : 'never'
    console.log(`  Last poll: ${chalk.white(lastPoll)}`)
    console.log(`  Errors:    ${chalk.white(`${state.consecutiveErrors} consecutive`)}`)
    console.log(`  Events:    ${chalk.white(`${state.eventsThisSession} this session`)}`)
    console.log()

    // SYNC QUEUE section
    console.log(chalk.bold('SYNC QUEUE'))
    console.log(`  Pending:   ${chalk.white(`${queueCount} items`)}`)
    console.log()

    // WORKFLOW RUNS section
    console.log(chalk.bold('WORKFLOW RUNS'))
    const runs: ActiveRun[] = state.activeRuns ?? []
    if (runs.length === 0) {
      console.log(`  ${chalk.gray('No active runs')}`)
    } else {
      // Table header
      const idCol = 'RUN ID  '
      const titleCol = 'ISSUE TITLE                   '
      const jobCol = 'JOB             '
      const statusCol = 'STATUS    '
      const durationCol = 'DURATION'
      console.log(
        `  ${chalk.underline(idCol)}  ${chalk.underline(titleCol)}  ${chalk.underline(jobCol)}  ${chalk.underline(statusCol)}  ${chalk.underline(durationCol)}`
      )
      for (const run of runs) {
        const runIdStr = run.runId.slice(0, 8).padEnd(8)
        const titleStr = truncate(run.issueTitle, 30).padEnd(30)
        const jobStr = truncate(run.jobName, 16).padEnd(16)
        const statusColor =
          run.status === 'completed'
            ? chalk.green
            : run.status === 'failed'
              ? chalk.red
              : chalk.yellow
        const statusStr = statusColor(run.status.padEnd(10))
        const durationStr = formatRunDuration(run.startedAt)
        console.log(`  ${runIdStr}  ${titleStr}  ${jobStr}  ${statusStr}  ${durationStr}`)
      }
    }
    console.log()
  } else {
    console.log(`  PID:       ${chalk.gray('unknown')}`)
    console.log(`  Status:    ${chalk.green('Running')}`)
    console.log()
    console.log(chalk.bold('SYNC QUEUE'))
    console.log(`  Pending:   ${chalk.white(`${queueCount} items`)}`)
    console.log()
    console.log(chalk.bold('WORKFLOW RUNS'))
    console.log(`  ${chalk.gray('No active runs')}`)
    console.log()
  }

  console.log(chalk.gray('Press Ctrl+C to exit'))
}

export function registerDashboard(program: Command): void {
  program
    .command('dashboard')
    .description('Live terminal dashboard with 2s refresh')
    .action(() => {
      // Hide cursor
      process.stdout.write('\x1B[?25l')

      // Restore cursor on exit
      const cleanup = () => {
        process.stdout.write('\x1B[?25h')
        process.exit(0)
      }
      process.on('SIGINT', cleanup)

      // Initial render
      renderDashboard()

      // Refresh every 2 seconds
      setInterval(renderDashboard, 2000)
    })
}
