import Link from 'next/link';
import { cn } from '@/lib/utils';

export interface Crumb {
  label: string;
  href?: string;
}

/**
 * Breadcrumb trail that mirrors the URL/sidebar hierarchy. Ancestor crumbs
 * (those with an `href`) render as links; the final crumb is the current page
 * and renders muted, non-interactive. Long labels truncate so the row never
 * overflows on narrow viewports.
 */
export function Breadcrumb({ items, className }: { items: Crumb[]; className?: string }) {
  if (items.length === 0) return null;
  return (
    <nav
      aria-label="Breadcrumb"
      className={cn('flex flex-wrap items-center gap-1.5 text-sm text-muted-foreground', className)}
    >
      {items.map((item, i) => {
        const isLast = i === items.length - 1;
        return (
          <span key={`${item.label}-${i}`} className="flex items-center gap-1.5 min-w-0">
            {item.href && !isLast ? (
              <Link
                href={item.href}
                className="max-w-[12rem] truncate hover:text-foreground hover:underline"
                title={item.label}
              >
                {item.label}
              </Link>
            ) : (
              <span
                className={cn('max-w-[12rem] truncate', isLast && 'text-foreground')}
                title={item.label}
                aria-current={isLast ? 'page' : undefined}
              >
                {item.label}
              </span>
            )}
            {!isLast && <span aria-hidden className="text-muted-foreground/60">/</span>}
          </span>
        );
      })}
    </nav>
  );
}

/**
 * Standard page header used across the app so every screen shares one chrome:
 * a breadcrumb that reflects where you are, a consistent title, optional status
 * badge / description, and a right-aligned actions slot. Header row wraps on
 * small screens so the title and actions never clip.
 */
export function PageHeader({
  breadcrumbs,
  title,
  status,
  description,
  actions,
  className,
}: {
  breadcrumbs?: Crumb[];
  title: React.ReactNode;
  status?: React.ReactNode;
  description?: React.ReactNode;
  actions?: React.ReactNode;
  className?: string;
}) {
  return (
    <div className={cn('mb-6', className)}>
      {breadcrumbs && breadcrumbs.length > 0 && <Breadcrumb items={breadcrumbs} className="mb-2" />}
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <div className="flex items-center gap-3 flex-wrap">
            <h1 className="text-xl sm:text-2xl font-semibold text-foreground">{title}</h1>
            {status}
          </div>
          {description && <p className="text-sm text-muted-foreground mt-1">{description}</p>}
        </div>
        {actions && <div className="flex items-center gap-2 shrink-0">{actions}</div>}
      </div>
    </div>
  );
}
