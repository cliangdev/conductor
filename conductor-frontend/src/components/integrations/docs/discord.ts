const doc = `## What it does

Posts messages to a Discord channel via an incoming webhook. This is Conductor's outbound
**action** connector — workflow steps use it to *do* something in Discord (post a message),
not to read data back out.

## How authentication works

Create an incoming webhook in **Discord → Server Settings → Integrations → Webhooks → Copy Webhook
URL**, then paste it here as the **Webhook URL**. It's stored encrypted and is **write-only** — never
re-displayed by the API or written into workflow YAML.

## How Conductor uses it

Workflow steps call the \`post_message\` action with an \`action\` step:

\`\`\`yaml
- id: notify
  uses: action
  with:
    connector: discord
    action: post_message
    input:
      content: "Deploy finished: \${{ needs.build.outputs.version }}"
\`\`\`

\`content\` is required; \`username\` (override the webhook's display name) and \`embeds_json\` (a JSON
string of a Discord embeds array) are optional. The step exposes \`message_id\` and \`channel_id\` as
outputs, e.g. \`\${{ steps.notify.outputs.message_id }}\`.

## How an action step executes

\`\`\`mermaid
sequenceDiagram
    participant WF as Workflow run
    participant BE as Conductor action engine
    participant DC as Discord webhook
    WF->>BE: action step (connector: discord, action: post_message)
    BE->>BE: look up idempotent invocation record for this job run + step id
    BE->>DC: POST webhook_url (credential resolved at runtime)
    DC-->>BE: message posted (id, channel_id)
    BE-->>WF: step outputs (message_id, channel_id)
\`\`\`

**Idempotency.** Each invocation is keyed by the job run and step id, so re-driving the same run never
double-posts; a genuinely new run always posts again.

**Retries.** A network error or Discord 5xx is treated as transient and retried automatically (inline,
then a background sweep with backoff) before the action is dead-lettered; a 4xx rejection (bad
webhook URL, malformed input) is permanent and dead-letters immediately with no retry.

**Guard.** The webhook URL must be \`https://\` on \`discord.com\` or \`discordapp.com\` (including
subdomains) — anything else is rejected before any request is made, to prevent the credential being
used as an open outbound relay.
`

export default doc
