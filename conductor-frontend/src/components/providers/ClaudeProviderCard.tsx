'use client'

import { InfoIcon } from 'lucide-react'
import { ClaudeRuntimeSection } from './ClaudeRuntimeSection'
import { ProviderCredentialCard } from './ProviderCredentialCard'
import { Badge } from '@/components/ui/badge'

// Two peer methods for the same model provider — either, both, or neither may be connected.
// `claude-code` powers `claude-code` workflow steps and the claude-code agent runtime (billed
// against the subscription, no per-token cost); `claude` is the direct Anthropic API key that
// powers the api agent runtime. Both are stored via the generic provider-credential endpoints.
const CLAUDE_CODE_PROVIDER_ID = 'claude-code'
const CLAUDE_API_PROVIDER_ID = 'claude'

/**
 * The "Connect Claude" surface at Settings → AI Providers: two peer connection methods for the
 * same provider, replacing the two scattered panels (`ClaudeCodeCredentialPanel` under the GCP
 * integration page, `ProviderKeysPanel`'s claude row under Agents → Providers). Both methods can
 * be connected simultaneously — when they are, agents default to the claude-code runtime (no
 * per-token cost) and the API key powers the direct-API runtime.
 *
 * A thin wrapper around {@link ProviderCredentialCard}, which owns the shared three-state
 * verification UX.
 */
export function ClaudeProviderCard({ projectId }: { projectId: string }) {
  return (
    <ProviderCredentialCard
      projectId={projectId}
      title="Claude"
      subtitle="Two ways to connect — set up one or both."
      rows={[
        {
          id: CLAUDE_CODE_PROVIDER_ID,
          label: 'Claude Code subscription',
          chip: <Badge variant="secondary">Recommended</Badge>,
          description: 'Uses your Claude plan — no per-token API cost. Get a token with claude setup-token.',
          technicalNote: 'Paste the output of claude setup-token. Stored encrypted, never displayed after saving.',
          honestyNote:
            'Preflight checks runtime configuration and cloud access. Subscription token validity is confirmed on the first run.',
          placeholder: 'Enter subscription token',
          copy: {
            saveSuccess: 'Subscription token saved.',
            saveFail: 'Failed to save subscription token.',
            removeSuccess: 'Subscription token removed.',
            removeFail: 'Failed to remove subscription token.',
          },
          extra: ({ setVerification }) => (
            <ClaudeRuntimeSection
              projectId={projectId}
              onVerified={(report) =>
                setVerification({
                  status: report.status,
                  checkedAt: report.checkedAt,
                  error: report.checks.find((c) => c.status === 'fail')?.message ?? null,
                })
              }
            />
          ),
        },
        {
          id: CLAUDE_API_PROVIDER_ID,
          label: 'Anthropic API key',
          description: 'Bring your own API key, billed per token — powers the direct-API agent runtime.',
          placeholder: 'Enter API key',
          copy: {
            saveSuccess: 'API key saved.',
            saveFail: 'Failed to save API key.',
            removeSuccess: 'API key removed.',
            removeFail: 'Failed to remove API key.',
          },
        },
      ]}
      footer={(configured) =>
        (configured[CLAUDE_CODE_PROVIDER_ID] ?? false) && (configured[CLAUDE_API_PROVIDER_ID] ?? false) ? (
          <div className="flex items-start gap-2 px-4 py-3 text-xs text-muted-foreground">
            <InfoIcon className="h-3.5 w-3.5 shrink-0 translate-y-0.5" />
            <p>Agents default to Claude Code (cost saving). The API key powers the direct-API runtime.</p>
          </div>
        ) : null
      }
    />
  )
}
