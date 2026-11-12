import { SectionHeading, SectionLabel, Tick } from "./primitives";

const AGENT_TOOLS = [
  "Claude Code and Claude Desktop, through the Conductor MCP server",
  "The conductor command-line tool",
  "Discord’s /ask command, to talk to your agents",
  "Bring your own model keys, stored encrypted",
];

const accent = { color: "var(--lp-ink-accent)" };
const muted = { color: "var(--lp-ink-muted)" };

function Terminal() {
  return (
    <div
      aria-hidden="true"
      className="lp-ink lp-window min-w-0 flex-[1_1_460px] overflow-hidden rounded-lg"
    >
      <div className="flex gap-2 border-b px-3.5 py-3" style={{ borderColor: "var(--lp-ink-border)" }}>
        {[0, 1, 2].map((i) => (
          <b key={i} className="block h-[9px] w-[9px] rounded-full" style={{ background: "var(--lp-ink-border)" }} />
        ))}
      </div>
      <pre className="overflow-x-auto px-5 py-[18px] font-mono text-[12.5px] leading-[1.85]">
        {"$ npx @cliangdev/conductor init\n"}
        <span style={muted}>Workspace linked. MCP server ready.</span>
        {"\n\n> /conductor:prd offline mode for saved recipes\n"}
        <span style={accent}>create_work_item</span>
        {"   ENG-118  PRD\n"}
        <span style={accent}>write_document</span>
        {"     prd.html\n"}
        <span style={muted}>Sent to Dana for review.</span>
        {"\n\n> /conductor:marketing create fall menu teaser\n"}
        <span style={accent}>render_creative</span>
        {"    3 slides, draft ready\n"}
        <span style={muted}>Approve the draft to save it.</span>
      </pre>
    </div>
  );
}

export function Tools() {
  return (
    <section id="agents" className="scroll-mt-14 border-b border-border bg-surface-raised">
      <div className="mx-auto flex max-w-6xl flex-wrap items-center gap-12 px-5 py-16 sm:py-[88px]">
        <div className="flex-[1_1_360px]">
          <SectionLabel>Works where you work</SectionLabel>
          <SectionHeading>Drive it from Claude Code, the terminal or Discord.</SectionHeading>
          <p className="mt-3.5 text-[15px] leading-[1.7] text-foreground-muted">
            Conductor ships an MCP server and a command-line tool, so the agent already in your
            editor can create work, write documents, publish posts and run workflows without copy
            and paste.
          </p>
          <div className="mt-5 flex flex-col gap-[9px]">
            {AGENT_TOOLS.map((t) => (
              <Tick key={t}>{t}</Tick>
            ))}
          </div>
        </div>
        <Terminal />
      </div>
    </section>
  );
}
