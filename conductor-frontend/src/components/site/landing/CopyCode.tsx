"use client";

import { useEffect, useRef, useState } from "react";
import { Check, Copy } from "lucide-react";

/** Small copy button for a code window. Copies `text` and says "Copied" for a moment. */
export function CopyCode({ text }: { text: string }) {
  const [copied, setCopied] = useState(false);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(
    () => () => {
      if (timer.current) clearTimeout(timer.current);
    },
    [],
  );

  async function copy() {
    try {
      await navigator.clipboard.writeText(text);
    } catch {
      return;
    }
    setCopied(true);
    if (timer.current) clearTimeout(timer.current);
    timer.current = setTimeout(() => setCopied(false), 1500);
  }

  return (
    <button
      type="button"
      onClick={copy}
      aria-label="Copy command"
      className="m-2 inline-flex shrink-0 min-h-8 items-center gap-1.5 rounded-md border px-2 py-1 text-[11.5px] font-medium transition-colors hover:opacity-80"
      style={{
        borderColor: "var(--lp-ink-border)",
        color: "var(--lp-ink-muted)",
        background: "var(--lp-ink-bg-raised)",
      }}
    >
      {copied ? (
        <Check className="h-3.5 w-3.5" aria-hidden="true" />
      ) : (
        <Copy className="h-3.5 w-3.5" aria-hidden="true" />
      )}
      <span aria-live="polite">{copied ? "Copied" : null}</span>
    </button>
  );
}
