import * as React from 'react'
import { cva, type VariantProps } from 'class-variance-authority'
import { AlertTriangleIcon, InfoIcon, XCircleIcon } from 'lucide-react'
import { cn } from '@/lib/utils'
import { statusHueClasses } from '@/components/ui/status-badge'

// Each variant's tint recipe comes from the same hue table StatusBadge uses, rather than
// re-typing bg/text/border literals here.
const blue = statusHueClasses('blue')
const amber = statusHueClasses('amber')
const red = statusHueClasses('red')

const alertVariants = cva('flex items-start gap-2.5 rounded-md border px-3 py-2.5 text-sm', {
  variants: {
    variant: {
      info: `${blue.border} ${blue.bg} ${blue.text}`,
      warning: `${amber.border} ${amber.bg} ${amber.text}`,
      destructive: `${red.border} ${red.bg} ${red.text}`,
    },
  },
  defaultVariants: {
    variant: 'info',
  },
})

const ICONS = {
  info: InfoIcon,
  warning: AlertTriangleIcon,
  destructive: XCircleIcon,
} as const

export interface AlertProps
  extends React.HTMLAttributes<HTMLDivElement>,
    VariantProps<typeof alertVariants> {}

function Alert({ className, variant, children, ...props }: AlertProps) {
  const Icon = ICONS[variant ?? 'info']
  return (
    <div role="alert" className={cn(alertVariants({ variant }), className)} {...props}>
      <Icon className="h-4 w-4 shrink-0 translate-y-0.5" />
      <div className="text-foreground [&_p]:leading-relaxed">{children}</div>
    </div>
  )
}

export { Alert, alertVariants }
