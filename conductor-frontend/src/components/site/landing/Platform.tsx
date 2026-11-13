import type { ReactNode } from "react";
import { Chip, SectionHeading, SectionLabel, mono } from "./primitives";

function Tile({ title, body, children }: { title: string; body: string; children: ReactNode }) {
  return (
    <div className="flex flex-col gap-2.5 rounded-lg border border-border bg-surface p-[22px]">
      <h3 className="text-[16px] font-semibold text-foreground">{title}</h3>
      <p className="text-[13.5px] leading-[1.6] text-foreground-muted">{body}</p>
      {children}
    </div>
  );
}

const well = "mt-2 rounded-md border border-border bg-surface-raised p-3";

function BenchRow({ label, w, tone = "primary", children }: { label: string; w: string; tone?: "primary" | "progress"; children?: ReactNode }) {
  return (
    <div className="flex items-center gap-2">
      <span className="w-[62px]">{label}</span>
      <span className="flex-1">
        <span
          className={`block h-2 rounded ${tone === "primary" ? "bg-primary" : "bg-status-progress"}`}
          style={{ width: w }}
        />
      </span>
      {children}
    </div>
  );
}

export function Platform() {
  return (
    <section id="platform" className="scroll-mt-14 border-b border-border bg-surface-raised">
      <div className="mx-auto max-w-6xl px-5 py-16 sm:py-[88px]">
        <SectionLabel>The platform underneath</SectionLabel>
        <SectionHeading className="max-w-[720px]">
          Everything a team of agents needs to work like a team.
        </SectionHeading>
        <div className="mt-11 grid gap-5 [grid-template-columns:repeat(auto-fit,minmax(min(100%,310px),1fr))]">
          <Tile
            title="Agents you can talk to"
            body="Give each agent a role, tools and a model. Ask one a question from Discord with /ask, or let the CEO agent route it to the right teammate."
          >
            <div aria-hidden="true" className={`${well} flex flex-col gap-2 text-[12.5px] leading-[1.5] text-foreground`}>
              <span><span className={mono}>/ask</span> How did last week&rsquo;s posts do?</span>
              <span className="rounded-lg border border-border bg-surface px-2.5 py-2">
                <b className="font-semibold">CEO agent</b> · The pasta video led on views. I asked
                Marketer to make two more in that format.
              </span>
            </div>
          </Tile>
          <Tile
            title="Memory that lasts"
            body="Agents remember facts, decisions and preferences from past conversations, and bring them to the next one. You can browse and correct what they know."
          >
            <div aria-hidden="true" className={`${well} flex flex-col gap-[7px] text-[12.5px] text-foreground`}>
              <span>Decision · No discounts in ad copy <span className={mono}>Oct 12</span></span>
              <span>Preference · Post before 10 AM local <span className={mono}>Oct 20</span></span>
              <span className="text-foreground-muted line-through">Fact · Launch is in November <span className={mono}>replaced</span></span>
            </div>
          </Tile>
          <Tile
            title="Workflows that run themselves"
            body="Write automation in YAML and trigger it on a schedule, a webhook or an event. Steps can be agents, Claude Code, containers, HTTP calls or integration actions."
          >
            <pre
              aria-hidden="true"
              className="lp-ink mt-2 overflow-x-auto rounded-md border p-3 font-mono text-[11.5px] leading-[1.7]"
              style={{ borderColor: "var(--lp-ink-border)" }}
            >{`on:
  schedule: "0 9 * * MON"
jobs:
  report:
    steps:
      - agent: growth-analyst
      - action: discord.post`}</pre>
          </Tile>
          <Tile
            title="Your process, not ours"
            body="Each kind of work follows a lifecycle you define: which statuses exist, who acts at each step, and where a person has to sign off."
          >
            <div aria-hidden="true" className={`${well} flex flex-wrap items-center gap-1.5`}>
              <Chip tone="draft">Draft</Chip><span className="text-foreground-subtle">›</span>
              <Chip tone="review">In review</Chip><span className="text-foreground-subtle">›</span>
              <Chip tone="approved">Approved</Chip><span className="text-foreground-subtle">›</span>
              <Chip tone="done">Published</Chip>
            </div>
          </Tile>
          <Tile
            title="A report that tells you what to do"
            body="One report covers the whole funnel, organic and paid, compared against industry benchmarks, with next actions ranked by impact."
          >
            <div aria-hidden="true" className={`${well} flex flex-col gap-2 text-[12px] text-foreground-muted`}>
              <BenchRow label="Views" w="100%" />
              <BenchRow label="Visits" w="58%" />
              <BenchRow label="Installs" w="27%" tone="progress"><Chip tone="progress" dot={false}>Below benchmark</Chip></BenchRow>
              <BenchRow label="Trials" w="14%" />
            </div>
          </Tile>
          <Tile
            title="Your keys, your runtime"
            body="Agents run on your own model provider keys, stored encrypted. Run workflow jobs on Conductor's runtime, your own Cloud Run project or a self-hosted runner."
          >
            <div aria-hidden="true" className={`${well} flex flex-col gap-2 text-[12.5px] text-foreground`}>
              <div className="flex items-center justify-between"><span>Model provider</span><Chip tone="done">Connected</Chip></div>
              <div className="flex items-center justify-between gap-2"><span>Runtime target</span><span className={mono}>your-project / us-central1</span></div>
            </div>
          </Tile>
        </div>
      </div>
    </section>
  );
}
