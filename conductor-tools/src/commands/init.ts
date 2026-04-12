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

export const CONDUCTOR_SKILL_CONTENT = `# /conductor:prd

You are a PRD creation assistant for the Conductor workflow.

## Trigger
This skill runs when the user invokes \`/conductor:prd\`.

## Prerequisites Check
Read \`~/.conductor/config.json\`. If missing or lacking \`localPath\`, \`projectId\`, or \`apiKey\`, respond:
> Run \`conductor login\` and \`conductor init\` first to set up Conductor in this project.

## Phase 1 — Discovery

Start with a single open-ended question:
> "What are you trying to build? Tell me about the problem you're solving."

Ask adaptive follow-up questions until you have clarity on all four areas:
- **Problem**: What pain point does this solve? Who feels it?
- **Users**: Who are the primary users and what do they need?
- **Solution**: What is the core approach or mechanism?
- **Scope**: Is this one feature/change, or a larger initiative?

If the scope seems large (>3 distinct epics), suggest:
> "This sounds like a large initiative. Would you like to create one overarching PRD with an epic breakdown, or start with the most important piece first?"

Do NOT proceed to research until the problem, users, and core solution are clear.

## Phase 2 — Research

Before drafting, explore the codebase:
1. Read \`CLAUDE.md\` for conventions and architecture
2. Search for relevant existing patterns using Grep/Glob
3. Identify tech stack, data models, and constraints relevant to the feature
4. Note any files that will need to change

Use the codebase context to make the PRD technically accurate and grounded.

## Phase 3 — Generate

1. Draft a brief outline:
   - Title
   - Problem statement (2-3 sentences)
   - Target users
   - Core solution
   - Feature list with P0/P1 classification

2. Present outline and ask:
   > "Does this capture your intent? Any adjustments before I write the full PRD?"

3. After approval, write the full PRD in the format below.

4. Present the draft and ask:
   > "Ready to save this, or would you like to revise anything?"

### PRD Format
\`\`\`markdown
---
issueId: {issueId}
type: PRD
title: {title}
status: DRAFT
createdAt: {ISO timestamp}
---

# {title}

## Problem
{problem statement}

## Users
{target users and their needs}

## Solution
{core solution approach}

## Features

### P0: Must Have

#### {Feature Name}
- **What**: {precise scope}
- **Not**: {explicit exclusions}
- **Acceptance Criteria**:
  - [ ] {testable, specific criterion}
- **Edge Cases**:
  - {scenario}: {expected behavior}

### P1: Should Have
...

### Out of Scope
...

## Technical Context
{relevant tech stack, dependencies, data model changes, API changes}

## Open Questions
- [ ] {unresolved item}
\`\`\`

## Phase 4 — Save

Call MCP tools in this exact sequence:

1. **Create issue**: \`create_issue({type: "PRD", title, description})\`
   - Receive: \`{issueId, localPath}\`

2. **Scaffold document**: \`scaffold_document({issueId, filename: "prd.md"})\`
   - Receive: \`{localPath: ".conductor/issues/{issueId}/prd.md"}\`

3. **Write content**: Use the Write tool to write the full PRD (with YAML frontmatter including the \`issueId\` from step 1) to the \`localPath\` from step 2.

4. **Confirm**: "PRD saved — syncing to conductor in the background."

Then offer supporting documents:
> "Would you like to add any supporting documents?
> - **Architecture diagram** (\`architecture.md\`) — Mermaid system diagram + component table
> - **Wireframes** (\`wireframes.md\`) — ASCII layouts for desktop and mobile
> - **HTML mock** (\`mockup.html\`) — standalone HTML prototype
>
> Which would you like? (or 'none' to finish)"

For each accepted doc:
1. \`scaffold_document({issueId, filename: "{doc-filename}"})\`
2. Write the template content (see templates below) to the returned localPath

## Supporting Document Templates

### Architecture Diagram (architecture.md)
\`\`\`markdown
---
issueId: {issueId}
type: architecture
title: {title} — Architecture
---

# {title} — Architecture

## System Overview

\\\`\\\`\\\`mermaid
flowchart TD
    User([User]) --> Frontend[Next.js Frontend]
    Frontend --> API[Spring Boot API]
    API --> DB[(PostgreSQL)]
    API --> Storage[GCP Cloud Storage]
\\\`\\\`\\\`

## Component Responsibilities

| Component | Technology | Responsibility |
|-----------|-----------|----------------|
| Frontend  | Next.js 14 | UI, auth, API calls |
| API       | Spring Boot 3.3 | Business logic, data |
| Database  | PostgreSQL 15 | Persistent storage |
| Storage   | GCP Cloud Storage | File storage |

## Key Sequence

\\\`\\\`\\\`mermaid
sequenceDiagram
    participant U as User
    participant F as Frontend
    participant A as API
    participant D as DB
    U->>F: User action
    F->>A: API request
    A->>D: Query
    D-->>A: Result
    A-->>F: Response
    F-->>U: Updated UI
\\\`\\\`\\\`
\`\`\`

### Wireframes (wireframes.md)
\`\`\`markdown
---
issueId: {issueId}
type: wireframes
title: {title} — Wireframes
---

# {title} — Wireframes

## Desktop Layout

\\\`\\\`\\\`
+--------------------+----------------------------------+
|  Sidebar           |  Main Content Area               |
|                    |                                  |
|  [Issues]          |  Page Title                      |
|  [Members]         |  +----------------------------+  |
|  [Settings]        |  |  Primary Content           |  |
|                    |  |                            |  |
|                    |  +----------------------------+  |
+--------------------+----------------------------------+
\\\`\\\`\\\`

## Mobile Layout

\\\`\\\`\\\`
+----------------------+
|  ☰  Conductor        |
+----------------------+
|  Page Title          |
|                      |
|  Primary Content     |
|                      |
+----------------------+
\\\`\\\`\\\`

## Element Description

| Element | Description | Interaction |
|---------|-------------|-------------|
| ...     | ...         | ...         |
\`\`\`

### HTML Mock (mockup.html)
Write a valid standalone HTML file with inline CSS — no external dependencies.
`

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

export function writeSkillFile(projectRoot: string): void {
  const commandDir = path.join(projectRoot, '.claude', 'commands', 'conductor')
  fs.mkdirSync(commandDir, { recursive: true })
  fs.writeFileSync(path.join(commandDir, 'prd.md'), CONDUCTOR_SKILL_CONTENT, 'utf8')
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

async function askYesNo(question: string): Promise<boolean> {
  const rl = readline.createInterface({ input: process.stdin, output: process.stdout })
  return new Promise(resolve => {
    rl.question(question, answer => {
      rl.close()
      resolve(answer.trim().toLowerCase() !== 'n')
    })
  })
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
    .action(async (options: { projectId?: string, path: string }) => {
      const workingDir = path.resolve(options.path)

      const apiUrl = process.env['CONDUCTOR_API_URL'] ?? 'https://conductor-backend-199707291514.us-central1.run.app'
      const frontendUrl = process.env['CONDUCTOR_FRONTEND_URL'] ?? 'https://conductor-frontend-199707291514.us-central1.run.app'

      let config = readConfig()
      if (!config || !(await isKeyValid(config.apiUrl ?? apiUrl, config.apiKey))) {
        console.log('Not logged in. Opening browser for authentication...')
        const { apiKey, email } = await performBrowserLogin(apiUrl, frontendUrl)
        config = { apiKey, email, projectId: '', projectName: '', apiUrl, frontendUrl }
      } else {
        console.log(chalk.green(`✓ Logged in as ${config.email}`))
      }

      if (options.projectId && options.projectId !== config.projectId) {
        try {
          const project = await apiGet<{ id: string, name: string }>(
            `/api/v1/projects/${options.projectId}`,
            config.apiKey,
            config.apiUrl ?? apiUrl
          )
          config = { ...config, projectId: project.id, projectName: project.name }
          writeConfig(config)
        } catch (err) {
          const status = (err as { statusCode?: number }).statusCode
          if (status === 403 || status === 404) {
            console.error(chalk.red(`✗ You don't have access to project "${options.projectId}".`))
            console.error(`  Ask your admin to invite you, then run this command again.`)
            process.exit(1)
            return
          }
          throw err
        }
        console.log(chalk.green(`✓ Connected to "${config.projectName}"`))
      } else if (!options.projectId) {
        const projects = await apiGet<Array<{ id: string; name: string }>>(
          '/api/v1/projects',
          config.apiKey,
          config.apiUrl ?? apiUrl
        )

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

      writeSkillFile(projectRoot)
      console.log(chalk.green('✓ Installed /conductor:prd command'))

      const existing = readMcpJson(workingDir)
      writeMcpJson(workingDir, buildMcpJson(existing))
      console.log(chalk.green('✓ Updated .mcp.json'))

      writeConfig({ ...config, localPath: projectRoot })

      if (process.stdin.isTTY) {
        console.log()
        const shouldSync = await askYesNo('Start syncing now? [Y/n] ')
        if (shouldSync) {
          const ok = await startDaemon()
          if (ok) {
            console.log(chalk.green('✓ Sync daemon started'))
          } else {
            console.log(chalk.red('✗ Daemon failed to start — run `conductor start` to retry'))
          }
        } else {
          console.log(chalk.dim('  Run `conductor start` when ready.'))
        }
      }
    })
}
