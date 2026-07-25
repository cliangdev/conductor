import {
  GlobeIcon,
  ContainerIcon,
  WorkflowIcon,
  SplitIcon,
  PlugIcon,
  ZapIcon,
  BotIcon,
  TerminalIcon,
  PlayIcon,
  WebhookIcon,
  ListChecksIcon,
  GitPullRequestIcon,
  ClockIcon,
} from 'lucide-react'
import type { StepKind, TriggerKind } from '@/lib/workflowAutomation'
import type { AvatarColorToken } from '@/components/agents/AgentAvatar'

/**
 * Renders the lucide icon representing an automation step's kind — used by the diagram's step
 * cards and the step detail panel. A `switch` over statically-referenced icon components (matching
 * KnowledgeTypeIcon's convention), never a dynamically-resolved component.
 */
export function WorkflowStepTypeIcon({ kind, className }: { kind: StepKind; className?: string }) {
  switch (kind) {
    case 'http':
      return <GlobeIcon className={className} />
    case 'docker':
      return <ContainerIcon className={className} />
    case 'kestra':
      return <WorkflowIcon className={className} />
    case 'condition':
      return <SplitIcon className={className} />
    case 'integration':
      return <PlugIcon className={className} />
    case 'action':
      return <ZapIcon className={className} />
    case 'agent':
      return <BotIcon className={className} />
    case 'claude-code':
      return <TerminalIcon className={className} />
  }
}

/** Decorative identity color per step kind — the avatar token system, deliberately not the status
 * ramp (which stays reserved for run-state coloring). With 8 step kinds + 5 trigger kinds over 8
 * avatar colors, some colors repeat; icon shape carries the primary identity, same tradeoff
 * AgentAvatar already accepts by reusing colors across many agents. */
export function stepTypeAvatarColor(kind: StepKind): AvatarColorToken {
  switch (kind) {
    case 'http':
      return 'blue'
    case 'docker':
      return 'slate'
    case 'kestra':
      return 'violet'
    case 'condition':
      return 'amber'
    case 'integration':
      return 'teal'
    case 'action':
      return 'rose'
    case 'agent':
      return 'green'
    case 'claude-code':
      return 'gray'
  }
}

/** Renders the lucide icon representing a declared workflow trigger's kind. */
export function WorkflowTriggerTypeIcon({ kind, className }: { kind: TriggerKind; className?: string }) {
  switch (kind) {
    case 'workflow_dispatch':
      return <PlayIcon className={className} />
    case 'webhook':
      return <WebhookIcon className={className} />
    case 'work_item_status_changed':
      return <ListChecksIcon className={className} />
    case 'github_pull_request':
      return <GitPullRequestIcon className={className} />
    case 'schedule':
      return <ClockIcon className={className} />
  }
}

export function triggerTypeAvatarColor(kind: TriggerKind): AvatarColorToken {
  switch (kind) {
    case 'workflow_dispatch':
      return 'gray'
    case 'webhook':
      return 'blue'
    case 'work_item_status_changed':
      return 'teal'
    case 'github_pull_request':
      return 'violet'
    case 'schedule':
      return 'amber'
  }
}
