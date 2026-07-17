'use client'

import { useState } from 'react'
import { Dices } from 'lucide-react'
import { cn } from '@/lib/utils'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import {
  AgentAvatar,
  AVATAR_COLOR_CLASSES,
  AVATAR_COLOR_TOKENS,
  type AvatarColorToken,
} from '@/components/agents/AgentAvatar'

// Mirrors AgentAvatarDefaults.EMOJIS on the backend (conductor-backend/src/main/java/com/conductor/
// agent/AgentAvatarDefaults.java) exactly, so the picker only ever offers emoji the server already
// knows how to fall back to. Keep the two lists in sync if either changes.
export const CURATED_EMOJIS = [
  '🤖', '🦾', '🧠', '🛠️', '🔧', '🔍',
  '📚', '📖', '✍️', '🧪', '🔬', '📊',
  '📈', '🗂️', '🧭', '🚀', '🛰️', '💡',
  '🎯', '🧩', '🕵️', '🦉', '🐙', '🦊',
  '🐝', '🧙', '🎨', '🎼', '⚙️', '🌱',
  '🔭', '⚗️',
]

/** Exported so callers (e.g. AgentForm) can seed a new agent's initial avatar before first save. */
export function randomAvatar(): { emoji: string; color: AvatarColorToken } {
  const emoji = CURATED_EMOJIS[Math.floor(Math.random() * CURATED_EMOJIS.length)]
  const color = AVATAR_COLOR_TOKENS[Math.floor(Math.random() * AVATAR_COLOR_TOKENS.length)]
  return { emoji, color }
}

const SELECTED_RING = 'ring-2 ring-primary ring-offset-2 ring-offset-background'

export interface AgentAvatarPickerProps {
  emoji: string
  color: string
  onChange: (next: { emoji: string; color: string }) => void
  className?: string
}

/** Controlled emoji + color picker for an agent's avatar. Dependency-free (no popover/combobox lib). */
export function AgentAvatarPicker({ emoji, color, onChange, className }: AgentAvatarPickerProps) {
  const [customEmoji, setCustomEmoji] = useState(emoji)
  // Render-time state adjustment (React-endorsed alternative to a setState-in-effect sync — see
  // https://react.dev/learn/you-might-not-need-an-effect#adjusting-some-state-when-a-prop-changes):
  // keeps the free-text buffer aligned when the grid, swatches, or shuffle change the value elsewhere,
  // while still letting the user type over it without every keystroke fighting the controlled prop.
  const [lastSyncedEmoji, setLastSyncedEmoji] = useState(emoji)
  if (emoji !== lastSyncedEmoji) {
    setLastSyncedEmoji(emoji)
    setCustomEmoji(emoji)
  }

  function handleCustomEmojiChange(value: string) {
    setCustomEmoji(value)
    if (value.trim() !== '') {
      onChange({ emoji: value, color })
    }
  }

  return (
    <div className={cn('space-y-3', className)}>
      <div className="flex items-center gap-3">
        <AgentAvatar emoji={emoji} color={color} size="lg" />
        <Button
          type="button"
          variant="outline"
          size="sm"
          onClick={() => onChange(randomAvatar())}
        >
          <Dices className="h-4 w-4 mr-1.5" aria-hidden />
          Shuffle
        </Button>
      </div>

      <div>
        <p className="text-xs font-medium text-foreground-muted mb-1.5">Emoji</p>
        <div className="grid grid-cols-8 gap-1" role="group" aria-label="Choose an emoji">
          {CURATED_EMOJIS.map((candidate) => {
            const selected = candidate === emoji
            return (
              <button
                key={candidate}
                type="button"
                aria-pressed={selected}
                aria-label={`Use ${candidate}`}
                onClick={() => onChange({ emoji: candidate, color })}
                className={cn(
                  'h-9 w-9 rounded-md flex items-center justify-center text-lg transition-colors',
                  selected ? SELECTED_RING : 'hover:bg-surface-3'
                )}
              >
                <span aria-hidden>{candidate}</span>
              </button>
            )
          })}
        </div>
        <Input
          value={customEmoji}
          onChange={(e) => handleCustomEmojiChange(e.target.value)}
          maxLength={4}
          placeholder="Or type any emoji…"
          aria-label="Custom emoji"
          className="mt-2 max-w-[160px]"
        />
      </div>

      <div>
        <p className="text-xs font-medium text-foreground-muted mb-1.5">Color</p>
        <div className="flex items-center gap-2" role="group" aria-label="Choose a color">
          {AVATAR_COLOR_TOKENS.map((token) => {
            const selected = token === color
            return (
              <button
                key={token}
                type="button"
                aria-pressed={selected}
                aria-label={`Use ${token}`}
                onClick={() => onChange({ emoji, color: token })}
                className={cn(
                  'h-6 w-6 rounded-full',
                  AVATAR_COLOR_CLASSES[token],
                  selected && SELECTED_RING
                )}
              />
            )
          })}
        </div>
      </div>
    </div>
  )
}
