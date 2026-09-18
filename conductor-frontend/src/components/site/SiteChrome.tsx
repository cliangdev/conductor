import Link from 'next/link'
import { ConductorLogo } from '@/components/brand/ConductorLogo'

/**
 * Chrome for the public marketing pages (`/`, `/privacy`, `/terms`), which is what a visitor or a
 * platform's app reviewer sees before signing in.
 *
 * Deliberately not `PageContainer`/`PageHeader`: those carry the signed-in app's breadcrumb and
 * status chrome, and they assume a workspace. This is the same token set in a different shell.
 */

/** Where review-facing contact links point. */
export const CONTACT_EMAIL = 'support@rexipe.io'

/** The public repository. Conductor's source is readable by anyone. */
export const REPO_URL = 'https://github.com/cliangdev/conductor'

/**
 * How the licence is described in public copy.
 *
 * PolyForm Noncommercial is source-available, not OSI open source: commercial use needs a separate
 * licence (see the repo's LICENSE and README). So the copy says "source available" and names the
 * licence, rather than claiming "open source" on pages a platform app reviewer reads.
 */
export const LICENSE_NAME = 'PolyForm Noncommercial 1.0.0'
export const LICENSE_URL = `${REPO_URL}/blob/main/LICENSE`

/** Nav rendered in the header of every public page. */
const NAV = [
  { href: '/#how-it-works', label: 'How it works' },
  { href: '/#platforms', label: 'Platforms' },
  { href: '/#insights', label: 'Insights' },
  { href: '/#source', label: 'Source' },
]

/**
 * The GitHub mark. Inline rather than from lucide, which dropped its brand icons, and the same
 * approach `AuthCard`'s `GoogleGlyph` already takes for the Google mark.
 */
export function GithubGlyph({ className }: { className?: string }) {
  return (
    <svg viewBox="0 0 16 16" aria-hidden="true" fill="currentColor" className={className}>
      <path d="M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38 0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15-.07-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82.64-.18 1.32-.27 2-.27.68 0 1.36.09 2 .27 1.53-1.04 2.2-.82 2.2-.82.44 1.1.15 1.92.08 2.12.51.56.82 1.27.82 2.15 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 0 .21.15.46.55.38A7.995 7.995 0 0 0 16 8c0-4.42-3.58-8-8-8Z" />
    </svg>
  )
}

export function SiteHeader() {
  return (
    <header className="sticky top-0 z-20 border-b border-border bg-background/95 backdrop-blur">
      <div className="mx-auto flex h-14 max-w-6xl items-center justify-between gap-6 px-5">
        <Link href="/" aria-label="Conductor home">
          <ConductorLogo size="md" />
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
        <div className="flex items-center gap-2">
          <a
            href={REPO_URL}
            target="_blank"
            rel="noreferrer"
            className="inline-flex items-center gap-1.5 rounded-md px-2 py-1.5 text-[13px] text-foreground-muted transition-colors hover:bg-surface-3 hover:text-foreground"
          >
            <GithubGlyph className="h-4 w-4" />
            <span className="hidden sm:inline">GitHub</span>
          </a>
          <Link
            href="/login"
            className="rounded-md border border-border-strong bg-surface px-3 py-1.5 text-[13px] font-medium text-foreground transition-colors hover:bg-surface-3"
          >
            Sign in
          </Link>
        </div>
      </div>
    </header>
  )
}

export function SiteFooter() {
  return (
    <footer className="border-t border-border bg-surface">
      <div className="mx-auto flex max-w-6xl flex-col gap-4 px-5 py-8 text-[13px] text-foreground-muted sm:flex-row sm:items-center sm:justify-between">
        <div className="space-y-1">
          <p>© {new Date().getFullYear()} Conductor. All rights reserved.</p>
          <p>
            Source available on{' '}
            <a
              href={REPO_URL}
              target="_blank"
              rel="noreferrer"
              className="transition-colors hover:text-foreground"
            >
              GitHub
            </a>{' '}
            under the{' '}
            <a
              href={LICENSE_URL}
              target="_blank"
              rel="noreferrer"
              className="transition-colors hover:text-foreground"
            >
              {LICENSE_NAME}
            </a>{' '}
            licence.
          </p>
        </div>
        <nav className="flex flex-wrap items-center gap-x-5 gap-y-2">
          <a
            href={REPO_URL}
            target="_blank"
            rel="noreferrer"
            className="inline-flex items-center gap-1.5 transition-colors hover:text-foreground"
          >
            <GithubGlyph className="h-3.5 w-3.5" />
            GitHub
          </a>
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
 * The reading shell for `/privacy` and `/terms`: a 720px column, matching the design system's
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
