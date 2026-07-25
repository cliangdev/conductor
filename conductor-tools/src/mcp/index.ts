#!/usr/bin/env node
import { Server } from '@modelcontextprotocol/sdk/server/index.js'
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js'
import { ListToolsRequestSchema, CallToolRequestSchema } from '@modelcontextprotocol/sdk/types.js'
import { getConfig, resolveProject } from './config.js'
import {
  createWorkItem,
  updateWorkItem,
  setWorkItemStatus,
  listWorkItems,
  getWorkItem,
} from './tools/issues.js'
import { deleteDocument, scaffoldDocument, writeDocument } from './tools/documents.js'
import { listWorkItemComments } from './tools/comments.js'
import {
  listWorkflows,
  getAvailableTransitions,
  transitionWorkItem,
  recordAsset,
  reportStepRun,
  createWorkflow,
  getWorkflow,
  updateWorkflow,
  deleteWorkflow,
  publishWorkflow,
  dispatchWorkflow,
  cancelWorkflowRun,
  getWorkflowRun,
  listWorkflowRuns,
  listWorkflowSecrets,
  getWorkflowStepSchema,
} from './tools/workflows.js'
import { listIntegrationTools, listConnectorCatalog } from './tools/integrations.js'
import { listAgents, createAgent, updateAgent, deleteAgent } from './tools/agents.js'
import { listSkills, registerSkill } from './tools/skills.js'
import {
  submitKnowledgeSource,
  readKnowledgeSources,
  searchKnowledge,
  readKnowledgePages,
  writeKnowledgePages,
  listKnowledgeDomains,
  suggestKnowledgeDomain,
  type KnowledgePageWrite,
} from './tools/knowledge.js'

const TOOLS = [
  // --- Canonical Work Item tools (v2 /work-items surface) ---
  {
    name: 'create_work_item',
    description: 'Create a new Work Item in the project (targets the v2 work-items API). Discover-then-create: call list_workflows({kind:"LIFECYCLE"}) first, pick the Workflow whose vocabulary fits (its area + allowed types), then pass that Workflow slug explicitly — do not assume ENGINEERING.',
    inputSchema: {
      type: 'object',
      properties: {
        workflow: { type: 'string', description: 'Lifecycle Workflow slug that governs this Work Item (required). Discover with list_workflows({kind:"LIFECYCLE"}).' },
        type: { type: 'string', description: 'Work Item type, validated against the chosen Workflow\'s allowed types (e.g. PRD, FEATURE_REQUEST, BUG_REPORT)' },
        title: { type: 'string', description: 'Work Item title' },
        description: { type: 'string', description: 'Work Item description (optional)' },
      },
      required: ['workflow', 'type', 'title'],
    },
  },
  {
    name: 'update_work_item',
    description: 'Update an existing Work Item. Canonical tool (targets the v2 work-items API).',
    inputSchema: {
      type: 'object',
      properties: {
        issueId: { type: 'string', description: 'Work Item ID' },
        title: { type: 'string', description: 'New title (optional)' },
        description: { type: 'string', description: 'New description (optional)' },
      },
      required: ['issueId'],
    },
  },
  {
    name: 'set_work_item_status',
    description: 'Update the status of a Work Item. Canonical tool (targets the v2 work-items API). For Workflow-validated moves prefer transition_work_item.',
    inputSchema: {
      type: 'object',
      properties: {
        issueId: { type: 'string', description: 'Work Item ID' },
        status: { type: 'string', description: 'New status' },
      },
      required: ['issueId', 'status'],
    },
  },
  {
    name: 'list_work_items',
    description: 'List Work Items in the project. Canonical tool (targets the v2 work-items API).',
    inputSchema: {
      type: 'object',
      properties: {
        type: { type: 'string', description: 'Filter by type (optional)' },
        status: { type: 'string', description: 'Filter by status (optional)' },
        workflow: { type: 'string', description: 'Filter by bound Workflow slug (optional, e.g. ENGINEERING)' },
      },
    },
  },
  {
    name: 'get_work_item',
    description: 'Get a single Work Item by ID. Canonical tool (targets the v2 work-items API).',
    inputSchema: {
      type: 'object',
      properties: {
        issueId: { type: 'string', description: 'Work Item ID' },
      },
      required: ['issueId'],
    },
  },
  {
    name: 'list_work_item_comments',
    description: 'List comments on a Work Item, optionally filtered by resolved status. Canonical tool.',
    inputSchema: {
      type: 'object',
      properties: {
        issueId: { type: 'string', description: 'Work Item ID' },
        resolved: {
          type: 'boolean',
          description: 'Filter by resolved status. true = resolved only, false = unresolved only, omit = all comments',
        },
      },
      required: ['issueId'],
    },
  },
  {
    name: 'scaffold_document',
    description: 'Create an empty document file locally and register it with the backend. Returns absolutePath (use this with the Write tool — Write requires absolute paths) and localPath (relative, for display). Prefer write_document — this tool requires the local daemon and does not work in headless containers.',
    inputSchema: {
      type: 'object',
      properties: {
        issueId: { type: 'string', description: 'Issue ID' },
        filename: { type: 'string', description: 'Document filename (e.g., prd.md)' },
      },
      required: ['issueId', 'filename'],
    },
  },
  {
    name: 'write_document',
    description: 'Create or update a Work Item document by filename with full content (upsert). Works headlessly (workflow containers) — supersedes scaffold_document+Write for new documents. Verify via the returned document response.',
    inputSchema: {
      type: 'object',
      properties: {
        issueId: { type: 'string', description: 'Work Item (issue) ID' },
        filename: { type: 'string', description: 'Document filename (e.g., prd.md)' },
        content: { type: 'string', description: 'Full document content' },
        contentType: { type: 'string', description: 'MIME type (optional, defaults to text/markdown)' },
      },
      required: ['issueId', 'filename', 'content'],
    },
  },
  {
    name: 'delete_document',
    description: 'Delete a document from an issue',
    inputSchema: {
      type: 'object',
      properties: {
        issueId: { type: 'string', description: 'Issue ID' },
        documentId: { type: 'string', description: 'Document ID' },
        filename: { type: 'string', description: 'Document filename for local deletion' },
      },
      required: ['issueId', 'documentId', 'filename'],
    },
  },
  {
    name: 'list_workflows',
    description: 'List the project\'s Workflows, each flattened to {slug, name, area, noun, kind, state, version, workflowId, types, statuses}. Discovery entry point: filter by kind=LIFECYCLE and match the user\'s intent to a Workflow (its area + allowed types) to pick the slug for create_work_item; kind=AUTOMATION lists YAML run-automations.',
    inputSchema: {
      type: 'object',
      properties: {
        kind: { type: 'string', enum: ['LIFECYCLE', 'AUTOMATION'], description: 'Filter by Workflow kind (optional). LIFECYCLE = statechart governing Work Items; AUTOMATION = YAML run-automation.' },
      },
    },
  },
  {
    name: 'get_available_transitions',
    description: 'Get the valid next statuses for a Work Item from its current status (the doer projection). Review-gated transitions are hidden until satisfied. Walk a Work Item by calling this, then transition_work_item.',
    inputSchema: {
      type: 'object',
      properties: { issueId: { type: 'string', description: 'Work Item (issue) ID' } },
      required: ['issueId'],
    },
  },
  {
    name: 'transition_work_item',
    description: 'Move a Work Item to a new status. The backend validates the move against the active Workflow and the Review gate (rejects an invalid or un-approved transition).',
    inputSchema: {
      type: 'object',
      properties: {
        issueId: { type: 'string', description: 'Work Item (issue) ID' },
        toStatus: { type: 'string', description: 'Target status (one of get_available_transitions)' },
      },
      required: ['issueId', 'toStatus'],
    },
  },
  {
    name: 'record_asset',
    description: 'Record a produced-output Asset on a Work Item (e.g. a github_pr). Type is validated against the Workflow\'s asset_types.',
    inputSchema: {
      type: 'object',
      properties: {
        issueId: { type: 'string', description: 'Work Item (issue) ID' },
        type: { type: 'string', description: 'Asset type (e.g. github_pr)' },
        kind: { type: 'string', enum: ['link', 'file'], description: 'link (URL) or file (stored reference)' },
        ref: { type: 'string', description: 'URL or stored-file reference' },
        label: { type: 'string', description: 'Optional label' },
        done: { type: 'boolean', description: 'Optional done flag' },
      },
      required: ['issueId', 'type', 'kind', 'ref'],
    },
  },
  {
    name: 'list_integration_tools',
    description: 'List connected integrations and their available data operations for workflow authoring. Always call before designing a workflow — returns ACTIVE connections with connectorId, displayLabel, capabilities, and toolMetadata (description + operations list with id, outputShape, and outputKeys). Use connectorId in workflow YAML as: uses: integration / with: / connector: <connectorId> / operation: <operationId>',
    inputSchema: { type: 'object', properties: {} },
  },
  {
    name: 'list_connector_catalog',
    description: 'List ALL connector types Conductor supports (connected or not): capabilities (FETCH/ACTION/WEBHOOK), config fields, and whether this project has an active connection. Use to recommend integrations to connect; use list_integration_tools for operations/actions of ACTIVE connections.',
    inputSchema: { type: 'object', properties: {} },
  },
  {
    name: 'list_agents',
    description: 'List the project\'s named AI Agents (id, slug, provider, model, state). Discovery for workflow authoring: resolve an agent name to its slug before referencing it from a workflow agent step. Also the read-back companion for create_agent — call after creating to verify the stored result.',
    inputSchema: { type: 'object', properties: {} },
  },
  {
    name: 'create_agent',
    description: 'Create a named AI Agent in the project (provider, model, system prompt, tool bindings, guardrails). Returns the created agent. Always call list_agents after to verify it was stored correctly.',
    inputSchema: {
      type: 'object',
      properties: {
        name: { type: 'string', description: 'Agent display name' },
        provider: { type: 'string', description: 'Model provider id (e.g. "claude"). Discover with list_agents or the providers endpoint.' },
        slug: { type: 'string', description: 'URL-safe unique slug (optional — derived from name if omitted)' },
        description: { type: 'string', description: 'What this agent does (optional)' },
        model: { type: 'string', description: 'Model id override (optional — provider default applies if omitted)' },
        systemPrompt: { type: 'string', description: 'System prompt (optional)' },
        config: {
          type: 'object',
          description: 'Generation guardrails, all optional',
          properties: {
            temperature: { type: 'number' },
            maxTokens: { type: 'integer' },
            maxToolTurns: { type: 'integer' },
            runtime: { type: 'string', enum: ['api', 'claude-code'], description: 'Pins the runtime that executes this agent\'s workflow steps. Omit to auto-detect.' },
          },
        },
        toolIds: { type: 'array', items: { type: 'string' }, description: 'Namespaced tool ids the agent may call (optional)' },
        state: { type: 'string', enum: ['DRAFT', 'ACTIVE'], description: 'Defaults to DRAFT if omitted' },
        avatarEmoji: { type: 'string', description: 'Avatar emoji (optional — a default is derived from the slug)' },
        avatarColor: { type: 'string', enum: ['gray', 'blue', 'amber', 'violet', 'teal', 'green', 'rose', 'slate'], description: 'Avatar color token (optional — a default is derived from the slug)' },
      },
      required: ['name', 'provider'],
    },
  },
  {
    name: 'update_agent',
    description: 'Update an existing AI Agent. Partial: only the fields you supply change, everything omitted keeps its stored value. Also how you set an ACTIVE agent back to DRAFT ({state: "DRAFT"}) so it can be deleted. Always call list_agents after to verify the stored result.',
    inputSchema: {
      type: 'object',
      properties: {
        agentId: { type: 'string', description: 'Agent ID (from list_agents)' },
        name: { type: 'string', description: 'New display name (optional)' },
        provider: { type: 'string', description: 'New model provider id (optional)' },
        slug: { type: 'string', description: 'New URL-safe unique slug (optional)' },
        description: { type: 'string', description: 'New description (optional)' },
        model: { type: 'string', description: 'New model id (optional)' },
        systemPrompt: { type: 'string', description: 'New system prompt (optional)' },
        config: {
          type: 'object',
          description: 'Generation guardrails, all optional',
          properties: {
            temperature: { type: 'number' },
            maxTokens: { type: 'integer' },
            maxToolTurns: { type: 'integer' },
            runtime: { type: 'string', enum: ['api', 'claude-code'], description: 'Pins the runtime that executes this agent\'s workflow steps.' },
          },
        },
        toolIds: { type: 'array', items: { type: 'string' }, description: 'Replacement list of namespaced tool ids (optional)' },
        state: { type: 'string', enum: ['DRAFT', 'ACTIVE'], description: 'New state (optional)' },
        avatarEmoji: { type: 'string', description: 'New avatar emoji (optional)' },
        avatarColor: { type: 'string', enum: ['gray', 'blue', 'amber', 'violet', 'teal', 'green', 'rose', 'slate'], description: 'New avatar color token (optional)' },
      },
      required: ['agentId'],
    },
  },
  {
    name: 'delete_agent',
    description: 'Delete an AI Agent. Only a DRAFT agent can be deleted — the backend rejects deleting an ACTIVE one, so set it to Draft first with update_agent ({state: "DRAFT"}). Also rejected if any automation workflow still references this agent from an agent step — the error names the referencing workflows; repoint or remove those steps first. Call list_agents after to verify it is gone.',
    inputSchema: {
      type: 'object',
      properties: {
        agentId: { type: 'string', description: 'Agent ID (from list_agents)' },
      },
      required: ['agentId'],
    },
  },
  {
    name: 'list_skills',
    description: 'List the Claude Code skills a lifecycle Workflow may bind from a `skill` transition step: shipped built-ins (builtIn=true) plus skills this project has registered. Call before binding a skill in a statechart to confirm it is registered — Publish rejects an unregistered skill.',
    inputSchema: { type: 'object', properties: {} },
  },
  {
    name: 'register_skill',
    description: 'Register a project-scoped skill id so a lifecycle Workflow can bind it from a transition step and publish without a backend redeploy. Idempotent on the skill id. Use for a new domain (e.g. marketing:seo-report) whose skill is not a shipped built-in.',
    inputSchema: {
      type: 'object',
      properties: {
        skillId: { type: 'string', description: 'Bindable skill id to register (e.g. marketing:seo-report)' },
        label: { type: 'string', description: 'Human-readable label (optional)' },
        description: { type: 'string', description: 'What the skill does (optional)' },
      },
      required: ['skillId'],
    },
  },
  {
    name: 'create_workflow',
    description: 'Create a new workflow definition in DRAFT state. Use this to save a designed workflow. Returns workflowId. Always call get_workflow after to verify the workflow was stored correctly.',
    inputSchema: {
      type: 'object',
      properties: {
        name: { type: 'string', description: 'Workflow display name' },
        area: { type: 'string', description: 'Nav-grouping slug (e.g. "marketing", "engineering")' },
        yaml: { type: 'string', description: 'YAML automation workflow definition (for schedule/webhook/event-triggered automations)' },
        definition: { type: 'object', description: 'Statechart lifecycle definition (for Work Item state management — COND-18 format)' },
      },
      required: ['name', 'area'],
    },
  },
  {
    name: 'get_workflow',
    description: 'Get a workflow definition by ID. Call this after create_workflow or update_workflow to verify the change was stored correctly (observability close). Always verify after mutations.',
    inputSchema: {
      type: 'object',
      properties: {
        workflowId: { type: 'string', description: 'Workflow definition ID' },
      },
      required: ['workflowId'],
    },
  },
  {
    name: 'update_workflow',
    description: 'Update a DRAFT workflow definition. Use this to fix validation errors returned by publish_workflow before retrying. Do NOT create a second workflow — fix in place.',
    inputSchema: {
      type: 'object',
      properties: {
        workflowId: { type: 'string', description: 'Workflow definition ID' },
        name: { type: 'string', description: 'New display name (optional)' },
        area: { type: 'string', description: 'New area slug (optional)' },
        yaml: { type: 'string', description: 'Updated YAML (optional)' },
        definition: { type: 'object', description: 'Updated statechart definition (optional)' },
      },
      required: ['workflowId'],
    },
  },
  {
    name: 'publish_workflow',
    description: 'Promote a workflow from DRAFT to PUBLISHED. Returns {success, errors[]}. If errors is non-empty, fix with update_workflow and retry — do not create a new workflow. DRAFT acts as a dry-run buffer: no commitment until publish succeeds.',
    inputSchema: {
      type: 'object',
      properties: {
        workflowId: { type: 'string', description: 'Workflow definition ID' },
      },
      required: ['workflowId'],
    },
  },
  {
    name: 'delete_workflow',
    description: 'Delete a workflow definition. Only a DRAFT workflow can be deleted — PUBLISHED and DISABLED workflows are rejected by the backend. For a DRAFT with problems, prefer update_workflow (fix in place) over delete + recreate. Call list_workflows after to verify it is gone.',
    inputSchema: {
      type: 'object',
      properties: {
        workflowId: { type: 'string', description: 'Workflow definition ID' },
      },
      required: ['workflowId'],
    },
  },
  {
    name: 'dispatch_workflow',
    description: 'Manually trigger a workflow run for testing. Optional inputs map becomes ${{ inputs.KEY }} in the run. Returns runId. Only works on PUBLISHED YAML automation workflows (not statechart lifecycle workflows). Call get_workflow_run after to verify the run started; for scheduled/event runs use list_workflow_runs instead.',
    inputSchema: {
      type: 'object',
      properties: {
        workflowId: { type: 'string', description: 'Workflow definition ID' },
        inputs: { type: 'object', description: 'Input values passed to the workflow run, available as ${{ inputs.KEY }} in the YAML (optional)' },
      },
      required: ['workflowId'],
    },
  },
  {
    name: 'cancel_workflow_run',
    description: 'Cancel a PENDING or RUNNING workflow run. Idempotent while the run is already CANCELLING; fails if the run has already finished. Call get_workflow_run afterward to confirm the run reached CANCELLING/CANCELLED.',
    inputSchema: {
      type: 'object',
      properties: {
        workflowId: { type: 'string', description: 'Workflow definition ID (from dispatch_workflow response)' },
        runId: { type: 'string', description: 'Run ID (from dispatch_workflow response)' },
      },
      required: ['workflowId', 'runId'],
    },
  },
  {
    name: 'get_workflow_run',
    description: 'Get status and step details for a workflow run. Returns status (PENDING/RUNNING/SUCCESS/FAILED), per-job and per-step breakdown, and step logs. A FAILED step also carries errorReason (a stable code) plus, when known, explanation/remediation for it. Call once after dispatch_workflow to verify the test run started or succeeded before reporting to the user. workflowId and runId come from the dispatch_workflow response.',
    inputSchema: {
      type: 'object',
      properties: {
        workflowId: { type: 'string', description: 'Workflow definition ID (from dispatch_workflow response)' },
        runId: { type: 'string', description: 'Run ID (from dispatch_workflow response)' },
      },
      required: ['workflowId', 'runId'],
    },
  },
  {
    name: 'list_workflow_runs',
    description: 'List recent runs for a workflow (newest first): runId, status, triggerType, timings. Entry point for checking scheduled/event runs — get runId here, then get_workflow_run for step detail.',
    inputSchema: {
      type: 'object',
      properties: {
        workflowId: { type: 'string', description: 'Workflow definition ID' },
        page: { type: 'number', description: 'Page number, 0-based (optional, default 0)' },
        size: { type: 'number', description: 'Page size (optional, default 50)' },
      },
      required: ['workflowId'],
    },
  },
  {
    name: 'list_workflow_secrets',
    description: 'List the names of workflow secrets configured for this project (keys only — values are never returned here or anywhere over MCP). Secrets are set in the app under Settings → Secrets.',
    inputSchema: { type: 'object', properties: {} },
  },
  {
    name: 'get_workflow_step_schema',
    description: 'The live, authoritative reference for workflow YAML: every step type\'s fields (name, type, required, constraints) plus the valid `${{ }}`/`if:` interpolation roots and functions, read straight from the engine\'s schema registry. Call this before designing or editing workflow YAML instead of recalling step shapes from memory — it always matches the current engine, including step types added after your training data.',
    inputSchema: { type: 'object', properties: {} },
  },
  {
    name: 'report_step_run',
    description: 'Report an agent-run step on a Work Item so a human can judge it at a Review gate (P0-6): what the agent was asked (inputBrief), what it produced, and any flags.',
    inputSchema: {
      type: 'object',
      properties: {
        issueId: { type: 'string', description: 'Work Item (issue) ID' },
        stepKind: { type: 'string', enum: ['skill', 'http', 'notify', 'set_field', 'create_sub_items'] },
        status: { type: 'string', enum: ['RUNNING', 'SUCCEEDED', 'FAILED', 'AWAITING_REVIEW'] },
        inputBrief: { type: 'string', description: 'Plain-language statement of what the agent was asked to do' },
        reportedBy: { type: 'string', description: 'Who ran the step (attribution)' },
        workflow: { type: 'string' },
        fromStatus: { type: 'string' },
        toStatus: { type: 'string' },
        skill: { type: 'string', description: 'For stepKind=skill: the skill id (e.g. conductor:implement)' },
        startedAt: { type: 'string', description: 'ISO-8601 timestamp' },
        finishedAt: { type: 'string', description: 'ISO-8601 timestamp' },
        produced: { type: 'array', description: 'Produced artifacts: [{kind: document|asset, ref, label?, assetType?}]' },
        beforeAfter: { type: 'object', description: '{before, after} when the step edited existing content' },
        flags: { type: 'array', description: 'Reviewer flags: [{level: info|warn, message}]' },
      },
      required: ['issueId', 'stepKind', 'status', 'inputBrief', 'reportedBy'],
    },
  },
  {
    name: 'submit_knowledge_source',
    description: "Push an observation, event, or document into the project's knowledge inbox for the librarian to file into wiki pages. Provide payload (inline content) and/or sourceRef (external reference) — at least one is required. Returns {sourceId, status}; status DUPLICATE means this was already submitted (by dedupKey) and is safe to ignore, not an error. Verify with read_knowledge_sources.",
    inputSchema: {
      type: 'object',
      properties: {
        sourceType: { type: 'string', description: 'Kind of source (e.g. slack_message, github_pr, observation, document)' },
        payload: { type: 'string', description: 'Inline content of the source (optional if sourceRef is given)' },
        sourceRef: { type: 'string', description: 'External reference/URL for the source (optional if payload is given)' },
        title: { type: 'string', description: 'Human-readable title (optional)' },
        contentType: { type: 'string', description: 'MIME type of payload (optional, defaults to text/plain)' },
        occurredAt: { type: 'string', description: 'ISO-8601 timestamp of when the event occurred (optional)' },
        dedupKey: { type: 'string', description: 'Idempotency key — resubmitting the same key returns status DUPLICATE instead of a second entry (optional)' },
        metadata: { type: 'object', description: 'Arbitrary structured metadata (optional)' },
        domain: { type: 'string', description: 'Explicit knowledge domain slug to route this into (optional) — omit to let the registry route by sourceType instead' },
      },
      required: ['sourceType'],
    },
  },
  {
    name: 'read_knowledge_sources',
    description: "Fetch knowledge-inbox sources by id, with offloaded payload content resolved inline. Companion to submit_knowledge_source (verify a submission landed) and the librarian's read path for filing pending sources.",
    inputSchema: {
      type: 'object',
      properties: {
        ids: { type: 'array', items: { type: 'string' }, description: 'Source IDs to fetch' },
      },
      required: ['ids'],
    },
  },
  {
    name: 'search_knowledge',
    description: 'Search the project wiki for pages matching a query: returns path, type, title, description, snippet, and rank per hit. Orientation step before reading — follow with read_knowledge_pages on the paths that matter.',
    inputSchema: {
      type: 'object',
      properties: {
        q: { type: 'string', description: 'Search query' },
        type: { type: 'string', description: 'Filter by page type (optional)' },
        pathPrefix: { type: 'string', description: 'Filter to paths under this prefix (optional)' },
        limit: { type: 'number', description: 'Max results (optional)' },
      },
      required: ['q'],
    },
  },
  {
    name: 'read_knowledge_pages',
    description: 'Fetch full content of wiki pages by path. Pass ["index.md"] for wiki orientation (a virtual index of all pages); "log.md" is also virtual (recent activity log). Unknown paths are silently omitted. The returned `version` feeds `baseVersion` on write_knowledge_pages.',
    inputSchema: {
      type: 'object',
      properties: {
        paths: { type: 'array', items: { type: 'string' }, description: 'Page paths to fetch' },
      },
      required: ['paths'],
    },
  },
  {
    name: 'write_knowledge_pages',
    description: "Create, update, or delete wiki pages atomically. Updating an existing page requires its current version as baseVersion (read_knowledge_pages first) — a stale write returns a structured {conflict: true, conflicts: [{path, currentVersion, currentContent}]} result instead of throwing; merge and retry once. Optionally pass sourceIds to mark those knowledge-inbox sources PROCESSED atomically with the write — pass writes: [] with sourceIds to ack a batch that warrants no wiki change. Verify with read_knowledge_pages.",
    inputSchema: {
      type: 'object',
      properties: {
        writes: {
          type: 'array',
          items: {
            type: 'object',
            properties: {
              path: { type: 'string', description: 'Wiki page path' },
              content: { type: 'string', description: 'Full page content (Markdown with frontmatter); omit when delete=true' },
              baseVersion: { type: 'number', description: 'Current version of the page being updated (required for updates; omit only when creating a new page)' },
              delete: { type: 'boolean', description: 'Delete this page instead of writing content (optional)' },
            },
            required: ['path'],
          },
          description: 'Pages to write or delete; may be empty when sourceIds is provided (no wiki change needed for this batch)',
        },
        sourceIds: { type: 'array', items: { type: 'string' }, description: 'Knowledge-inbox source IDs to mark PROCESSED atomically with this write (optional)' },
      },
      required: ['writes'],
    },
  },
  {
    name: 'list_knowledge_domains',
    description: "List this project's knowledge domains — slug, displayName, description, pathPrefix, schemaPagePath, sourceTypePatterns, state, owningAgentSlug. Call before suggest_knowledge_domain to check whether a domain (including a DISMISSED one) already exists, and to see each domain's filing conventions.",
    inputSchema: {
      type: 'object',
      properties: {},
      required: [],
    },
  },
  {
    name: 'suggest_knowledge_domain',
    description: 'Raise a gap report for a domain not yet in the registry. Claim-or-return on slug — calling this again for the same slug is safe and returns the existing row instead of erroring or resetting it. A DISMISSED result means an admin already declined this slug — do not call again for it. Verify with list_knowledge_domains.',
    inputSchema: {
      type: 'object',
      properties: {
        slug: { type: 'string', description: 'Lowercase, hyphenated (^[a-z0-9][a-z0-9-]*$) — becomes the domain\'s wiki path prefix' },
        displayName: { type: 'string', description: 'Human-readable domain name' },
        reason: { type: 'string', description: 'Why this domain is needed — shown to the admin reviewing the gap report' },
        description: { type: 'string', description: 'Optional longer description of the domain' },
        sourceTypePatterns: { type: 'array', items: { type: 'string' }, description: 'Optional glob patterns to seed routing with, if known upfront' },
      },
      required: ['slug', 'displayName', 'reason'],
    },
  },
]

function authErrorResponse() {
  return {
    content: [
      {
        type: 'text' as const,
        text: JSON.stringify({ error: 'Not authenticated — run conductor login' }),
      },
    ],
  }
}

function successResponse(data: unknown) {
  return {
    content: [
      {
        type: 'text' as const,
        text: JSON.stringify(data),
      },
    ],
  }
}

function errorResponse(message: string) {
  return {
    content: [
      {
        type: 'text' as const,
        text: JSON.stringify({ error: message }),
      },
    ],
    isError: true,
  }
}

export async function runMcpServer(): Promise<void> {
  const server = new Server(
    { name: 'conductor-mcp', version: '0.1.0' },
    { capabilities: { tools: {} } }
  )

  server.setRequestHandler(ListToolsRequestSchema, async () => {
    return { tools: TOOLS }
  })

  server.setRequestHandler(CallToolRequestSchema, async (request) => {
    let config
    try {
      config = getConfig()
    } catch {
      return authErrorResponse()
    }

    // Fail closed: never guess the project. If the cwd is inside a git repo that
    // is not linked to any configured project, refuse rather than silently
    // writing to whatever project is "active" (the cross-project mis-stamp bug).
    const resolution = resolveProject(config)
    if (resolution.mismatch) {
      return errorResponse(
        `Conductor refused to act: the current directory (${process.cwd()}) is a git ` +
          `repository that is not linked to any configured Conductor project, so targeting ` +
          `the active project "${config.projectName}" (${config.projectId}) is unsafe. ` +
          `Run \`conductor init\` in this repository to link it (this also pins the project ` +
          `in .mcp.json), or set CONDUCTOR_PROJECT_ID.`
      )
    }
    config.projectId = resolution.projectId

    const { name, arguments: args } = request.params
    const params = (args ?? {}) as Record<string, unknown>

    try {
      switch (name) {
        case 'create_work_item': {
          const workflow = params['workflow'] as string | undefined
          if (!workflow) {
            return errorResponse(
              'create_work_item requires an explicit `workflow` slug. Call list_workflows({kind:"LIFECYCLE"}) ' +
                'and pass the slug of the Workflow that governs this Work Item.'
            )
          }
          const result = await createWorkItem(
            {
              workflow,
              type: params['type'] as string,
              title: params['title'] as string,
              description: params['description'] as string | undefined,
            },
            config
          )
          return successResponse(result)
        }
        case 'list_integration_tools': {
          return successResponse(await listIntegrationTools({}, config))
        }
        case 'list_connector_catalog': {
          return successResponse(await listConnectorCatalog({}, config))
        }
        case 'list_workflows': {
          return successResponse(
            await listWorkflows(
              { kind: params['kind'] as 'LIFECYCLE' | 'AUTOMATION' | undefined },
              config
            )
          )
        }
        case 'list_agents': {
          return successResponse(await listAgents({}, config))
        }
        case 'create_agent': {
          return successResponse(
            await createAgent(
              {
                name: params['name'] as string,
                provider: params['provider'] as string,
                slug: params['slug'] as string | undefined,
                description: params['description'] as string | undefined,
                model: params['model'] as string | undefined,
                systemPrompt: params['systemPrompt'] as string | undefined,
                config: params['config'] as
                  | {
                      temperature?: number
                      maxTokens?: number
                      maxToolTurns?: number
                      runtime?: 'api' | 'claude-code'
                    }
                  | undefined,
                toolIds: params['toolIds'] as string[] | undefined,
                state: params['state'] as 'DRAFT' | 'ACTIVE' | undefined,
                avatarEmoji: params['avatarEmoji'] as string | undefined,
                avatarColor: params['avatarColor'] as string | undefined,
              },
              config
            )
          )
        }
        case 'update_agent': {
          return successResponse(
            await updateAgent(
              {
                agentId: params['agentId'] as string,
                name: params['name'] as string | undefined,
                provider: params['provider'] as string | undefined,
                slug: params['slug'] as string | undefined,
                description: params['description'] as string | undefined,
                model: params['model'] as string | undefined,
                systemPrompt: params['systemPrompt'] as string | undefined,
                config: params['config'] as
                  | {
                      temperature?: number
                      maxTokens?: number
                      maxToolTurns?: number
                      runtime?: 'api' | 'claude-code'
                    }
                  | undefined,
                toolIds: params['toolIds'] as string[] | undefined,
                state: params['state'] as 'DRAFT' | 'ACTIVE' | undefined,
                avatarEmoji: params['avatarEmoji'] as string | undefined,
                avatarColor: params['avatarColor'] as string | undefined,
              },
              config
            )
          )
        }
        case 'delete_agent': {
          return successResponse(
            await deleteAgent({ agentId: params['agentId'] as string }, config)
          )
        }
        case 'list_skills': {
          return successResponse(await listSkills({}, config))
        }
        case 'register_skill': {
          return successResponse(
            await registerSkill(
              {
                skillId: params['skillId'] as string,
                label: params['label'] as string | undefined,
                description: params['description'] as string | undefined,
              },
              config
            )
          )
        }
        case 'create_workflow': {
          return successResponse(
            await createWorkflow(
              {
                name: params['name'] as string,
                area: params['area'] as string,
                yaml: params['yaml'] as string | undefined,
                definition: params['definition'] as Record<string, unknown> | undefined,
              },
              config
            )
          )
        }
        case 'get_workflow': {
          return successResponse(
            await getWorkflow({ workflowId: params['workflowId'] as string }, config)
          )
        }
        case 'update_workflow': {
          return successResponse(
            await updateWorkflow(
              {
                workflowId: params['workflowId'] as string,
                name: params['name'] as string | undefined,
                area: params['area'] as string | undefined,
                yaml: params['yaml'] as string | undefined,
                definition: params['definition'] as Record<string, unknown> | undefined,
              },
              config
            )
          )
        }
        case 'delete_workflow': {
          return successResponse(
            await deleteWorkflow({ workflowId: params['workflowId'] as string }, config)
          )
        }
        case 'publish_workflow': {
          return successResponse(
            await publishWorkflow({ workflowId: params['workflowId'] as string }, config)
          )
        }
        case 'dispatch_workflow': {
          return successResponse(
            await dispatchWorkflow(
              {
                workflowId: params['workflowId'] as string,
                inputs: params['inputs'] as Record<string, unknown> | undefined,
              },
              config
            )
          )
        }
        case 'cancel_workflow_run': {
          return successResponse(
            await cancelWorkflowRun(
              {
                workflowId: params['workflowId'] as string,
                runId: params['runId'] as string,
              },
              config
            )
          )
        }
        case 'get_workflow_run': {
          return successResponse(
            await getWorkflowRun(
              {
                workflowId: params['workflowId'] as string,
                runId: params['runId'] as string,
              },
              config
            )
          )
        }
        case 'list_workflow_runs': {
          return successResponse(
            await listWorkflowRuns(
              {
                workflowId: params['workflowId'] as string,
                page: params['page'] as number | undefined,
                size: params['size'] as number | undefined,
              },
              config
            )
          )
        }
        case 'list_workflow_secrets': {
          return successResponse(await listWorkflowSecrets({}, config))
        }
        case 'get_workflow_step_schema': {
          return successResponse(await getWorkflowStepSchema({}, config))
        }
        case 'get_available_transitions': {
          return successResponse(
            await getAvailableTransitions({ issueId: params['issueId'] as string }, config)
          )
        }
        case 'transition_work_item': {
          return successResponse(
            await transitionWorkItem(
              { issueId: params['issueId'] as string, toStatus: params['toStatus'] as string },
              config
            )
          )
        }
        case 'record_asset': {
          return successResponse(
            await recordAsset(
              {
                issueId: params['issueId'] as string,
                type: params['type'] as string,
                kind: params['kind'] as string,
                ref: params['ref'] as string,
                label: params['label'] as string | undefined,
                done: params['done'] as boolean | undefined,
              },
              config
            )
          )
        }
        case 'report_step_run': {
          return successResponse(
            await reportStepRun(
              {
                issueId: params['issueId'] as string,
                stepKind: params['stepKind'] as string,
                status: params['status'] as string,
                inputBrief: params['inputBrief'] as string,
                reportedBy: params['reportedBy'] as string,
                workflow: params['workflow'] as string | undefined,
                fromStatus: params['fromStatus'] as string | undefined,
                toStatus: params['toStatus'] as string | undefined,
                skill: params['skill'] as string | undefined,
                startedAt: params['startedAt'] as string | undefined,
                finishedAt: params['finishedAt'] as string | undefined,
                produced: params['produced'] as unknown[] | undefined,
                beforeAfter: params['beforeAfter'],
                flags: params['flags'] as unknown[] | undefined,
              },
              config
            )
          )
        }
        case 'scaffold_document': {
          const result = await scaffoldDocument(
            {
              issueId: params['issueId'] as string,
              filename: params['filename'] as string,
            },
            config
          )
          return successResponse(result)
        }
        case 'write_document': {
          const result = await writeDocument(
            {
              issueId: params['issueId'] as string,
              filename: params['filename'] as string,
              content: params['content'] as string,
              contentType: params['contentType'] as string | undefined,
            },
            config
          )
          return successResponse(result)
        }
        case 'delete_document': {
          const result = await deleteDocument(
            {
              issueId: params['issueId'] as string,
              documentId: params['documentId'] as string,
              filename: params['filename'] as string,
            },
            config
          )
          return successResponse(result)
        }
        case 'update_work_item': {
          const result = await updateWorkItem(
            {
              issueId: params['issueId'] as string,
              title: params['title'] as string | undefined,
              description: params['description'] as string | undefined,
            },
            config
          )
          return successResponse(result)
        }
        case 'set_work_item_status': {
          const result = await setWorkItemStatus(
            {
              issueId: params['issueId'] as string,
              status: params['status'] as string,
            },
            config
          )
          return successResponse(result)
        }
        case 'list_work_items': {
          const result = await listWorkItems(
            {
              type: params['type'] as string | undefined,
              status: params['status'] as string | undefined,
              workflow: params['workflow'] as string | undefined,
            },
            config
          )
          return successResponse(result)
        }
        case 'get_work_item': {
          const result = await getWorkItem(
            { issueId: params['issueId'] as string },
            config
          )
          return successResponse(result)
        }
        case 'list_work_item_comments': {
          const result = await listWorkItemComments(
            {
              issueId: params['issueId'] as string,
              resolved: params['resolved'] as boolean | undefined,
            },
            config
          )
          return successResponse(result)
        }
        case 'submit_knowledge_source': {
          const result = await submitKnowledgeSource(
            {
              sourceType: params['sourceType'] as string,
              sourceRef: params['sourceRef'] as string | undefined,
              title: params['title'] as string | undefined,
              contentType: params['contentType'] as string | undefined,
              payload: params['payload'] as string | undefined,
              occurredAt: params['occurredAt'] as string | undefined,
              dedupKey: params['dedupKey'] as string | undefined,
              metadata: params['metadata'] as Record<string, unknown> | undefined,
              domain: params['domain'] as string | undefined,
            },
            config
          )
          return successResponse(result)
        }
        case 'read_knowledge_sources': {
          const result = await readKnowledgeSources(
            { ids: (params['ids'] as string[] | undefined) ?? [] },
            config
          )
          return successResponse(result)
        }
        case 'search_knowledge': {
          const result = await searchKnowledge(
            {
              q: params['q'] as string,
              type: params['type'] as string | undefined,
              pathPrefix: params['pathPrefix'] as string | undefined,
              limit: params['limit'] as number | undefined,
            },
            config
          )
          return successResponse(result)
        }
        case 'read_knowledge_pages': {
          const result = await readKnowledgePages(
            { paths: (params['paths'] as string[] | undefined) ?? [] },
            config
          )
          return successResponse(result)
        }
        case 'write_knowledge_pages': {
          const result = await writeKnowledgePages(
            {
              writes: (params['writes'] as KnowledgePageWrite[] | undefined) ?? [],
              sourceIds: params['sourceIds'] as string[] | undefined,
            },
            config
          )
          return successResponse(result)
        }
        case 'list_knowledge_domains': {
          const result = await listKnowledgeDomains(config)
          return successResponse(result)
        }
        case 'suggest_knowledge_domain': {
          const result = await suggestKnowledgeDomain(
            {
              slug: params['slug'] as string,
              displayName: params['displayName'] as string,
              reason: params['reason'] as string,
              description: params['description'] as string | undefined,
              sourceTypePatterns: params['sourceTypePatterns'] as string[] | undefined,
            },
            config
          )
          return successResponse(result)
        }
        default:
          return errorResponse(`Unknown tool: ${name}`)
      }
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err)
      return errorResponse(message)
    }
  })

  const transport = new StdioServerTransport()
  await server.connect(transport)
}

// Only auto-run if executed directly
if (import.meta.url === new URL(process.argv[1], 'file:').href) {
  runMcpServer().catch((err) => {
    process.stderr.write(`Fatal error: ${String(err)}\n`)
    process.exit(1)
  })
}
