import Link from "next/link";
import type { Metadata } from "next";
import {
  ArrowRight,
  BarChart3,
  BookOpen,
  Check,
  CheckCheck,
  GitPullRequest,
  Megaphone,
  PenLine,
  Send,
} from "lucide-react";
import { ConductorMarkBoxed } from "@/components/brand/ConductorLogo";
import {
  GithubGlyph,
  LICENSE_NAME,
  LICENSE_URL,
  REPO_URL,
  SiteShell,
} from "@/components/site/SiteChrome";

export const metadata: Metadata = {
  // The public landing page is the site root, so it keeps the bare product name rather than the
  // layout's "%s · Conductor" template. The browser tab has to read exactly "Conductor".
  title: "Conductor",
  description:
    "Conductor is one workspace for teams that work with AI agents: agents draft social posts, creatives, specs and code, a teammate approves, and Conductor publishes the approved work to the accounts you connected and shows you how it did.",
  alternates: { canonical: "/" },
};

/** What teams use Conductor for. One card per kind of work, with the tools each one touches. */
const USE_CASES = [
  {
    icon: Megaphone,
    label: "Marketing",
    title: "Make, schedule and measure social content.",
    body: "Create on-brand images and short videos from your Brand Kit, write the captions, and schedule one Post to TikTok, Instagram, Facebook Pages and YouTube. After it goes live, see which posts, formats and times performed best.",
    chips: ["TikTok", "Instagram", "Facebook", "YouTube"],
  },
  {
    icon: GitPullRequest,
    label: "Engineering",
    title: "Turn specs into reviewed code.",
    body: "Write a spec or have an agent draft it, review it line by line, then let Claude Code implement it and open the pull request. Work Item status follows the pull request in GitHub.",
    chips: ["GitHub", "Claude Code", "Discord"],
  },
  {
    icon: BookOpen,
    label: "Knowledge",
    title: "A team wiki that keeps itself current.",
    body: "Feed Conductor your documents, notes and weekly metrics. A librarian agent files them into a wiki your team can browse, and your agents read it before they draft.",
    chips: ["Google Drive", "Weekly digests"],
  },
];

/** How a piece of work moves through Conductor, start to finish. */
const STEPS = [
  {
    icon: PenLine,
    title: "Draft",
    body: "An agent or a teammate drafts the work, whether that’s a post, a creative or a spec, in your workspace.",
  },
  {
    icon: CheckCheck,
    title: "Review",
    body: "Teammates comment line by line, then approve or ask for changes. Nothing goes out without a person’s approval.",
  },
  {
    icon: Send,
    title: "Ship",
    body: "Conductor publishes approved work where you pointed it, on the schedule you set: a social account, a repository or a chat channel.",
  },
  {
    icon: BarChart3,
    title: "Learn",
    body: "Conductor reads back how it did and writes a weekly summary. Your agents read it before the next draft.",
  },
];

const AGENT_TOOLS = [
  "Claude Code and Claude Desktop, through the Conductor MCP server",
  "The conductor command-line tool",
  "Discord’s /ask command, to talk to your agents",
  "Bring your own model keys, stored encrypted",
];

const TRUST_POINTS = [
  "You connect each account through the platform’s own sign-in screen, and you can disconnect it any time from Integrations.",
  "Conductor only publishes what a person in your workspace approved.",
  "It only reads results for posts it published for you, and shows them only to your workspace.",
  "Tokens and model keys are stored encrypted and scoped to your workspace.",
];

function SectionLabel({ children }: { children: React.ReactNode }) {
  return (
    <p className="text-[11.5px] font-semibold uppercase tracking-[0.06em] text-foreground-subtle">
      {children}
    </p>
  );
}

export default function LandingPage() {
  return (
    <SiteShell>
      {/* Hero */}
      <section className="border-b border-border">
        <div className="mx-auto flex max-w-6xl flex-col items-center px-5 py-20 text-center">
          {/* The name itself sits in the header lockup and the footer, so the hero leads with the
              mark and gives the h1 to the headline. */}
          <ConductorMarkBoxed className="h-14 w-14" />
          <h1 className="mx-auto mt-7 max-w-3xl text-[40px] font-semibold leading-[1.1] tracking-[-0.02em] text-foreground sm:text-[52px]">
            Your AI agents do the work. Your team decides what ships.
          </h1>
          <p className="mx-auto mt-6 max-w-2xl text-[15px] leading-[1.7] text-foreground-muted">
            Conductor is one workspace for teams that work with AI agents.
            Agents draft social posts, creatives, specs and code. A teammate
            reviews and approves. Conductor publishes the approved work to the
            accounts you connected and shows you how it did.
          </p>
          <div className="mt-9 flex flex-col items-center justify-center gap-3 sm:flex-row">
            <Link
              href="/login?mode=signup"
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
            Free to start. Sign up with Google or your email and your workspace
            is created for you.
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

      {/* Use cases */}
      <section id="use-cases" className="scroll-mt-14 border-b border-border">
        <div className="mx-auto max-w-6xl px-5 py-16">
          <SectionLabel>Use cases</SectionLabel>
          <h2 className="mt-3 max-w-2xl text-[28px] font-semibold tracking-[-0.015em] text-foreground">
            One place for the work your agents draft
          </h2>
          <div className="mt-10 grid gap-5 lg:grid-cols-3">
            {USE_CASES.map((useCase) => (
              <div
                key={useCase.label}
                className="flex flex-col rounded-lg border border-border bg-surface p-5"
              >
                <div className="flex items-center gap-2.5">
                  <useCase.icon className="h-5 w-5 text-primary" />
                  <SectionLabel>{useCase.label}</SectionLabel>
                </div>
                <h3 className="mt-4 text-[15px] font-semibold text-foreground">
                  {useCase.title}
                </h3>
                <p className="mt-2 flex-1 text-[13px] leading-[1.6] text-foreground-muted">
                  {useCase.body}
                </p>
                <ul className="mt-5 flex flex-wrap gap-2">
                  {useCase.chips.map((chip) => (
                    <li
                      key={chip}
                      className="inline-flex items-center rounded-full border border-border bg-background px-3 py-1 text-[13px] text-foreground"
                    >
                      {chip}
                    </li>
                  ))}
                </ul>
              </div>
            ))}
          </div>
        </div>
      </section>

      {/* How it works */}
      <section
        id="how-it-works"
        className="scroll-mt-14 border-b border-border bg-surface"
      >
        <div className="mx-auto max-w-6xl px-5 py-16">
          <SectionLabel>How it works</SectionLabel>
          <h2 className="mt-3 max-w-2xl text-[28px] font-semibold tracking-[-0.015em] text-foreground">
            Draft, review, ship, learn
          </h2>
          <div className="mt-10 grid gap-5 md:grid-cols-2 lg:grid-cols-4">
            {STEPS.map((step, i) => (
              <div
                key={step.title}
                className="rounded-lg border border-border bg-background p-5"
              >
                <div className="flex items-center gap-2.5">
                  <span className="flex h-7 w-7 items-center justify-center rounded-full bg-accent-soft text-[12px] font-semibold text-primary">
                    {i + 1}
                  </span>
                  <step.icon className="h-4 w-4 text-foreground-subtle" />
                </div>
                <h3 className="mt-4 text-[15px] font-semibold text-foreground">
                  {step.title}
                </h3>
                <p className="mt-2 text-[13px] leading-[1.6] text-foreground-muted">
                  {step.body}
                </p>
              </div>
            ))}
          </div>
        </div>
      </section>

      {/* Agents and tools */}
      <section id="agents" className="scroll-mt-14 border-b border-border">
        <div className="mx-auto max-w-6xl px-5 py-16">
          <div className="grid gap-10 lg:grid-cols-2 lg:items-center">
            <div>
              <SectionLabel>Agents</SectionLabel>
              <h2 className="mt-3 text-[28px] font-semibold tracking-[-0.015em] text-foreground">
                Works with the tools you already use
              </h2>
              <p className="mt-4 max-w-xl text-[14px] leading-[1.7] text-foreground-muted">
                Your agents run on your own model provider keys. Drive Conductor
                from Claude Code or Claude Desktop through its MCP server and
                command-line tool, or ask an agent a question from Discord.
              </p>
            </div>
            <ul className="divide-y divide-border rounded-lg border border-border bg-surface">
              {AGENT_TOOLS.map((tool) => (
                <li
                  key={tool}
                  className="flex gap-3 px-5 py-3.5 text-[13px] leading-[1.6] text-foreground-muted"
                >
                  <Check className="mt-0.5 h-4 w-4 shrink-0 text-primary" />
                  <span>{tool}</span>
                </li>
              ))}
            </ul>
          </div>
        </div>
      </section>

      {/* Trust */}
      <section
        id="trust"
        className="scroll-mt-14 border-b border-border bg-surface"
      >
        <div className="mx-auto max-w-6xl px-5 py-16">
          <SectionLabel>Trust</SectionLabel>
          <h2 className="mt-3 max-w-2xl text-[28px] font-semibold tracking-[-0.015em] text-foreground">
            Your accounts stay yours
          </h2>
          <ul className="mt-10 grid gap-5 sm:grid-cols-2">
            {TRUST_POINTS.map((point) => (
              <li
                key={point}
                className="flex gap-3 rounded-lg border border-border bg-background p-5 text-[13px] leading-[1.6] text-foreground-muted"
              >
                <Check className="mt-0.5 h-4 w-4 shrink-0 text-primary" />
                <span>{point}</span>
              </li>
            ))}
          </ul>
          <p className="mt-6 flex flex-wrap gap-x-5 gap-y-2 text-[13px]">
            <Link
              href="/privacy"
              className="text-primary transition-colors hover:text-primary-hover"
            >
              Privacy Policy
            </Link>
            <Link
              href="/data-deletion"
              className="text-primary transition-colors hover:text-primary-hover"
            >
              Data Deletion
            </Link>
          </p>
        </div>
      </section>

      {/* Source */}
      <section
        id="source"
        className="scroll-mt-14 border-b border-border bg-surface"
      >
        <div className="mx-auto max-w-6xl px-5 py-16">
          <div className="grid gap-8 lg:grid-cols-[1.4fr_1fr] lg:items-center">
            <div>
              <SectionLabel>Source</SectionLabel>
              <h2 className="mt-3 text-[28px] font-semibold tracking-[-0.015em] text-foreground">
                Built in the open
              </h2>
              <p className="mt-4 max-w-2xl text-[14px] leading-[1.7] text-foreground-muted">
                Conductor&rsquo;s source is public, so you can read how a Post
                gets approved and what each integration asks permission for.
                Issues and pull requests are welcome.
              </p>
              <p className="mt-3 max-w-2xl text-[13px] leading-[1.6] text-foreground-subtle">
                Licensed under{" "}
                <a
                  href={LICENSE_URL}
                  target="_blank"
                  rel="noreferrer"
                  className="text-primary transition-colors hover:text-primary-hover"
                >
                  {LICENSE_NAME}
                </a>
                : free for personal, research, educational and other
                non-commercial use. Commercial use needs a separate licence.
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
            Brands, agencies and product teams use Conductor. Sign up with
            Google or your email, invite your teammates and connect your
            accounts.
          </p>
          <Link
            href="/login?mode=signup"
            className="mt-8 inline-flex items-center gap-2 rounded-md bg-primary px-5 py-2.5 text-[14px] font-medium text-primary-foreground transition-colors hover:bg-primary-hover"
          >
            Create your workspace
            <ArrowRight className="h-4 w-4" />
          </Link>
        </div>
      </section>
    </SiteShell>
  );
}
