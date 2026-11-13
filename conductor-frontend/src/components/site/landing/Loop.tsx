import { SectionHeading, SectionLabel } from "./primitives";

const STEPS = [
  { n: "01", title: "Draft", body: "An agent or a teammate drafts the work, whether that’s a post, a creative or a spec, in your workspace." },
  { n: "02", title: "Review", body: "Teammates comment line by line, then approve or ask for changes. Nothing goes out without a person’s approval." },
  { n: "03", title: "Ship", body: "Conductor publishes approved work where you pointed it, on the schedule you set: a social account, a repository or a chat channel." },
  { n: "04", title: "Learn", body: "Conductor reads back how it did and writes a weekly summary. Your agents read it before the next draft." },
];

export function Loop() {
  return (
    <section id="how-it-works" className="scroll-mt-14 border-b border-border">
      <div className="mx-auto max-w-6xl px-5 py-16 sm:py-[88px]">
        <SectionLabel>How it works</SectionLabel>
        <SectionHeading>Draft, review, ship, learn. Then again, better.</SectionHeading>
        <div className="mt-12 grid [grid-template-columns:repeat(auto-fit,minmax(min(100%,230px),1fr))]">
          {STEPS.map((s) => (
            <div key={s.n} className="flex flex-col gap-2.5 border-t-2 border-primary pb-6 pr-6 pt-[22px]">
              <span className="font-mono text-[12px] text-primary">{s.n}</span>
              <h3 className="text-[16px] font-semibold text-foreground">{s.title}</h3>
              <p className="text-[13.5px] leading-[1.6] text-foreground-muted">{s.body}</p>
            </div>
          ))}
        </div>
      </div>
    </section>
  );
}
