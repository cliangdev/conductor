import * as React from 'react'
import { cn } from '@/lib/utils'

export interface SliderProps
  extends Omit<React.InputHTMLAttributes<HTMLInputElement>, 'type' | 'value' | 'onChange' | 'min' | 'max' | 'step'> {
  value: number
  onValueChange: (value: number) => void
  min: number
  max: number
  step?: number
}

// A single styled native <input type="range"> — same idiom as Input/Select: keep the platform's own
// drag/keyboard/a11y behavior instead of building a custom track+thumb widget. `accent-primary` (a
// Tailwind `accent-color` utility) tints the native thumb/track with the one accent token rather than
// a browser default, so it reads as Conductor chrome in both themes with no extra markup.
const Slider = React.forwardRef<HTMLInputElement, SliderProps>(
  ({ value, onValueChange, min, max, step, className, ...props }, ref) => (
    <input
      ref={ref}
      type="range"
      min={min}
      max={max}
      step={step}
      value={value}
      onChange={(e) => onValueChange(Number(e.target.value))}
      className={cn(
        'h-1.5 w-full cursor-pointer appearance-none rounded-full bg-surface-3 accent-primary',
        'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background',
        'disabled:cursor-not-allowed disabled:opacity-50',
        className
      )}
      {...props}
    />
  )
)
Slider.displayName = 'Slider'

export { Slider }
