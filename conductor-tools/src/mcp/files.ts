import * as fs from 'fs'
import * as path from 'path'
import { Config } from './config.js'

export function resolveLocalPath(config: Config): string {
  const proj = config.projects?.[config.projectId]
  const localPath = proj?.localPath ?? config.localPath
  if (!localPath) {
    throw new Error('Run conductor init to set up local project directory')
  }
  return localPath
}

export function getLocalIssueDir(config: Config, issueId: string): string {
  return path.join(resolveLocalPath(config), '.conductor', 'issues', issueId)
}

export function issueFilePath(config: Config, issueId: string): string {
  return path.join(getLocalIssueDir(config, issueId), 'issue.md')
}

export function documentFilePath(config: Config, issueId: string, filename: string): string {
  return path.join(getLocalIssueDir(config, issueId), filename)
}

export function writeIssueFile(config: Config, issueId: string, content: string): void {
  const filePath = issueFilePath(config, issueId)
  fs.mkdirSync(path.dirname(filePath), { recursive: true })
  fs.writeFileSync(filePath, content, 'utf8')
}

export function readIssueFile(config: Config, issueId: string): string | null {
  const filePath = issueFilePath(config, issueId)
  try {
    return fs.readFileSync(filePath, 'utf8')
  } catch {
    return null
  }
}

export function writeDocumentFile(config: Config, issueId: string, filename: string, content: string): void {
  const filePath = documentFilePath(config, issueId, filename)
  fs.mkdirSync(path.dirname(filePath), { recursive: true })
  fs.writeFileSync(filePath, content, 'utf8')
}

export function deleteDocumentFile(config: Config, issueId: string, filename: string): void {
  const filePath = documentFilePath(config, issueId, filename)
  try {
    fs.unlinkSync(filePath)
  } catch {
    // File may not exist; that's fine
  }
}
