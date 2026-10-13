import { describe, it, expect } from 'vitest'
import { TOOLS } from '../mcp/index.js'

type Prop = { type?: string; enum?: string[]; maxItems?: number; additionalProperties?: unknown }
function props(name: string): Record<string, Prop> {
  const tool = TOOLS.find((t) => t.name === name)
  if (!tool) throw new Error(`no tool named ${name}`)
  return (tool.inputSchema as { properties: Record<string, Prop> }).properties
}

describe('CAROUSEL tool schemas', () => {
  for (const name of ['create_creative', 'update_creative', 'preview_creative_draft']) {
    it(`${name} offers kind CAROUSEL, slides and slideAltText`, () => {
      const p = props(name)
      expect(p['kind']!.enum).toContain('CAROUSEL')
      expect(p['slides']!.type).toBe('object')
      expect(p['slideAltText']!.type).toBe('array')
    })
  }

  it('preview_creative_draft takes slidePaths', () => {
    expect(props('preview_creative_draft')['slidePaths']!.type).toBe('object')
  })

  it('list_creative_media takes labelPrefix', () => {
    expect(props('list_creative_media')['labelPrefix']!.type).toBe('string')
  })

  it('upload_creative_media takes a batch of up to 100 files or a folder', () => {
    const p = props('upload_creative_media')
    expect(p['files']!.type).toBe('array')
    expect(p['files']!.maxItems).toBe(100)
    for (const k of ['folder', 'glob', 'labelTemplate']) expect(p[k]!.type).toBe('string')
  })
})
