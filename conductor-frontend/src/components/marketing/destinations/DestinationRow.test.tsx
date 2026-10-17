import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { DestinationRow } from './DestinationRow'
import type { DestinationRowModel } from './model'

function row(blockers: Array<{ code: string; message: string }>): DestinationRowModel {
  return {
    key: 'instagram:c1',
    option: { platform: 'instagram', label: '@acme', connectionId: 'c1' },
    checked: true,
    settled: false,
    unhealthy: false,
    manual: false,
    customized: false,
    blockers,
    warnings: [],
    effectiveAssets: [],
  } as unknown as DestinationRowModel
}

describe('DestinationRow findings', () => {
  it('renders an identical finding once and keeps first-seen order, without key warnings', () => {
    const error = vi.spyOn(console, 'error').mockImplementation(() => {})
    const dup = { code: 'MEDIA_SIZE', message: 'Slide is too tall' }
    render(
      <ul>
        <DestinationRow
          row={row([dup, { ...dup }, { code: 'OTHER', message: 'Other problem' }, { ...dup }, { ...dup }])}
          mode="outcome"
          expanded={false}
          hasDetails={false}
          detailsId="d"
          retrying={false}
          onToggle={() => {}}
          onExpand={() => {}}
          onRetry={() => {}}
          onMarkPublished={() => {}}
        />
      </ul>,
    )
    expect(screen.getAllByText('Slide is too tall')).toHaveLength(1)
    const texts = screen.getAllByText(/Slide is too tall|Other problem/).map((n) => n.textContent)
    expect(texts).toEqual(['Slide is too tall', 'Other problem'])
    expect(error.mock.calls.flat().join(' ')).not.toMatch(/same key/)
    error.mockRestore()
  })

  it('has no empty second band when a row has nothing to say beside its name', () => {
    render(
      <ul>
        <DestinationRow
          row={row([])}
          mode="outcome"
          expanded={false}
          hasDetails
          detailsId="d"
          retrying={false}
          onToggle={() => {}}
          onExpand={() => {}}
          onRetry={() => {}}
          onMarkPublished={() => {}}
        />
      </ul>,
    )
    expect(screen.queryByTestId('destination-meta')).not.toBeInTheDocument()
    expect(screen.getByText('@acme')).toHaveClass('truncate')
  })
})
