'use client';

import type { ReactNode } from 'react';
import Link from 'next/link';
import { cn } from '@/lib/utils';

interface ConnectorCardProps {
  icon: ReactNode;
  name: string;
  description: string;
  /** Right-aligned content — a status pill, an action label, or nothing for a static card. */
  trailing?: ReactNode;
  /** Renders as a Link when set. */
  href?: string;
  /** Renders as a button when set (and no href). Neither prop renders a static, non-interactive card. */
  onClick?: () => void;
  /**
   * One short line explaining why this connector can't be connected yet — e.g. no platform app
   * credential behind its consent flow. Shown under the description in any of the three variants,
   * so a card that routes to the fix can still say what's wrong.
   */
  unavailableReason?: string;
}

/**
 * The one card shell for the integrations browse grid. Renders as a link, a button, or a
 * static div depending on whether the connector is navigable or connectable, while keeping
 * the three variants visually identical.
 *
 * A connector that can't be connected yet is given `href` (pointing at the page that fixes it)
 * rather than a connect `onClick`, so the unavailable affordance isn't merely styled off — it is
 * structurally absent, and no click can reach a flow that would fail.
 */
export function ConnectorCard({
  icon,
  name,
  description,
  trailing,
  href,
  onClick,
  unavailableReason,
}: ConnectorCardProps) {
  const interactive = Boolean(href || onClick);
  const className = cn(
    'bg-card rounded-lg border border-border p-4 flex items-center gap-4',
    interactive && 'hover:border-primary/50 transition-colors'
  );

  const content = (
    <>
      {icon}
      <div className="flex-1 min-w-0">
        <div className="font-medium text-sm text-foreground">{name}</div>
        <div className="text-xs text-muted-foreground truncate">{description}</div>
        {unavailableReason && (
          <div className="text-xs text-status-progress mt-0.5">{unavailableReason}</div>
        )}
      </div>
      {trailing}
    </>
  );

  if (href) {
    return (
      <Link href={href} className={className}>
        {content}
      </Link>
    );
  }

  if (onClick) {
    return (
      <button onClick={onClick} className={cn(className, 'text-left w-full')}>
        {content}
      </button>
    );
  }

  return <div className={className}>{content}</div>;
}
