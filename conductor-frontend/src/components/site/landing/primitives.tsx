import type { ReactNode } from "react";
import { Check } from "lucide-react";

export type Tone = "review" | "approved" | "done" | "code-review" | "progress" | "draft";

// Static class lookup, not interpolated: Tailwind can't see `bg-status-${tone}` templates.
const TONES: Record<Tone, { chip: string; dot: string }> = {
  review: { chip: "bg-status-review/10 text-status-review", dot: "bg-status-review" },
  approved: { chip: "bg-status-approved/10 text-status-approved", dot: "bg-status-approved" },
  done: { chip: "bg-status-done/10 text-status-done", dot: "bg-status-done" },
  "code-review": {
    chip: "bg-status-code-review/10 text-status-code-review",
    dot: "bg-status-code-review",
  },
  progress: { chip: "bg-status-progress/10 text-status-progress", dot: "bg-status-progress" },
  draft: { chip: "bg-status-draft/10 text-status-draft", dot: "bg-status-draft" },
};

export function Chip({
  tone,
  dot = true,
  className = "",
  children,
}: {
  tone: Tone;
  dot?: boolean;
  className?: string;
  children: ReactNode;
}) {
  const t = TONES[tone];
  return (
    <span
      className={`inline-flex items-center gap-1.5 whitespace-nowrap rounded-full px-2.5 py-[3px] text-[12px] font-medium ${t.chip} ${className}`}
    >
      {dot && <i className={`block h-1.5 w-1.5 rounded-full ${t.dot}`} />}
      {children}
    </span>
  );
}

export function SectionLabel({ children }: { children: ReactNode }) {
  return (
    <p className="text-[11.5px] font-semibold uppercase tracking-[0.06em] text-foreground-muted">
      {children}
    </p>
  );
}

export function SectionHeading({
  children,
  className = "",
}: {
  children: ReactNode;
  className?: string;
}) {
  return (
    <h2
      className={`mt-3 text-[28px] font-semibold leading-[1.15] tracking-[-0.02em] text-foreground sm:text-[34px] ${className}`}
    >
      {children}
    </h2>
  );
}

export function Tick({ children, small = false }: { children: ReactNode; small?: boolean }) {
  return (
    <div
      className={`flex gap-2.5 leading-[1.6] text-foreground-muted ${small ? "text-[13px]" : "text-[13.5px]"}`}
    >
      <Check className="mt-[3px] h-4 w-4 shrink-0 text-primary" aria-hidden="true" />
      <span>{children}</span>
    </div>
  );
}

const AVATARS = {
  amber: "bg-avatar-amber",
  blue: "bg-avatar-blue",
  violet: "bg-avatar-violet",
  green: "bg-avatar-green",
  gray: "bg-avatar-gray",
} as const;

export function Monogram({ tone, children }: { tone: keyof typeof AVATARS; children: ReactNode }) {
  return (
    <span
      className={`flex h-6 w-6 shrink-0 items-center justify-center rounded-full text-[10px] font-semibold text-foreground ${AVATARS[tone]}`}
    >
      {children}
    </span>
  );
}

/** A decorative product window. Scenes are illustrations, so they're hidden from assistive tech. */
export function SceneWindow({
  title,
  right,
  className = "",
  children,
}: {
  title: string;
  right?: ReactNode;
  className?: string;
  children: ReactNode;
}) {
  return (
    <div
      aria-hidden="true"
      className={`lp-window min-w-0 overflow-hidden rounded-lg border border-border bg-surface ${className}`}
    >
      <div className="flex items-center gap-2 border-b border-border bg-surface-raised px-3.5 py-2.5">
        <b className="block h-[9px] w-[9px] rounded-full bg-border-strong" />
        <b className="block h-[9px] w-[9px] rounded-full bg-border-strong" />
        <b className="block h-[9px] w-[9px] rounded-full bg-border-strong" />
        <span className="ml-2.5 truncate font-mono text-[12px] text-foreground-muted">{title}</span>
        {right && <span className="ml-auto">{right}</span>}
      </div>
      {children}
    </div>
  );
}

export const mono = "font-mono text-[12px] text-foreground-muted";
export const btnPrimary =
  "inline-flex min-h-11 items-center gap-2 rounded-md bg-primary px-5 py-3 text-[14px] font-medium text-primary-foreground transition-colors hover:bg-primary-hover";
export const btnSecondary =
  "inline-flex min-h-11 items-center gap-2 rounded-md border border-border-strong bg-surface px-5 py-3 text-[14px] font-medium text-foreground transition-colors hover:bg-surface-3";
export const linkCls = "text-primary transition-colors hover:text-primary-hover";
