/**
 * Human-facing names for model-provider ids. Provider ids are free-form strings the backend
 * registry hands out (`claude`, `openai`, …), and capitalizing the first letter — the rule this
 * replaces — renders "Openai", which is wrong for every vendor that cases its own name. Ids with
 * no entry fall back to that capitalization, so a newly registered provider still reads sensibly
 * without a frontend change.
 */
const DISPLAY_NAMES: Record<string, string> = {
  claude: 'Claude',
  'claude-code': 'Claude Code',
  openai: 'OpenAI',
}

export function providerDisplayName(providerId: string): string {
  return DISPLAY_NAMES[providerId] ?? providerId.charAt(0).toUpperCase() + providerId.slice(1)
}
