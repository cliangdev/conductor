import { cn } from '@/lib/utils'

/** A thin determinate bar; `total` 0 renders an empty track. */
export function ProgressBar({
  done,
  total,
  label,
  className,
}: {
  done: number
  total: number
  label: string
  className?: string
}) {
  const percent = total > 0 ? Math.min(100, Math.round((done / total) * 100)) : 0
  return (
    <div
      role="progressbar"
      aria-label={label}
      aria-valuemin={0}
      aria-valuemax={100}
      aria-valuenow={percent}
      title={`${done} of ${total}`}
      className={cn('h-1.5 w-full overflow-hidden rounded-full bg-muted', className)}
    >
      <div className="h-full rounded-full bg-status-done" style={{ width: `${percent}%` }} />
    </div>
  )
}
