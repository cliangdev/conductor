import { describe, it, expect } from 'vitest'
import { TOOLS } from '../mcp/index.js'
import { needsLocalRender } from '../lib/creative-local-render.js'

type Prop = { type?: string; enum?: string[]; description?: string; properties?: Record<string, Prop>; items?: Prop }
function props(name: string): Record<string, Prop> {
  const tool = TOOLS.find((t) => t.name === name)
  if (!tool) throw new Error(`no tool named ${name}`)
  return (tool.inputSchema as { properties: Record<string, Prop> }).properties
}

describe('audio and slideshow on CAROUSEL and CLIP', () => {
  for (const name of ['create_creative', 'update_creative', 'preview_creative_draft']) {
    it(`${name} accepts audio sources clip|track|mix|none and no longer says MOTION only`, () => {
      const audio = props(name)['audio']!
      expect(audio.properties!['source']!.enum).toEqual(['clip', 'track', 'mix', 'none'])
      expect(audio.properties!['trackId']!.type).toBe('string')
      expect(audio.description).toMatch(/CAROUSEL/)
      expect(audio.description).toMatch(/CLIP/)
      expect(audio.description).not.toMatch(/MOTION only/)
    })

    it(`${name} accepts slideshow { slideDurations, zoom }`, () => {
      const slideshow = props(name)['slideshow']!
      expect(slideshow.type).toBe('object')
      expect(slideshow.properties!['slideDurations']!.type).toBe('array')
      expect(slideshow.properties!['slideDurations']!.items!.type).toBe('number')
      expect(slideshow.properties!['zoom']!.type).toBe('boolean')
    })
  }

  it('preview_creative_draft documents audioPath for CAROUSEL and the slideshow video path', () => {
    expect(props('preview_creative_draft')['audioPath']!.description).toMatch(/CAROUSEL/)
    const tool = TOOLS.find((t) => t.name === 'preview_creative_draft')!
    expect(tool.description).toMatch(/slideshowVideoPath/)
  })

  it('set_publish_targets and create_post describe carouselAs', () => {
    const targets = props('set_publish_targets')['targets']!
    const publishOptions = (targets.items as Prop).properties!['publishOptions']!
    expect(publishOptions.description).toMatch(/carouselAs/)
    expect(JSON.stringify(TOOLS.find((t) => t.name === 'create_post')!.inputSchema)).toMatch(/carouselAs/)
  })

  it('render_creative and attach_creative_to_post no longer claim CAROUSEL and CLIP always render on the server', () => {
    const render = TOOLS.find((t) => t.name === 'render_creative')!
    expect(render.description).toMatch(/CAROUSEL with a track/)
    const attach = TOOLS.find((t) => t.name === 'attach_creative_to_post')!
    expect(attach.description).toMatch(/carouselAs/)
  })
})

describe('needsLocalRender (matches the backend rule)', () => {
  it('CAROUSEL goes local only with a track', () => {
    expect(needsLocalRender('CAROUSEL', { source: 'track' })).toBe(true)
    expect(needsLocalRender('CAROUSEL', { source: 'none' })).toBe(false)
    expect(needsLocalRender('CAROUSEL', null)).toBe(false)
    expect(needsLocalRender('CAROUSEL', undefined)).toBe(false)
  })
  it('CLIP goes local for track, mix and none, not for clip or absent', () => {
    for (const source of ['track', 'mix', 'none']) expect(needsLocalRender('CLIP', { source })).toBe(true)
    expect(needsLocalRender('CLIP', { source: 'clip' })).toBe(false)
    expect(needsLocalRender('CLIP', null)).toBe(false)
  })
  it('MOTION and STILL always go local', () => {
    expect(needsLocalRender('MOTION', { source: 'clip' })).toBe(true)
    expect(needsLocalRender('STILL', undefined)).toBe(true)
  })
})
