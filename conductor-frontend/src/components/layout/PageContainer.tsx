import { cn } from '@/lib/utils';

export type PageWidth = 'narrow' | 'default' | 'wide';

/**
 * Page max-width tiers, chosen by content type (industry best practice: content
 * width follows content, not a single arbitrary cap):
 *  - narrow  — forms / settings (readable line length)
 *  - default — card grids and most content
 *  - wide    — dense data tables and lists that benefit from horizontal space
 */
const WIDTHS: Record<PageWidth, string> = {
  narrow: 'max-w-2xl',
  default: 'max-w-4xl',
  wide: 'max-w-7xl',
};

/**
 * Standard page shell used across the app so every screen shares one responsive
 * container: centered, capped width (per `width`), consistent gutters that step
 * up by breakpoint (16 → 24 → 32px), and a single vertical rhythm. Pages render
 * their `PageHeader` + content inside this and never re-invent the wrapper.
 */
export function PageContainer({
  children,
  width = 'default',
  className,
}: {
  children: React.ReactNode;
  width?: PageWidth;
  className?: string;
}) {
  return (
    <div className={cn('mx-auto w-full px-4 sm:px-6 lg:px-8 py-8', WIDTHS[width], className)}>
      {children}
    </div>
  );
}
