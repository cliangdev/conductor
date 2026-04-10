import * as fs from 'fs'
import * as path from 'path'
import { Command } from 'commander'
import chalk from 'chalk'
import { readConfig, CONFIG_PATH } from '../lib/config.js'
import { getIssuesDir } from './init.js'

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

export function checkIssuesDir(projectId: string): boolean {
  return fs.existsSync(getIssuesDir(projectId))
}

export function checkConfigFile(): boolean {
  return fs.existsSync(CONFIG_PATH)
}

export function registerDoctor(program: Command): void {
  program
    .command('doctor')
    .description('Run health checks on your Conductor setup')
    .action(async () => {
      const configExists = checkConfigFile()
      const configMark = configExists ? chalk.green('✓') : chalk.red('✗')
      console.log(`${configMark} Config file found (~/.conductor/config.json)`)

      const config = readConfig()

      if (config) {
        const apiReachable = await checkApiHealth(config.apiUrl)
        const apiMark = apiReachable ? chalk.green('✓') : chalk.red('✗')
        console.log(`${apiMark} API reachable (GET /api/v1/health → ${apiReachable ? '200' : 'failed'})`)
      } else {
        console.log(`${chalk.red('✗')} API reachable (no config — skipped)`)
      }

      const mcpExists = checkMcpJson(process.cwd())
      const mcpMark = mcpExists ? chalk.green('✓') : chalk.red('✗')
      console.log(`${mcpMark} .mcp.json ${mcpExists ? 'found' : 'not found'} in current directory`)

      if (config) {
        const issuesDirExists = checkIssuesDir(config.projectId)
        const issuesMark = issuesDirExists ? chalk.green('✓') : chalk.red('✗')
        console.log(`${issuesMark} Local issues directory ${issuesDirExists ? 'exists' : 'not found'}`)
      } else {
        console.log(`${chalk.red('✗')} Local issues directory (no config — skipped)`)
      }
    })
}
