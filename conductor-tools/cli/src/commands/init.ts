import * as fs from 'fs'
import * as path from 'path'
import * as os from 'os'
import { execSync } from 'child_process'
import { Command } from 'commander'
import chalk from 'chalk'
import { readConfig, writeConfig } from '../lib/config.js'

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

const CONDUCTOR_SKILL_CONTENT = `# /conductor:prd

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
  const skillDir = path.join(projectRoot, '.claude', 'skills')
  fs.mkdirSync(skillDir, { recursive: true })
  fs.writeFileSync(path.join(skillDir, 'conductor.md'), CONDUCTOR_SKILL_CONTENT, 'utf8')
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

      const projectRoot = getProjectRoot(workingDir)
      const conductorIssuesDir = path.join(projectRoot, '.conductor', 'issues')
      fs.mkdirSync(conductorIssuesDir, { recursive: true })
      ensureGitignore(projectRoot)
      console.log(chalk.green('✓ Created: .conductor/issues/'))
      console.log(chalk.green('✓ .gitignore updated'))

      const existingConfig = readConfig()
      if (existingConfig) {
        writeConfig({ ...existingConfig, localPath: projectRoot })
        console.log(chalk.green(`✓ localPath saved: ${projectRoot}`))
      }

      writeSkillFile(projectRoot)
      console.log(chalk.green('✓ Skill installed: .claude/skills/conductor.md'))
    })
}
