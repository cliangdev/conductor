import * as fs from 'fs'
import * as path from 'path'
import { Command } from 'commander'
import { readConfig } from '../lib/config.js'
import { apiGet, apiPost } from '../lib/api.js'

interface Document {
  id: string
  filename: string
  contentType: string
  createdAt?: string
}

function requireConfig() {
  const config = readConfig()
  if (!config) {
    console.error('Not authenticated — run conductor login')
    process.exit(1)
  }
  return config
}

function detectContentType(filename: string): string {
  const ext = path.extname(filename).toLowerCase()
  if (ext === '.md') return 'text/markdown'
  if (ext === '.txt') return 'text/plain'
  return 'application/octet-stream'
}

function padEnd(str: string, length: number): string {
  return str.length >= length ? str : str + ' '.repeat(length - str.length)
}

function renderDocTable(docs: Document[]): void {
  const COL_FILENAME = 24
  const COL_CONTENT_TYPE = 24
  const header = padEnd('FILENAME', COL_FILENAME) + padEnd('CONTENT TYPE', COL_CONTENT_TYPE) + 'CREATED'
  console.log(header)
  for (const doc of docs) {
    const row =
      padEnd(doc.filename, COL_FILENAME) +
      padEnd(doc.contentType, COL_CONTENT_TYPE) +
      (doc.createdAt ?? '')
    console.log(row)
  }
}

export function registerDoc(program: Command): void {
  const doc = program.command('doc').description('Manage issue documents')

  doc
    .command('add <issueId> <file>')
    .description('Attach a file to an issue')
    .action(async (issueId: string, file: string) => {
      const config = requireConfig()
      const filename = path.basename(file)
      const contentType = detectContentType(filename)
      let content: string
      try {
        content = fs.readFileSync(file, 'utf8') as string
      } catch (err) {
        console.error(`Failed to read file: ${(err as Error).message}`)
        process.exit(1)
        return
      }
      try {
        await apiPost<Document>(
          `/api/v1/projects/${config.projectId}/issues/${issueId}/documents`,
          { filename, content, contentType },
          config.apiKey,
          config.apiUrl
        )
        console.log(`✓ Document ${filename} attached to ${issueId}`)
      } catch (err) {
        console.error((err as Error).message)
        process.exit(1)
      }
    })

  doc
    .command('list <issueId>')
    .description('List documents attached to an issue')
    .action(async (issueId: string) => {
      const config = requireConfig()
      try {
        const docs = await apiGet<Document[]>(
          `/api/v1/projects/${config.projectId}/issues/${issueId}/documents`,
          config.apiKey,
          config.apiUrl
        )
        renderDocTable(docs)
      } catch (err) {
        console.error((err as Error).message)
        process.exit(1)
      }
    })
}
