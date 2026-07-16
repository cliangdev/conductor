'use client'

export const dynamic = 'force-dynamic'

import { useEffect, useRef, useState } from 'react'
import { useParams } from 'next/navigation'
import { ChevronDownIcon } from 'lucide-react'
import { Alert } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { PageHeader } from '@/components/layout/PageHeader'
import { settingsBreadcrumbs } from '@/lib/navigation'

// ─── Types ────────────────────────────────────────────────────────────────────

interface CliCommandOption {
  flag: string
  description: string
}

interface CliCommand {
  name: string
  syntax: string
  description: string
  category: string
  options?: CliCommandOption[]
}

interface ClaudeAsset {
  name: string
  description: string
}

interface McpTool {
  name: string
  description: string
  category: string
  requiredParams?: string[]
}

interface CliManifest {
  package: {
    name: string
    version: string
    bin: string
    installCommand: string
  }
  commands: CliCommand[]
  claudeIntegration: {
    description: string
    mcpConfig: string
    slashCommands: ClaudeAsset[]
    agents: ClaudeAsset[]
    skills: ClaudeAsset[]
  }
  mcpTools: McpTool[]
}

// ─── Bundled fallback (shown when CDN is unreachable) ─────────────────────────

const BUNDLED_MANIFEST: CliManifest = {
  package: {
    name: '@cliangdev/conductor',
    version: '0.4.2',
    bin: 'conductor',
    installCommand: 'npm install -g @cliangdev/conductor',
  },
  commands: [
    { name: 'login', syntax: 'conductor login', description: 'Authenticate with Conductor via browser.', category: 'auth', options: [{ flag: '--force', description: 'Re-authenticate even if already logged in' }, { flag: '--local', description: 'Email/password login (local dev only)' }] },
    { name: 'logout', syntax: 'conductor logout', description: 'Clear saved Conductor credentials.', category: 'auth' },
    { name: 'init', syntax: 'conductor init', description: 'Initialize Conductor in the current project. Links the project, installs the Claude Code plugin, and starts the sync daemon.', category: 'project', options: [{ flag: '--project-id <id>', description: 'Project ID to link' }, { flag: '--path <dir>', description: 'Project directory (defaults to current)' }] },
    { name: 'start', syntax: 'conductor start', description: 'Start the file watcher daemon.', category: 'daemon' },
    { name: 'stop', syntax: 'conductor stop', description: 'Stop the file watcher daemon.', category: 'daemon' },
    { name: 'status', syntax: 'conductor status', description: 'Show daemon running state and sync queue depth.', category: 'daemon', options: [{ flag: '--json', description: 'Output as JSON' }] },
    { name: 'mcp', syntax: 'conductor mcp', description: 'Start the MCP stdio server for Claude Code integration.', category: 'mcp' },
    { name: 'dashboard', syntax: 'conductor dashboard', description: 'Show a live Conductor status dashboard in the terminal.', category: 'debug' },
    { name: 'doctor', syntax: 'conductor doctor', description: 'Run health checks: config, API connectivity, MCP server, and plugin installation.', category: 'debug', options: [{ flag: '--json', description: 'Output results as JSON' }] },
    { name: 'lint', syntax: 'conductor lint [issueId]', description: 'Lint local PRD and tasks.json artifacts against the current schema.', category: 'debug', options: [{ flag: '--json', description: 'Output as JSON' }] },
    { name: 'config show', syntax: 'conductor config show', description: 'Display the current CLI configuration (API key redacted).', category: 'config', options: [{ flag: '--json', description: 'Output as JSON' }] },
    { name: 'config use', syntax: 'conductor config use <env>', description: 'Switch to a named environment (e.g. prod).', category: 'config' },
    { name: 'config set-url', syntax: 'conductor config set-url <url>', description: 'Update the API base URL for self-hosted deployments.', category: 'config' },
  ],
  claudeIntegration: {
    description: 'Conductor ships a full Claude Code plugin — slash commands, skills, and a background agent — installed automatically by conductor init.',
    mcpConfig: '{\n  "mcpServers": {\n    "conductor": {\n      "command": "conductor",\n      "args": ["mcp"]\n    }\n  }\n}',
    slashCommands: [
      { name: 'conductor:prd', description: 'Create a PRD through guided discovery, research, and writing.' },
      { name: 'conductor:implement', description: 'Take a PRD issue from BACKLOG to a green PR — status transitions, branch, parallel coding agents, CI monitoring.' },
      { name: 'conductor:fix', description: 'Fix a bug found during PR review — root cause investigation, build validation, push to existing PR branch.' },
      { name: 'conductor:workflow', description: 'Design and create a Conductor workflow via guided discovery and MCP creation.' },
    ],
    agents: [
      { name: 'conductor-researcher', description: 'Researches unfamiliar technologies, libraries, and APIs for PRD creation. Auto-triggered when confidence is low.' },
    ],
    skills: [
      { name: 'conductor-coder', description: 'Conductor-native coding agent. Implements tasks TDD-style: writes tests first, implements until passing, commits with task ref.' },
      { name: 'conductor-ux-ui-design', description: 'Expert UX/UI design guidance for simple, user-focused interfaces.' },
    ],
  },
  mcpTools: [
    { name: 'create_issue', description: 'Create a new issue (Work Item) in the project.', category: 'issues', requiredParams: ['type', 'title'] },
    { name: 'update_issue', description: "Update an existing issue's title or description.", category: 'issues', requiredParams: ['issueId'] },
    { name: 'set_issue_status', description: 'Update the status of an issue.', category: 'issues', requiredParams: ['issueId', 'status'] },
    { name: 'list_issues', description: 'List issues in the project, optionally filtered by type or status.', category: 'issues' },
    { name: 'get_issue', description: 'Get a single issue by ID, including status and linked documents.', category: 'issues', requiredParams: ['issueId'] },
    { name: 'scaffold_document', description: 'Create an empty document file locally and register it with the backend. Returns absolutePath for use with the Write tool.', category: 'documents', requiredParams: ['issueId', 'filename'] },
    { name: 'delete_document', description: 'Delete a document from an issue locally and from the backend.', category: 'documents', requiredParams: ['issueId', 'documentId', 'filename'] },
    { name: 'list_issue_comments', description: 'List comments on an issue, optionally filtered by resolved status.', category: 'comments', requiredParams: ['issueId'] },
    { name: 'list_workflows', description: "List the project's Workflows. Discovery entry point — resolves workflow names to slugs.", category: 'workflows' },
    { name: 'get_available_transitions', description: 'Get valid next statuses for a Work Item. Review-gated transitions are hidden until approved.', category: 'workflows', requiredParams: ['issueId'] },
    { name: 'transition_work_item', description: 'Move a Work Item to a new status. Backend validates against the active Workflow and Review gate.', category: 'workflows', requiredParams: ['issueId', 'toStatus'] },
    { name: 'record_asset', description: 'Record a produced-output Asset on a Work Item (e.g. a github_pr link).', category: 'workflows', requiredParams: ['issueId', 'type', 'kind', 'ref'] },
    { name: 'report_step_run', description: 'Report an agent-run step on a Work Item for human review at a Review gate.', category: 'workflows', requiredParams: ['issueId', 'stepKind', 'status', 'inputBrief', 'reportedBy'] },
    { name: 'create_workflow', description: 'Create a new workflow definition in DRAFT state. Returns workflowId.', category: 'workflows', requiredParams: ['name', 'area'] },
    { name: 'get_workflow', description: 'Get a workflow definition by ID. Verify after create or update.', category: 'workflows', requiredParams: ['workflowId'] },
    { name: 'update_workflow', description: 'Update a DRAFT workflow definition. Fix validation errors before retrying publish.', category: 'workflows', requiredParams: ['workflowId'] },
    { name: 'publish_workflow', description: 'Promote a workflow from DRAFT to PUBLISHED. Returns success and any validation errors.', category: 'workflows', requiredParams: ['workflowId'] },
    { name: 'dispatch_workflow', description: 'Manually trigger a workflow run for testing. Returns runId.', category: 'workflows', requiredParams: ['workflowId'] },
    { name: 'get_workflow_run', description: 'Get status and step details for a workflow run (PENDING/RUNNING/SUCCESS/FAILED).', category: 'workflows', requiredParams: ['workflowId', 'runId'] },
    { name: 'list_integration_tools', description: 'List connected integrations and their available data operations. Always call before designing a workflow.', category: 'discovery' },
    { name: 'list_agents', description: "List the project's named AI Agents (id, slug, provider, model, state). Discovery for workflow authoring.", category: 'discovery' },
  ],
}

const MANIFEST_URL = 'https://unpkg.com/@cliangdev/conductor@latest/assets/cli-manifest.json'

// ─── Helpers ──────────────────────────────────────────────────────────────────

function CodeBlock({ code }: { code: string }) {
  const [copied, setCopied] = useState(false)

  function handleCopy() {
    navigator.clipboard.writeText(code).then(() => {
      setCopied(true)
      setTimeout(() => setCopied(false), 2000)
    })
  }

  return (
    <div className="relative group">
      <pre className="bg-muted rounded-md px-4 py-3 text-sm font-mono overflow-x-auto whitespace-pre-wrap break-all">
        <code>{code}</code>
      </pre>
      <button
        onClick={handleCopy}
        className="absolute top-2 right-2 opacity-0 group-hover:opacity-100 transition-opacity p-1.5 rounded bg-background border text-xs"
        aria-label="Copy"
      >
        {copied ? 'Copied!' : 'Copy'}
      </button>
    </div>
  )
}

function SectionLabel({ children }: { children: React.ReactNode }) {
  return (
    <div className="px-0 pb-2 pt-1 text-xs font-semibold uppercase tracking-wider text-muted-foreground">
      {children}
    </div>
  )
}

function CollapsibleSection({
  id,
  title,
  children,
  defaultOpen = true,
}: {
  id: string
  title: string
  children: React.ReactNode
  defaultOpen?: boolean
}) {
  return (
    <details open={defaultOpen} className="group border-b border-border last:border-0">
      <summary className="flex items-center justify-between cursor-pointer list-none py-4 select-none">
        <h2 id={id} className="text-base font-semibold text-foreground scroll-mt-4">
          {title}
        </h2>
        <ChevronDownIcon className="h-4 w-4 text-muted-foreground transition-transform duration-200 group-open:rotate-180" />
      </summary>
      <div className="pb-6 space-y-4">{children}</div>
    </details>
  )
}

function CategoryGroup({
  label,
  children,
}: {
  label: string
  children: React.ReactNode
}) {
  return (
    <details open className="group/cat">
      <summary className="flex items-center gap-2 cursor-pointer list-none py-1.5 select-none">
        <ChevronDownIcon className="h-3 w-3 text-muted-foreground transition-transform duration-150 group-open/cat:rotate-180" />
        <SectionLabel>{label}</SectionLabel>
      </summary>
      <div className="space-y-2 mt-1">{children}</div>
    </details>
  )
}

// ─── Sections ─────────────────────────────────────────────────────────────────

const COMMAND_CATEGORY_ORDER = ['auth', 'project', 'daemon', 'mcp', 'debug', 'config']
const COMMAND_CATEGORY_LABELS: Record<string, string> = {
  auth: 'Authentication',
  project: 'Project',
  daemon: 'Daemon',
  mcp: 'MCP Server',
  debug: 'Debug',
  config: 'Configuration',
}

function CommandsSection({ commands }: { commands: CliCommand[] }) {
  const grouped: Record<string, CliCommand[]> = {}
  for (const cmd of commands) {
    if (!grouped[cmd.category]) grouped[cmd.category] = []
    grouped[cmd.category].push(cmd)
  }

  return (
    <div className="space-y-2">
      {COMMAND_CATEGORY_ORDER.filter((c) => grouped[c]).map((cat) => (
        <CategoryGroup key={cat} label={COMMAND_CATEGORY_LABELS[cat] ?? cat}>
          <div className="space-y-2">
            {grouped[cat].map((cmd) => (
              <div key={cmd.name} className="bg-card rounded-lg border border-border p-4">
                <div className="flex items-start justify-between gap-4 flex-wrap">
                  <code className="font-mono text-sm text-foreground">{cmd.syntax}</code>
                  <Badge variant="outline" className="text-xs shrink-0">
                    {COMMAND_CATEGORY_LABELS[cmd.category] ?? cmd.category}
                  </Badge>
                </div>
                <p className="text-sm text-muted-foreground mt-1">{cmd.description}</p>
                {cmd.options && cmd.options.length > 0 && (
                  <div className="mt-3 space-y-1.5 border-t border-border pt-3">
                    {cmd.options.map((opt) => (
                      <div key={opt.flag} className="flex gap-4 text-xs">
                        <code className="font-mono text-muted-foreground w-40 shrink-0">{opt.flag}</code>
                        <span className="text-muted-foreground">{opt.description}</span>
                      </div>
                    ))}
                  </div>
                )}
              </div>
            ))}
          </div>
        </CategoryGroup>
      ))}
    </div>
  )
}

function AssetRow({ name, description, prefix = '' }: { name: string; description: string; prefix?: string }) {
  return (
    <div className="bg-card rounded-lg border border-border p-4 flex items-start gap-4">
      <code className="font-mono text-sm text-foreground w-52 shrink-0 break-all">
        {prefix}{name}
      </code>
      <p className="text-sm text-muted-foreground flex-1">{description}</p>
    </div>
  )
}

function ClaudeSection({ integration }: { integration: CliManifest['claudeIntegration'] }) {
  return (
    <div className="space-y-6">
      <p className="text-sm text-muted-foreground">{integration.description}</p>

      <div>
        <h3 id="mcp-config" className="text-sm font-medium text-foreground mb-2 scroll-mt-4">
          MCP Configuration
        </h3>
        <p className="text-xs text-muted-foreground mb-2">
          Added to <code className="font-mono bg-muted px-1 py-0.5 rounded">.mcp.json</code> automatically by{' '}
          <code className="font-mono bg-muted px-1 py-0.5 rounded">conductor init</code>.
        </p>
        <div className="max-w-sm">
          <CodeBlock code={integration.mcpConfig} />
        </div>
      </div>

      <div>
        <h3 id="slash-commands" className="text-sm font-medium text-foreground mb-2 scroll-mt-4">
          Slash Commands
        </h3>
        <div className="space-y-2">
          {integration.slashCommands.map((cmd) => (
            <AssetRow key={cmd.name} name={cmd.name} description={cmd.description} prefix="/" />
          ))}
        </div>
      </div>

      <div>
        <h3 id="skills" className="text-sm font-medium text-foreground mb-2 scroll-mt-4">
          Skills
        </h3>
        <div className="space-y-2">
          {integration.skills.map((skill) => (
            <AssetRow key={skill.name} name={skill.name} description={skill.description} />
          ))}
        </div>
      </div>

      <div>
        <h3 id="agents" className="text-sm font-medium text-foreground mb-2 scroll-mt-4">
          Agents
        </h3>
        <div className="space-y-2">
          {integration.agents.map((agent) => (
            <AssetRow key={agent.name} name={agent.name} description={agent.description} />
          ))}
        </div>
      </div>
    </div>
  )
}

const MCP_CATEGORY_ORDER = ['issues', 'documents', 'comments', 'workflows', 'discovery']
const MCP_CATEGORY_LABELS: Record<string, string> = {
  issues: 'Issues',
  documents: 'Documents',
  comments: 'Comments',
  workflows: 'Workflows',
  discovery: 'Discovery',
}

function McpToolsSection({ tools }: { tools: McpTool[] }) {
  const grouped: Record<string, McpTool[]> = {}
  for (const tool of tools) {
    if (!grouped[tool.category]) grouped[tool.category] = []
    grouped[tool.category].push(tool)
  }

  return (
    <div className="space-y-2">
      {MCP_CATEGORY_ORDER.filter((c) => grouped[c]).map((cat) => (
        <CategoryGroup key={cat} label={MCP_CATEGORY_LABELS[cat] ?? cat}>
          <div className="space-y-2">
            {grouped[cat].map((tool) => (
              <div key={tool.name} className="bg-card rounded-lg border border-border p-4">
                <div className="flex items-start gap-4">
                  <code className="font-mono text-sm text-foreground w-52 shrink-0 break-all">{tool.name}</code>
                  <p className="text-sm text-muted-foreground flex-1">{tool.description}</p>
                </div>
                {tool.requiredParams && tool.requiredParams.length > 0 && (
                  <div className="mt-2 flex gap-1.5 flex-wrap pl-[calc(13rem+1rem)]">
                    {tool.requiredParams.map((p) => (
                      <code
                        key={p}
                        className="text-xs bg-muted rounded px-1.5 py-0.5 text-muted-foreground"
                      >
                        {p}*
                      </code>
                    ))}
                  </div>
                )}
              </div>
            ))}
          </div>
        </CategoryGroup>
      ))}
    </div>
  )
}

// ─── Anchor nav ───────────────────────────────────────────────────────────────

const NAV_ITEMS = [
  { id: 'install', label: 'Install' },
  { id: 'commands', label: 'Commands' },
  {
    id: 'claude',
    label: 'Claude Integration',
    children: [
      { id: 'mcp-config', label: 'MCP Configuration' },
      { id: 'slash-commands', label: 'Slash Commands' },
      { id: 'skills', label: 'Skills' },
      { id: 'agents', label: 'Agents' },
    ],
  },
  { id: 'mcp-tools', label: 'MCP Tools' },
]

function AnchorNav() {
  const [active, setActive] = useState<string>('install')
  const observerRef = useRef<IntersectionObserver | null>(null)

  useEffect(() => {
    const allIds = NAV_ITEMS.flatMap((item) =>
      item.children ? [item.id, ...item.children.map((c) => c.id)] : [item.id]
    )

    const entries = new Map<string, number>()

    observerRef.current = new IntersectionObserver(
      (observed) => {
        for (const entry of observed) {
          entries.set(entry.target.id, entry.intersectionRatio)
        }
        const topmost = allIds.find((id) => (entries.get(id) ?? 0) > 0)
        if (topmost) setActive(topmost)
      },
      { rootMargin: '-8px 0px -80% 0px', threshold: [0, 0.5, 1] }
    )

    for (const id of allIds) {
      const el = document.getElementById(id)
      if (el) observerRef.current.observe(el)
    }

    return () => observerRef.current?.disconnect()
  }, [])

  function scrollTo(id: string) {
    document.getElementById(id)?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  }

  return (
    <nav className="w-44 shrink-0 sticky top-8 self-start hidden lg:block">
      <p className="text-xs font-semibold uppercase tracking-wider text-muted-foreground mb-3">
        On this page
      </p>
      <ul className="space-y-1">
        {NAV_ITEMS.map((item) => (
          <li key={item.id}>
            <button
              onClick={() => scrollTo(item.id)}
              className={`text-sm text-left w-full truncate transition-colors hover:text-foreground ${
                active === item.id ? 'text-foreground font-medium' : 'text-muted-foreground'
              }`}
            >
              {item.label}
            </button>
            {item.children && (
              <ul className="ml-3 mt-0.5 space-y-0.5 border-l border-border pl-2">
                {item.children.map((child) => (
                  <li key={child.id}>
                    <button
                      onClick={() => scrollTo(child.id)}
                      className={`text-xs text-left w-full truncate transition-colors hover:text-foreground ${
                        active === child.id ? 'text-foreground font-medium' : 'text-muted-foreground'
                      }`}
                    >
                      {child.label}
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </li>
        ))}
      </ul>
    </nav>
  )
}

// ─── Page ─────────────────────────────────────────────────────────────────────

export default function CliPage() {
  const { projectId } = useParams<{ projectId: string }>()
  const [manifest, setManifest] = useState<CliManifest | null>(null)
  const [loadError, setLoadError] = useState(false)

  useEffect(() => {
    const controller = new AbortController()
    fetch(MANIFEST_URL, { signal: controller.signal })
      .then((r) => {
        if (!r.ok) throw new Error(`HTTP ${r.status}`)
        return r.json() as Promise<CliManifest>
      })
      .then(setManifest)
      .catch(() => {
        if (!controller.signal.aborted) {
          setLoadError(true)
          setManifest(BUNDLED_MANIFEST)
        }
      })
    return () => controller.abort()
  }, [])

  const version = manifest?.package.version

  return (
    <div className="space-y-6">
      <PageHeader
        title="CLI"
        description="Install and configure the Conductor CLI and Claude Code integration."
        breadcrumbs={settingsBreadcrumbs(projectId, 'settings-cli')}
        actions={
          version ? (
            <Badge variant="outline" className="font-mono text-xs">
              v{version}
            </Badge>
          ) : (
            <div className="h-5 w-14 bg-muted animate-pulse rounded-full" />
          )
        }
      />

      {loadError && (
        <Alert variant="warning">
          Could not reach the npm registry. Showing bundled reference — may not reflect the latest version.
        </Alert>
      )}

      <div className="flex gap-10 items-start">
        {/* Main content */}
        <div className="flex-1 min-w-0">
          {!manifest ? (
            <div className="space-y-4">
              {[0, 1, 2].map((i) => (
                <div key={i} className="h-32 bg-muted rounded-lg animate-pulse" />
              ))}
            </div>
          ) : (
            <div className="divide-y divide-border">
              <CollapsibleSection id="install" title="Install">
                <div className="space-y-3 max-w-lg">
                  <CodeBlock code={manifest.package.installCommand} />
                  <p className="text-sm text-muted-foreground">
                    After installing, run{' '}
                    <code className="font-mono text-xs bg-muted px-1 py-0.5 rounded">conductor init</code>{' '}
                    inside any project to link it, install the Claude Code plugin, and start the sync daemon.
                  </p>
                  <CodeBlock code="conductor init" />
                </div>
              </CollapsibleSection>

              <CollapsibleSection id="commands" title="Commands">
                <CommandsSection commands={manifest.commands} />
              </CollapsibleSection>

              <CollapsibleSection id="claude" title="Claude Integration">
                <ClaudeSection integration={manifest.claudeIntegration} />
              </CollapsibleSection>

              <CollapsibleSection id="mcp-tools" title="MCP Tools">
                <McpToolsSection tools={manifest.mcpTools} />
              </CollapsibleSection>
            </div>
          )}
        </div>

        {/* Sticky anchor nav */}
        <AnchorNav />
      </div>
    </div>
  )
}
