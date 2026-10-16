import { describe, expect, it } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import { StageChart, productStages } from './StageChart'
import { metric, productChannel } from './test-fixtures'

describe('StageChart', () => {
  it('lists only the stages, in funnel order, with the share of the previous stage', () => {
    render(<StageChart metrics={productChannel().metrics} />)
    const items = within(screen.getByRole('region', { name: 'Stages' })).getAllByRole('listitem')
    expect(items.map((li) => li.getAttribute('aria-label'))).toEqual([
      'First opens',
      'Onboarding started',
      'Trials started',
      'Paid',
    ])
    expect(within(items[1]).getByText('62.2% of first opens')).toBeInTheDocument()
    expect(within(items[0]).queryByText(/% of/)).not.toBeInTheDocument()
  })

  it('flags provisional and small-sample stages', () => {
    render(<StageChart metrics={productChannel().metrics} />)
    const items = within(screen.getByRole('region', { name: 'Stages' })).getAllByRole('listitem')
    expect(within(items[2]).getByText('Provisional')).toBeInTheDocument()
    expect(within(items[3]).getByText('Small sample')).toBeInTheDocument()
  })

  it('renders nothing when no metric is a stage', () => {
    const { container } = render(<StageChart metrics={[metric()]} />)
    expect(container).toBeEmptyDOMElement()
    expect(productStages([metric()])).toEqual([])
  })
})
