import { Command } from 'commander'
import { readConfig } from '../lib/config.js'
import { apiGet, apiPost, apiPatch } from '../lib/api.js'

interface Issue {
  id: string
  type: string
  status: string
  title: string
  description?: string
  createdAt?: string
  updatedAt?: string
}

function requireConfig() {
  const config = readConfig()
  if (!config) {
    console.error('Not authenticated — run conductor login')
    process.exit(1)
  }
  return config
}

function padEnd(str: string, length: number): string {
  return str.length >= length ? str : str + ' '.repeat(length - str.length)
}

function renderIssueTable(issues: Issue[]): void {
  const COL_ID = 16
  const COL_TYPE = 16
  const COL_STATUS = 12
  const header =
    padEnd('ID', COL_ID) + padEnd('TYPE', COL_TYPE) + padEnd('STATUS', COL_STATUS) + 'TITLE'
  console.log(header)
  for (const issue of issues) {
    const row =
      padEnd(issue.id, COL_ID) +
      padEnd(issue.type, COL_TYPE) +
      padEnd(issue.status, COL_STATUS) +
      issue.title
    console.log(row)
  }
}

export function registerIssue(program: Command): void {
  const issue = program.command('issue').description('Manage issues')

  issue
    .command('create')
    .description('Create a new issue')
    .requiredOption('--title <title>', 'Issue title')
    .option('--type <type>', 'Issue type (PRD, FEATURE_REQUEST, BUG_REPORT)', 'PRD')
    .option('--description <description>', 'Issue description')
    .action(async (options: { title: string; type: string; description?: string }) => {
      const config = requireConfig()
      const body: Record<string, string> = { type: options.type, title: options.title }
      if (options.description) {
        body['description'] = options.description
      }
      try {
        const created = await apiPost<Issue>(
          `/api/v1/projects/${config.projectId}/issues`,
          body,
          config.apiKey,
          config.apiUrl
        )
        console.log(`${created.id}: ${created.title} [${created.status}]`)
      } catch (err) {
        console.error((err as Error).message)
        process.exit(1)
      }
    })

  issue
    .command('list')
    .description('List issues')
    .option('--type <type>', 'Filter by issue type')
    .option('--status <status>', 'Filter by issue status')
    .action(async (options: { type?: string; status?: string }) => {
      const config = requireConfig()
      const params = new URLSearchParams()
      if (options.type) params.set('type', options.type)
      if (options.status) params.set('status', options.status)
      const query = params.toString() ? `?${params.toString()}` : ''
      try {
        const issues = await apiGet<Issue[]>(
          `/api/v1/projects/${config.projectId}/issues${query}`,
          config.apiKey,
          config.apiUrl
        )
        renderIssueTable(issues)
      } catch (err) {
        console.error((err as Error).message)
        process.exit(1)
      }
    })

  issue
    .command('get <issueId>')
    .description('Get issue details')
    .action(async (issueId: string) => {
      const config = requireConfig()
      try {
        const found = await apiGet<Issue>(
          `/api/v1/projects/${config.projectId}/issues/${issueId}`,
          config.apiKey,
          config.apiUrl
        )
        console.log(`ID:          ${found.id}`)
        console.log(`Type:        ${found.type}`)
        console.log(`Status:      ${found.status}`)
        console.log(`Title:       ${found.title}`)
        if (found.description) console.log(`Description: ${found.description}`)
        if (found.createdAt) console.log(`Created:     ${found.createdAt}`)
        if (found.updatedAt) console.log(`Updated:     ${found.updatedAt}`)
      } catch (err) {
        console.error((err as Error).message)
        process.exit(1)
      }
    })

  issue
    .command('set-status <issueId> <status>')
    .description('Update issue status')
    .action(async (issueId: string, status: string) => {
      const config = requireConfig()
      try {
        await apiPatch<Issue>(
          `/api/v1/projects/${config.projectId}/issues/${issueId}`,
          { status },
          config.apiKey,
          config.apiUrl
        )
        console.log(`✓ ${issueId} status updated to ${status}`)
      } catch (err) {
        console.error((err as Error).message)
        process.exit(1)
      }
    })
}
