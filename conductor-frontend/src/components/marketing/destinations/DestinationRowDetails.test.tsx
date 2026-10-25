import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { tiktokOption, option } from '@/components/marketing/test-fixtures'
import { DestinationRowDetails } from './DestinationRowDetails'
import type { DestinationActions, DestinationRowModel } from './model'
import type { CarouselAs } from './types'

function row(platform: 'tiktok' | 'instagram', carouselAs: CarouselAs | null): DestinationRowModel {
  const opt = platform === 'tiktok' ? tiktokOption('c1') : option({ platform: 'instagram', connectionId: 'ig' })
  return {
    key: `${platform} x`,
    option: { ...opt, formats: ['feed'] },
    checked: true,
    settled: false,
    unhealthy: false,
    unavailable: false,
    manual: false,
    customized: false,
    hidden: false,
    format: 'feed',
    content: { captionOverride: null, assetIds: null },
    tiktokOptions: {
      privacyLevel: 'PUBLIC_TO_EVERYONE',
      disableComment: true,
      disableDuet: true,
      disableStitch: true,
      brandContentToggle: false,
      brandOrganicToggle: false,
      isAigc: false,
    },
    instagramOptions: {},
    youtubeOptions: {},
    carouselAs,
    effectiveAssets: [],
    blockers: [],
    warnings: [],
  } as unknown as DestinationRowModel
}

function setup(r: DestinationRowModel) {
  const actions = { setCarouselAs: vi.fn(), setFormat: vi.fn(), setTikTokOptions: vi.fn(), setContent: vi.fn() } as unknown as DestinationActions
  render(
    <DestinationRowDetails
      row={r}
      mode="pick"
      assets={[]}
      caption={null}
      saving={false}
      locked={false}
      completing={false}
      consentSaving={false}
      consentError={null}
      actions={actions}
      onCancelComplete={() => {}}
    />,
  )
  return actions
}

describe('DestinationRowDetails — Post as (music carousel)', () => {
  it('offers no choice when the target carries no carouselAs', () => {
    setup(row('tiktok', null))
    expect(screen.queryByLabelText('Post as')).not.toBeInTheDocument()
  })

  it('shows Photos / Video with music and reports a change', async () => {
    const actions = setup(row('instagram', 'photos'))
    const select = screen.getByLabelText('Post as') as HTMLSelectElement
    expect(select).toHaveValue('photos')
    expect(Array.from(select.options).map((o) => o.textContent)).toEqual(['Photos (no chosen music)', 'Video with music'])
    await userEvent.selectOptions(select, 'video')
    expect(actions.setCarouselAs).toHaveBeenCalledWith(expect.anything(), 'video')
  })

  it('keeps TikTok photo-only options while posting as photos', () => {
    setup(row('tiktok', 'photos'))
    expect(screen.getByRole('switch', { name: 'Add TikTok music automatically' })).toBeInTheDocument()
    expect(screen.getByLabelText('Cover image')).toBeInTheDocument()
  })

  it('hides TikTok auto-music and cover image when posting as video', () => {
    setup(row('tiktok', 'video'))
    expect(screen.getByLabelText('Post as')).toHaveValue('video')
    expect(screen.queryByRole('switch', { name: 'Add TikTok music automatically' })).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Cover image')).not.toBeInTheDocument()
  })
})
