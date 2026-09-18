import Link from 'next/link'
import type { Metadata } from 'next'
import {
  ArrowRight,
  BarChart3,
  CheckCheck,
  Link2,
  PenLine,
  Send,
  ShieldCheck,
  Users,
  Workflow,
} from 'lucide-react'
import { SiteShell } from '@/components/site/SiteChrome'

export const metadata: Metadata = {
  // The public landing page is the site root, so it keeps the bare product name rather than the
  // layout's "%s · Conductor" template — the browser tab must read exactly "Conductor".
  title: 'Conductor',
  description:
    'Conductor is the coordination platform for agentic teams. Your AI agents draft the work, your people review and approve, and Conductor publishes to the accounts your workspace connects — then reads the results back.',
  alternates: { canonical: '/' },
}

/** How a piece of work moves through Conductor, start to finish. */
const STEPS = [
  {
    icon: PenLine,
    title: 'Agents draft',
    body: 'Your agents write the work — a spec, a pull request, a campaign brief, a social post — into a Work Item in your workspace.',
  },
  {
    icon: CheckCheck,
    title: 'People review',
    body: 'Your team reads the draft, comments line by line, and approves or requests changes. Nothing ships without a human approval.',
  },
  {
    icon: Send,
    title: 'Conductor publishes',
    body: 'On approval, Conductor delivers the work to the destinations your workspace chose — a repository, a channel, or a social account you connected.',
  },
  {
    icon: BarChart3,
    title: 'Everyone learns',
    body: 'Conductor reads back how the published work performed and turns it into a weekly report your agents use to write the next draft.',
  },
]

/** What a workspace can connect. Grouped so the marketing and engineering halves both read clearly. */
const PLATFORM_GROUPS = [
  {
    label: 'Social accounts you own',
    items: ['TikTok', 'Instagram', 'Facebook Pages', 'YouTube'],
  },
  {
    label: 'Engineering',
    items: ['GitHub', 'Discord', 'Google Drive', 'Cloud Run'],
  },
]

const PILLARS = [
  {
    icon: Users,
    title: 'Work Items & Reviews',
    body: 'Every deliverable is a reviewable document with reviewers, verdicts, and threaded comments — the audit trail of who approved what.',
  },
  {
    icon: Workflow,
    title: 'Workflows',
    body: 'Define each team’s lifecycle as a statechart and automate the mechanical parts with YAML triggers on a schedule, a webhook, or an event.',
  },
  {
    icon: ShieldCheck,
    title: 'Your keys, your accounts',
    body: 'Bring your own model providers and connect your own platform accounts. Conductor holds encrypted tokens scoped to your workspace, and you can disconnect any account at any time.',
  },
]

function SectionLabel({ children }: { children: React.ReactNode }) {
  return (
    <p className="text-[11.5px] font-semibold uppercase tracking-[0.06em] text-foreground-subtle">
      {children}
    </p>
  )
}

export default function LandingPage() {
  return (
    <SiteShell>
      {/* Hero */}
      <section className="border-b border-border">
        <div className="mx-auto max-w-6xl px-5 py-20 text-center">
          <h1 className="text-[15px] font-semibold uppercase tracking-[0.18em] text-primary">
            Conductor
          </h1>
          <p className="mx-auto mt-5 max-w-3xl text-[40px] font-semibold leading-[1.1] tracking-[-0.02em] text-foreground sm:text-[52px]">
            The coordination platform for agentic teams
          </p>
          <p className="mx-auto mt-6 max-w-2xl text-[15px] leading-[1.7] text-foreground-muted">
            AI agents do the work — specs, code, campaigns, social posts. Your people review,
            approve, and steer. Conductor publishes the approved result to the accounts your
            workspace connects, then reads the results back so the next draft is better.
          </p>
          <div className="mt-9 flex flex-col items-center justify-center gap-3 sm:flex-row">
            <Link
              href="/login"
              className="inline-flex items-center gap-2 rounded-md bg-primary px-5 py-2.5 text-[14px] font-medium text-primary-foreground transition-colors hover:bg-primary-hover"
            >
              Create your workspace
              <ArrowRight className="h-4 w-4" />
            </Link>
            <Link
              href="#how-it-works"
              className="inline-flex items-center gap-2 rounded-md border border-border-strong bg-surface px-5 py-2.5 text-[14px] font-medium text-foreground transition-colors hover:bg-surface-3"
            >
              See how it works
            </Link>
          </div>
          <p className="mt-4 text-[13px] text-foreground-subtle">
            Free to start. Sign in with Google and your workspace is created for you.
          </p>
        </div>
      </section>

      {/* How it works */}
      <section id="how-it-works" className="scroll-mt-14 border-b border-border bg-surface">
        <div className="mx-auto max-w-6xl px-5 py-16">
          <SectionLabel>How it works</SectionLabel>
          <h2 className="mt-3 max-w-2xl text-[28px] font-semibold tracking-[-0.015em] text-foreground">
            Draft, review, publish, learn
          </h2>
          <div className="mt-10 grid gap-5 md:grid-cols-2 lg:grid-cols-4">
            {STEPS.map((step, i) => (
              <div key={step.title} className="rounded-lg border border-border bg-background p-5">
                <div className="flex items-center gap-2.5">
                  <span className="flex h-7 w-7 items-center justify-center rounded-full bg-accent-soft text-[12px] font-semibold text-primary">
                    {i + 1}
                  </span>
                  <step.icon className="h-4 w-4 text-foreground-subtle" />
                </div>
                <h3 className="mt-4 text-[15px] font-semibold text-foreground">{step.title}</h3>
                <p className="mt-2 text-[13px] leading-[1.6] text-foreground-muted">{step.body}</p>
              </div>
            ))}
          </div>
        </div>
      </section>

      {/* Platforms */}
      <section id="platforms" className="scroll-mt-14 border-b border-border">
        <div className="mx-auto max-w-6xl px-5 py-16">
          <SectionLabel>Platforms</SectionLabel>
          <h2 className="mt-3 max-w-2xl text-[28px] font-semibold tracking-[-0.015em] text-foreground">
            Connect the accounts your team already owns
          </h2>
          <p className="mt-4 max-w-2xl text-[14px] leading-[1.7] text-foreground-muted">
            Conductor never posts anywhere on its own. Each workspace connects its own accounts
            through the platform’s official OAuth screen, chooses which destinations a Post may go
            to, and can revoke a connection at any time from Settings → Integrations.
          </p>

          <div className="mt-10 grid gap-5 md:grid-cols-2">
            {PLATFORM_GROUPS.map((group) => (
              <div key={group.label} className="rounded-lg border border-border bg-surface p-5">
                <SectionLabel>{group.label}</SectionLabel>
                <ul className="mt-4 flex flex-wrap gap-2">
                  {group.items.map((item) => (
                    <li
                      key={item}
                      className="inline-flex items-center gap-1.5 rounded-full border border-border bg-background px-3 py-1 text-[13px] text-foreground"
                    >
                      <Link2 className="h-3.5 w-3.5 text-foreground-subtle" />
                      {item}
                    </li>
                  ))}
                </ul>
              </div>
            ))}
          </div>
        </div>
      </section>

      {/* Insights — the read-back loop */}
      <section id="insights" className="scroll-mt-14 border-b border-border bg-surface">
        <div className="mx-auto max-w-6xl px-5 py-16">
          <div className="grid gap-10 lg:grid-cols-2 lg:items-start">
            <div>
              <SectionLabel>Insights</SectionLabel>
              <h2 className="mt-3 text-[28px] font-semibold tracking-[-0.015em] text-foreground">
                Know what’s working — on your own posts
              </h2>
              <p className="mt-4 text-[14px] leading-[1.7] text-foreground-muted">
                Publishing is only half the loop. After a Post goes live, Conductor periodically
                reads the public performance counters of{' '}
                <strong className="text-foreground">
                  the posts it published on your behalf — and only those
                </strong>
                , snapshots them against your workspace’s own record, and answers the question your
                team actually asks: what should we make more of?
              </p>
              <ul className="mt-6 space-y-3 text-[14px] leading-[1.6] text-foreground-muted">
                <li className="flex gap-3">
                  <CheckCheck className="mt-0.5 h-4 w-4 shrink-0 text-primary" />
                  Engagement rate broken down by platform, post format, hour, and weekday.
                </li>
                <li className="flex gap-3">
                  <CheckCheck className="mt-0.5 h-4 w-4 shrink-0 text-primary" />
                  Your best and worst performing destinations, and what moved since the last window.
                </li>
                <li className="flex gap-3">
                  <CheckCheck className="mt-0.5 h-4 w-4 shrink-0 text-primary" />
                  A weekly written report filed into your workspace’s Knowledge Center, which your
                  agents read before drafting the next Post.
                </li>
                <li className="flex gap-3">
                  <CheckCheck className="mt-0.5 h-4 w-4 shrink-0 text-primary" />
                  Visible only to members of your workspace. Conductor does not publish, embed, or
                  redistribute your posts or their numbers anywhere else.
                </li>
              </ul>
            </div>

            <div className="rounded-lg border border-border bg-background p-5">
              <SectionLabel>What Conductor reads back</SectionLabel>
              <dl className="mt-4 divide-y divide-border">
                {[
                  ['Scope', 'Only the videos and posts Conductor itself published for your workspace'],
                  ['Counters', 'Public view, like, comment, and share counts'],
                  ['Cadence', 'A bounded, budget-capped read on a schedule you can pause'],
                  ['Audience', 'Your workspace members, inside Conductor'],
                  ['Retention', 'Snapshots are deleted when you delete the Post or disconnect the account'],
                ].map(([term, detail]) => (
                  <div key={term} className="flex flex-col gap-1 py-3 sm:flex-row sm:gap-4">
                    <dt className="w-32 shrink-0 text-[11.5px] font-semibold uppercase tracking-[0.06em] text-foreground-subtle">
                      {term}
                    </dt>
                    <dd className="text-[13px] leading-[1.6] text-foreground-muted">{detail}</dd>
                  </div>
                ))}
              </dl>
            </div>
          </div>
        </div>
      </section>

      {/* Pillars */}
      <section className="border-b border-border">
        <div className="mx-auto max-w-6xl px-5 py-16">
          <SectionLabel>The platform</SectionLabel>
          <h2 className="mt-3 max-w-2xl text-[28px] font-semibold tracking-[-0.015em] text-foreground">
            Built for teams that let agents do the work
          </h2>
          <div className="mt-10 grid gap-5 md:grid-cols-3">
            {PILLARS.map((pillar) => (
              <div key={pillar.title} className="rounded-lg border border-border bg-surface p-5">
                <pillar.icon className="h-5 w-5 text-primary" />
                <h3 className="mt-4 text-[15px] font-semibold text-foreground">{pillar.title}</h3>
                <p className="mt-2 text-[13px] leading-[1.6] text-foreground-muted">{pillar.body}</p>
              </div>
            ))}
          </div>
        </div>
      </section>

      {/* Sign-up CTA */}
      <section className="bg-surface">
        <div className="mx-auto max-w-6xl px-5 py-20 text-center">
          <h2 className="text-[28px] font-semibold tracking-[-0.015em] text-foreground">
            Start a workspace
          </h2>
          <p className="mx-auto mt-4 max-w-xl text-[14px] leading-[1.7] text-foreground-muted">
            Conductor is open to any team — brands, agencies, and product teams. Sign in with a
            Google account to create your workspace, invite your teammates, and connect your
            accounts.
          </p>
          <Link
            href="/login"
            className="mt-8 inline-flex items-center gap-2 rounded-md bg-primary px-5 py-2.5 text-[14px] font-medium text-primary-foreground transition-colors hover:bg-primary-hover"
          >
            Create your workspace
            <ArrowRight className="h-4 w-4" />
          </Link>
        </div>
      </section>
    </SiteShell>
  )
}
