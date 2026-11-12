import { Chip, SceneWindow, SectionHeading, SectionLabel, Tick, mono } from "./primitives";

const h3 = "mt-4 text-[26px] font-semibold leading-[1.2] tracking-[-0.015em] text-foreground";
const lead = "mt-3.5 text-[15px] leading-[1.7] text-foreground-muted";
const kv = "flex items-center justify-between border-b border-border py-[9px]";

function MarketingScene() {
  return (
    <SceneWindow
      title="MKT-42 · Fall menu teaser"
      right={<Chip tone="approved">Approved</Chip>}
      className="flex-[1_1_460px]"
    >
      <div className="flex flex-wrap gap-5 p-5">
        <div className="flex gap-2">
          <div className="lp-ink lp-creative flex h-[184px] w-[104px] flex-col justify-end rounded-lg p-3 text-[14px] font-semibold leading-[1.2]">
            Fall is on the menu
            <span className="mt-2 block h-[3px] w-[30px]" style={{ background: "var(--lp-ink-accent)" }} />
          </div>
          <div className="lp-ink lp-creative-alt flex h-[184px] w-[104px] flex-col justify-end rounded-lg p-3 text-[13px] font-semibold leading-[1.2]">
            Three new dishes
          </div>
        </div>
        <div className="flex min-w-0 flex-[1_1_200px] flex-col text-[13px]">
          <div className={kv}><span>TikTok</span><Chip tone="done">Published</Chip></div>
          <div className={kv}><span>Instagram</span><Chip tone="done">Published</Chip></div>
          <div className={kv}><span>Facebook Page</span><Chip tone="review">Scheduled 9:00</Chip></div>
          <div className="flex items-center justify-between py-[9px]"><span>YouTube</span><Chip tone="review">Scheduled 9:00</Chip></div>
          <span className="mt-2.5 text-[12px] text-foreground-muted">
            Approved by Dana for this media and these four destinations.
          </span>
        </div>
      </div>
    </SceneWindow>
  );
}

function Bar({ w = "92%" }: { w?: string }) {
  return <span className="block h-2 rounded bg-surface-3" style={{ width: w }} />;
}

function EngineeringScene() {
  return (
    <SceneWindow title="ENG-118 · Offline mode for saved recipes" className="flex-[1_1_460px]">
      <div className="flex flex-wrap">
        <div className="flex flex-[1_1_240px] flex-col gap-2.5 border-r border-border p-5">
          <span className="text-[14px] font-semibold text-foreground">Requirements</span>
          <Bar w="92%" />
          <Bar w="78%" />
          <div className="rounded-r-md border-l-2 border-status-review bg-status-review/10 px-2.5 py-2 text-[12.5px] leading-[1.5] text-foreground">
            Saved recipes open with no connection.
            <br />
            <span className="text-status-review">Dana: what about recipes saved on another device?</span>
          </div>
          <Bar w="85%" />
          <Bar w="60%" />
        </div>
        <div className="flex flex-[1_1_220px] flex-col gap-2.5 p-5 text-[13px] text-foreground">
          <SectionLabel>Tasks · 2 of 4 done</SectionLabel>
          <span className="block h-1.5 rounded-sm bg-surface-3">
            <span className="block h-1.5 w-1/2 rounded-sm bg-status-done" />
          </span>
          <div className="flex justify-between gap-2"><span>Local recipe cache</span><Chip tone="done">Done</Chip></div>
          <div className="flex justify-between gap-2"><span>Sync queue</span><Chip tone="done">Done</Chip></div>
          <div className="flex justify-between gap-2"><span>Retry failed syncs</span><Chip tone="code-review">PR #412</Chip></div>
          <div className="flex justify-between gap-2"><span>Offline banner</span><Chip tone="progress">In progress</Chip></div>
        </div>
      </div>
    </SceneWindow>
  );
}

function KnowledgeScene() {
  return (
    <SceneWindow title="Knowledge" className="flex-[1_1_460px]">
      <div className="flex flex-wrap">
        <div className="flex flex-[1_1_220px] flex-col gap-2.5 border-r border-border p-5 text-[13px] text-foreground">
          <SectionLabel>Inbox</SectionLabel>
          <div className="flex justify-between gap-2"><span>Customer interview notes</span><Chip tone="approved">Filed</Chip></div>
          <div className="flex justify-between gap-2"><span>Weekly metrics digest</span><Chip tone="approved">Filed</Chip></div>
          <div className="flex justify-between gap-2"><span>Pricing change memo</span><Chip tone="progress">Filing</Chip></div>
          <div className="flex justify-between gap-2"><span>Brand voice guide</span><Chip tone="draft">Waiting</Chip></div>
        </div>
        <div className="flex flex-[1_1_220px] flex-col gap-[9px] p-5 text-[13px] text-foreground">
          <SectionLabel>Wiki</SectionLabel>
          <span className="font-medium">Product</span>
          <span className="pl-3.5 text-foreground-muted">Pricing and plans <span className={mono}>· rev 6</span></span>
          <span className="pl-3.5 text-foreground-muted">Onboarding flow</span>
          <span className="font-medium">Customers</span>
          <span className="pl-3.5 text-foreground-muted">Who buys and why <span className={mono}>· rev 3</span></span>
          <span className="font-medium">Growth</span>
          <span className="pl-3.5 text-foreground-muted">What&rsquo;s working this month</span>
        </div>
      </div>
    </SceneWindow>
  );
}

export function Departments() {
  return (
    <section id="use-cases" className="scroll-mt-14 border-b border-border">
      <div className="mx-auto max-w-6xl px-5 py-16 sm:py-[88px]">
        <SectionLabel>What your AI team does</SectionLabel>
        <SectionHeading className="max-w-[720px]">
          Three departments, staffed by agents, run from one place.
        </SectionHeading>

        <div className="mt-16 flex flex-wrap items-center gap-12">
          <div className="flex-[1_1_360px]">
            <Chip tone="progress">Marketing</Chip>
            <h3 className={h3}>From a photo and a headline to a post that&rsquo;s live everywhere.</h3>
            <p className={lead}>
              Set up a Brand Kit once. Agents turn your photos and clips into on-brand images,
              carousels and short videos, write the captions, and schedule one Post to every
              account. You approve the exact media and destinations. Conductor publishes on time and
              reads the results back.
            </p>
            <div className="mt-[18px] flex flex-col gap-2">
              <Tick>One Post to TikTok, Instagram, Facebook Pages and YouTube</Tick>
              <Tick>A calendar, per-destination outcomes and the live link for each</Tick>
              <Tick>A whole-funnel report that says what&rsquo;s working and what to do next</Tick>
            </div>
          </div>
          <MarketingScene />
        </div>

        <div className="mt-[88px] flex flex-row-reverse flex-wrap items-center gap-12">
          <div className="flex-[1_1_360px]">
            <Chip tone="code-review">Engineering</Chip>
            <h3 className={h3}>From an idea to a reviewed pull request.</h3>
            <p className={lead}>
              An agent drafts the PRD with you. Your team comments line by line and approves.
              Conductor breaks it into tasks with acceptance criteria, Claude Code builds them and
              opens the pull request, and the Work Item follows it in GitHub until it&rsquo;s
              merged.
            </p>
            <div className="mt-[18px] flex flex-col gap-2">
              <Tick>Line-level comments and one verdict per review</Tick>
              <Tick>Every acceptance criterion checked off with evidence</Tick>
              <Tick>Plans, milestones and a roadmap built from the real work</Tick>
            </div>
          </div>
          <EngineeringScene />
        </div>

        <div className="mt-[88px] flex flex-wrap items-center gap-12">
          <div className="flex-[1_1_360px]">
            <Chip tone="done">Knowledge</Chip>
            <h3 className={h3}>A company wiki that writes itself, and that your agents actually read.</h3>
            <p className={lead}>
              Drop in documents, notes and weekly metrics. A librarian agent files them into
              reviewed, versioned wiki pages. Before an agent drafts a post or a spec, it reads the
              wiki, so the work starts from what your company already knows.
            </p>
          </div>
          <KnowledgeScene />
        </div>
      </div>
    </section>
  );
}
