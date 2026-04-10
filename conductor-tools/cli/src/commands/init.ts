import * as fs from 'fs'
import * as path from 'path'
import * as os from 'os'
import { Command } from 'commander'
import chalk from 'chalk'
import { readConfig } from '../lib/config.js'

interface McpServerEntry {
  command: string
  args?: string[]
  env?: Record<string, string>
}

interface McpJson {
  mcpServers?: Record<string, McpServerEntry>
  [key: string]: unknown
}

const CONDUCTOR_MCP_ENTRY: McpServerEntry = {
  command: 'npx',
  args: ['@conductor/mcp'],
}

export function getIssuesDir(projectId: string): string {
  return path.join(os.homedir(), '.conductor', projectId, 'issues')
}

export function readMcpJson(workingDir: string): McpJson {
  const mcpPath = path.join(workingDir, '.mcp.json')
  try {
    const raw = fs.readFileSync(mcpPath, 'utf8')
    return JSON.parse(raw) as McpJson
  } catch {
    return {}
  }
}

export function writeMcpJson(workingDir: string, content: McpJson): void {
  const mcpPath = path.join(workingDir, '.mcp.json')
  fs.writeFileSync(mcpPath, JSON.stringify(content, null, 2) + '\n', 'utf8')
}

export function buildMcpJson(existing: McpJson): McpJson {
  return {
    ...existing,
    mcpServers: {
      ...(existing.mcpServers ?? {}),
      conductor: CONDUCTOR_MCP_ENTRY,
    },
  }
}

export function registerInit(program: Command): void {
  program
    .command('init')
    .description('Initialize Conductor in the current project')
    .option('--path <dir>', 'Working directory to initialize', process.cwd())
    .action((options: { path: string }) => {
      const config = readConfig()
      if (!config) {
        console.error('Please run `conductor login` first')
        process.exit(1)
        return
      }

      const issuesDir = getIssuesDir(config.projectId)
      fs.mkdirSync(issuesDir, { recursive: true })

      const workingDir = path.resolve(options.path)
      const existing = readMcpJson(workingDir)
      const updated = buildMcpJson(existing)
      writeMcpJson(workingDir, updated)

      const relativeIssuesDir = issuesDir.replace(os.homedir(), '~')
      console.log(chalk.green(`✓ Local directory: ${relativeIssuesDir}`))
      console.log(chalk.green('✓ .mcp.json updated'))
    })
}
