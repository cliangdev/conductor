import * as fs from 'fs'
import * as path from 'path'
import { Command } from 'commander'
import { readConfig } from '../lib/config.js'

const REQUIRED_SECTIONS = [
  'Problem',
  'Glossary',
  'Goals',
  'Non-Goals',
  'Users',
  'Reference Patterns',
  'Features',
] as const

const REQUIRED_FEATURE_LINES = [
  '**What**',
  '**Not**',
  '**Inputs**',
  '**Outputs**',
  '**Invariants**',
  '**Acceptance Criteria**',
] as const

const AC_LINE_REGEX = /^- \[[ x]\] `AC-P[01]-\d+\.\d+`/
const AC_ID_IN_LINE = /`(AC-P[01]-\d+\.\d+)`/

interface PrdResult {
  errors: string[]
  warnings: string[]
  acIds: string[]
  isV2: boolean
}

interface TasksResult {
  errors: string[]
  warnings: string[]
  coveredAcs: Set<string>
  isV2: boolean
}

interface IssueLintResult {
  issueId: string
  prd: { ok: boolean; errors: string[] }
  tasks: { ok: boolean; errors: string[] }
  warnings: string[]
}

export function parseFrontmatter(content: string): Record<string, string> | null {
  const lines = content.split(/\r?\n/)
  if (lines[0] !== '---') return null
  const result: Record<string, string> = {}
  for (let i = 1; i < lines.length; i++) {
    if (lines[i] === '---') return result
    const match = lines[i].match(/^([A-Za-z_][\w-]*)\s*:\s*(.*)$/)
    if (match) {
      result[match[1]] = match[2].trim()
    }
  }
  return null
}

export function validatePrd(content: string): PrdResult {
  const errors: string[] = []
  const warnings: string[] = []
  const acIds: string[] = []

  const frontmatter = parseFrontmatter(content)
  const schemaVersion = frontmatter ? frontmatter['schemaVersion'] : undefined

  if (schemaVersion !== '2') {
    warnings.push('prd.md: schemaVersion: 1 — upgrade recommended')
    return { errors, warnings, acIds, isV2: false }
  }

  const lines = content.split(/\r?\n/)

  // Find H2 sections present
  const presentSections = new Set<string>()
  for (const line of lines) {
    const m = line.match(/^## (.+)$/)
    if (m) presentSections.add(m[1].trim())
  }
  for (const section of REQUIRED_SECTIONS) {
    if (!presentSections.has(section)) {
      errors.push(`prd.md: missing required section: ${section}`)
    }
  }

  // Walk features grouped by priority. Contract-block check applies only to P0;
  // AC line + ID collection applies to all features.
  const features = collectFeatures(lines)
  for (const feature of features) {
    if (feature.priority === 'P0') {
      for (const required of REQUIRED_FEATURE_LINES) {
        const has = feature.body.some(
          l => l.includes(`- ${required}`) || l.includes(`${required}:`)
        )
        if (!has) {
          errors.push(`prd.md: feature ${feature.heading}: missing required line: ${required}`)
        }
      }
    }
    const acLines = collectAcLines(feature.body)
    for (const acLine of acLines) {
      if (!AC_LINE_REGEX.test(acLine.trimEnd())) {
        errors.push(
          `prd.md: feature ${feature.heading}: malformed acceptance criterion line: ${acLine.trim()}`
        )
      }
    }
  }

  // Collect AC IDs from declared AC lines only (lines inside an Acceptance
  // Criteria block). Mentions of AC IDs in prose (Glossary, What lines, etc.)
  // are not declarations.
  const seen = new Set<string>()
  for (const feature of features) {
    const acLines = collectAcLines(feature.body)
    for (const line of acLines) {
      const m = line.match(AC_ID_IN_LINE)
      if (!m) continue
      const id = m[1]
      if (seen.has(id)) {
        errors.push(`prd.md: duplicate AC id: ${id}`)
      } else {
        seen.add(id)
        acIds.push(id)
      }
    }
  }

  return { errors, warnings, acIds, isV2: true }
}

interface Feature {
  heading: string
  priority: string | null
  body: string[]
}

function collectFeatures(lines: string[]): Feature[] {
  const features: Feature[] = []
  let currentPriority: string | null = null
  let current: Feature | null = null

  const flush = () => {
    if (current) {
      features.push(current)
      current = null
    }
  }

  for (const line of lines) {
    const h3 = line.match(/^### (.+)$/)
    if (h3) {
      flush()
      const m = h3[1].trim().match(/^(P\d+)\b/)
      currentPriority = m ? m[1] : null
      continue
    }
    const h4 = line.match(/^#### (.+)$/)
    if (h4) {
      flush()
      if (currentPriority) {
        current = { heading: h4[1].trim(), priority: currentPriority, body: [] }
      }
      continue
    }
    const h2 = line.match(/^## (.+)$/)
    if (h2) {
      flush()
      currentPriority = null
      continue
    }
    if (current) {
      current.body.push(line)
    }
  }
  flush()
  return features
}

function collectAcLines(body: string[]): string[] {
  const out: string[] = []
  let inAc = false
  for (const line of body) {
    if (/^- \*\*Acceptance Criteria\*\*/.test(line)) {
      inAc = true
      continue
    }
    if (inAc) {
      // Stop when we hit a non-indented bullet that's a different field (starts with "- **" at column 0)
      if (/^- \*\*/.test(line)) {
        inAc = false
        continue
      }
      // Indented AC lines start with at least two spaces and a dash
      if (/^\s{2,}- /.test(line)) {
        out.push(line.replace(/^\s+/, ''))
      } else if (line.trim() === '') {
        // blank line — keep going (allow blank within block)
        continue
      } else if (/^#{1,4} /.test(line)) {
        inAc = false
      }
    }
  }
  return out
}

export function validateTasks(content: string, prdAcIds: string[]): TasksResult {
  const errors: string[] = []
  const warnings: string[] = []
  const coveredAcs = new Set<string>()

  let parsed: unknown
  try {
    parsed = JSON.parse(content)
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e)
    errors.push(`tasks.json: invalid JSON: ${msg}`)
    return { errors, warnings, coveredAcs, isV2: false }
  }

  if (typeof parsed !== 'object' || parsed === null) {
    errors.push('tasks.json: top-level value must be an object')
    return { errors, warnings, coveredAcs, isV2: false }
  }

  const root = parsed as Record<string, unknown>
  const schemaVersion = root['schema_version']
  if (schemaVersion !== 2) {
    return { errors, warnings, coveredAcs, isV2: false }
  }

  const prdAcSet = new Set(prdAcIds)
  const epics = Array.isArray(root['epics']) ? (root['epics'] as unknown[]) : []
  for (const epic of epics) {
    if (typeof epic !== 'object' || epic === null) continue
    const e = epic as Record<string, unknown>
    const tasks = Array.isArray(e['tasks']) ? (e['tasks'] as unknown[]) : []
    for (const task of tasks) {
      if (typeof task !== 'object' || task === null) continue
      const t = task as Record<string, unknown>
      const id = typeof t['id'] === 'string' ? (t['id'] as string) : '?'

      const filesOwned = t['files_owned']
      if (!Array.isArray(filesOwned) || filesOwned.length === 0) {
        errors.push(`${id}: missing files_owned`)
      }

      const coversAcs = t['covers_acs']
      if (!Array.isArray(coversAcs) || coversAcs.length === 0) {
        errors.push(`${id}: missing covers_acs`)
      } else {
        for (const ac of coversAcs) {
          if (typeof ac !== 'string') continue
          coveredAcs.add(ac)
          if (!prdAcSet.has(ac)) {
            errors.push(`${id}: covers unknown AC ${ac}`)
          }
        }
      }

      const contract = t['contract']
      if (typeof contract !== 'object' || contract === null) {
        errors.push(`${id}: missing contract.input`)
        errors.push(`${id}: missing contract.output`)
        errors.push(`${id}: missing contract.invariant`)
      } else {
        const c = contract as Record<string, unknown>
        for (const field of ['input', 'output', 'invariant']) {
          const v = c[field]
          if (typeof v !== 'string' || v.trim() === '') {
            errors.push(`${id}: missing contract.${field}`)
          }
        }
      }

      const testPlan = t['test_plan']
      if (!Array.isArray(testPlan) || testPlan.length === 0) {
        errors.push(`${id}: missing test_plan`)
      }
    }
  }

  return { errors, warnings, coveredAcs, isV2: true }
}

function lintIssue(localPath: string, issueId: string): IssueLintResult {
  const issueDir = path.join(localPath, '.conductor', 'issues', issueId)
  const prdPath = path.join(issueDir, 'prd.md')
  const tasksPath = path.join(issueDir, 'tasks.json')

  const prdResult: { ok: boolean; errors: string[] } = { ok: true, errors: [] }
  const tasksResult: { ok: boolean; errors: string[] } = { ok: true, errors: [] }
  const warnings: string[] = []

  let prdValidation: PrdResult | null = null
  let tasksValidation: TasksResult | null = null

  if (!fs.existsSync(prdPath)) {
    prdResult.ok = false
    prdResult.errors.push(`prd.md: file not found at ${prdPath}`)
  } else {
    const content = String(fs.readFileSync(prdPath, 'utf8'))
    prdValidation = validatePrd(content)
    prdResult.errors = prdValidation.errors
    prdResult.ok = prdValidation.errors.length === 0
    warnings.push(...prdValidation.warnings)
  }

  if (!fs.existsSync(tasksPath)) {
    tasksResult.ok = false
    tasksResult.errors.push(`tasks.json: file not found at ${tasksPath}`)
  } else {
    const content = String(fs.readFileSync(tasksPath, 'utf8'))
    const acIds = prdValidation ? prdValidation.acIds : []
    tasksValidation = validateTasks(content, acIds)
    tasksResult.errors = [...tasksValidation.errors]
    warnings.push(...tasksValidation.warnings)
  }

  // Cross-file coverage check: only when both files are v2
  if (prdValidation && prdValidation.isV2 && tasksValidation && tasksValidation.isV2) {
    for (const acId of prdValidation.acIds) {
      if (!tasksValidation.coveredAcs.has(acId)) {
        tasksResult.errors.push(`coverage: ${acId} has no covering task`)
      }
    }
  }

  tasksResult.ok = tasksResult.errors.length === 0
  return { issueId, prd: prdResult, tasks: tasksResult, warnings }
}

function listLocalIssues(localPath: string): string[] {
  const issuesDir = path.join(localPath, '.conductor', 'issues')
  if (!fs.existsSync(issuesDir)) return []
  try {
    const entries = fs.readdirSync(issuesDir, { withFileTypes: true }) as unknown as fs.Dirent[]
    return entries.filter(e => e.isDirectory()).map(e => e.name)
  } catch {
    // Fallback: readdirSync without withFileTypes
    try {
      const names = fs.readdirSync(issuesDir) as unknown as string[]
      return Array.isArray(names) ? names : []
    } catch {
      return []
    }
  }
}

export function registerLint(program: Command): void {
  program
    .command('lint [issueId]')
    .description('Lint local Conductor PRD and tasks.json artifacts against schema v2')
    .option('--json', 'Output results as JSON')
    .addHelpText(
      'after',
      `
Examples:
  conductor lint
  conductor lint <issueId>
  conductor lint <issueId> --json`
    )
    .action(async (issueId: string | undefined, options: { json?: boolean }) => {
      const config = readConfig()
      if (!config) {
        if (!options.json) {
          console.log('No config found at ~/.conductor/config.json — run `conductor login` first')
        } else {
          process.stdout.write(
            JSON.stringify(
              { error: 'No config found at ~/.conductor/config.json' },
              null,
              2
            ) + '\n'
          )
        }
        process.exit(78)
        return
      }

      const localPath = resolveLocalPath(config)
      if (!localPath) {
        if (!options.json) {
          console.log(
            'No localPath configured for the active project — run `conductor init` to set one up'
          )
        } else {
          process.stdout.write(
            JSON.stringify({ error: 'No localPath configured' }, null, 2) + '\n'
          )
        }
        process.exit(1)
        return
      }

      const targets = issueId ? [issueId] : listLocalIssues(localPath)

      if (targets.length === 0) {
        if (!options.json) {
          console.log('No issues found to lint')
        } else {
          process.stdout.write(JSON.stringify({ issues: [] }, null, 2) + '\n')
        }
        process.exit(0)
        return
      }

      const results = targets.map(id => lintIssue(localPath, id))

      if (options.json) {
        const payload = {
          issues: results.map(r => ({
            issueId: r.issueId,
            prd: { ok: r.prd.ok, errors: r.prd.errors },
            tasks: { ok: r.tasks.ok, errors: r.tasks.errors },
          })),
        }
        process.stdout.write(JSON.stringify(payload, null, 2) + '\n')
        const anyError = results.some(r => !r.prd.ok || !r.tasks.ok)
        process.exit(anyError ? 1 : 0)
        return
      }

      let anyError = false
      for (const r of results) {
        if (results.length > 1) {
          console.log(`\n${r.issueId}`)
        }

        for (const w of r.warnings) {
          console.log(w)
        }

        if (r.prd.ok && r.prd.errors.length === 0) {
          if (!r.warnings.some(w => w.startsWith('prd.md:'))) {
            console.log('✓ prd.md')
          }
        } else {
          anyError = true
          for (const e of r.prd.errors) {
            console.log(e.startsWith('prd.md:') || e.startsWith('coverage:') ? e : `prd.md: ${e}`)
          }
        }

        if (r.tasks.ok && r.tasks.errors.length === 0) {
          console.log('✓ tasks.json')
        } else {
          anyError = true
          for (const e of r.tasks.errors) {
            console.log(e)
          }
        }
      }

      process.exit(anyError ? 1 : 0)
    })
}

function resolveLocalPath(config: ReturnType<typeof readConfig>): string | null {
  if (!config) return null
  if (config.localPath) return config.localPath
  if (config.projects) {
    const active = config.projects[config.projectId]
    if (active?.localPath) return active.localPath
    for (const entry of Object.values(config.projects)) {
      if (entry?.localPath) return entry.localPath
    }
  }
  return null
}
