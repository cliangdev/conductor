import * as fs from 'fs'
import * as path from 'path'
import * as os from 'os'

function conductorDir(): string {
  return path.join(os.homedir(), '.conductor')
}

function issueFilePath(projectId: string, issueId: string): string {
  return path.join(conductorDir(), projectId, 'issues', issueId, 'issue.md')
}

function documentFilePath(projectId: string, issueId: string, filename: string): string {
  return path.join(conductorDir(), projectId, 'issues', issueId, filename)
}

export function writeIssueFile(projectId: string, issueId: string, content: string): void {
  const filePath = issueFilePath(projectId, issueId)
  fs.mkdirSync(path.dirname(filePath), { recursive: true })
  fs.writeFileSync(filePath, content, 'utf8')
}

export function readIssueFile(projectId: string, issueId: string): string | null {
  const filePath = issueFilePath(projectId, issueId)
  try {
    return fs.readFileSync(filePath, 'utf8')
  } catch {
    return null
  }
}

export function writeDocumentFile(
  projectId: string,
  issueId: string,
  filename: string,
  content: string
): void {
  const filePath = documentFilePath(projectId, issueId, filename)
  fs.mkdirSync(path.dirname(filePath), { recursive: true })
  fs.writeFileSync(filePath, content, 'utf8')
}

export function deleteDocumentFile(projectId: string, issueId: string, filename: string): void {
  const filePath = documentFilePath(projectId, issueId, filename)
  try {
    fs.unlinkSync(filePath)
  } catch {
    // File may not exist; that's fine
  }
}
