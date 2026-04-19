import * as fs from 'fs'
import * as path from 'path'
import * as os from 'os'
import { Command } from 'commander'
import chalk from 'chalk'
import { readConfig, CONFIG_PATH } from '../lib/config.js'
import { getAssetSrcDir, getPluginInstallStatus } from '../lib/plugin-assets.js'
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

      if (failures === 0 && warnings === 0) {
        printNextSteps()
      }
    })
}
