import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { EMPTY_TIKTOK_OPTIONS, type TikTokPublishOptionValues } from './TikTokPublishOptions'
import {
  TikTokConsentStep,
  TikTokPublishGateProvider,
  tiktokSubmissionBlockedReason,
  useTikTokPublishGate,
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
