import { describe, it, expect } from 'vitest'
import { TOOLS } from '../mcp/index.js'

/**
 * `create_creative`/`update_creative`'s JSON schemas must accept `motion`/`audio` (MOTION's animation
 * timeline and audio track) so the MCP client can actually send them — see docs/mcp-tool-guidelines.md's
 * "no false promises" rule. This checks the schema shape directly rather than round-tripping through
 * the tool implementation (creative-render.test.ts / mcp-creatives.test.ts already cover that motion/audio
 * flow through to the API unchanged, since CreativeFields/withCreativeErrors pass every field through).
 */
function schemaFor(name: string) {
  const tool = TOOLS.find((t) => t.name === name)
  if (!tool) throw new Error(`no tool named ${name}`)
  return tool.inputSchema as { properties: Record<string, { type?: string; enum?: string[]; properties?: Record<string, unknown> }> }
}

describe('create_creative / update_creative schemas accept motion/audio', () => {
  for (const name of ['create_creative', 'update_creative']) {
    it(`${name} accepts a motion object with the contract's preset/duration/background/endCard fields`, () => {
      const { properties } = schemaFor(name)
      expect(properties['motion']).toBeDefined()
      expect(properties['motion'].type).toBe('object')
      const motionProps = properties['motion'].properties as Record<string, { type?: string; enum?: string[]; properties?: Record<string, unknown> }>
      expect(motionProps['preset'].enum).toEqual(['fade-up', 'word-by-word', 'accent-pop', 'none'])
      expect(motionProps['durationSec'].type).toBe('number')
      expect(motionProps['endCard'].type).toBe('boolean')
      const backgroundProps = motionProps['background'].properties as Record<string, { type?: string; enum?: string[] }>
      expect(backgroundProps['source'].enum).toEqual(['photo', 'clip'])
      expect(backgroundProps['motion'].enum).toEqual(['zoom-in', 'zoom-out', 'pan-left', 'pan-right', 'none'])
      expect(backgroundProps['clipMediaId'].type).toBe('string')
      expect(backgroundProps['clipStartSec'].type).toBe('number')
    })

    it(`${name} accepts an audio object with the contract's source/track/volume/fadeOut fields`, () => {
      const { properties } = schemaFor(name)
      expect(properties['audio']).toBeDefined()
      expect(properties['audio'].type).toBe('object')
      const audioProps = properties['audio'].properties as Record<string, { type?: string; enum?: string[] }>
      expect(audioProps['source'].enum).toEqual(['clip', 'track', 'none'])
      expect(audioProps['trackId'].type).toBe('string')
      expect(audioProps['volume'].type).toBe('number')
      expect(audioProps['fadeOutSec'].type).toBe('number')
    })
  }

  it('create_creative\'s kind description no longer claims MOTION is unrenderable', () => {
    const { properties } = schemaFor('create_creative')
    const kind = properties['kind'] as unknown as { enum: string[]; description: string }
    expect(kind.enum).toEqual(['STILL', 'MOTION', 'CLIP'])
    expect(kind.description).not.toMatch(/not yet renderable/)
  })
})
