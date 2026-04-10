import { Command } from 'commander'
import chalk from 'chalk'
import ora from 'ora'
import { readConfig, writeConfig } from '../lib/config.js'
import { findAvailablePort, waitForOAuthCallback } from '../lib/oauth-server.js'

const CONDUCTOR_API_URL = process.env['CONDUCTOR_API_URL'] ?? 'http://localhost:8080'

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

      try {
        const { default: open } = await import('open')
        const loginUrl = `${CONDUCTOR_API_URL}/auth/cli-login?port=${port}`
        await open(loginUrl)

        const payload = await waitForOAuthCallback(port, spinner)
        const config = { ...payload, apiUrl: CONDUCTOR_API_URL }
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
