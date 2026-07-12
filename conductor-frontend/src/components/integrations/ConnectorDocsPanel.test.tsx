import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'

vi.mock('@/components/markdown/MarkdownRenderer', () => ({
  MarkdownRenderer: ({ content }: { content: string }) => <div data-testid="markdown">{content}</div>,
}))

import ConnectorDocsPanel from './ConnectorDocsPanel'

describe('ConnectorDocsPanel', () => {
  it('renders the gcp doc with its key headings', () => {
    render(<ConnectorDocsPanel connectorId="gcp" />)

    const markdown = screen.getByTestId('markdown').textContent ?? ''
    expect(markdown).toContain('## What it does')
    expect(markdown).toContain('## How authentication works')
    expect(markdown).toContain('## Runtime targets')
    expect(markdown).toContain('## How a claude-code step executes')
    expect(markdown).toContain('## Using it in workflows')
  })

  it('shows a fallback message for an unknown connector', () => {
    render(<ConnectorDocsPanel connectorId="not-a-real-connector" />)

    expect(screen.getByText('No documentation available for this integration.')).toBeInTheDocument()
    expect(screen.queryByTestId('markdown')).not.toBeInTheDocument()
  })
})
