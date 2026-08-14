# AI Providers

An **Agent** runs against a **model provider** — the vendor whose API answers its ReAct loop. Conductor
is bring-your-own-key: each project stores its own credential per provider, agents pick a provider (and
optionally pin a model), and Conductor never bills or proxies usage on your behalf.

## Table of contents

- [The BYO-key model](#the-byo-key-model)
- [Registered providers](#registered-providers)
- [Connection states](#connection-states)
- [Picking a provider and model](#picking-a-provider-and-model)
- [Model discovery](#model-discovery)
- [Runtime interaction](#runtime-interaction)
- [Extending: adding a provider](#extending-adding-a-provider)

---

## The BYO-key model

One credential per `(project, provider)` — set from **Settings → AI Providers**. Saving a key replaces
any existing one for that provider; the plaintext value is never returned by the API or displayed again
after saving, and it never appears in workflow YAML or step logs — an `agent` step's provider credential
is resolved at run time by `AgentExecutionService`, not passed through the workflow definition. At rest
it's envelope-encrypted (a per-row AES-256 data key wrapped by a GCP KMS key), the same pattern the
connector framework uses for its own credentials.

`GET /api/v1/projects/{projectId}/agents/providers/credentials` lists every provider's status
(configured or not, plus last verification) in one call — this backs the Settings page and is the
source `ProviderCredentialService#listStatuses` also uses internally to build actionable error messages
(e.g. "no key for `openai`, but this project has a key for `claude`").

## Registered providers

| Provider id | What it is | Selectable as an agent's `provider`? |
|---|---|---|
| `claude` | Anthropic API key. | Yes. |
| `openai` | OpenAI API key. | Yes. |
| `claude-code` | The Claude Code subscription OAuth token (`claude setup-token`), consumed by `claude-code` workflow steps and the `claude-code` agent runtime — see [`docs/workflows.md`](workflows.md#claude-code--run-claude-code-headlessly). | **No** — it's a credential id, not a `ChatModelProvider`. Deliberately kept out of `ModelProviderRegistry` so it can never be picked as an agent's model provider; `ProviderCredentialService.NON_MODEL_PROVIDERS` is the one place that still has to know about it (so it can be stored/verified through the same credential surface). |

Only `claude` and `openai` are chat-completion providers today — there is no Gemini provider, no
streaming, no Azure/OpenAI-compatible custom base URL, and no per-model cost accounting. If a future
provider needs any of those, it's new scope, not an extension of what exists.

## Connection states

Each provider's row on the Settings page shows one of three states:

| State | Meaning |
|---|---|
| **Not connected** | No credential stored for this provider in this project. |
| **Connected** (unverified) | A key is stored, but no live probe has run against it yet — saving alone doesn't prove the key works. |
| **Verified** / **Error** | A real preflight probe ran (`ClaudeApiPreflight`/`OpenAiApiPreflight`, dispatched through the `ProviderPreflight` SPI) and either succeeded or reported a concrete failure. Verification runs automatically right after a save, and on demand via the row's **Verify** button. |

A **Verified** badge carries a relative timestamp ("Verified · 2h ago") and flips to a `(stale)` suffix
once it's more than 7 days old — the key hasn't necessarily stopped working, but nothing has re-confirmed
it recently, so treat a stale badge as a prompt to re-verify rather than a failure.

## Picking a provider and model

An agent's `provider` is required and validated against `ModelProviderRegistry` at create/update time —
an unregistered id (including `claude-code`, see above) is rejected outright. `model` is nullable:

- Left blank, the provider supplies its own default. For `openai` that's resolved live — the newest
  model [model discovery](#model-discovery) reports for that specific key — so a project tracks new
  releases without a code change. For `claude` it's a pinned constant (`ClaudeProvider.DEFAULT_MODEL`).
  The tradeoff is real and cuts both ways: a live default keeps you current, but a vendor-side release
  can change a blank-model agent's behavior with nobody touching its config. If that matters for a given
  agent, pin the model.
- Pinned to a specific id (e.g. `claude-opus-4-8`, `gpt-5.4`), the agent always uses exactly that model —
  useful when you've validated behavior against one version and don't want a vendor-side rollout to
  change it under you, or when you deliberately want an older/cheaper tier than the current default.

Nothing here is enforced beyond "the provider is registered" — an agent's `model` string is passed
through to the provider's API as-is, so a typo or a retired model id surfaces as a run failure, not a
save-time validation error.

## Model discovery

`GET /api/v1/projects/{projectId}/agents/providers/{provider}/models` backs the Agents-form model
picker: it calls the provider's live models-list API with the project's stored key and returns what that
account can actually use today, instead of a hardcoded list going stale as vendors ship new models.

- **Claude**: no filtering — Anthropic's models-list API only returns general-purpose Claude chat models.
- **OpenAI**: the raw catalog mixes in embeddings, audio/realtime/transcription/TTS models, web-search
  and image variants, the coding-specialized `codex` variant, and dated snapshots (e.g.
  `gpt-5.2-2025-12-11`) alongside the rolling aliases that supersede them — all excluded, along with the
  `-mini`/`-nano` cheaper tiers, since none of those are "the latest general-purpose flagship" a
  blank-model agent should default onto. "Latest" means the newest id `ChatModel` recognizes after that
  filter, sorted by creation date — not literally the newest id string, which could be a snapshot or a
  specialized variant shipped after the flagship.

Both providers cache a successful listing for 30 minutes and an empty/failed one for 5 (so a dead or
rate-limited key doesn't get retried on every keystroke), and neither ever throws for a listing failure —
an unreachable vendor API degrades to an empty suggestion list, never a broken picker.

## Runtime interaction

Model provider and execution runtime are different axes. The `api` runtime — the in-process ReAct loop —
runs any registered provider. The `claude-code` runtime is a headless Claude Code container and is
**Claude-only by design**: the container always runs Claude regardless of what an agent's `provider` says,
so an agent on `openai` (or any future non-Claude provider) is never auto-detected onto `claude-code` — it
resolves to `api`. An explicit `runtime: claude-code` pin on such an agent is still honoured as a
deliberate operator override, and it will run Claude rather than the provider the definition names. See
[`docs/workflows.md#runtimes`](workflows.md#runtimes) for the full resolution order and guardrail
differences between the two runtimes.

## Extending: adding a provider

Both the chat-completion surface and its verification probe are auto-discovered Spring bean lists, not a
switch statement to edit:

1. A `ChatModelProvider` bean — implements `id()`, `complete()`, and optionally `defaultModel()` /
   `availableModels()`. `ModelProviderRegistry` picks it up automatically and it becomes selectable as an
   agent's `provider`. Worked example: `OpenAiProvider.java`.
2. A `ProviderPreflight` bean — implements `provider()` and `check()`, returning one or more `Check`s.
   `ProviderVerificationService` dispatches to it by `provider()` id, so the new provider earns a real
   **Verified** badge instead of the warn-only "verification not supported" report a provider gets by
   default. Worked example: `OpenAiProviderPreflight.java`, a six-line adapter over the shared
   resolve-decrypt-delegate base, wrapping the actual network probe in `OpenAiApiPreflight.java`.

No registry edit, no new switch case, no frontend wiring beyond adding a card for the new provider (the
generic `ProviderCredentialCard` component handles the connect/verify UX for any provider row it's given).
