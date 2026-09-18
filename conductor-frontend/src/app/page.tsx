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
import {
  GithubGlyph,
  LICENSE_NAME,
  LICENSE_URL,
  REPO_URL,
  SiteShell,
} from '@/components/site/SiteChrome'

export const metadata: Metadata = {
  // The public landing page is the site root, so it keeps the bare product name rather than the
  // layout's "%s · Conductor" template. The browser tab has to read exactly "Conductor".
  title: 'Conductor',
  description:
    'Conductor is a coordination platform for teams that work with AI agents. Agents write the drafts, your people approve them, and Conductor publishes to the accounts your workspace has connected.',
  alternates: { canonical: '/' },
}

/** How a piece of work moves through Conductor, start to finish. */
const STEPS = [
  {
    icon: PenLine,
    title: 'An agent drafts',
    body: 'One of your agents writes a spec, a pull request, a campaign brief or a social post into a Work Item in your workspace.',
  },
  {
    icon: CheckCheck,
    title: 'Your team reviews',
    body: 'Someone reads the draft, comments on it line by line, then approves it or asks for changes. Nothing ships until a person approves it.',
  },
  {
    icon: Send,
    title: 'Conductor publishes',
    body: 'Once the draft is approved, Conductor sends it where your workspace told it to go: a repository, a chat channel, or a social account you connected.',
  },
  {
    icon: BarChart3,
    title: 'Everyone sees how it did',
    body: 'Conductor checks how the published work performed and writes up a weekly summary. Your agents read that summary before they draft again.',
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
    title: 'Work Items and Reviews',
    body: 'Every deliverable is a document someone can review, with named reviewers, verdicts and threaded comments. You can always tell who approved what.',
  },
  {
    icon: Workflow,
    title: 'Workflows',
    body: 'Describe each team’s lifecycle as a statechart, then automate the mechanical parts with YAML triggers that fire on a schedule, a webhook or an event.',
  },
  {
    icon: ShieldCheck,
    title: 'Your keys, your accounts',
    body: 'Bring your own model provider keys and connect your own platform accounts. Conductor stores the tokens encrypted and scoped to your workspace, and you can disconnect any account whenever you want.',
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
            A coordination platform for teams that work with AI agents
          </p>
          <p className="mx-auto mt-6 max-w-2xl text-[15px] leading-[1.7] text-foreground-muted">
            Your agents write the specs, the code, the campaigns and the social posts. Your team
            reviews them and decides what ships. Conductor publishes what was approved to the
            accounts your workspace has connected, then reports back on how it did.
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
          <a
            href={REPO_URL}
            target="_blank"
            rel="noreferrer"
            className="mt-5 inline-flex items-center gap-2 rounded-full border border-border bg-surface px-3.5 py-1.5 text-[13px] text-foreground-muted transition-colors hover:bg-surface-3 hover:text-foreground"
          >
            <GithubGlyph className="h-3.5 w-3.5" />
            Read the source on GitHub
          </a>
        </div>
      </section>

      {/* How it works */}
      <section id="how-it-works" className="scroll-mt-14 border-b border-border bg-surface">
        <div className="mx-auto max-w-6xl px-5 py-16">
          <SectionLabel>How it works</SectionLabel>
          <h2 className="mt-3 max-w-2xl text-[28px] font-semibold tracking-[-0.015em] text-foreground">
            Draft, review, publish, then check the results
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
            through the platform’s own sign-in screen, picks which of them a Post is allowed to
            reach, and can revoke any connection from Settings, under Integrations.
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

      {/* Insights, the read-back loop */}
      <section id="insights" className="scroll-mt-14 border-b border-border bg-surface">
        <div className="mx-auto max-w-6xl px-5 py-16">
          <div className="grid gap-10 lg:grid-cols-2 lg:items-start">
            <div>
              <SectionLabel>Insights</SectionLabel>
              <h2 className="mt-3 text-[28px] font-semibold tracking-[-0.015em] text-foreground">
                See what is working on your own posts
              </h2>
              <p className="mt-4 text-[14px] leading-[1.7] text-foreground-muted">
                After a Post goes live, Conductor reads its public performance counters on a
                schedule. It reads them{' '}
                <strong className="text-foreground">
                  only for the posts it published for you
                </strong>
                , records each reading against your workspace’s own copy of the Post, and works out
                which of your content did best.
              </p>
              <ul className="mt-6 space-y-3 text-[14px] leading-[1.6] text-foreground-muted">
                <li className="flex gap-3">
                  <CheckCheck className="mt-0.5 h-4 w-4 shrink-0 text-primary" />
                  Engagement rate by platform, post format, hour of day and weekday.
                </li>
                <li className="flex gap-3">
                  <CheckCheck className="mt-0.5 h-4 w-4 shrink-0 text-primary" />
                  Your best and worst performing destinations, and what changed since the previous
                  period.
                </li>
                <li className="flex gap-3">
                  <CheckCheck className="mt-0.5 h-4 w-4 shrink-0 text-primary" />
                  A weekly write-up filed in your workspace’s Knowledge Center, for your agents to
                  read before they draft the next Post.
                </li>
                <li className="flex gap-3">
                  <CheckCheck className="mt-0.5 h-4 w-4 shrink-0 text-primary" />
                  All of it visible only to people in your workspace. Conductor does not publish,
                  embed or pass on your posts or their numbers anywhere else.
                </li>
              </ul>
            </div>

            <div className="rounded-lg border border-border bg-background p-5">
              <SectionLabel>What Conductor reads back</SectionLabel>
              <dl className="mt-4 divide-y divide-border">
                {[
                  ['Scope', 'Only the videos and posts Conductor itself published for your workspace'],
                  ['Counters', 'Public view, like, comment and share counts'],
                  ['Cadence', 'A capped number of reads, on a schedule you can pause'],
                  ['Audience', 'People in your workspace, inside Conductor'],
                  ['Retention', 'Readings are deleted when you delete the Post or disconnect the account'],
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
            Built for teams that hand real work to agents
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

      {/* Source */}
      <section id="source" className="scroll-mt-14 border-b border-border bg-surface">
        <div className="mx-auto max-w-6xl px-5 py-16">
          <div className="grid gap-8 lg:grid-cols-[1.4fr_1fr] lg:items-center">
            <div>
              <SectionLabel>Source</SectionLabel>
              <h2 className="mt-3 text-[28px] font-semibold tracking-[-0.015em] text-foreground">
                Built in the open
              </h2>
              <p className="mt-4 max-w-2xl text-[14px] leading-[1.7] text-foreground-muted">
                Conductor&rsquo;s source is public. You can read exactly how a Post gets approved,
                what each integration asks permission for, and what happens to your data, instead of
                taking our word for it. Issues and pull requests are welcome.
              </p>
              <p className="mt-3 max-w-2xl text-[13px] leading-[1.6] text-foreground-subtle">
                Licensed under{' '}
                <a
                  href={LICENSE_URL}
                  target="_blank"
                  rel="noreferrer"
                  className="text-primary transition-colors hover:text-primary-hover"
                >
                  {LICENSE_NAME}
                </a>
                : free for personal, research, educational and other non-commercial use. Commercial
                use needs a separate licence.
              </p>
            </div>
            <a
              href={REPO_URL}
              target="_blank"
              rel="noreferrer"
              className="inline-flex items-center justify-center gap-2 rounded-md border border-border-strong bg-surface px-5 py-2.5 text-[14px] font-medium text-foreground transition-colors hover:bg-surface-3 lg:justify-self-end"
            >
              <GithubGlyph className="h-4 w-4" />
              cliangdev/conductor
            </a>
          </div>
        </div>
      </section>

      {/* Sign-up CTA */}
      <section>
        <div className="mx-auto max-w-6xl px-5 py-20 text-center">
          <h2 className="text-[28px] font-semibold tracking-[-0.015em] text-foreground">
            Start a workspace
          </h2>
          <p className="mx-auto mt-4 max-w-xl text-[14px] leading-[1.7] text-foreground-muted">
            Conductor is open to any team: brands, agencies, product teams. Sign in with a Google
            account to create your workspace, invite your teammates and connect your accounts.
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
