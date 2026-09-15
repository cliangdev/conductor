import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { EMPTY_TIKTOK_OPTIONS, type TikTokPublishOptionValues } from './TikTokPublishOptions'
import {
  TikTokConsentCheckbox,
  TikTokConsentPreview,
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
    // The copy that will actually go out to this account, which is what consent is about.
    caption: 'Acme launches the thing',
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

describe('TikTokConsentPreview', () => {
  it('names the destination account unmistakably, or the connection label when TikTok reported no nickname', () => {
    render(<TikTokConsentPreview target={target()} assets={[IMAGE]} />)
    expect(screen.getByText(/you are posting to/i)).toBeInTheDocument()
    expect(screen.getByText('@acme_official')).toBeInTheDocument()
    render(<TikTokConsentPreview target={target({ creatorNickname: null })} assets={[IMAGE]} />)
    expect(screen.getByText('Acme on TikTok')).toBeInTheDocument()
  })

  it('shows the content that will be uploaded', () => {
    render(<TikTokConsentPreview target={target()} assets={[IMAGE, VIDEO]} />)
    expect(screen.getByAltText('Launch teaser')).toHaveAttribute('src', IMAGE.previewUrl)
    expect(screen.getByLabelText('Launch cut')).toHaveAttribute('src', VIDEO.previewUrl)
    expect(screen.getByText('Acme launches the thing')).toBeInTheDocument()
  })

  it('previews only the media that goes to this account, not the whole Post', () => {
    // This destination publishes the video alone; the image goes somewhere else.
    render(<TikTokConsentPreview target={target({ assetIds: [VIDEO.id], caption: 'The TikTok cut' })} assets={[IMAGE, VIDEO]} />)
    expect(screen.getByLabelText('Launch cut')).toBeInTheDocument()
    expect(screen.queryByAltText('Launch teaser')).not.toBeInTheDocument()
    expect(screen.getByText('The TikTok cut')).toBeInTheDocument()
  })

  it('says so rather than showing an empty frame when nothing has been uploaded', () => {
    render(<TikTokConsentPreview target={target()} assets={[]} />)
    expect(screen.getByText(/no media/i)).toBeInTheDocument()
  })

  it('summarises the options the post will carry', () => {
    render(<TikTokConsentPreview target={target({ options: options({ disableComment: true, brandContentToggle: true }) })} assets={[IMAGE]} />)
    expect(screen.getByText(/Everyone/)).toBeInTheDocument()
    expect(screen.getByText(/Comments off/)).toBeInTheDocument()
    expect(screen.getByText(/Paid partnership/)).toBeInTheDocument()
  })
})

describe('TikTokConsentCheckbox', () => {
  function renderBox(props: Partial<React.ComponentProps<typeof TikTokConsentCheckbox>> = {}) {
    const onChange = vi.fn()
    render(<TikTokConsentCheckbox given={false} unresolved={false} anyPaidPartnership={false} onChange={onChange} {...props} />)
    return { onChange }
  }

  it('records an explicit consent action, and its withdrawal', async () => {
    const { onChange } = renderBox()
    await userEvent.click(screen.getByRole('checkbox', { name: /consent/i }))
    expect(onChange).toHaveBeenCalledWith(true)
    const second = renderBox({ given: true })
    await userEvent.click(screen.getAllByRole('checkbox', { name: /consent/i })[1])
    expect(second.onChange).toHaveBeenCalledWith(false)
  })

  it('will not take consent while a target still has an unresolved problem, and says why', () => {
    renderBox({ unresolved: true })
    expect(screen.getByRole('checkbox', { name: /consent/i })).toBeDisabled()
    expect(screen.getByText(/Resolve the option problem/)).toBeInTheDocument()
  })

  it('says who consented and when, and the TikTok policies the creator is agreeing to', () => {
    renderBox({ given: true, consentedAt: '2026-09-11T10:00:00Z', consentedByName: 'Bryan', anyPaidPartnership: true })
    expect(screen.getByText(/Consented by Bryan on/)).toBeInTheDocument()
    expect(screen.getByText(/Branded Content Policy/)).toBeInTheDocument()
    expect(screen.getByText(/Music Usage Confirmation/)).toBeInTheDocument()
  })

  it('shows a recording failure without pretending it was recorded', () => {
    renderBox({ error: 'Could not record your TikTok consent.' })
    expect(screen.getByRole('alert')).toHaveTextContent('Could not record your TikTok consent.')
    expect(screen.getByRole('checkbox', { name: /consent/i })).not.toBeChecked()
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
