import { Chip } from "./primitives";

type Item = readonly [name: string, logo: string | null];

// Discord and Claude Code are text-only chips (no logo file in public/integrations).
const ITEMS: Item[] = [
  ["TikTok", "tiktok"],
  ["Instagram", "instagram"],
  ["Facebook Pages", "facebook"],
  ["YouTube", "youtube"],
  ["GitHub", "github"],
  ["Discord", null],
  ["Claude Code", null],
  ["PostHog", "posthog"],
  ["Search Console", "gsc"],
  ["App Store Connect", "app-store-connect"],
  ["Apple Search Ads", "apple-search-ads"],
];

export function LogoStrip() {
  return (
    <section className="border-b border-border bg-surface">
      <div className="mx-auto flex max-w-6xl flex-wrap items-center justify-center gap-x-3 gap-y-2.5 px-5 py-[26px]">
        <p className="mr-2 text-[11.5px] font-semibold uppercase tracking-[0.06em] text-foreground-muted">
          Connects to
        </p>
        {ITEMS.map(([name, logo]) => (
          <Chip key={name} tone="draft" dot={false}>
            {logo && (
              // eslint-disable-next-line @next/next/no-img-element
              <img src={`/integrations/${logo}.svg`} alt="" width={14} height={14} className="h-3.5 w-3.5" />
            )}
            {name}
          </Chip>
        ))}
      </div>
    </section>
  );
}
