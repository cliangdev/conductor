import { describe, it, expect } from 'vitest'
import { resolveProjectIdByCwd } from '../mcp/config.js'
import type { Config } from '../mcp/config.js'

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
