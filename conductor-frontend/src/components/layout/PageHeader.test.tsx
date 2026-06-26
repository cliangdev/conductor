import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { PageHeader, Breadcrumb } from './PageHeader';

describe('Breadcrumb', () => {
  it('renders ancestors as links and the current page as plain text', () => {
    render(
      <Breadcrumb
        items={[
          { label: 'Workflows', href: '/workflows' },
          { label: 'Ping Test', href: '/workflows/1' },
          { label: 'Run History' },
        ]}
      />
    );

    const workflows = screen.getByRole('link', { name: 'Workflows' });
    expect(workflows).toHaveAttribute('href', '/workflows');
    expect(screen.getByRole('link', { name: 'Ping Test' })).toBeInTheDocument();

    // The final crumb is the current page — not a link.
    expect(screen.queryByRole('link', { name: 'Run History' })).toBeNull();
    const current = screen.getByText('Run History');
    expect(current).toHaveAttribute('aria-current', 'page');
  });

  it('renders nothing when there are no items', () => {
    const { container } = render(<Breadcrumb items={[]} />);
    expect(container).toBeEmptyDOMElement();
  });
});

describe('PageHeader', () => {
  it('renders the title, description and actions', () => {
    render(
      <PageHeader
        breadcrumbs={[{ label: 'Workflows', href: '/workflows' }, { label: 'Run History' }]}
        title="Run History"
        description="All runs"
        actions={<button>Run Now</button>}
      />
    );

    expect(screen.getByRole('heading', { name: 'Run History' })).toBeInTheDocument();
    expect(screen.getByText('All runs')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Run Now' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Workflows' })).toHaveAttribute('href', '/workflows');
  });
});
