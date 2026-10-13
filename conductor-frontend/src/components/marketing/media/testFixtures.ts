import type { CreativePhoto } from '@/components/marketing/creatives/types'

// Shared fixtures for the Media library tests.
export function photo(id: string, label: string, width = 1080, height = 1350, extra: Partial<CreativePhoto> = {}): CreativePhoto {
  return {
    id,
    projectId: 'proj-1',
    label,
    contentType: 'image/png',
    sizeBytes: 2048,
    width,
    height,
    mediaKind: 'IMAGE',
    aiGenerated: false,
    checked: false,
    blocked: false,
    focal: {},
    uploadStatus: 'UPLOADED',
    url: `https://storage.example/${id}.png`,
    warnings: [],
    createdAt: '2026-01-01T00:00:00Z',
    ...extra,
  }
}

export const slides = (aspect: '4x5' | '9x16', n: number) =>
  Array.from({ length: n }, (_, i) =>
    photo(`${aspect}-${i + 1}`, `01-import ${aspect} slide ${String(i + 1).padStart(2, '0')}`, 1080, aspect === '4x5' ? 1350 : 1920),
  )
