import {
  BarChart3Icon,
  FileTextIcon,
  FolderKanbanIcon,
  NetworkIcon,
  PlugIcon,
  PuzzleIcon,
  ScaleIcon,
  UserIcon,
  UsersIcon,
} from 'lucide-react'

/**
 * Renders the lucide icon representing a knowledge page's `type` frontmatter — used for the rail's
 * page rows and the page header's icon tile. A `switch` over statically-referenced icon components
 * (rather than resolving one from a lookup table into a locally assigned variable) so the icon
 * choice never becomes a dynamically-created component at render time. Falls back to a plain
 * document icon for unknown types (the taxonomy is a documented convention, not enforced by the
 * parser).
 */
export function KnowledgeTypeIcon({ type, className }: { type: string; className?: string }) {
  switch (type) {
    case 'person':
      return <UserIcon className={className} />
    case 'project':
      return <FolderKanbanIcon className={className} />
    case 'decision':
      return <ScaleIcon className={className} />
    case 'meeting':
      return <UsersIcon className={className} />
    case 'metric':
      return <BarChart3Icon className={className} />
    case 'feature':
      return <PuzzleIcon className={className} />
    case 'architecture':
      return <NetworkIcon className={className} />
    case 'integration':
      return <PlugIcon className={className} />
    default:
      return <FileTextIcon className={className} />
  }
}
