import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'

vi.mock('next/navigation', () => ({
  useParams: () => ({ projectId: 'proj-1', connectorId: 'gcp' }),
}))

vi.mock('@/components/integrations/WorkflowToolsPanel', () => ({
  default: ({ connectorId }: { connectorId: string }) => (
    <div data-testid="tools-panel">tools for {connectorId}</div>
  ),
}))

vi.mock('@/components/integrations/ConnectorDocsPanel', () => ({
  default: ({ connectorId }: { connectorId: string }) => (
    <div data-testid="docs-panel">docs for {connectorId}</div>
  ),
}))

import ConnectorLayout from './layout'

describe('ConnectorLayout', () => {
  it('shows the Documentation tab and breadcrumb label for gcp', () => {
    render(<ConnectorLayout>{'overview content'}</ConnectorLayout>)

    expect(screen.getByRole('button', { name: 'Documentation' })).toBeInTheDocument()
    expect(screen.getByText('Google Cloud')).toBeInTheDocument()
    expect(screen.getByText('overview content')).toBeInTheDocument()
    expect(screen.queryByTestId('docs-panel')).not.toBeInTheDocument()
  })

  it('renders ConnectorDocsPanel when the Documentation tab is clicked', () => {
    render(<ConnectorLayout>{'overview content'}</ConnectorLayout>)

    fireEvent.click(screen.getByRole('button', { name: 'Documentation' }))

    expect(screen.getByTestId('docs-panel')).toHaveTextContent('docs for gcp')
    expect(screen.queryByText('overview content')).not.toBeInTheDocument()
    expect(screen.queryByTestId('tools-panel')).not.toBeInTheDocument()
  })

  it('still renders the Tools tab alongside Documentation', () => {
    render(<ConnectorLayout>{'overview content'}</ConnectorLayout>)

    fireEvent.click(screen.getByRole('button', { name: 'Tools' }))

    expect(screen.getByTestId('tools-panel')).toHaveTextContent('tools for gcp')
  })
})
