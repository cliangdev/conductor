import { describe, it, expect, vi, beforeAll, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, fireEvent, act } from '@testing-library/react'
import type { WorkflowView } from '@/types/workItem'
import { MediaUploadPanel, isApprovedOrLater, type MediaAsset } from './MediaUploadPanel'

const API = 'https://api.test'
const SIGNED_URL = 'https://storage.googleapis.com/bucket/marketing-assets/asset-1-clip.mp4?X-Goog-Signature=abc'

const VIEW: WorkflowView = {
  slug: 'MARKETING',
  noun: 'Post',
  area: 'MARKETING',
  defaultView: 'list',
  version: 1,
  types: ['POST'],
  assetTypes: ['instagram_post', 'youtube_video'],
  statuses: [
    { id: 'DRAFT', label: 'Draft', category: 'open' },
    { id: 'IN_REVIEW', label: 'In Review', category: 'in_progress' },
    { id: 'APPROVED', label: 'Approved', category: 'in_progress' },
    { id: 'PUBLISHED', label: 'Published', category: 'terminal' },
  ],
  transitions: [
    { from: 'DRAFT', to: 'IN_REVIEW', label: 'Submit for review' },
    { from: 'IN_REVIEW', to: 'APPROVED', label: 'Approve', requiresReview: true },
    { from: 'APPROVED', to: 'PUBLISHED', label: 'Publish' },
    // The "send back" edge — the reason the reachability walk must never re-enter the review status.
    { from: 'APPROVED', to: 'IN_REVIEW', label: 'Send back' },
  ],
}

// ── Recorded traffic ────────────────────────────────────────────────────────

/** Every network call, in order — the invariant under test is mint → signed PUT → confirm. */
let calls: string[] = []
let mintBodies: Record<string, unknown>[] = []
let confirmBodies: Record<string, unknown>[] = []
let mintRejection: { status: number; detail: string } | null = null
let confirmRejection: { status: number; detail: string } | null = null

// ── Fake XHR (the signed PUT) ───────────────────────────────────────────────

class FakeXhr {
  static instances: FakeXhr[] = []
  /** When false the test drives progress/completion by hand. */
  static auto = true
  static completeStatus = 200

  method = ''
  url = ''
  headers: Record<string, string> = {}
  body: unknown = null
  status = 0
  upload = { onprogress: null as ((e: ProgressEvent) => void) | null }
  onload: (() => void) | null = null
  onerror: (() => void) | null = null

  open(method: string, url: string) {
    this.method = method
    this.url = url
  }

  setRequestHeader(key: string, value: string) {
    this.headers[key] = value
  }

  send(body: unknown) {
    this.body = body
    FakeXhr.instances.push(this)
    calls.push(`${this.method} ${this.url}`)
    if (FakeXhr.auto) {
      setTimeout(() => {
        this.emitProgress(100, 100)
        this.complete(FakeXhr.completeStatus)
      }, 0)
    }
  }

  emitProgress(loaded: number, total: number) {
    act(() => {
      this.upload.onprogress?.({ lengthComputable: true, loaded, total } as ProgressEvent)
    })
  }

  complete(status = 200) {
    this.status = status
    act(() => {
      this.onload?.()
    })
  }
}

// ── fetch stub ──────────────────────────────────────────────────────────────

function jsonResponse(status: number, body: unknown) {
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: { get: () => 'application/json' },
    json: async () => body,
  }
}

function noContentResponse() {
  return {
    ok: true,
    status: 204,
    headers: { get: () => null },
    json: async () => {
      throw new SyntaxError('Unexpected end of JSON input')
    },
  }
}

const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
  calls.push(`${init?.method ?? 'GET'} ${url}`)
  if (url.endsWith('/assets/uploads')) {
    if (mintRejection) {
      return jsonResponse(mintRejection.status, { detail: mintRejection.detail })
    }
    mintBodies.push(JSON.parse(init!.body as string))
    return jsonResponse(201, {
      assetId: 'asset-1',
      uploadUrl: SIGNED_URL,
      gcsPath: 'marketing-assets/p/w/asset-1-clip.mp4',
      expiresAt: '2026-08-30T12:00:00Z',
    })
  }
  if (url.endsWith('/confirm')) {
    if (confirmRejection) {
      return jsonResponse(confirmRejection.status, { detail: confirmRejection.detail })
    }
    confirmBodies.push(JSON.parse(init!.body as string))
    return noContentResponse()
  }
  throw new Error(`unexpected fetch: ${url}`)
})

// ── HTMLVideoElement metadata stub ──────────────────────────────────────────

const videoStub = { width: 1080, height: 1920, duration: 12.5, fireMetadata: true }

beforeAll(() => {
  Object.defineProperty(HTMLMediaElement.prototype, 'duration', {
    configurable: true,
    get: () => videoStub.duration,
  })
  Object.defineProperty(HTMLVideoElement.prototype, 'videoWidth', {
    configurable: true,
    get: () => videoStub.width,
  })
  Object.defineProperty(HTMLVideoElement.prototype, 'videoHeight', {
    configurable: true,
    get: () => videoStub.height,
  })
  // Setting `src` is what makes a real browser load metadata; mirror that so the panel's probe
  // resolves without an actual decoder.
  Object.defineProperty(HTMLMediaElement.prototype, 'src', {
    configurable: true,
    get(this: HTMLMediaElement) {
      return this.getAttribute('src') ?? ''
    },
    set(this: HTMLMediaElement, value: string) {
      this.setAttribute('src', value)
      if (videoStub.fireMetadata) {
        setTimeout(() => this.onloadedmetadata?.(new Event('loadedmetadata')), 0)
      }
    },
  })
})

beforeEach(() => {
  calls = []
  mintBodies = []
  confirmBodies = []
  mintRejection = null
  confirmRejection = null
  FakeXhr.instances = []
  FakeXhr.auto = true
  FakeXhr.completeStatus = 200
  videoStub.width = 1080
  videoStub.height = 1920
  videoStub.duration = 12.5
  videoStub.fireMetadata = true
  fetchMock.mockClear()
  vi.stubEnv('NEXT_PUBLIC_API_URL', API)
  vi.stubGlobal('fetch', fetchMock)
  vi.stubGlobal('XMLHttpRequest', FakeXhr)
  vi.stubGlobal('URL', Object.assign(URL, {
    createObjectURL: vi.fn(() => 'blob:mock'),
    revokeObjectURL: vi.fn(),
  }))
})

afterEach(() => {
  vi.unstubAllEnvs()
  vi.unstubAllGlobals()
})

// ── Helpers ─────────────────────────────────────────────────────────────────

const onUploaded = vi.fn()

function renderPanel(overrides: Partial<React.ComponentProps<typeof MediaUploadPanel>> = {}) {
  onUploaded.mockClear()
  return render(
    <MediaUploadPanel
      projectId="proj-1"
      workItemId="wi-1"
      token="token"
      status="DRAFT"
      workflowView={VIEW}
      assets={[]}
      onUploaded={onUploaded}
      {...overrides}
    />,
  )
}

function selectFile(file: File) {
  const input = screen.getByLabelText('Media file') as HTMLInputElement
  fireEvent.change(input, { target: { files: [file] } })
}

function videoFile() {
  return new File(['0123456789'], 'clip.mp4', { type: 'video/mp4' })
}

function imageFile() {
  return new File(['0123'], 'hero.png', { type: 'image/png' })
}

const uploadsUrl = `${API}/api/v2/projects/proj-1/work-items/wi-1/assets/uploads`
const confirmUrl = `${API}/api/v2/projects/proj-1/work-items/wi-1/assets/asset-1/confirm`

// ── Tests ───────────────────────────────────────────────────────────────────

describe('MediaUploadPanel', () => {
  // [auto] Upload flows mint → signed PUT → confirm
  it('selecting an mp4 calls mint, PUTs to the signed URL, then calls confirm, in that order', async () => {
    renderPanel()

    selectFile(videoFile())

    await waitFor(() => expect(onUploaded).toHaveBeenCalled())
    expect(calls).toEqual([`POST ${uploadsUrl}`, `PUT ${SIGNED_URL}`, `POST ${confirmUrl}`])
  })

  // [auto] Bytes go to the signed URL, never to the Conductor API
  it('sends the raw file to the signed URL with no Authorization header', async () => {
    renderPanel()

    selectFile(videoFile())

    await waitFor(() => expect(onUploaded).toHaveBeenCalled())
    const put = FakeXhr.instances[0]
    expect(put.url).toBe(SIGNED_URL)
    expect(put.url).not.toContain(API)
    expect(put.body).toBeInstanceOf(File)
    expect(put.headers['Content-Type']).toBe('video/mp4')
    expect(put.headers.Authorization).toBeUndefined()
    // The API only ever saw JSON control calls.
    expect(fetchMock.mock.calls.map((c) => c[0])).toEqual([uploadsUrl, confirmUrl])
  })

  // [auto] Video uploads carry browser-measured width, height and duration
  it('an uploaded video sends width, height and durationSeconds measured in the browser', async () => {
    renderPanel()

    selectFile(videoFile())

    await waitFor(() => expect(mintBodies).toHaveLength(1))
    expect(mintBodies[0]).toMatchObject({
      type: 'instagram_post',
      filename: 'clip.mp4',
      contentType: 'video/mp4',
      sizeBytes: 10,
      width: 1080,
      height: 1920,
      durationSeconds: 12.5,
    })
    await waitFor(() => expect(confirmBodies[0]).toEqual({ sizeBytes: 10 }))
  })

  // [auto] An image does not block on video metadata measurement
  it('an uploaded image mints without measuring video metadata', async () => {
    // A browser that never reports metadata would hang the upload if images went through the probe.
    videoStub.fireMetadata = false
    renderPanel()

    selectFile(imageFile())

    await waitFor(() => expect(mintBodies).toHaveLength(1))
    expect(mintBodies[0]).toMatchObject({ contentType: 'image/png', sizeBytes: 4 })
    expect(mintBodies[0]).not.toHaveProperty('width')
    expect(mintBodies[0]).not.toHaveProperty('height')
    expect(mintBodies[0]).not.toHaveProperty('durationSeconds')
    await waitFor(() => expect(onUploaded).toHaveBeenCalled())
  })

  // [auto] A visible progress indicator tracks the PUT
  it('shows upload progress while the bytes are in flight', async () => {
    FakeXhr.auto = false
    renderPanel()

    selectFile(videoFile())

    await waitFor(() => expect(FakeXhr.instances).toHaveLength(1))
    const put = FakeXhr.instances[0]
    put.emitProgress(30, 100)
    const bar = await screen.findByRole('progressbar')
    expect(bar).toHaveAttribute('aria-valuenow', '30')

    put.emitProgress(100, 100)
    expect(screen.getByRole('progressbar')).toHaveAttribute('aria-valuenow', '100')

    put.complete(200)
    await waitFor(() => expect(onUploaded).toHaveBeenCalled())
    await waitFor(() => expect(screen.queryByRole('progressbar')).not.toBeInTheDocument())
  })

  // [auto] Images and videos render inline previews after upload
  it('renders a video element for a video asset and an img for an image asset', () => {
    const assets: MediaAsset[] = [
      {
        id: 'a-vid', issueId: 'wi-1', type: 'youtube_video', label: 'Teaser', kind: 'file',
        ref: 'marketing-assets/a-vid', done: true, createdAt: '', updatedAt: '',
        uploadStatus: 'UPLOADED', contentType: 'video/mp4', sizeBytes: 2048,
        previewUrl: 'https://storage.googleapis.com/preview/teaser.mp4',
      },
      {
        id: 'a-img', issueId: 'wi-1', type: 'instagram_post', label: 'Hero', kind: 'file',
        ref: 'marketing-assets/a-img', done: true, createdAt: '', updatedAt: '',
        uploadStatus: 'UPLOADED', contentType: 'image/png', sizeBytes: 1024,
        previewUrl: 'https://storage.googleapis.com/preview/hero.png',
      },
    ]

    const { container } = renderPanel({ assets })

    const video = container.querySelector('video')
    expect(video).not.toBeNull()
    expect(video).toHaveAttribute('src', 'https://storage.googleapis.com/preview/teaser.mp4')
    expect(video).toHaveAttribute('controls')

    const img = screen.getByAltText('Hero')
    expect(img.tagName).toBe('IMG')
    expect(img).toHaveAttribute('src', 'https://storage.googleapis.com/preview/hero.png')
  })

  // [auto] The panel is hidden with an explanation on Approved-or-later Posts
  it('does not render the upload control once the work item is Approved-or-later', () => {
    renderPanel({ status: 'APPROVED' })

    expect(screen.queryByRole('button', { name: 'Choose file' })).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Media file')).not.toBeInTheDocument()
    expect(screen.getByRole('alert')).toHaveTextContent(
      /Media is locked while this post is Approved\. Revert it to In Review/i,
    )
  })

  it('still renders existing previews on a locked work item', () => {
    const assets: MediaAsset[] = [
      {
        id: 'a-img', issueId: 'wi-1', type: 'instagram_post', label: 'Hero', kind: 'file',
        ref: 'marketing-assets/a-img', done: true, createdAt: '', updatedAt: '',
        uploadStatus: 'UPLOADED', contentType: 'image/png', sizeBytes: 1024,
        previewUrl: 'https://storage.googleapis.com/preview/hero.png',
      },
    ]

    renderPanel({ status: 'PUBLISHED', assets })

    expect(screen.getByAltText('Hero')).toBeInTheDocument()
    expect(screen.queryByLabelText('Media file')).not.toBeInTheDocument()
  })

  it('offers the upload control before the review gate', () => {
    renderPanel({ status: 'IN_REVIEW' })

    expect(screen.getByRole('button', { name: 'Choose file' })).toBeInTheDocument()
  })

  // [auto] API errors surface verbatim
  it('surfaces a mint rejection verbatim and never attempts the PUT', async () => {
    mintRejection = {
      status: 400,
      detail:
        'Content type \'application/pdf\' is not allowed for a file asset. Allowed types: [image/png, video/mp4]',
    }
    renderPanel()

    selectFile(new File(['x'], 'brief.pdf', { type: 'application/pdf' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(
      "Content type 'application/pdf' is not allowed for a file asset.",
    )
    expect(FakeXhr.instances).toHaveLength(0)
    expect(calls).toEqual([`POST ${uploadsUrl}`])
    expect(onUploaded).not.toHaveBeenCalled()
  })

  it('surfaces a storage rejection of the signed PUT', async () => {
    FakeXhr.completeStatus = 403
    renderPanel()

    selectFile(videoFile())

    expect(await screen.findByRole('alert')).toHaveTextContent('storage rejected the file (403)')
    expect(confirmBodies).toHaveLength(0)
    expect(onUploaded).not.toHaveBeenCalled()
  })

  // The confirm endpoint answers 204 — the shared apiPost must resolve on it, not reject.
  it('surfaces a confirm rejection verbatim', async () => {
    confirmRejection = { status: 409, detail: 'Asset upload was already confirmed' }
    renderPanel()

    selectFile(videoFile())

    expect(await screen.findByRole('alert')).toHaveTextContent('Asset upload was already confirmed')
    expect(onUploaded).not.toHaveBeenCalled()
  })

  it('uploads under the channel the user picks', async () => {
    renderPanel()

    fireEvent.change(screen.getByLabelText('Channel'), { target: { value: 'youtube_video' } })
    selectFile(videoFile())

    await waitFor(() => expect(mintBodies).toHaveLength(1))
    expect(mintBodies[0]).toMatchObject({ type: 'youtube_video' })
  })

  it('accepts a dropped file', async () => {
    renderPanel()

    const dropzone = screen.getByText(/Drop an image or video here/i).parentElement!
    fireEvent.drop(dropzone, { dataTransfer: { files: [videoFile()] } })

    await waitFor(() => expect(onUploaded).toHaveBeenCalled())
    expect(calls).toEqual([`POST ${uploadsUrl}`, `PUT ${SIGNED_URL}`, `POST ${confirmUrl}`])
  })
})

describe('isApprovedOrLater', () => {
  it('is false before and at the review gate, true at and past it', () => {
    expect(isApprovedOrLater(VIEW, 'DRAFT')).toBe(false)
    expect(isApprovedOrLater(VIEW, 'IN_REVIEW')).toBe(false)
    expect(isApprovedOrLater(VIEW, 'APPROVED')).toBe(true)
    expect(isApprovedOrLater(VIEW, 'PUBLISHED')).toBe(true)
  })

  it('locks nothing in a workflow with no review gate', () => {
    const ungated: WorkflowView = {
      ...VIEW,
      transitions: VIEW.transitions.map((t) => ({ ...t, requiresReview: false })),
    }
    expect(isApprovedOrLater(ungated, 'APPROVED')).toBe(false)
  })

  it('locks nothing when the workflow has not loaded yet', () => {
    expect(isApprovedOrLater(undefined, 'APPROVED')).toBe(false)
  })
})
