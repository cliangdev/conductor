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

function Step({ children, chip }: { children: ReactNode; chip: ReactNode }) {
  return (
    <div className="flex items-center justify-between gap-2">
      <span>{children}</span>
      {chip}
    </div>
  );
}

const bubble = "max-w-[85%] rounded-lg border border-border px-3 py-2";

/** The Claude connection, shown as the conversation it is for the person using it. */
function ClaudeTile() {
  return (
    <div
      id="agents"
      className="col-span-full flex scroll-mt-20 flex-wrap items-center gap-x-10 gap-y-5 rounded-lg border border-border bg-surface p-[22px] sm:p-7"
    >
      <div className="flex flex-[1_1_320px] flex-col gap-2.5">
        <h3 className="text-[18px] font-semibold text-foreground">Works inside Claude</h3>
        <p className="text-[13.5px] leading-[1.6] text-foreground-muted">
          Already use Claude? Connect it to Conductor once through the Conductor MCP server, then
          just ask. Claude creates the work, writes the documents, makes the artwork and schedules
          the posts in your workspace, with no copy and paste.
        </p>
        <p className="text-[12.5px] leading-[1.6] text-foreground-muted">
          The MCP server works with Claude Code and Claude Desktop. Developers also get a
          command-line tool.
        </p>
      </div>
      <div
        aria-hidden="true"
        className="flex min-w-0 flex-[1_1_380px] flex-col gap-2 rounded-md border border-border bg-surface-raised p-3.5 text-[12.5px] leading-[1.5] text-foreground"
      >
        <span className={`${bubble} self-end bg-accent-soft`}>
          Write up offline mode for saved recipes and send it to Dana.
        </span>
        <span className={`${bubble} bg-surface`}>
          <b className="font-semibold">Claude</b> · Done. ENG-118 is drafted and waiting for
          Dana&rsquo;s review.
        </span>
        <span className={`${bubble} self-end bg-accent-soft`}>
          Make a three-slide teaser for the fall menu.
        </span>
        <span className={`${bubble} bg-surface`}>
          <b className="font-semibold">Claude</b> · The draft is ready. Approve it and I&rsquo;ll
          save it to your workspace.
        </span>
      </div>
    </div>
  );
}

export function Platform() {
  return (
    <section id="features" className="scroll-mt-14 border-b border-border bg-surface-raised">
      <div className="mx-auto max-w-6xl px-5 py-16 sm:py-[88px]">
        <SectionLabel>Features</SectionLabel>
        <SectionHeading className="max-w-[720px]">
          Everything a team of agents needs to work like a team.
        </SectionHeading>
        <div className="mt-11 grid gap-5 [grid-template-columns:repeat(auto-fit,minmax(min(100%,310px),1fr))]">
          <ClaudeTile />
          <Tile
            title="Agents you can talk to"
            body="Give each agent a job and the tools it needs. Ask one a question from Discord, or let the CEO agent pass it to the right teammate."
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
            title="Routine work on autopilot"
            body="Set up a routine once and it runs on a schedule or when something happens, like a report every Monday or a message when a post goes live."
          >
            <div aria-hidden="true" className={`${well} flex flex-col gap-2 text-[12.5px] text-foreground`}>
              <Step chip={<Chip tone="done">Done</Chip>}>Every Monday at 9:00</Step>
              <Step chip={<Chip tone="done">Done</Chip>}>Growth analyst writes the report</Step>
              <Step chip={<Chip tone="progress">Running</Chip>}>Posted to your Discord channel</Step>
            </div>
          </Tile>
          <Tile
            title="Your process, not ours"
            body="Each kind of work follows the steps you choose: which stages it goes through, who acts at each one, and where a person has to sign off."
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
            body="One report follows people from first view to paying customer, across free and paid channels, compares you with your industry, and ranks what to do next."
          >
            <div aria-hidden="true" className={`${well} flex flex-col gap-2 text-[12px] text-foreground-muted`}>
              <BenchRow label="Views" w="100%" />
              <BenchRow label="Visits" w="58%" />
              <BenchRow label="Installs" w="27%" tone="progress"><Chip tone="progress" dot={false}>Below average</Chip></BenchRow>
              <BenchRow label="Trials" w="14%" />
            </div>
          </Tile>
          <Tile
            title="Your own AI account"
            body="Agents run on your own AI provider account, with the key stored encrypted, so you control the model and the cost. Technical teams can also run jobs on their own cloud."
          >
            <div aria-hidden="true" className={`${well} flex flex-col gap-2 text-[12.5px] text-foreground`}>
              <div className="flex items-center justify-between"><span>AI provider</span><Chip tone="done">Connected</Chip></div>
              <div className="flex items-center justify-between gap-2"><span>Runs on</span><span className="text-foreground-muted">Conductor&rsquo;s cloud</span></div>
            </div>
          </Tile>
        </div>
      </div>
    </section>
  );
}
