import { GithubGlyph, LICENSE_NAME, LICENSE_URL, REPO_URL } from "../SiteChrome";
import { SectionLabel, btnSecondary, linkCls } from "./primitives";

export function Source() {
  return (
    <section id="source" className="scroll-mt-14 border-b border-border bg-surface-raised">
      <div className="mx-auto flex max-w-6xl flex-wrap items-center justify-between gap-7 px-5 py-16">
        <div className="flex-[1_1_420px]">
          <SectionLabel>Source</SectionLabel>
          <h2 className="mt-3 text-[28px] font-semibold leading-[1.15] tracking-[-0.02em] text-foreground">
            Built in the open
          </h2>
          <p className="mt-3.5 max-w-[640px] text-[14px] leading-[1.7] text-foreground-muted">
            Conductor&rsquo;s source is public, so you can read how a Post gets approved and what
            each integration asks permission for. Issues and pull requests are welcome.
          </p>
          <p className="mt-2.5 max-w-[640px] text-[13px] leading-[1.6] text-foreground-muted">
            Licensed under{" "}
            <a href={LICENSE_URL} target="_blank" rel="noreferrer" className={linkCls}>
              {LICENSE_NAME}
            </a>
            : free for personal, research, educational and other non-commercial use. Commercial use
            needs a separate licence.
          </p>
        </div>
        <a href={REPO_URL} target="_blank" rel="noreferrer" className={btnSecondary}>
          <GithubGlyph className="h-4 w-4" />
          cliangdev/conductor
        </a>
      </div>
    </section>
  );
}
