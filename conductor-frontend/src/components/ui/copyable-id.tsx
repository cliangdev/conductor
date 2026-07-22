'use client';

import { useState } from 'react';
import { CopyIcon, CheckIcon } from 'lucide-react';
import { cn } from '@/lib/utils';
import { toastSuccess } from '@/components/ui/toast';

const DEFAULT_LENGTH = 8;

interface CopyableIdProps {
  id: string;
  /** Characters of `id` to show before the ellipsis. Defaults to 8 (short-hash convention). */
  length?: number;
  className?: string;
}

/**
 * A shortened, monospace ID (full value on hover via `title`) with a one-click copy affordance —
 * the whole control is the click target, not just the icon, and copying stops event propagation so
 * it's safe to drop into a clickable table row.
 */
export function CopyableId({ id, length = DEFAULT_LENGTH, className }: CopyableIdProps) {
  const [copied, setCopied] = useState(false);
  const short = id.length > length ? id.slice(0, length) : id;

  const handleCopy = (e: React.MouseEvent) => {
    e.stopPropagation();
    navigator.clipboard.writeText(id).then(() => {
      setCopied(true);
      toastSuccess('Copied to clipboard');
      setTimeout(() => setCopied(false), 1500);
    });
  };

  return (
    <button
      type="button"
      onClick={handleCopy}
      title={`${id} — click to copy`}
      aria-label={`Copy full ID ${id}`}
      className={cn(
        'group inline-flex items-center gap-1 rounded px-1 -mx-1 font-mono text-xs text-muted-foreground',
        'hover:bg-muted hover:text-foreground transition-colors',
        className
      )}
    >
      <span>{short}</span>
      {copied ? (
        <CheckIcon className="h-3 w-3 text-status-done" />
      ) : (
        <CopyIcon className="h-3 w-3 opacity-40 group-hover:opacity-100" />
      )}
    </button>
  );
}
