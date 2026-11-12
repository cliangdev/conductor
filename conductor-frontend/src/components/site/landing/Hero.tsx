import Link from "next/link";
import { ArrowRight } from "lucide-react";
import { Chip, Monogram, SceneWindow, btnPrimary, btnSecondary, mono } from "./primitives";
import "./landing.css";

const lbl = "text-[11.5px] font-semibold uppercase tracking-[0.06em] text-foreground-muted";
const rowText = "min-w-0 flex-1 truncate text-[13.5px]";

function Row({
  av,
  id,
  title,
  status,
  active = false,
  last = false,
}: {
  av: [React.ComponentProps<typeof Monogram>["tone"], string];
  id: string;
  title: string;
  status: React.ReactNode;
  active?: boolean;
  last?: boolean;
}) {
  return (
    <div
      className={`flex items-center gap-3 rounded-md px-2.5 py-3 ${active ? "bg-accent-soft" : ""} ${active || last ? "" : "border-b border-border"}`}
    >
      <Monogram tone={av[0]}>{av[1]}</Monogram>
      <span className={mono}>{id}</span>
      <span className={`${rowText} ${active ? "font-medium" : ""} text-foreground`}>{title}</span>
      {status}
    </div>
  );
}

function WorkspaceScene() {
  return (
    <SceneWindow title="conductor / Acme workspace" className="mt-14 w-full text-left">
      <div className="flex flex-wrap">
        <div className="hidden flex-1 basis-[170px] flex-col gap-[3px] border-r border-border bg-surface-raised px-3 py-4 text-[13px] text-foreground-muted md:flex">
          <span className="rounded-md bg-accent-soft px-2.5 py-[7px] font-medium text-primary">
            Needs your review <span className={`float-right ${mono}`}>3</span>
          </span>
          {["Marketing", "Engineering", "Knowledge", "Agents", "Workflows", "Integrations"].map(
            (n) => (
              <span key={n} className="px-2.5 py-[7px]">
                {n}
              </span>
            ),
          )}
        </div>
        <div className="min-w-0 flex-[999_1_440px] px-5 py-[18px]">
          <p className={lbl}>Needs your review</p>
          <div className="mt-2.5 flex flex-col">
            <Row
              active
              av={["amber", "MK"]}
              id="MKT-42"
              title="Fall menu teaser, 3-slide carousel"
              status={
                <span className="lp-cycle grid">
                  <Chip tone="review">In review</Chip>
                  <Chip tone="approved">Approved</Chip>
                  <Chip tone="done">Published</Chip>
                </span>
              }
            />
            <Row
              av={["blue", "PM"]}
              id="ENG-118"
              title="PRD: offline mode for saved recipes"
              status={<Chip tone="review">In review</Chip>}
            />
            <Row
              av={["violet", "CC"]}
              id="ENG-121"
              title="Retry failed syncs, pull request opened"
              status={<Chip tone="code-review">Code review</Chip>}
            />
          </div>
          <p className={`mt-5 ${lbl}`}>Done while you were away</p>
          <div className="mt-2.5 flex flex-col">
            <Row
              av={["amber", "MK"]}
              id="MKT-39"
              title="Five-minute weeknight pasta, short video"
              status={<Chip tone="done">Published</Chip>}
            />
            <Row
              av={["green", "LB"]}
              id="WIKI"
              title="Pricing and plans, page updated from 2 sources"
              status={<Chip tone="approved">Filed</Chip>}
            />
            <Row
              last
              av={["gray", "WF"]}
              id="RUN"
              title="Weekly growth report"
              status={<Chip tone="progress">Running</Chip>}
            />
          </div>
        </div>
        <div className="flex flex-1 basis-[250px] flex-col gap-3 border-l border-border p-[18px]">
          <span className={mono}>MKT-42 · Post</span>
          <div className="flex gap-3">
            <div className="lp-ink lp-creative flex h-[150px] w-[92px] shrink-0 flex-col justify-end rounded-lg p-2.5 text-[12px] font-semibold leading-[1.25]">
              Fall is on the menu
              <span
                className="mt-1.5 block h-[3px] w-7"
                style={{ background: "var(--lp-ink-accent)" }}
              />
            </div>
            <div className="flex flex-col items-start gap-[7px] text-[12px] text-foreground-muted">
              <span>Drafted by Marketer</span>
              <span>Thu 9:00 AM</span>
              <Chip tone="draft" dot={false}>TikTok</Chip>
              <Chip tone="draft" dot={false}>Instagram</Chip>
              <Chip tone="draft" dot={false}>YouTube</Chip>
            </div>
          </div>
          <div className="mt-1 flex gap-2">
            <span className="flex-1 rounded-md bg-primary py-[9px] text-center text-[13px] font-medium text-primary-foreground">
              Approve
            </span>
            <span className="flex-1 rounded-md border border-border-strong py-2 text-center text-[13px] font-medium text-foreground">
              Request changes
            </span>
          </div>
        </div>
      </div>
    </SceneWindow>
  );
}

export function Hero() {
  return (
    <section id="top" className="border-b border-border bg-background">
      <div className="mx-auto flex max-w-6xl flex-col items-center px-5 pb-[72px] pt-16 text-center sm:pt-[84px]">
        <Chip tone="approved" className="!px-3 !py-[5px] !text-[12.5px]">
          Conductor · one workspace for a team of AI agents
        </Chip>
        <h1 className="mt-[26px] max-w-[900px] text-[38px] font-bold leading-[1.04] tracking-[-0.035em] text-foreground sm:text-[52px] lg:text-[66px]">
          Run your company with a team of AI agents.{" "}
          <span className="text-primary">You make the calls.</span>
        </h1>
        <p className="mt-6 max-w-[680px] text-[17px] leading-[1.65] text-foreground-muted">
          Your agents write the specs, ship the code, make and publish the marketing, and keep the
          company wiki current. Nothing goes out until a person approves it, and every result comes
          back so next week&rsquo;s work is better.
        </p>
        <div className="mt-[34px] flex flex-wrap justify-center gap-3">
          <Link href="/login?mode=signup" className={btnPrimary}>
            Create your workspace
            <ArrowRight className="h-4 w-4" aria-hidden="true" />
          </Link>
          <Link href="#how-it-works" className={btnSecondary}>
            See how it works
          </Link>
        </div>
        <p className="mt-4 text-[13px] text-foreground-muted">
          Free to start. Sign up with Google or your email. Source available on GitHub.
        </p>
        <WorkspaceScene />
        <p className="mt-3.5 text-[12px] text-foreground-muted">
          Illustration of a workspace with sample data.
        </p>
      </div>
    </section>
  );
}
