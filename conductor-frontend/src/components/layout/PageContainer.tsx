import { cn } from '@/lib/utils';

/**
 * Standard page shell used across the app so every screen shares one identical
 * responsive container: same max-width, same gutters that step up by breakpoint
 * (16 → 24 → 32px), and the same vertical rhythm. Every page renders its
 * `PageHeader` + content inside this and never re-invents the wrapper, so all
 * pages line up at the same width and horizontal position.
 *
 * Content that needs a narrower measure (e.g. a settings form's input column)
 * constrains itself *inside* this container — the page frame stays uniform.
 */
export function PageContainer({
  children,
  className,
}: {
  children: React.ReactNode;
  className?: string;
}) {
  return (
    <div className={cn('mx-auto w-full max-w-6xl px-4 sm:px-6 lg:px-8 py-8', className)}>
      {children}
    </div>
  );
}
