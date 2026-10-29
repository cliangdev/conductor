import * as fs from 'fs'
import * as path from 'path'
import { Command } from 'commander'
import { parse as parseHtml, HTMLElement, NodeType } from 'node-html-parser'
import { readConfig } from '../lib/config.js'
import { lintMermaid } from '../lib/mermaid-lint.js'

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
const AC_ID_REGEX = /^AC-P[01]-\d+\.\d+$/
const FEATURE_ID_REGEX = /^P[01]-\d+$/

// HTML PRD (schema v2): section ids in required order, with their display names.
const HTML_REQUIRED_SECTIONS = [
  ['problem', 'Problem'],
  ['glossary', 'Glossary'],
  ['goals', 'Goals'],
  ['non-goals', 'Non-Goals'],
  ['users', 'Users'],
  ['reference-patterns', 'Reference Patterns'],
  ['features', 'Features'],
] as const
const HTML_NONEMPTY_SECTIONS = new Set(['glossary', 'reference-patterns'])
const HTML_P0_FIELDS = ['What', 'Not', 'Inputs', 'Outputs', 'Invariants'] as const
const HTML_SCRIPT_HOSTS = new Set(['cdnjs.cloudflare.com', 'cdn.jsdelivr.net', 'unpkg.com'])

export interface PrdResult {
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
  prdFile: string
  prd: { ok: boolean; errors: string[] }
  tasks: { ok: boolean; errors: string[] }
  mermaid: { ok: boolean; errors: string[]; warnings: string[] }
  warnings: string[]
}

/** Lint every ```mermaid block across all .md docs in an issue directory. */
function lintIssueMermaid(issueDir: string): { errors: string[]; warnings: string[] } {
  const errors: string[] = []
  const warnings: string[] = []
  if (!fs.existsSync(issueDir)) return { errors, warnings }
  let files: string[] = []
  try {
    files = (fs.readdirSync(issueDir) as unknown as string[]).filter(f => f.endsWith('.md'))
  } catch {
    return { errors, warnings }
  }
  for (const file of files.sort()) {
    const content = String(fs.readFileSync(path.join(issueDir, file), 'utf8'))
    for (const issue of lintMermaid(content)) {
      const formatted = `${file}:${issue.line}: ${issue.message}`
      if (issue.level === 'error') errors.push(formatted)
      else warnings.push(formatted)
    }
  }
  return { errors, warnings }
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

/** Visible text of a node, skipping <script>/<style> and (optionally) <h2> headings. */
function htmlText(node: { childNodes: unknown[] }, skipH2 = false): string {
  let out = ''
  for (const child of node.childNodes as Array<{ nodeType: number; rawText: string }>) {
    if (child.nodeType === NodeType.TEXT_NODE) {
      out += ` ${child.rawText}`
    } else if (child.nodeType === NodeType.ELEMENT_NODE) {
      const el = child as unknown as HTMLElement
      const tag = el.tagName.toLowerCase()
      if (tag === 'script' || tag === 'style' || (skipH2 && tag === 'h2')) continue
      out += ` ${htmlText(el)}`
    }
  }
  return out.replace(/\s+/g, ' ').trim()
}

/** Validate an HTML PRD (`prd.html`, schema v2) against the same rules as the markdown validator. */
export function validatePrdHtml(content: string): PrdResult {
  const errors: string[] = []
  const warnings: string[] = []
  const acIds: string[] = []
  const F = 'prd.html'

  const root = parseHtml(content)
  const meta = (name: string): string | undefined =>
    root.querySelector(`meta[name="${name}"]`)?.getAttribute('content')?.trim()

  if (meta('conductor:schema-version') !== '2') {
    errors.push(`${F}: missing <meta name="conductor:schema-version" content="2">`)
    return { errors, warnings, acIds, isV2: false }
  }
  if (meta('conductor:doc-type') !== 'prd') {
    errors.push(`${F}: missing <meta name="conductor:doc-type" content="prd">`)
  }

  // Sections: present, in order, each with an <h2>.
  const sections = root.querySelectorAll('section[data-prd-section]')
  const byName = new Map<string, HTMLElement>()
  for (const sec of sections) {
    const name = sec.getAttribute('data-prd-section') ?? ''
    if (!byName.has(name)) byName.set(name, sec)
  }
  for (const [name, label] of HTML_REQUIRED_SECTIONS) {
    const sec = byName.get(name)
    if (!sec) {
      errors.push(`${F}: missing required section: ${label}`)
      continue
    }
    if (!sec.querySelector('h2')) {
      errors.push(`${F}: section ${label}: missing <h2> heading`)
    }
    if (HTML_NONEMPTY_SECTIONS.has(name) && htmlText(sec, true) === '' && !sec.querySelector('img, svg, table')) {
      errors.push(`${F}: section ${label}: empty — write entries or <p><em>None.</em></p>`)
    }
  }
  const requiredNames: readonly string[] = HTML_REQUIRED_SECTIONS.map(([n]) => n)
  const seenOrder = sections
    .map(sec => sec.getAttribute('data-prd-section') ?? '')
    .filter((n, i, arr) => requiredNames.includes(n) && arr.indexOf(n) === i)
  const expectedOrder = requiredNames.filter(n => seenOrder.includes(n))
  if (seenOrder.join('|') !== expectedOrder.join('|')) {
    errors.push(`${F}: sections out of order: expected ${requiredNames.join(', ')}`)
  }

  // Features and acceptance criteria.
  const featuresRoot = byName.get('features') ?? root
  const seenFeatures = new Set<string>()
  const seenAcs = new Set<string>()
  for (const art of featuresRoot.querySelectorAll('article[data-feature]')) {
    const fid = (art.getAttribute('data-feature') ?? '').trim()
    if (!FEATURE_ID_REGEX.test(fid)) {
      errors.push(`${F}: feature ${fid || '?'}: malformed feature id (expected P0-1, P1-2, ...)`)
      continue
    }
    if (seenFeatures.has(fid)) errors.push(`${F}: duplicate feature id: ${fid}`)
    seenFeatures.add(fid)
    const priority = fid.slice(0, 2)

    if (!art.querySelector('h3')) errors.push(`${F}: feature ${fid}: missing <h3> heading`)

    if (priority === 'P0') {
      const fields = new Set(
        art.querySelectorAll('dt').map(dt => htmlText(dt).replace(/:$/, '').trim().toLowerCase())
      )
      for (const field of HTML_P0_FIELDS) {
        if (!fields.has(field.toLowerCase())) {
          errors.push(`${F}: feature ${fid}: missing required field: ${field}`)
        }
      }
      if (!art.querySelector('ul[data-acs]')) {
        errors.push(`${F}: feature ${fid}: missing required field: Acceptance Criteria`)
      }
    }

    for (const li of art.querySelectorAll('ul[data-acs] > li')) {
      if (!li.hasAttribute('data-ac')) {
        errors.push(`${F}: feature ${fid}: acceptance criterion missing data-ac: ${htmlText(li).slice(0, 60)}`)
      }
    }
    for (const li of art.querySelectorAll('[data-ac]')) {
      const id = (li.getAttribute('data-ac') ?? '').trim()
      if (!AC_ID_REGEX.test(id)) {
        errors.push(`${F}: feature ${fid}: malformed acceptance criterion id: ${id || '(empty)'}`)
        continue
      }
      if (!id.startsWith(`AC-${fid}.`)) {
        errors.push(`${F}: feature ${fid}: AC ${id} does not belong to this feature`)
      }
      if (seenAcs.has(id)) {
        errors.push(`${F}: duplicate AC id: ${id}`)
      } else {
        seenAcs.add(id)
        acIds.push(id)
      }
    }
  }

  // Self-containment: Conductor renders the page with connect-src 'none'.
  for (const script of root.querySelectorAll('script')) {
    const src = script.getAttribute('src')
    if (src) {
      let host = ''
      try {
        host = new URL(src).hostname
      } catch {
        host = ''
      }
      if (!HTML_SCRIPT_HOSTS.has(host)) {
        warnings.push(`${F}: script src ${src} is not from an allowed CDN (cdnjs.cloudflare.com, cdn.jsdelivr.net, unpkg.com)`)
      }
    }
    const code = script.innerHTML
    if (/window\.claude\b/.test(code)) {
      warnings.push(`${F}: script uses window.claude, which is unavailable in Conductor`)
    }
    if (/\bfetch\s*\(|XMLHttpRequest/.test(code)) {
      warnings.push(`${F}: script makes network requests, which Conductor blocks (connect-src 'none')`)
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
  const htmlPrdPath = path.join(issueDir, 'prd.html')
  const useHtml = fs.existsSync(htmlPrdPath)
  const prdFile = useHtml ? 'prd.html' : 'prd.md'
  const prdPath = useHtml ? htmlPrdPath : path.join(issueDir, 'prd.md')
  const tasksPath = path.join(issueDir, 'tasks.json')

  const prdResult: { ok: boolean; errors: string[] } = { ok: true, errors: [] }
  const tasksResult: { ok: boolean; errors: string[] } = { ok: true, errors: [] }
  const warnings: string[] = []

  let prdValidation: PrdResult | null = null
  let tasksValidation: TasksResult | null = null

  if (!fs.existsSync(prdPath)) {
    prdResult.ok = false
    prdResult.errors.push(`${prdFile}: file not found at ${prdPath}`)
  } else {
    const content = String(fs.readFileSync(prdPath, 'utf8'))
    prdValidation = useHtml ? validatePrdHtml(content) : validatePrd(content)
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

  const mermaidFindings = lintIssueMermaid(issueDir)
  const mermaid = {
    ok: mermaidFindings.errors.length === 0,
    errors: mermaidFindings.errors,
    warnings: mermaidFindings.warnings,
  }

  tasksResult.ok = tasksResult.errors.length === 0
  return { issueId, prdFile, prd: prdResult, tasks: tasksResult, mermaid, warnings }
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
            mermaid: { ok: r.mermaid.ok, errors: r.mermaid.errors, warnings: r.mermaid.warnings },
          })),
        }
        process.stdout.write(JSON.stringify(payload, null, 2) + '\n')
        const anyError = results.some(r => !r.prd.ok || !r.tasks.ok || !r.mermaid.ok)
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
          if (!r.warnings.some(w => w.startsWith(`${r.prdFile}:`))) {
            console.log(`✓ ${r.prdFile}`)
          }
        } else {
          anyError = true
          for (const e of r.prd.errors) {
            console.log(e.startsWith(`${r.prdFile}:`) || e.startsWith('coverage:') ? e : `${r.prdFile}: ${e}`)
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

        for (const w of r.mermaid.warnings) {
          console.log(`mermaid: ${w}`)
        }
        if (r.mermaid.ok) {
          if (r.mermaid.warnings.length === 0) {
            console.log('✓ mermaid')
          }
        } else {
          anyError = true
          for (const e of r.mermaid.errors) {
            console.log(`mermaid: ${e}`)
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
