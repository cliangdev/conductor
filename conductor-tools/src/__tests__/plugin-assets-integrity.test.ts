import { describe, it, expect } from 'vitest'
import * as fs from 'fs'
import * as path from 'path'
import { fileURLToPath } from 'url'
import { getAssetSrcDir } from '../lib/plugin-assets.js'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const assetDir = getAssetSrcDir()
const manifestPath = path.join(__dirname, '..', '..', 'assets', 'cli-manifest.json')

interface Parsed {
  /** Path relative to assets/claude/. */
  rel: string
  fm: Record<string, string>
  body: string
  text: string
}

function parseFile(rel: string): Parsed {
  const text = fs.readFileSync(path.join(assetDir, rel), 'utf8')
  const m = text.match(/^---\n([\s\S]*?)\n---\n?([\s\S]*)$/)
  const fm: Record<string, string> = {}
  if (m) {
    // Top-level `key: value` lines only; a YAML list under a key is kept as its raw text.
    let current: string | null = null
    for (const line of m[1]!.split('\n')) {
      const kv = line.match(/^([A-Za-z][\w-]*):\s*(.*)$/)
      if (kv) {
        current = kv[1]!
        fm[current] = kv[2]!.trim()
      } else if (current && line.trim()) {
        fm[current] += `\n${line.trim()}`
      }
    }
  }
  return { rel, fm, body: m ? m[2]! : text, text }
}

function listFiles(sub: string): string[] {
  const dir = path.join(assetDir, sub)
  if (!fs.existsSync(dir)) return []
  const out: string[] = []
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const rel = path.join(sub, e.name)
    if (e.isDirectory()) out.push(...listFiles(rel))
    else out.push(rel)
  }
  return out
}

const allFiles = listFiles('')
const commands = allFiles.filter(f => /^commands\/conductor\/[^/]+\.md$/.test(f)).map(parseFile)
const agents = allFiles.filter(f => /^agents\/[^/]+\.md$/.test(f)).map(parseFile)
const skills = allFiles.filter(f => /^skills\/[^/]+\/SKILL\.md$/.test(f)).map(parseFile)
const allMarkdown = allFiles.filter(f => f.endsWith('.md')).map(parseFile)

const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8')) as {
  claudeIntegration: {
    slashCommands: { name: string }[]
    skills: { name: string }[]
    agents: { name: string }[]
  }
}

describe('plugin assets integrity', () => {
  it('finds the assets it is meant to check', () => {
    expect(commands.length).toBeGreaterThan(0)
    expect(agents.length).toBeGreaterThan(0)
    expect(skills.length).toBeGreaterThan(0)
  })

  it('every referenced conductor subagent has an agents/<name>.md', () => {
    const agentNames = new Set(agents.map(a => path.basename(a.rel, '.md')))
    const missing: string[] = []
    for (const f of allMarkdown) {
      const refs = [
        ...[...f.text.matchAll(/subagent_type:\s*"(conductor-[a-z0-9-]+)"/g)].map(m => m[1]!),
        ...[...f.text.matchAll(/`(conductor-[a-z0-9-]+)`\s+subagents?\b/g)].map(m => m[1]!),
      ]
      for (const r of refs) if (!agentNames.has(r)) missing.push(`${f.rel} -> ${r}`)
    }
    expect(missing, `Subagents referenced but not defined:\n${missing.join('\n')}`).toEqual([])
  })

  it("each skill's frontmatter name equals its folder", () => {
    for (const s of skills) {
      expect(s.fm.name, s.rel).toBe(s.rel.split('/')[1])
    }
  })

  it('each command name matches its file', () => {
    for (const c of commands) {
      expect(c.fm.name, c.rel).toBe(`conductor:${path.basename(c.rel, '.md')}`)
    }
  })

  it('descriptions are present and at most 1,536 characters', () => {
    for (const f of [...skills, ...agents, ...commands]) {
      expect(f.fm.description, `${f.rel} has no description`).toBeTruthy()
      expect(f.fm.description!.length, `${f.rel} description is too long`).toBeLessThanOrEqual(1536)
    }
  })

  it('SKILL.md files are at most 500 lines', () => {
    for (const s of skills) {
      expect(s.text.split('\n').length, s.rel).toBeLessThanOrEqual(500)
    }
  })

  it('every agent sets name, description, tools and model, and the name matches its file', () => {
    for (const a of agents) {
      for (const key of ['name', 'description', 'tools', 'model']) {
        expect(a.fm[key], `${a.rel} is missing ${key}`).toBeTruthy()
      }
      expect(a.fm.name, a.rel).toBe(path.basename(a.rel, '.md'))
    }
  })

  it('every skill an agent preloads exists', () => {
    const skillNames = new Set(skills.map(s => s.rel.split('/')[1]))
    for (const a of agents) {
      const list = a.fm.skills
      if (!list) continue
      for (const name of list.split('\n').map(l => l.replace(/^-\s*/, '').trim()).filter(Boolean)) {
        expect(skillNames.has(name), `${a.rel} preloads unknown skill ${name}`).toBe(true)
      }
    }
  })

  it('every skill, agent and command file has a manifest entry', () => {
    const { slashCommands, skills: mSkills, agents: mAgents } = manifest.claudeIntegration
    const cmdNames = new Set(slashCommands.map(c => c.name))
    const skillNames = new Set(mSkills.map(s => s.name))
    const agentNames = new Set(mAgents.map(a => a.name))
    const missing: string[] = []
    for (const c of commands) if (!cmdNames.has(c.fm.name!)) missing.push(c.rel)
    for (const s of skills) if (!skillNames.has(s.rel.split('/')[1]!)) missing.push(s.rel)
    for (const a of agents) if (!agentNames.has(path.basename(a.rel, '.md'))) missing.push(a.rel)
    expect(missing, `No cli-manifest.json entry for:\n${missing.join('\n')}`).toEqual([])
  })

  it('every references/... path in a SKILL.md or command exists', () => {
    const refRe = /references\/[A-Za-z0-9._-]+\.md/g
    const allReferenceFiles = allFiles.filter(f => /^skills\/[^/]+\/references\//.test(f))
    const missing: string[] = []
    for (const s of skills) {
      const skillDir = s.rel.split('/').slice(0, 2).join('/')
      for (const ref of new Set(s.text.match(refRe) ?? [])) {
        if (!fs.existsSync(path.join(assetDir, skillDir, ref))) missing.push(`${s.rel} -> ${ref}`)
      }
    }
    for (const c of commands) {
      // A command points into a skill's references folder; accept a match in any skill.
      for (const ref of new Set(c.text.match(refRe) ?? [])) {
        if (!allReferenceFiles.some(f => f.endsWith(`/${ref}`))) missing.push(`${c.rel} -> ${ref}`)
      }
    }
    expect(missing, `Missing reference files:\n${missing.join('\n')}`).toEqual([])
  })
})
