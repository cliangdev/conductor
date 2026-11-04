// Shared fixtures for the slide-text editor tests.
import type { SlideshowSpec } from '@cliangdev/creative-render/slideshow/spec.js'
import type { Creative, CreativePhoto } from './types'

export const BG_ID = '11111111-1111-4111-8111-111111111111'

export const SPEC: SlideshowSpec = {
  id: 'nov06',
  slides: [
    { type: 'photo', bg: `media:${BG_ID}`, dim: true, texts: [{ t: 'Hook line\nsecond line', y: 30 }, { t: 'Small print', style: 'sub' }] },
    { type: 'notes', title: 'Plan', date: 'Nov 2', items: ['Buy milk', 'Call mum'], paras: ['One paragraph'] },
    { type: 'messages', bubbles: [{ from: 'me', t: 'Hi' }, { from: 'them', t: 'Hello' }] },
  ],
}

export function media(id: string): CreativePhoto {
  return {
    id,
    projectId: 'p',
    label: id,
    contentType: 'image/jpeg',
    sizeBytes: 1,
    width: 1080,
    height: 1920,
    mediaKind: 'IMAGE',
    aiGenerated: false,
    checked: true,
    blocked: false,
    focal: {},
    uploadStatus: 'UPLOADED',
    url: `https://storage.example/${id}.jpg`,
    createdAt: '2026-01-01T00:00:00Z',
  } as CreativePhoto
}

export const MEDIA = new Map([[BG_ID, media(BG_ID)]])

export function creative(over: Partial<Creative> = {}): Creative {
  return {
    id: 'cr-1',
    projectId: 'p',
    brandKitId: 'bk',
    number: 158,
    variantLetter: 'a',
    displayId: '158a',
    state: 'READY',
    kind: 'CAROUSEL',
    layout: 'stacked',
    theme: 'dark',
    placements: [],
    sequence: [],
    typeOverrides: {},
    lockup: 'plain',
    version: 4,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    slides: { '9x16': ['s1', 's2', 's3'], '4x5': ['t1', 't2', 't3'] },
    slideshowSpec: SPEC,
    ...over,
  } as Creative
}
