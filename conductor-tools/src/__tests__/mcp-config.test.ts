import { describe, it, expect, afterEach, vi } from 'vitest'
import * as fs from 'fs'
import { resolveProjectIdByCwd, resolveProject, getConfig } from '../mcp/config.js'
import type { Config } from '../mcp/config.js'

vi.mock('fs')
const mockFs = vi.mocked(fs)

const baseConfig: Config = {
  apiKey: 'key',
  projectId: 'proj_global',
  projectName: 'Global Project',
  email: 'user@example.com',
  apiUrl: 'http://localhost:8080',
}

const configWithProjects: Config = {
  ...baseConfig,
  projects: {
    proj_nexus: { localPath: '/home/user/nexus', projectName: 'Nexus' },
    proj_other: { localPath: '/home/user/other', projectName: 'Other' },
  },
}

describe('resolveProjectIdByCwd', () => {
  it('returns global projectId when projects map is absent', () => {
    expect(resolveProjectIdByCwd(baseConfig, '/home/user/nexus')).toBe('proj_global')
  })

  it('returns global projectId when CWD does not match any project', () => {
    expect(resolveProjectIdByCwd(configWithProjects, '/home/user/unrelated')).toBe('proj_global')
  })

  it('returns matching projectId when CWD equals a project localPath exactly', () => {
    expect(resolveProjectIdByCwd(configWithProjects, '/home/user/nexus')).toBe('proj_nexus')
  })

  it('returns matching projectId when CWD is a subdirectory of a project localPath', () => {
    expect(resolveProjectIdByCwd(configWithProjects, '/home/user/nexus/src/components')).toBe('proj_nexus')
  })

  it('does not match a path that is only a prefix of localPath', () => {
    expect(resolveProjectIdByCwd(configWithProjects, '/home/user/nex')).toBe('proj_global')
  })

  it('uses longest-match when paths are nested', () => {
    const nested: Config = {
      ...baseConfig,
      projects: {
        proj_parent: { localPath: '/home/user/workspace', projectName: 'Workspace' },
        proj_child: { localPath: '/home/user/workspace/nexus', projectName: 'Nexus' },
      },
    }
    expect(resolveProjectIdByCwd(nested, '/home/user/workspace/nexus/src')).toBe('proj_child')
    expect(resolveProjectIdByCwd(nested, '/home/user/workspace/other')).toBe('proj_parent')
  })

  it('uses process.cwd() when no cwd argument is provided', () => {
    // process.cwd() won't match any test project path, so falls back to global
    expect(resolveProjectIdByCwd(configWithProjects)).toBe('proj_global')
  })
})

describe('resolveProject (fail-closed resolution)', () => {
  const notInGit = () => false
  const inGit = () => true

  afterEach(() => {
    delete process.env['CONDUCTOR_PROJECT_ID']
  })

  it('honors CONDUCTOR_PROJECT_ID env above everything else', () => {
    process.env['CONDUCTOR_PROJECT_ID'] = 'proj_env'
    // env wins even when cwd would match a different project
    const r = resolveProject(configWithProjects, '/home/user/nexus', inGit)
    expect(r).toEqual({ projectId: 'proj_env', source: 'env', mismatch: false })
  })

  it('resolves by cwd match with no mismatch', () => {
    const r = resolveProject(configWithProjects, '/home/user/nexus', inGit)
    expect(r).toEqual({ projectId: 'proj_nexus', source: 'cwd', mismatch: false })
  })

  it('flags a mismatch when cwd is inside a git repo that matches no project', () => {
    const r = resolveProject(configWithProjects, '/home/user/unrelated', inGit)
    expect(r).toEqual({ projectId: 'proj_global', source: 'fallback', mismatch: true })
  })

  it('does NOT flag a mismatch when cwd is not inside a git repo (escape hatch)', () => {
    const r = resolveProject(configWithProjects, '/home/user/unrelated', notInGit)
    expect(r).toEqual({ projectId: 'proj_global', source: 'fallback', mismatch: false })
  })

  it('never flags a mismatch when there is no projects map (legacy single-project)', () => {
    const r = resolveProject(baseConfig, '/home/user/anywhere', inGit)
    expect(r).toEqual({ projectId: 'proj_global', source: 'fallback', mismatch: false })
  })
})

describe('getConfig (env fallback for containerized MCP runs)', () => {
  afterEach(() => {
    vi.resetAllMocks()
    delete process.env['CONDUCTOR_API_KEY']
    delete process.env['CONDUCTOR_API_URL']
    delete process.env['CONDUCTOR_PROJECT_ID']
  })

  it('synthesizes a config from env vars when no file exists', () => {
    mockFs.readFileSync.mockImplementation(() => {
      throw new Error('ENOENT: no such file or directory')
    })
    process.env['CONDUCTOR_API_KEY'] = 'env-key'
    process.env['CONDUCTOR_API_URL'] = 'http://backend:8080'
    process.env['CONDUCTOR_PROJECT_ID'] = 'proj_env'

    expect(getConfig()).toEqual({
      apiKey: 'env-key',
      apiUrl: 'http://backend:8080',
      projectId: 'proj_env',
      projectName: '',
      email: '',
    })
  })

  it('throws when no file exists and no env vars are set', () => {
    mockFs.readFileSync.mockImplementation(() => {
      throw new Error('ENOENT: no such file or directory')
    })

    expect(() => getConfig()).toThrow('Config not found — run conductor login')
  })

  it('prefers the on-disk file over env vars when both are present', () => {
    mockFs.readFileSync.mockReturnValue(JSON.stringify(baseConfig))
    process.env['CONDUCTOR_API_KEY'] = 'env-key'
    process.env['CONDUCTOR_API_URL'] = 'http://backend:8080'

    expect(getConfig()).toEqual(baseConfig)
  })
})
