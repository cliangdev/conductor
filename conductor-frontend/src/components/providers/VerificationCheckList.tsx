import type { VerificationCheck } from '@/lib/api'

/**
 * The shared checks[] list body rendered inside a verification <details> — used by both
 * ClaudeProviderCard (credential verify report) and ClaudeRuntimeSection (post-designation
 * re-verify report) so fail-coloring and layout can't drift between the two surfaces.
 */
export function VerificationCheckList({ checks }: { checks: VerificationCheck[] }) {
  return (
    <ul className="mt-1 space-y-0.5 pl-4 list-disc">
      {checks.map((check) => (
        <li
          key={check.name}
          className={check.status === 'fail' ? 'text-destructive' : 'text-muted-foreground'}
        >
          {check.name}: {check.message}
        </li>
      ))}
    </ul>
  )
}
