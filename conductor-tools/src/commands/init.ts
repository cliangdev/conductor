import * as fs from 'fs'
import * as path from 'path'
import * as os from 'os'
import * as readline from 'readline'
import { execSync } from 'child_process'
import { Command } from 'commander'
import chalk from 'chalk'
import ora from 'ora'
import { readConfig, writeConfig } from '../lib/config.js'
import { apiGet, apiPost } from '../lib/api.js'
import { findAvailablePort, waitForOAuthCallback } from '../lib/oauth-server.js'
import { startDaemon } from './start.js'
import { installPluginAssets, getAssetSrcDir } from '../lib/plugin-assets.js'
import { printNextSteps } from '../lib/next-steps.js'

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
  command: 'conductor',
  args: ['mcp'],
}

export function getIssuesDir(projectId: string): string {
  return path.join(os.homedir(), '.conductor', projectId, 'issues')
}

export function getProjectRoot(workingDir: string): string {
  try {
    return execSync('git rev-parse --show-toplevel', {
      cwd: workingDir,
      encoding: 'utf8',
      stdio: ['pipe', 'pipe', 'pipe'],
    }).trim()
  } catch {
    return workingDir
  }
}

export function ensureGitignore(projectRoot: string): void {
  const gitignorePath = path.join(projectRoot, '.gitignore')
  const entry = '.conductor/'
  let content = ''
  try {
    content = fs.readFileSync(gitignorePath, 'utf8')
  } catch { /* doesn't exist */ }
  if (content.split('\n').some(line => line.trim() === entry)) return
  const newContent = content && !content.endsWith('\n') ? `${content}\n${entry}\n` : `${content}${entry}\n`
  fs.writeFileSync(gitignorePath, newContent, 'utf8')
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

async function performBrowserLogin(
  apiUrl: string,
  frontendUrl: string
): Promise<{ apiKey: string; email: string }> {
  const spinner = ora('Opening browser for authentication... (Ctrl+C to cancel)').start()
  const port = await findAvailablePort()
  const { default: open } = await import('open')
  const loginUrl = `${frontendUrl}/auth/cli-login?port=${port}`
  await open(loginUrl)
  const payload = await waitForOAuthCallback(port, spinner)
  spinner.succeed(chalk.green(`Logged in as ${payload.email}`))
  return { apiKey: payload.apiKey, email: payload.email }
}

async function selectOption(promptText: string, choices: string[]): Promise<number> {
  console.log(promptText)
  choices.forEach((c, i) => console.log(`  [${i + 1}] ${c}`))
  const rl = readline.createInterface({ input: process.stdin, output: process.stdout })
  return new Promise(resolve => {
    const ask = () => {
      rl.question(`Select (1-${choices.length}): `, answer => {
        const n = parseInt(answer.trim(), 10)
        if (!isNaN(n) && n >= 1 && n <= choices.length) {
          rl.close()
          resolve(n - 1)
        } else {
          console.log(chalk.red(`  Please enter a number between 1 and ${choices.length}`))
          ask()
        }
      })
    }
    ask()
  })
}

async function askText(question: string): Promise<string> {
  const rl = readline.createInterface({ input: process.stdin, output: process.stdout })
  return new Promise(resolve => {
    rl.question(question, answer => {
      rl.close()
      resolve(answer.trim())
    })
  })
}

export function registerInit(program: Command): void {
  program
    .command('init')
    .description('Initialize Conductor in the current project')
    .option('--project-id <id>', 'Project ID to connect to (for new members or switching projects)')
    .option('--path <dir>', 'Working directory', process.cwd())
    .addHelpText('after', `
Examples:
  conductor init
  conductor init --project-id proj_abc123
  conductor init --path /workspace/my-project`)
    .action(async (options: { projectId?: string, path: string }) => {
      const workingDir = path.resolve(options.path)

      const apiUrl = process.env['CONDUCTOR_API_URL'] ?? 'https://conductor-backend-199707291514.us-central1.run.app'
      const frontendUrl = process.env['CONDUCTOR_FRONTEND_URL'] ?? 'https://conductor-frontend-199707291514.us-central1.run.app'

      let config = readConfig()
      // Capture original projects before any mutations so we can merge into it later
      const originalConfig = config

      if (config) {
        const authSpinner = ora('Checking authentication...').start()
        const valid = await isKeyValid(config.apiUrl ?? apiUrl, config.apiKey)
        if (!valid) {
          authSpinner.warn('Session expired. Opening browser for authentication...')
          const { apiKey, email } = await performBrowserLogin(apiUrl, frontendUrl)
          config = { apiKey, email, projectId: '', projectName: '', apiUrl, frontendUrl }
        } else {
          authSpinner.succeed(chalk.green(`Logged in as ${config.email}`))
        }
      } else {
        console.log('Not logged in. Opening browser for authentication...')
        const { apiKey, email } = await performBrowserLogin(apiUrl, frontendUrl)
        config = { apiKey, email, projectId: '', projectName: '', apiUrl, frontendUrl }
      }

      if (options.projectId && options.projectId !== config.projectId) {
        const projectSpinner = ora(`Fetching project "${options.projectId}"...`).start()
        try {
          const project = await apiGet<{ id: string, name: string }>(
            `/api/v1/projects/${options.projectId}`,
            config.apiKey,
            config.apiUrl ?? apiUrl
          )
          config = { ...config, projectId: project.id, projectName: project.name }
          writeConfig(config)
          projectSpinner.succeed(chalk.green(`Connected to "${config.projectName}"`))
        } catch (err) {
          const status = (err as { statusCode?: number }).statusCode
          if (status === 403 || status === 404) {
            projectSpinner.fail('Access denied')
            console.error(chalk.red(`✗ You don't have access to project "${options.projectId}".`))
            console.error(`  Ask your admin to invite you, then run this command again.`)
            process.exit(1)
            return
          }
          throw err
        }
      } else if (!options.projectId) {
        const listSpinner = ora('Fetching your projects...').start()
        const projects = await apiGet<Array<{ id: string; name: string }>>(
          '/api/v1/projects',
          config.apiKey,
          config.apiUrl ?? apiUrl
        )
        listSpinner.succeed('Projects loaded')

        let projectId: string
        let projectName: string

        if (projects.length === 0) {
          console.log('\nNo projects found. Let\'s create one.')
          const name = await askText('Project name: ')
          if (!name) {
            console.error(chalk.red('✗ Project name cannot be empty.'))
            process.exit(1)
            return
          }
          const spinner = ora('Creating project...').start()
          const project = await apiPost<{ id: string; name: string }>(
            '/api/v1/projects',
            { name },
            config.apiKey,
            config.apiUrl ?? apiUrl
          )
          spinner.succeed(chalk.green(`Created project "${project.name}"`))
          projectId = project.id
          projectName = project.name
        } else {
          console.log()
          const choices = ['Create a new project', ...projects.map(p => p.name)]
          const idx = await selectOption('Select a project to link:', choices)

          if (idx === 0) {
            const name = await askText('Project name: ')
            if (!name) {
              console.error(chalk.red('✗ Project name cannot be empty.'))
              process.exit(1)
              return
            }
            const spinner = ora('Creating project...').start()
            const project = await apiPost<{ id: string; name: string }>(
              '/api/v1/projects',
              { name },
              config.apiKey,
              config.apiUrl ?? apiUrl
            )
            spinner.succeed(chalk.green(`Created project "${project.name}"`))
            projectId = project.id
            projectName = project.name
          } else {
            const picked = projects[idx - 1]!
            projectId = picked.id
            projectName = picked.name
          }
        }

        config = { ...config, projectId, projectName, apiUrl: config.apiUrl ?? apiUrl, frontendUrl: config.frontendUrl ?? frontendUrl }
        writeConfig(config)
        console.log(chalk.green(`✓ Connected to "${projectName}"`))
      } else {
        console.log(chalk.green(`✓ Connected to "${config.projectName}"`))
      }


      console.log(`\nSetting up local project directory for "${config.projectName}"...`)
      const projectRoot = getProjectRoot(workingDir)
      console.log(chalk.green(`✓ Detected project root: ${projectRoot}`))

      const issuesDir = getIssuesDir(config.projectId)
      fs.mkdirSync(issuesDir, { recursive: true })

      fs.mkdirSync(path.join(projectRoot, '.conductor', 'issues'), { recursive: true })
      console.log(chalk.green('✓ Created .conductor/issues/'))

      ensureGitignore(projectRoot)
      console.log(chalk.green('✓ Updated .gitignore'))

      const claudeDir = path.join(projectRoot, '.claude')
      const pluginStatus = installPluginAssets(claudeDir, getAssetSrcDir())
      if (pluginStatus === 'installed') {
        console.log(chalk.green('✓ Installed conductor Claude plugin'))
      } else if (pluginStatus === 'updated') {
        console.log(chalk.green('✓ Updated conductor Claude plugin'))
      } else {
        console.log(chalk.green('✓ Conductor Claude plugin up to date'))
      }

      const existing = readMcpJson(workingDir)
      writeMcpJson(workingDir, buildMcpJson(existing))
      console.log(chalk.green('✓ Updated .mcp.json'))

      const legacyEntry =
        originalConfig?.projectId && originalConfig?.localPath
          ? { [originalConfig.projectId]: { localPath: originalConfig.localPath, projectName: originalConfig.projectName } }
          : {}
      const existingProjects = originalConfig?.projects ?? legacyEntry
      const updatedProjects = {
        ...existingProjects,
        [config.projectId]: { localPath: projectRoot, projectName: config.projectName },
      }
      writeConfig({ ...config, localPath: projectRoot, projects: updatedProjects })

      console.log()
      const ok = await startDaemon()
      if (ok) {
        console.log(chalk.green('✓ Sync daemon started'))
      } else {
        console.log(chalk.red('✗ Daemon failed to start — run `conductor start` to retry'))
      }
      printNextSteps()
    })
}
