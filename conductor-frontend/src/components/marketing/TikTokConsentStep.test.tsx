import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { EMPTY_TIKTOK_OPTIONS, type TikTokPublishOptionValues } from './TikTokPublishOptions'
import {
  TikTokConsentStep,
  TikTokPublishGateProvider,
  tiktokSubmissionBlockedReason,
  useTikTokPublishGate,
  type PublishConsentState,
  type TikTokConsentTarget,
  type TikTokPreviewAsset,
} from './TikTokConsentStep'

function options(overrides: Partial<TikTokPublishOptionValues> = {}): TikTokPublishOptionValues {
  return { ...EMPTY_TIKTOK_OPTIONS, privacyLevel: 'PUBLIC_TO_EVERYONE', ...overrides }
}

function target(overrides: Partial<TikTokConsentTarget> = {}): TikTokConsentTarget {
  return {
    connectionId: 'conn-tt',
    label: 'Acme on TikTok',
    creatorNickname: 'acme_official',
    options: options(),
    problem: null,
    ...overrides,
  }
}

const IMAGE: TikTokPreviewAsset = {
  id: 'asset-1',
  label: 'Launch teaser',
  contentType: 'image/png',
  previewUrl: 'https://cdn.test/teaser.png',
}

const VIDEO: TikTokPreviewAsset = {
  id: 'asset-2',
  label: 'Launch cut',
  contentType: 'video/mp4',
  previewUrl: 'https://cdn.test/cut.mp4',
}

function renderStep(props: Partial<React.ComponentProps<typeof TikTokConsentStep>> = {}) {
  const onConsentChange = vi.fn()
  const result = render(
    <TikTokConsentStep
      targets={[target()]}
      assets={[IMAGE]}
      caption="Acme launches the thing"
      consented={false}
      onConsentChange={onConsentChange}
      {...props}
    />
  )
  return { onConsentChange, ...result }
}

describe('tiktokSubmissionBlockedReason', () => {
  it('never blocks a Post with no TikTok target', () => {
    expect(tiktokSubmissionBlockedReason([], false)).toBeNull()
  })

  it('blocks until the creator has consented', () => {
    expect(tiktokSubmissionBlockedReason([target()], false)).toMatch(/consent/i)
  })

  it('clears once the creator has consented', () => {
    expect(tiktokSubmissionBlockedReason([target()], true)).toBeNull()
  })

  it('reports an unresolved option problem in preference to the consent prompt', () => {
    const reason = tiktokSubmissionBlockedReason(
      [target({ problem: 'Choose who can see this TikTok post.' })],
      true
    )
    expect(reason).toContain('Acme on TikTok')
    expect(reason).toContain('Choose who can see this TikTok post.')
  })

  it('blocks when any one of several accounts is unresolved', () => {
    const reason = tiktokSubmissionBlockedReason(
      [target(), target({ connectionId: 'conn-2', label: '@acme_uk', problem: 'Nope.' })],
      true
    )
    expect(reason).toContain('@acme_uk')
  })
})

describe('TikTokConsentStep', () => {
  it('renders nothing when the Post has no TikTok target', () => {
    const { container } = renderStep({ targets: [] })
    expect(container).toBeEmptyDOMElement()
  })

  it('names the destination account unmistakably', () => {
    renderStep()
    expect(screen.getByText(/you are posting to/i)).toBeInTheDocument()
    expect(screen.getByText('@acme_official')).toBeInTheDocument()
  })

  it('falls back to the connection label when TikTok reported no nickname', () => {
    renderStep({ targets: [target({ creatorNickname: null })] })
    expect(screen.getByText('Acme on TikTok')).toBeInTheDocument()
  })

  it('names both accounts when one Post posts to two creators', () => {
    renderStep({
      targets: [target(), target({ connectionId: 'conn-2', creatorNickname: 'acme_uk' })],
    })
    expect(screen.getByText('@acme_official')).toBeInTheDocument()
    expect(screen.getByText('@acme_uk')).toBeInTheDocument()
  })

  it('shows the content that will be uploaded', () => {
    renderStep({ assets: [IMAGE, VIDEO] })
    expect(screen.getByAltText('Launch teaser')).toHaveAttribute('src', IMAGE.previewUrl)
    expect(screen.getByLabelText('Launch cut')).toHaveAttribute('src', VIDEO.previewUrl)
    expect(screen.getByText('Acme launches the thing')).toBeInTheDocument()
  })

  it('says so rather than showing an empty frame when nothing has been uploaded', () => {
    renderStep({ assets: [] })
    expect(screen.getByText(/no media/i)).toBeInTheDocument()
  })

  it('summarises the options the post will carry', () => {
    renderStep({
      targets: [
        target({
          options: options({ disableComment: true, brandContentToggle: true }),
        }),
      ],
    })
    expect(screen.getByText(/Everyone/)).toBeInTheDocument()
    expect(screen.getByText(/Comments off/)).toBeInTheDocument()
    expect(screen.getByText(/Paid partnership/)).toBeInTheDocument()
  })

  it('spells out that submission is blocked until consent is given', () => {
    renderStep()
    expect(screen.getByRole('checkbox', { name: /consent/i })).not.toBeChecked()
    expect(screen.getByRole('alert')).toHaveTextContent(/consent/i)
  })

  it('records an explicit consent action', async () => {
    const { onConsentChange } = renderStep()
    await userEvent.click(screen.getByRole('checkbox', { name: /consent/i }))
    expect(onConsentChange).toHaveBeenCalledWith(true)
  })

  it('stops warning once consent has been given', () => {
    renderStep({ consented: true })
    expect(screen.getByRole('checkbox', { name: /consent/i })).toBeChecked()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('will not take consent while a target still has an unresolved problem', () => {
    renderStep({
      targets: [target({ options: options({ privacyLevel: null }), problem: 'Choose who can see this TikTok post.' })],
    })
    expect(screen.getByRole('checkbox', { name: /consent/i })).toBeDisabled()
    expect(screen.getByRole('alert')).toHaveTextContent('Choose who can see this TikTok post.')
  })

  it('withdraws consent when the creator unchecks it', async () => {
    const { onConsentChange } = renderStep({ consented: true })
    await userEvent.click(screen.getByRole('checkbox', { name: /consent/i }))
    expect(onConsentChange).toHaveBeenCalledWith(false)
  })

  it('states the TikTok policies the creator is agreeing to', () => {
    renderStep({ targets: [target({ options: options({ brandContentToggle: true }) })] })
    expect(screen.getByText(/Branded Content Policy/)).toBeInTheDocument()
    expect(screen.getByText(/Music Usage Confirmation/)).toBeInTheDocument()
  })
})

describe('TikTokPublishGateProvider', () => {
  function Probe() {
    return <span data-testid="gate">{useTikTokPublishGate() ?? 'clear'}</span>
  }

  it('publishes the blocking reason to whatever renders the status control', () => {
    render(
      <TikTokPublishGateProvider reason="Nope.">
        <Probe />
      </TikTokPublishGateProvider>
    )
    expect(screen.getByTestId('gate')).toHaveTextContent('Nope.')
  })

  it('blocks nothing outside a Post that has TikTok targets', () => {
    render(<Probe />)
    expect(screen.getByTestId('gate')).toHaveTextContent('clear')
  })
})


// ── MKT-1: consent that is actually persisted ───────────────────────────────
//
// The point of these is that the answer no longer lives here. A consent held in component state did
// not survive a reload and, far more seriously, was invisible to every other client — so what is
// asserted below is a round trip to the endpoint the backend gates the transition on, not a boolean.

const API = 'https://api.test'
const PROJECT = 'project-1'
const WORK_ITEM = 'post-1'
const CONSENT_PATH = `/api/v2/projects/${PROJECT}/work-items/${WORK_ITEM}/publish-consent`

function consentState(overrides: Partial<PublishConsentState> = {}): PublishConsentState {
  return {
    workItemId: WORK_ITEM,
    required: true,
    valid: false,
    verdict: 'NEVER_GIVEN',
    consentedAt: null,
    consentedByUserId: null,
    consentedByName: null,
    ...overrides,
  }
}

const GIVEN = consentState({
  valid: true,
  verdict: 'VALID',
  consentedAt: '2026-08-30T12:00:00Z',
  consentedByUserId: 'user-1',
  consentedByName: 'Ada Creator',
})

describe('TikTokConsentStep (persisted)', () => {
  let served: PublishConsentState
  let putBodies: Array<{ consented: boolean }>
  let readStatus: number

  function jsonResponse(status: number, body: unknown) {
    return {
      ok: status >= 200 && status < 300,
      status,
      headers: { get: () => 'application/json' },
      json: async () => body,
    }
  }

  const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
    const method = init?.method ?? 'GET'
    if (!url.endsWith(CONSENT_PATH)) throw new Error(`unexpected fetch: ${method} ${url}`)
    if (method === 'GET') {
      return readStatus === 200
        ? jsonResponse(200, served)
        : jsonResponse(readStatus, { detail: 'Consent is unavailable' })
    }
    if (method === 'PUT') {
      const body = JSON.parse(init!.body as string)
      putBodies.push(body)
      served = body.consented ? GIVEN : consentState()
      return jsonResponse(200, served)
    }
    throw new Error(`unexpected fetch: ${method} ${url}`)
  })

  beforeEach(() => {
    served = consentState()
    putBodies = []
    readStatus = 200
    fetchMock.mockClear()
    vi.stubEnv('NEXT_PUBLIC_API_URL', API)
    vi.stubGlobal('fetch', fetchMock)
  })

  afterEach(() => {
    vi.unstubAllEnvs()
    vi.unstubAllGlobals()
  })

  function renderPersisted(props: Partial<React.ComponentProps<typeof TikTokConsentStep>> = {}) {
    const onConsentChange = vi.fn()
    const result = render(
      <TikTokConsentStep
        targets={[target()]}
        assets={[IMAGE]}
        caption="Acme launches the thing"
        projectId={PROJECT}
        workItemId={WORK_ITEM}
        token="jwt"
        onConsentChange={onConsentChange}
        {...props}
      />
    )
    return { onConsentChange, ...result }
  }

  function checkbox() {
    return screen.getByRole('checkbox', { name: /consent/i })
  }

  // [auto] Consent survives a page reload

  it('reads a standing consent back from the server rather than starting blank', async () => {
    served = GIVEN
    const { onConsentChange } = renderPersisted()

    await waitFor(() => expect(checkbox()).toBeChecked())
    expect(onConsentChange).toHaveBeenCalledWith(true)
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(screen.getByText(/Consented by Ada Creator/)).toBeInTheDocument()
  })

  it('starts unconsented when nobody has consented to this post', async () => {
    const { onConsentChange } = renderPersisted()

    await waitFor(() => expect(onConsentChange).toHaveBeenCalledWith(false))
    expect(checkbox()).not.toBeChecked()
    expect(screen.getByRole('alert')).toHaveTextContent(/consent/i)
  })

  // [auto] Consent is recorded through the API

  it('records consent through the API instead of holding it in component state', async () => {
    const { onConsentChange } = renderPersisted()
    await waitFor(() => expect(fetchMock).toHaveBeenCalled())

    await userEvent.click(checkbox())

    await waitFor(() => expect(putBodies).toEqual([{ consented: true }]))
    await waitFor(() => expect(checkbox()).toBeChecked())
    expect(onConsentChange).toHaveBeenLastCalledWith(true)
  })

  it('withdraws consent through the API when the creator unchecks it', async () => {
    served = GIVEN
    const { onConsentChange } = renderPersisted()
    await waitFor(() => expect(checkbox()).toBeChecked())

    await userEvent.click(checkbox())

    await waitFor(() => expect(putBodies).toEqual([{ consented: false }]))
    await waitFor(() => expect(checkbox()).not.toBeChecked())
    expect(onConsentChange).toHaveBeenLastCalledWith(false)
  })

  // [auto] Changing the targets or the media invalidates existing consent

  it('says consent was withdrawn by an edit rather than never given', async () => {
    served = consentState({
      verdict: 'SUPERSEDED',
      consentedAt: '2026-08-30T12:00:00Z',
      consentedByName: 'Ada Creator',
    })
    renderPersisted()

    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent(/changed since you consented/i))
    expect(checkbox()).not.toBeChecked()
  })

  it('re-reads consent when the destination account changes', async () => {
    served = GIVEN
    const { rerender } = renderPersisted()
    await waitFor(() => expect(checkbox()).toBeChecked())

    served = consentState({ verdict: 'SUPERSEDED', consentedAt: '2026-08-30T12:00:00Z' })
    rerender(
      <TikTokConsentStep
        targets={[target({ connectionId: 'conn-other', creatorNickname: 'acme_uk' })]}
        assets={[IMAGE]}
        projectId={PROJECT}
        workItemId={WORK_ITEM}
        token="jwt"
        onConsentChange={vi.fn()}
      />
    )

    await waitFor(() => expect(checkbox()).not.toBeChecked())
    expect(fetchMock.mock.calls.filter(([, init]) => (init?.method ?? 'GET') === 'GET')).toHaveLength(2)
  })

  it('re-reads consent when the media changes', async () => {
    served = GIVEN
    const { rerender } = renderPersisted()
    await waitFor(() => expect(checkbox()).toBeChecked())

    served = consentState({ verdict: 'SUPERSEDED', consentedAt: '2026-08-30T12:00:00Z' })
    rerender(
      <TikTokConsentStep
        targets={[target()]}
        assets={[VIDEO]}
        projectId={PROJECT}
        workItemId={WORK_ITEM}
        token="jwt"
        onConsentChange={vi.fn()}
      />
    )

    await waitFor(() => expect(checkbox()).not.toBeChecked())
  })

  // [auto] A Post with no TikTok target is unaffected

  it('asks the server nothing about a Post with no TikTok target', async () => {
    const { container } = renderPersisted({ targets: [] })

    expect(container).toBeEmptyDOMElement()
    expect(fetchMock).not.toHaveBeenCalled()
  })

  // A consent that cannot be read is not a consent that was given.

  it('fails closed when the consent cannot be read', async () => {
    readStatus = 500
    const { onConsentChange } = renderPersisted()

    await waitFor(() => expect(screen.getByText('Consent is unavailable')).toBeInTheDocument())
    expect(checkbox()).not.toBeChecked()
    expect(onConsentChange).toHaveBeenLastCalledWith(false)
  })

  it('surfaces a refusal to record consent without pretending it was recorded', async () => {
    const { onConsentChange } = renderPersisted()
    await waitFor(() => expect(fetchMock).toHaveBeenCalled())
    fetchMock.mockImplementationOnce(async () =>
      jsonResponse(403, { detail: 'Not a member of this project' })
    )

    await userEvent.click(checkbox())

    await waitFor(() =>
      expect(screen.getByText('Not a member of this project')).toBeInTheDocument()
    )
    expect(checkbox()).not.toBeChecked()
    expect(onConsentChange).not.toHaveBeenCalledWith(true)
  })
})
