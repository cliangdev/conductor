import Link from 'next/link'

/**
 * Chrome for the public marketing pages (`/`, `/privacy`, `/terms`) — the surfaces a visitor, or a
 * platform's app reviewer, sees before signing in.
 *
 * Deliberately not `PageContainer`/`PageHeader`: those carry the signed-in app's breadcrumb/status
 * chrome and assume a workspace. This is the same token set, a different shell.
 */

/** Where review-facing contact links point. */
export const CONTACT_EMAIL = 'support@rexipe.io'

/** Nav rendered in the header of every public page. */
const NAV = [
  { href: '/#how-it-works', label: 'How it works' },
  { href: '/#platforms', label: 'Platforms' },
  { href: '/#insights', label: 'Insights' },
]

export function SiteHeader() {
  return (
    <header className="sticky top-0 z-20 border-b border-border bg-background/95 backdrop-blur">
      <div className="mx-auto flex h-14 max-w-6xl items-center justify-between gap-6 px-5">
        <Link href="/" className="text-[15px] font-semibold tracking-[-0.015em] text-foreground">
          Conductor
        </Link>
        <nav className="hidden items-center gap-6 md:flex">
          {NAV.map((item) => (
            <Link
              key={item.href}
              href={item.href}
              className="text-[13px] text-foreground-muted transition-colors hover:text-foreground"
            >
              {item.label}
            </Link>
          ))}
        </nav>
        <Link
          href="/login"
          className="rounded-md border border-border-strong bg-surface px-3 py-1.5 text-[13px] font-medium text-foreground transition-colors hover:bg-surface-3"
        >
          Sign in
        </Link>
      </div>
    </header>
  )
}

export function SiteFooter() {
  return (
    <footer className="border-t border-border bg-surface">
      <div className="mx-auto flex max-w-6xl flex-col gap-4 px-5 py-8 text-[13px] text-foreground-muted sm:flex-row sm:items-center sm:justify-between">
        <p>© {new Date().getFullYear()} Conductor. All rights reserved.</p>
        <nav className="flex items-center gap-5">
          <Link href="/privacy" className="transition-colors hover:text-foreground">
            Privacy Policy
          </Link>
          <Link href="/terms" className="transition-colors hover:text-foreground">
            Terms of Service
          </Link>
          <a href={`mailto:${CONTACT_EMAIL}`} className="transition-colors hover:text-foreground">
            Contact
          </a>
        </nav>
      </div>
    </footer>
  )
}

/** Header + footer wrapper. `children` is the page body. */
export function SiteShell({ children }: { children: React.ReactNode }) {
  return (
    <div className="flex min-h-screen flex-col bg-background">
      <SiteHeader />
      <main className="flex-1">{children}</main>
      <SiteFooter />
    </div>
  )
}

/**
 * The reading shell for `/privacy` and `/terms`: a ~720px column per the design system's
 * long-form cap, with a title and a last-updated line.
 */
export function LegalPage({
  title,
  updated,
  children,
}: {
  title: string
  updated: string
  children: React.ReactNode
}) {
  return (
    <SiteShell>
      <article className="mx-auto max-w-[45rem] px-5 py-12">
        <h1 className="text-[28px] font-semibold tracking-[-0.015em] text-foreground">{title}</h1>
        <p className="mt-2 text-[13px] text-foreground-subtle">Last updated {updated}</p>
        <div className="prose prose-sm mt-8 max-w-none text-foreground-muted prose-headings:text-foreground prose-headings:font-semibold prose-headings:tracking-[-0.01em] prose-a:text-primary prose-strong:text-foreground">
          {children}
        </div>
      </article>
    </SiteShell>
  )
}
