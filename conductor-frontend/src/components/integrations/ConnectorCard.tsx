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
   * Withholds the link/button entirely — for a connector that can't be connected yet (e.g. no
   * platform app credential), where offering the affordance would only produce a server error.
   */
  disabled?: boolean;
  /** One short line explaining why it's unavailable. Shown under the description when `disabled`. */
  disabledReason?: string;
}

/**
 * The one card shell for the integrations browse grid. Renders as a link, a button, or a
 * static div depending on whether the connector is navigable or connectable, while keeping
 * the three variants visually identical.
 */
export function ConnectorCard({
  icon,
  name,
  description,
  trailing,
  href,
  onClick,
  disabled,
  disabledReason,
}: ConnectorCardProps) {
  const interactive = Boolean(href || onClick) && !disabled;
  const className = cn(
    'bg-card rounded-lg border border-border p-4 flex items-center gap-4',
    interactive && 'hover:border-primary/50 transition-colors',
    disabled && 'opacity-70'
  );

  const content = (
    <>
      {icon}
      <div className="flex-1 min-w-0">
        <div className="font-medium text-sm text-foreground">{name}</div>
        <div className="text-xs text-muted-foreground truncate">{description}</div>
        {disabled && disabledReason && (
          <div className="text-xs text-status-progress mt-0.5">{disabledReason}</div>
        )}
      </div>
      {trailing}
    </>
  );

  if (disabled) {
    return (
      <div className={className} aria-disabled="true">
        {content}
      </div>
    );
  }

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
