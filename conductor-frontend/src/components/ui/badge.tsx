import * as React from 'react'
import { cva, type VariantProps } from 'class-variance-authority'
import { cn } from '@/lib/utils'

const badgeVariants = cva(
  'inline-flex items-center rounded-full border px-2.5 py-0.5 text-xs font-semibold transition-colors focus:outline-none focus:ring-2 focus:ring-ring focus:ring-offset-2',
  {
    variants: {
      variant: {
        default: 'border-transparent bg-primary text-primary-foreground hover:bg-primary/80',
        secondary:
          'border-transparent bg-secondary text-secondary-foreground hover:bg-secondary/80',
        destructive: 'border-transparent bg-destructive text-white hover:bg-destructive/80',
        outline: 'text-foreground border-border',
        'status-draft': 'border-status-draft/30 bg-status-draft/10 text-status-draft',
        'status-review': 'border-status-review/30 bg-status-review/10 text-status-review',
        'status-approved': 'border-status-approved/30 bg-status-approved/10 text-status-approved',
        'status-progress': 'border-status-progress/30 bg-status-progress/10 text-status-progress',
        'status-code-review': 'border-status-code-review/30 bg-status-code-review/10 text-status-code-review',
        'status-done': 'border-status-done/30 bg-status-done/10 text-status-done',
        'status-closed': 'border-status-closed/30 bg-status-closed/10 text-status-closed',
      },
    },
    defaultVariants: {
      variant: 'default',
    },
  }
)

export interface BadgeProps
  extends React.HTMLAttributes<HTMLDivElement>,
    VariantProps<typeof badgeVariants> {}

function Badge({ className, variant, ...props }: BadgeProps) {
  return <div className={cn(badgeVariants({ variant }), className)} {...props} />
}

export { Badge, badgeVariants }
