'use client'

import { ProviderCredentialCard } from './ProviderCredentialCard'

const OPENAI_PROVIDER_ID = 'openai'

/**
 * The "Connect OpenAI" surface at Settings → AI Providers: a single BYO API key row, using the
 * same credential/verification endpoints and three-state badge as {@link ClaudeProviderCard}.
 */
export function OpenAiProviderCard({ projectId }: { projectId: string }) {
  return (
    <ProviderCredentialCard
      projectId={projectId}
      title="OpenAI"
      rows={[
        {
          id: OPENAI_PROVIDER_ID,
          label: 'OpenAI API key',
          description: 'Bring your own OpenAI API key, billed per token — powers the api agent runtime.',
          placeholder: 'Enter API key',
          copy: {
            saveSuccess: 'API key saved.',
            saveFail: 'Failed to save API key.',
            removeSuccess: 'API key removed.',
            removeFail: 'Failed to remove API key.',
          },
        },
      ]}
    />
  )
}
