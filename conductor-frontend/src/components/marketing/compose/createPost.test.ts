import { describe, it, expect, vi, beforeEach } from 'vitest'

const api = vi.hoisted(() => ({
  apiDelete: vi.fn(),
  apiGet: vi.fn(),
  apiPatch: vi.fn(),
  apiPost: vi.fn(),
  apiPut: vi.fn(),
}))
vi.mock('@/lib/api', () => ({
  ...api,
  apiErrorMessage: (err: unknown, fallback: string) => (err instanceof Error ? err.message : fallback),
}))
const upload = vi.hoisted(() => ({ putToSignedUrl: vi.fn() }))
vi.mock('@/components/workitems/MediaUploadPanel', () => ({
  isVideoContentType: () => false,
  measureVideoMetadata: vi.fn(),
  putToSignedUrl: upload.putToSignedUrl,
}))

import { createPost, type CreatePostInput } from './createPost'

function input(): CreatePostInput {
  return {
    projectId: 'p1',
    token: 't',
    workflowSlug: 'MARKETING',
    types: ['POST'],
    assetType: 'instagram_post',
    noun: 'Post',
    title: 'T',
    caption: 'c',
    files: [new File(['x'], 'a.png', { type: 'image/png' })],
    fileIds: ['pending-1'],
    options: [],
    draft: {} as CreatePostInput['draft'],
    schedule: { onApproval: true, local: '', timeZone: 'UTC' },
  }
}

const base = '/api/v2/projects/p1/work-items/wi-1'

beforeEach(() => {
  vi.clearAllMocks()
  api.apiPost.mockImplementation(async (path: string) => {
    if (path.endsWith('/work-items')) return { id: 'wi-1' }
    if (path.endsWith('/assets/uploads')) return { assetId: 'asset-1', uploadUrl: 'https://storage/x' }
    return undefined
  })
  api.apiDelete.mockResolvedValue(undefined)
  api.apiPut.mockResolvedValue(undefined)
  api.apiPatch.mockResolvedValue(undefined)
  upload.putToSignedUrl.mockResolvedValue(undefined)
})

describe('createPost — failed uploads', () => {
  it('deletes the PENDING asset when the signed PUT fails', async () => {
    upload.putToSignedUrl.mockRejectedValue(new Error('storage rejected the file (403)'))

    const { problems } = await createPost(input())

    expect(api.apiDelete).toHaveBeenCalledWith(`${base}/assets/asset-1`, 't')
    expect(problems[0]).toContain('storage rejected the file (403)')
  })

  it('deletes the PENDING asset when confirm fails, ignoring a failing delete', async () => {
    api.apiPost.mockImplementation(async (path: string) => {
      if (path.endsWith('/work-items')) return { id: 'wi-1' }
      if (path.endsWith('/assets/uploads')) return { assetId: 'asset-1', uploadUrl: 'https://storage/x' }
      throw new Error('confirm refused')
    })
    api.apiDelete.mockRejectedValue(new Error('gone'))

    const { problems } = await createPost(input())

    expect(api.apiDelete).toHaveBeenCalledWith(`${base}/assets/asset-1`, 't')
    expect(problems[0]).toContain('confirm refused')
  })

  it('deletes nothing when the upload succeeds', async () => {
    await createPost(input())

    expect(api.apiDelete).not.toHaveBeenCalled()
  })
})
