import { describe, it, expect } from 'vitest'
import { imageResponse } from '../mcp/index.js'

describe('imageResponse', () => {
  it('base64-encodes the image bytes into an image content block with the given mimeType', () => {
    const bytes = Buffer.from([0x89, 0x50, 0x4e, 0x47])
    const result = imageResponse({ data: bytes, mimeType: 'image/png' })

    expect(result.content).toEqual([{ type: 'image', data: bytes.toString('base64'), mimeType: 'image/png' }])
  })

  it('appends a text block with the metadata JSON when meta is given', () => {
    const bytes = Buffer.from([1, 2, 3])
    const result = imageResponse({ data: bytes, mimeType: 'image/png' }, { renderId: 'r1', state: 'SUCCEEDED' })

    expect(result.content).toHaveLength(2)
    expect(result.content[0]).toEqual({ type: 'image', data: bytes.toString('base64'), mimeType: 'image/png' })
    expect(result.content[1]).toEqual({ type: 'text', text: JSON.stringify({ renderId: 'r1', state: 'SUCCEEDED' }) })
  })

  it('omits the text block entirely when meta is undefined', () => {
    const result = imageResponse({ data: Buffer.from([1]), mimeType: 'image/jpeg' })
    expect(result.content).toHaveLength(1)
  })
})
