'use client'

import { useEffect, useMemo, useState } from 'react'
import { useRouter } from 'next/navigation'
import {
  listAgentProviders,
  listAgentTools,
  type Agent,
  type AgentProviderInfo,
  type AvailableAgentTool,
  type CreateAgentBody,
} from '@/lib/api'
import { useAuth } from '@/contexts/AuthContext'
import { isReservedTag } from '@/lib/tags'
import { Button } from '@/components/ui/button'
import { AgentAvatarPicker, randomAvatar } from '@/components/agents/AgentAvatarPicker'

const INPUT = 'w-full rounded-md border border-input bg-background px-3 py-2 text-sm placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring'
const LABEL = 'block text-sm font-medium text-foreground mb-1'

interface AgentFormProps {
  projectId: string
  /** Present when editing; absent when creating. */
  initial?: Agent
  submitLabel: string
  saving: boolean
  error: string | null
  onSubmit: (body: CreateAgentBody) => void
}

function numOrUndef(value: string): number | undefined {
  if (value.trim() === '') return undefined
  const n = Number(value)
  return Number.isFinite(n) ? n : undefined
}

export function AgentForm({ projectId, initial, submitLabel, saving, error, onSubmit }: AgentFormProps) {
  const { accessToken } = useAuth()
  const router = useRouter()

  const [providers, setProviders] = useState<AgentProviderInfo[]>([])
  const [tools, setTools] = useState<AvailableAgentTool[]>([])
  const [toolsLoaded, setToolsLoaded] = useState(false)

  const [name, setName] = useState(initial?.name ?? '')
  const [description, setDescription] = useState(initial?.description ?? '')
  const [tag, setTag] = useState(initial?.tag ?? '')
  const [provider, setProvider] = useState(initial?.provider ?? '')
  const [model, setModel] = useState(initial?.model ?? '')
  const [systemPrompt, setSystemPrompt] = useState(initial?.systemPrompt ?? '')
  const [temperature, setTemperature] = useState(initial?.config?.temperature?.toString() ?? '')
  const [maxTokens, setMaxTokens] = useState(initial?.config?.maxTokens?.toString() ?? '')
  const [maxToolTurns, setMaxToolTurns] = useState(initial?.config?.maxToolTurns?.toString() ?? '')
  const [selectedTools, setSelectedTools] = useState<Set<string>>(new Set(initial?.toolIds ?? []))
  const [state, setState] = useState<'DRAFT' | 'ACTIVE'>(initial?.state ?? 'DRAFT')
  const [nameError, setNameError] = useState<string | null>(null)
  const [tagError, setTagError] = useState<string | null>(null)
  // New agent: seed a random pair client-side so what's shown is what gets submitted (the server
  // only derives its own default when the fields are omitted, which this form never does).
  const [avatar, setAvatar] = useState(() =>
    initial ? { emoji: initial.avatarEmoji, color: initial.avatarColor } : randomAvatar()
  )

  useEffect(() => {
    if (!accessToken || !projectId) return
    listAgentProviders(projectId, accessToken)
      .then((p) => {
        setProviders(p)
        // Default the provider select to the first registered one on create.
        if (!initial && p.length > 0) setProvider((prev) => prev || p[0].id)
      })
      .catch(() => {})
    listAgentTools(projectId, accessToken)
      .then(setTools)
      .catch(() => {})
      .finally(() => setToolsLoaded(true))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [projectId, accessToken])

  const defaultModel = useMemo(
    () => providers.find((p) => p.id === provider)?.defaultModel ?? null,
    [providers, provider],
  )

  const toolsBySource = useMemo(() => {
    const groups: Record<string, AvailableAgentTool[]> = {}
    for (const t of tools) (groups[t.source] ??= []).push(t)
    return groups
  }, [tools])

  function toggleTool(id: string) {
    setSelectedTools((prev) => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })
  }

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    if (!name.trim()) {
      setNameError('Name is required.')
      return
    }
    setNameError(null)
    if (tag.trim() && isReservedTag(tag)) {
      setTagError(`"${tag.trim()}" is a reserved tag.`)
      return
    }
    setTagError(null)

    // Send the full form state so a PATCH saves exactly what's on screen: blanked text fields and
    // guardrails clear (the backend normalizes blanks), and an empty tool set clears the bindings.
    const config: CreateAgentBody['config'] = {
      temperature: numOrUndef(temperature) ?? null,
      maxTokens: numOrUndef(maxTokens) ?? null,
      maxToolTurns: numOrUndef(maxToolTurns) ?? null,
    }

    onSubmit({
      name: name.trim(),
      description: description.trim(),
      tag: tag.trim(),
      provider,
      model: model.trim(),
      systemPrompt: systemPrompt.trim(),
      config,
      toolIds: Array.from(selectedTools),
      state,
      avatarEmoji: avatar.emoji,
      avatarColor: avatar.color,
    })
  }

  return (
    <form onSubmit={handleSubmit} noValidate className="space-y-6 max-w-2xl">
      {/* Identity */}
      <div className="space-y-4">
        <div>
          <p className={LABEL}>Avatar</p>
          <AgentAvatarPicker
            emoji={avatar.emoji}
            color={avatar.color}
            onChange={setAvatar}
          />
        </div>
        <div>
          <label className={LABEL} htmlFor="agent-name">Name</label>
          <input
            id="agent-name"
            className={INPUT}
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder="Marketing Analyst"
            required
          />
          {nameError && <p className="text-sm text-destructive mt-1">{nameError}</p>}
          {initial && <p className="text-xs text-muted-foreground mt-1">Slug: {initial.slug}</p>}
        </div>
        <div>
          <label className={LABEL} htmlFor="agent-description">Description</label>
          <textarea
            id="agent-description"
            className={`${INPUT} resize-none`}
            rows={2}
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            placeholder="What this agent is for (optional)."
          />
        </div>
        <div>
          <label className={LABEL} htmlFor="agent-tag">Tag</label>
          <input
            id="agent-tag"
            className={INPUT}
            value={tag}
            onChange={(e) => setTag(e.target.value)}
            placeholder="e.g. engineering, marketing"
          />
          {tagError && <p className="text-sm text-destructive mt-1">{tagError}</p>}
        </div>
      </div>

      {/* Model */}
      <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
        <div>
          <label className={LABEL} htmlFor="agent-provider">Provider</label>
          <select
            id="agent-provider"
            className={INPUT}
            value={provider}
            onChange={(e) => setProvider(e.target.value)}
            disabled={providers.length === 0}
            required
          >
            {providers.length === 0 && <option value="">Loading…</option>}
            {providers.map((p) => (
              <option key={p.id} value={p.id}>{p.id.charAt(0).toUpperCase() + p.id.slice(1)}</option>
            ))}
          </select>
        </div>
        <div>
          <label className={LABEL} htmlFor="agent-model">Model</label>
          <input
            id="agent-model"
            className={INPUT}
            value={model}
            onChange={(e) => setModel(e.target.value)}
            placeholder={defaultModel ? `${defaultModel} (default)` : 'Provider default'}
          />
          <p className="text-xs text-muted-foreground mt-1">Leave blank to use the provider default.</p>
        </div>
      </div>

      {/* System prompt */}
      <div>
        <label className={LABEL} htmlFor="agent-system-prompt">System prompt</label>
        <textarea
          id="agent-system-prompt"
          className={`${INPUT} resize-y font-mono`}
          rows={6}
          value={systemPrompt}
          onChange={(e) => setSystemPrompt(e.target.value)}
          placeholder="You are a marketing analyst. Analyze the data and write a concise report…"
        />
      </div>

      {/* Guardrails */}
      <div>
        <h3 className="text-sm font-medium text-foreground mb-2">Generation guardrails</h3>
        <div className="grid grid-cols-1 sm:grid-cols-3 gap-4">
          <div>
            <label className={LABEL} htmlFor="agent-temperature">Temperature</label>
            <input
              id="agent-temperature"
              type="number"
              step="0.1"
              min="0"
              max="2"
              className={INPUT}
              value={temperature}
              onChange={(e) => setTemperature(e.target.value)}
              placeholder="default"
            />
          </div>
          <div>
            <label className={LABEL} htmlFor="agent-max-tokens">Max tokens</label>
            <input
              id="agent-max-tokens"
              type="number"
              min="1"
              className={INPUT}
              value={maxTokens}
              onChange={(e) => setMaxTokens(e.target.value)}
              placeholder="8192"
            />
          </div>
          <div>
            <label className={LABEL} htmlFor="agent-max-tool-turns">Max tool turns</label>
            <input
              id="agent-max-tool-turns"
              type="number"
              min="1"
              className={INPUT}
              value={maxToolTurns}
              onChange={(e) => setMaxToolTurns(e.target.value)}
              placeholder="8"
            />
          </div>
        </div>
      </div>

      {/* Tools */}
      <div>
        <h3 className="text-sm font-medium text-foreground mb-1">Tools</h3>
        <p className="text-xs text-muted-foreground mb-3">
          Tools this agent may call during a run.
        </p>
        {!toolsLoaded ? (
          <div className="text-sm text-muted-foreground">Loading tools…</div>
        ) : tools.length === 0 ? (
          <div className="rounded-md border border-border bg-muted/30 p-4 text-sm text-muted-foreground">
            No tools available. Connect an integration to expose tools to agents.
          </div>
        ) : (
          <div className="space-y-4">
            {Object.entries(toolsBySource).map(([source, group]) => (
              <div key={source}>
                <p className="text-xs font-semibold uppercase tracking-wide text-muted-foreground mb-1.5">{source}</p>
                <div className="space-y-1.5">
                  {group.map((tool) => (
                    <label
                      key={tool.id}
                      className="flex items-start gap-2.5 rounded-md border border-border p-2.5 hover:border-primary/50 transition-colors cursor-pointer"
                    >
                      <input
                        type="checkbox"
                        className="mt-0.5"
                        checked={selectedTools.has(tool.id)}
                        onChange={() => toggleTool(tool.id)}
                      />
                      <span className="min-w-0">
                        <span className="block text-sm font-medium text-foreground">{tool.name}</span>
                        {tool.description && (
                          <span className="block text-xs text-muted-foreground">{tool.description}</span>
                        )}
                        <span className="block text-xs text-muted-foreground/70 font-mono truncate">{tool.id}</span>
                      </span>
                    </label>
                  ))}
                </div>
              </div>
            ))}
          </div>
        )}
      </div>

      {/* State */}
      <div>
        <label className={LABEL} htmlFor="agent-state">State</label>
        <select
          id="agent-state"
          className={`${INPUT} max-w-[200px]`}
          value={state}
          onChange={(e) => setState(e.target.value as 'DRAFT' | 'ACTIVE')}
        >
          <option value="DRAFT">Draft</option>
          <option value="ACTIVE">Active</option>
        </select>
        <p className="text-xs text-muted-foreground mt-1">Only Active agents can be invoked by workflows.</p>
      </div>

      {error && <p className="text-sm text-destructive">{error}</p>}

      <div className="flex gap-3 pt-2">
        <Button type="submit" disabled={saving}>{saving ? 'Saving…' : submitLabel}</Button>
        <Button type="button" variant="outline" onClick={() => router.back()} disabled={saving}>Cancel</Button>
      </div>
    </form>
  )
}
