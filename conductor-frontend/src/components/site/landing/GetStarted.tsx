import Link from "next/link";
import type { ReactNode } from "react";
import { REPO_URL } from "../SiteChrome";
import { CopyCode } from "./CopyCode";
import { SectionHeading, SectionLabel, linkCls } from "./primitives";

export const CLAUDE_CODE_INSTALL = "curl -fsSL https://claude.ai/install.sh | bash";
export const CLAUDE_CODE_SETUP_URL = "https://code.claude.com/docs/en/setup";

const CONDUCTOR_INSTALL = [
  "npm install -g @cliangdev/conductor",
  "conductor login",
  "cd your-project && conductor init",
];

const accent = { color: "var(--lp-ink-accent)" };

function Code({ lines, copy }: { lines: string[]; copy: string }) {
  return (
    <div className="lp-ink lp-window mt-4 flex min-w-0 items-start overflow-hidden rounded-lg">
      <pre className="min-w-0 flex-1 overflow-x-auto py-3.5 pl-4 pr-3 font-mono text-[12.5px] leading-[1.85]">
        {lines.map((l, i) => (
          <span key={l} style={l.startsWith(">") ? accent : undefined}>
            {l.startsWith(">") ? l : `$ ${l}`}
            {i < lines.length - 1 ? "\n" : ""}
          </span>
        ))}
      </pre>
      <CopyCode text={copy} />
    </div>
  );
}

function Step({ n, title, children }: { n: number; title: string; children: ReactNode }) {
  return (
    <li className="min-w-0 rounded-lg border border-border bg-surface p-5">
      <div className="flex items-center gap-3">
        <span className="flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-accent-soft text-[13px] font-semibold text-primary">
          {n}
        </span>
        <h3 className="text-[16px] font-semibold tracking-[-0.01em] text-foreground">{title}</h3>
      </div>
      {children}
    </li>
  );
}

const body = "mt-3.5 text-[14px] leading-[1.7] text-foreground-muted";
const code = "font-mono text-[12.5px] text-foreground";

export function GetStarted() {
  return (
    <section id="get-started" className="scroll-mt-14 border-b border-border bg-background">
      <div className="mx-auto max-w-6xl px-5 py-16 sm:py-[88px]">
        <SectionLabel>Get started</SectionLabel>
        <SectionHeading>Up and running in four steps.</SectionHeading>
        <p className="mt-3.5 max-w-[640px] text-[15px] leading-[1.7] text-foreground-muted">
          Conductor runs in the cloud. Your agent connects to it from Claude Code.
        </p>

        <ol className="mt-9 grid list-none gap-5 p-0 lg:grid-cols-2">
          <Step n={1} title="Create your workspace">
            <p className={body}>
              Sign up with Google or your email. Your workspace is created for you.
            </p>
            <p className="mt-3.5 text-[14px]">
              <Link href="/login?mode=signup" className={linkCls}>
                Create your workspace
              </Link>
            </p>
          </Step>

          <Step n={2} title="Install Claude Code">
            <Code lines={[CLAUDE_CODE_INSTALL]} copy={CLAUDE_CODE_INSTALL} />
            <p className="mt-3.5 text-[14px]">
              <a href={CLAUDE_CODE_SETUP_URL} target="_blank" rel="noreferrer" className={linkCls}>
                Other ways to install
              </a>
            </p>
          </Step>

          <Step n={3} title="Install Conductor and connect it">
            <Code lines={CONDUCTOR_INSTALL} copy={CONDUCTOR_INSTALL.join("\n")} />
            <p className={body}>
              <span className={code}>conductor init</span> adds the Conductor MCP server to the
              project&rsquo;s <span className={code}>.mcp.json</span> and installs the{" "}
              <span className={code}>/conductor:*</span> commands in{" "}
              <span className={code}>.claude/</span>.
            </p>
          </Step>

          <Step n={4} title="Give it work">
            <Code
              lines={["claude", "> /conductor:prd offline mode for saved recipes"]}
              copy="claude"
            />
            <p className={body}>
              Approve the conductor MCP server when Claude Code asks. Drafts land in your workspace
              for review, and nothing ships until you approve it.
            </p>
          </Step>
        </ol>

        <p className="mt-8 flex flex-wrap gap-x-6 gap-y-2 text-[14px]">
          <a
            href={`${REPO_URL}/tree/main/conductor-tools#readme`}
            target="_blank"
            rel="noreferrer"
            className={linkCls}
          >
            Full CLI reference
          </a>
          <a
            href={`${REPO_URL}#quick-start-local-development`}
            target="_blank"
            rel="noreferrer"
            className={linkCls}
          >
            Run Conductor yourself
          </a>
        </p>
      </div>
    </section>
  );
}
