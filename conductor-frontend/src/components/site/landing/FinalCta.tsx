import Link from "next/link";
import { ArrowRight } from "lucide-react";

export function FinalCta() {
  return (
    <section className="lp-ink">
      <div className="mx-auto max-w-6xl px-5 py-20 text-center sm:py-24">
        <h2 className="mx-auto max-w-[720px] text-[30px] font-bold leading-[1.1] tracking-[-0.03em] sm:text-[40px] lg:text-[46px]">
          Hire the team. Keep the final say.
        </h2>
        <p className="mx-auto mt-[18px] max-w-[560px] text-[15px] leading-[1.7]" style={{ color: "var(--lp-ink-muted)" }}>
          Sign up with Google or your email, invite your teammates and connect your accounts. Your
          workspace is created for you.
        </p>
        <Link
          href="/login?mode=signup"
          className="mt-[30px] inline-flex min-h-11 items-center gap-2 rounded-md px-5 py-3 text-[14px] font-semibold transition-opacity hover:opacity-90"
          style={{ background: "var(--lp-ink-accent)", color: "var(--lp-ink-bg)" }}
        >
          Create your workspace
          <ArrowRight className="h-4 w-4" aria-hidden="true" />
        </Link>
      </div>
    </section>
  );
}
