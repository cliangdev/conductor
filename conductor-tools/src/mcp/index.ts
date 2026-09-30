#!/usr/bin/env node
import * as path from 'node:path'
import * as os from 'node:os'
import { Server } from '@modelcontextprotocol/sdk/server/index.js'
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js'
import { ListToolsRequestSchema, CallToolRequestSchema } from '@modelcontextprotocol/sdk/types.js'
import { getConfig, resolveProject } from './config.js'
import { ApiError } from './api.js'
import { refreshPluginAssetsIfOutdated, getAssetSrcDir } from '../lib/plugin-assets.js'
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
  type KnowledgeSkippedSource,
} from './tools/knowledge.js'
import {
  listPublishTargets,
  setPublishTargets,
  uploadAsset,
  retryFailedPublishTargets,
  type PublishTargetSelection,
  completeManualPublish,
} from './tools/marketing.js'
import {
  createPost,
  getPostStatus,
  submitPost,
  listPosts,
  submitReview,
  listAssets,
  getPostAnalytics,
  listTopPosts,
  type CreatePostParams,
} from './tools/posts.js'
import { getMarketingInsights } from './tools/insights.js'
import {
  getBrandKit,
  updateBrandKit,
  uploadBrandImage,
  listCreatives,
  listCreativeMedia,
  getCreative,
  createCreative,
  updateCreative,
  uploadCreativeMedia,
  uploadCreativePhoto,
  renderCreativeTool,
  previewCreative,
  attachCreativeToPost,
  createExperiment,
  type CreateCreativeParams,
  type UpdateCreativeParams,
  type UpdateBrandKitParams,
  type UploadBrandImageParams,
  type ListCreativeMediaParams,
} from './tools/creatives.js'
import { resolveCreativeId } from '../lib/creative-id.js'

const CREATIVE_TOOLS = [
  'get_creative',
  'create_creative',
  'update_creative',
  'render_creative',
  'preview_creative',
  'attach_creative_to_post',
  'create_experiment',
] as const
import {
  listProjectDocs,
  readProjectDoc,
  writeProjectDoc,
  moveProjectDoc,
  deleteProjectDoc,
  setProjectDocTask,
  listProjectDocComments,
  commentOnProjectDoc,
  respondToProjectDocComment,
  listProjectDocVersions,
  restoreProjectDocVersion,
  uploadProjectDocImage,
  moveProjectFolder,
  deleteProjectFolder,
} from './tools/project-docs.js'

export const TOOLS = [
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
        description: { type: 'string', description: 'Work Item description (optional). On a publishing Workflow this is the caption — the text that goes out to the platform — not a note about the item.' },
        tags: { type: 'array', items: { type: 'string' }, description: 'Freeform labels for grouping work across type, status and Workflow. Stored lower-cased and de-duplicated, so "Autumn" and "autumn" are one tag. Sent whole: omit to leave existing tags alone, send [] to clear them.' },
      },
      required: ['workflow', 'type', 'title'],
    },
  },
  {
    name: 'update_work_item',
    description: 'Update an existing Work Item\'s title, description, tags, or schedule. On a publishing Workflow the description is the caption that goes out to the platform, not a note. Canonical tool (targets the v2 work-items API). A field you omit is left unchanged. Verify with get_work_item.',
    inputSchema: {
      type: 'object',
      properties: {
        issueId: { type: 'string', description: 'Work Item ID' },
        title: { type: 'string', description: 'New title (optional)' },
        description: { type: 'string', description: 'New description (optional)' },
        scheduledFor: { type: 'string', description: 'ISO-8601 date-time this Work Item is due to fire (optional). On a Post this is when its publish targets go out.' },
        scheduleTimezone: { type: 'string', description: 'IANA zone id the schedule is authored in, e.g. Europe/Berlin (optional). Empty string clears it; an unknown zone is rejected.' },
        publishOnApproval: { type: 'boolean', description: 'Publish as soon as approved (optional). true means no scheduledFor is needed: entering the scheduled status stamps the earliest time every destination accepts. false returns to an explicit scheduledFor.' },
        tags: { type: 'array', items: { type: 'string' }, description: 'Freeform labels for grouping work across type, status and Workflow. Stored lower-cased and de-duplicated, so "Autumn" and "autumn" are one tag. Sent whole: omit to leave existing tags alone, send [] to clear them.' },
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
        tag: { type: 'string', description: 'Filter to Work Items carrying this tag (optional). Matched case-insensitively.' },
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
    description: 'List recent runs for a workflow (newest first): runId, status, triggerType, timings, and waitReason (set to AWAITING_RUNNER when a run is blocked on an unclaimed self-hosted job). Entry point for checking scheduled/event runs — get runId here, then get_workflow_run for step detail. Use state=queued to see waiting work; status= filters raw statuses.',
    inputSchema: {
      type: 'object',
      properties: {
        workflowId: { type: 'string', description: 'Workflow definition ID' },
        page: { type: 'number', description: 'Page number, 0-based (optional, default 0)' },
        size: { type: 'number', description: 'Page size (optional, default 50)' },
        state: {
          type: 'string',
          enum: ['queued', 'running'],
          description: 'Derived filter (optional). "queued" is the reliable way to find waiting work — a run blocked on an unclaimed self-hosted runner is RUNNING at the run level, so status=PENDING misses it. "running" is the complement: actually executing, with no such unclaimed job. Cannot be combined with status.',
        },
        status: {
          type: 'array',
          items: { type: 'string' },
          description: 'Filter to one or more raw run statuses (optional; omit for all). Cannot be combined with state.',
        },
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
    description: "Create, update, or delete wiki pages atomically, plus settle the ingestion sources this batch reviewed. Updating an existing page requires its current version as baseVersion (read_knowledge_pages first) — a stale write returns a structured {conflict: true, conflicts: [{path, currentVersion, currentContent}]} result instead of throwing; merge and retry once. sourceIds are sources you filed into a page (marked PROCESSED); skipped (with a required reason each) are sources you reviewed and deliberately did not file (marked SKIPPED — the reason is shown to a human in the Inbox). Both may be set in one call; a source id belongs to exactly one — the same id in both is rejected. writes may be empty when sourceIds/skipped cover the batch, to settle sources with no page change. Verify with read_knowledge_sources.",
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
          description: 'Pages to write or delete; may be empty when sourceIds/skipped cover the batch (no wiki change needed)',
        },
        sourceIds: { type: 'array', items: { type: 'string' }, description: 'Knowledge-inbox source IDs to mark PROCESSED atomically with this write (optional)' },
        skipped: {
          type: 'array',
          items: {
            type: 'object',
            properties: {
              sourceId: { type: 'string' },
              reason: { type: 'string', description: "Why this source wasn't filed — required, shown to a human in the Inbox" },
            },
            required: ['sourceId', 'reason'],
          },
          description: 'Knowledge-inbox sources this batch reviewed and deliberately did not file; marked SKIPPED with the given reason atomically with this write (optional)',
        },
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

  // --- Project Docs (the project's own document tree, not Work Item documents) ---
  {
    name: 'list_project_docs',
    description: "Map the project's Docs section: every document as a \"Folder/Title\" path with its docId, plus each folder with its docCount and lastUpdatedAt so you can judge which parts of the tree carry weight or have gone stale. Pass query to full-text search instead. Call this first to orient before reading or writing project docs.",
    inputSchema: {
      type: 'object',
      properties: {
        folder: { type: 'string', description: 'Limit to one folder path, e.g. "Plans/Q3" (omit for the whole tree)' },
        query: { type: 'string', description: 'Full-text search over titles and content; returns matches with snippets instead of the tree' },
      },
      required: [],
    },
  },
  {
    name: 'read_project_doc',
    description: 'Read one project document by path ("Plans/Q3 Roadmap") or docId. Returns full Markdown content plus taskLines — every checkbox with its 1-based line number and state, which is what set_project_doc_task takes. Pass versionNumber (from list_project_doc_versions) to read an earlier version instead of the current one.',
    inputSchema: {
      type: 'object',
      properties: {
        path: { type: 'string', description: 'Document path, e.g. "Plans/Q3 Roadmap"' },
        docId: { type: 'string', description: 'Document ID — use instead of path when the title contains "/"' },
        versionNumber: { type: 'number', description: 'Read this historical version instead of current content' },
      },
      required: [],
    },
  },
  {
    name: 'write_project_doc',
    description: 'Create or replace a project document at a path, creating any missing folders (upsert). Replaces the whole document, records a version, and marks unresolved line comments stale — to tick a checkbox use set_project_doc_task instead, which does none of that. Verify with read_project_doc.',
    inputSchema: {
      type: 'object',
      properties: {
        path: { type: 'string', description: 'Destination path, e.g. "Plans/Q3 Roadmap" — folder segments are created if missing' },
        content: { type: 'string', description: 'Full Markdown content' },
      },
      required: ['path', 'content'],
    },
  },
  {
    name: 'move_project_doc',
    description: 'Rename a project document and/or move it to another folder, creating missing folders in the destination path. Verify with list_project_docs.',
    inputSchema: {
      type: 'object',
      properties: {
        path: { type: 'string', description: 'Current document path' },
        docId: { type: 'string', description: 'Document ID — use instead of path' },
        newPath: { type: 'string', description: 'New path, e.g. "Archive/Q2 Roadmap"' },
      },
      required: ['newPath'],
    },
  },
  {
    name: 'delete_project_doc',
    description: 'Delete a project document. Irreversible — its version history and comments go with it. Verify with list_project_docs.',
    inputSchema: {
      type: 'object',
      properties: {
        path: { type: 'string', description: 'Document path' },
        docId: { type: 'string', description: 'Document ID — use instead of path' },
      },
      required: [],
    },
  },
  {
    name: 'set_project_doc_task',
    description: 'Check or uncheck one Markdown checkbox in a project document. lineNumber is 1-based against raw content including frontmatter — take it from read_project_doc\'s taskLines. Unlike write_project_doc this records no version and leaves comment anchors valid. Idempotent; returns conflict: true if that line is no longer a task item.',
    inputSchema: {
      type: 'object',
      properties: {
        path: { type: 'string', description: 'Document path' },
        docId: { type: 'string', description: 'Document ID — use instead of path' },
        lineNumber: { type: 'number', description: '1-based line number of the task item (from read_project_doc taskLines)' },
        checked: { type: 'boolean', description: 'true to check the box, false to uncheck' },
      },
      required: ['lineNumber', 'checked'],
    },
  },
  {
    name: 'list_project_doc_comments',
    description: 'Read the comments humans left on a project document — each with its anchored lineNumber, quotedText, lineStale flag and replies. Unresolved only by default. Answer them with respond_to_project_doc_comment.',
    inputSchema: {
      type: 'object',
      properties: {
        path: { type: 'string', description: 'Document path' },
        docId: { type: 'string', description: 'Document ID — use instead of path' },
        includeResolved: { type: 'boolean', description: 'Include already-resolved threads (default false)' },
      },
      required: [],
    },
  },
  {
    name: 'comment_on_project_doc',
    description: 'Leave a comment anchored to one line of a project document. lineNumber is 1-based against raw content including frontmatter. Verify with list_project_doc_comments.',
    inputSchema: {
      type: 'object',
      properties: {
        path: { type: 'string', description: 'Document path' },
        docId: { type: 'string', description: 'Document ID — use instead of path' },
        lineNumber: { type: 'number', description: '1-based line number to anchor the comment to' },
        content: { type: 'string', description: 'Comment text' },
        quotedText: { type: 'string', description: 'Optional snippet of the line being commented on, shown as context' },
      },
      required: ['lineNumber', 'content'],
    },
  },
  {
    name: 'respond_to_project_doc_comment',
    description: 'Answer a comment thread on a project document: post a reply, mark it resolved, or both. Verify with list_project_doc_comments.',
    inputSchema: {
      type: 'object',
      properties: {
        path: { type: 'string', description: 'Document path' },
        docId: { type: 'string', description: 'Document ID — use instead of path' },
        commentId: { type: 'string', description: 'ID of the comment thread (from list_project_doc_comments)' },
        reply: { type: 'string', description: 'Reply text to post on the thread' },
        resolve: { type: 'boolean', description: 'true to mark the thread resolved' },
      },
      required: ['commentId'],
    },
  },
  {
    name: 'list_project_doc_versions',
    description: 'List a project document\'s edit history — versionNumber, who wrote it and when, newest first. Every full-content write adds one; ticking a checkbox does not. Read one with read_project_doc versionNumber, or roll back with restore_project_doc_version.',
    inputSchema: {
      type: 'object',
      properties: {
        path: { type: 'string', description: 'Document path' },
        docId: { type: 'string', description: 'Document ID — use instead of path' },
      },
      required: [],
    },
  },
  {
    name: 'restore_project_doc_version',
    description: 'Roll a project document back to an earlier version (from list_project_doc_versions). Appends the old content as a new version rather than rewinding, so the restore is itself undoable. Verify with read_project_doc.',
    inputSchema: {
      type: 'object',
      properties: {
        path: { type: 'string', description: 'Document path' },
        docId: { type: 'string', description: 'Document ID — use instead of path' },
        versionNumber: { type: 'number', description: 'Version to restore, from list_project_doc_versions' },
      },
      required: ['versionNumber'],
    },
  },
  {
    name: 'upload_project_doc_image',
    description: 'Upload a local image file (png, jpeg, gif, webp) to a project document and get the Markdown snippet to embed. Include that snippet in the content you pass to write_project_doc — the link is stored as a stable reference and re-signed on every read, so it will not expire.',
    inputSchema: {
      type: 'object',
      properties: {
        path: { type: 'string', description: 'Document path the image belongs to' },
        docId: { type: 'string', description: 'Document ID — use instead of path' },
        filePath: { type: 'string', description: 'Path to the image file on this machine' },
      },
      required: ['filePath'],
    },
  },
  {
    name: 'move_project_folder',
    description: 'Rename a docs folder and/or move it under another one, taking its whole subtree with it. Missing folders in the destination path are created. Cannot move a folder into its own subtree. Verify with list_project_docs.',
    inputSchema: {
      type: 'object',
      properties: {
        path: { type: 'string', description: 'Current folder path, e.g. "Plans/Q3"' },
        newPath: { type: 'string', description: 'New folder path, e.g. "Archive/2026/Q3"' },
      },
      required: ['path', 'newPath'],
    },
  },
  {
    name: 'delete_project_folder',
    description: 'Delete a docs folder and its subfolders. Not destructive to content: documents inside are kept and resurface at the project root. Use it to clear away empty or obsolete folders after reorganising. Verify with list_project_docs.',
    inputSchema: {
      type: 'object',
      properties: {
        path: { type: 'string', description: 'Folder path to delete, e.g. "Archive/Old"' },
      },
      required: ['path'],
    },
  },
  {
    name: 'list_publish_targets',
    description: 'Accounts a project can publish to: platform, connectionId, label, lane, health, accepted options and post formats, plus a manual destination per platform. With issueId, also that Work Item\'s selected targets and their outcomes. Call before set_publish_targets or create_post.',
    inputSchema: {
      type: 'object',
      properties: {
        issueId: { type: 'string', description: 'Work Item ID — include to also return its selected targets and their outcomes (optional)' },
      },
    },
  },
  {
    name: 'set_publish_targets',
    description: 'Choose the accounts a Work Item publishes to, each with an optional format, caption override, media subset and platform options. Replaces the complete selection every time. Verify with list_publish_targets, then read get_post_status for what the gate still wants.',
    inputSchema: {
      type: 'object',
      properties: {
        issueId: { type: 'string', description: 'Work Item ID' },
        targets: {
          type: 'array',
          description: 'The complete selection. Empty array clears every target.',
          items: {
            type: 'object',
            properties: {
              platform: { type: 'string', description: 'Platform of the account, from list_publish_targets' },
              connectionId: { type: 'string', description: 'The connected account, from list_publish_targets. Omit for the platform\'s manual destination, which a person posts by hand.' },
              format: { type: 'string', enum: ['feed', 'reel', 'story'], description: 'The surface this destination publishes to (default feed). Must be one of that platform\'s formats in list_publish_targets, or the selection is refused.' },
              captionOverride: { type: 'string', description: 'Copy for this destination alone. Omit to use the Post\'s caption. Ignored by a story, which the platform publishes without one.' },
              assetIds: {
                type: 'array',
                items: { type: 'string' },
                description: 'Ordered subset of the Post\'s uploaded media for this destination (ids from upload_asset or list_assets). Omit or [] to inherit the whole set. Order is content: Instagram crops a carousel to its first item. A story takes exactly one.',
              },
              publishOptions: {
                type: 'object',
                additionalProperties: true,
                description: 'Per-platform options, keyed exactly as list_publish_targets reports them under that platform\'s optionKeys. Never guess a key — read optionKeys first. Unknown keys are dropped.',
              },
            },
            required: ['platform'],
          },
        },
      },
      required: ['issueId', 'targets'],
    },
  },
  {
    name: 'upload_asset',
    description: 'Attach a media file to a Work Item in one call (mint, upload, confirm) from a local path or a public URL. Video is measured here (MP4/MOV) unless width, height and durationSeconds are passed. Returns the stored Asset; a warning names any measurement still missing. Use record_asset for a link.',
    inputSchema: {
      type: 'object',
      properties: {
        issueId: { type: 'string', description: 'Work Item ID' },
        filePath: { type: 'string', description: 'Absolute path to a file on this machine (use this or url)' },
        url: { type: 'string', description: 'Public http(s) URL to fetch and upload (use this or filePath)' },
        type: { type: 'string', description: 'Asset type from the Workflow\'s asset_types (optional — defaults to the first one)' },
        label: { type: 'string', description: 'Human label (optional — defaults to the filename)' },
        width: { type: 'number', description: 'Pixel width (optional; measured for MP4/MOV when omitted)' },
        height: { type: 'number', description: 'Pixel height (optional; measured for MP4/MOV when omitted)' },
        durationSeconds: { type: 'number', description: 'Playback length in seconds (optional; measured for MP4/MOV when omitted)' },
      },
      required: ['issueId'],
    },
  },
  {
    name: 'list_assets',
    description: 'Every Asset on a Work Item — uploaded media with id, label, contentType, dimensions and uploadStatus, and recorded links. The ids set_publish_targets.assetIds takes.',
    inputSchema: {
      type: 'object',
      properties: {
        issueId: { type: 'string', description: 'Work Item ID' },
      },
      required: ['issueId'],
    },
  },
  {
    name: 'complete_manual_publish',
    description: 'Record that a manual-lane (or hand-off) publish target was posted by hand, storing its live URL. Refused for any other automated target. Returns the target as stored — call get_post_status to verify.',
    inputSchema: {
      type: 'object',
      properties: {
        issueId: { type: 'string', description: 'Work Item ID' },
        targetId: { type: 'string', description: 'The publish target\'s id, from list_publish_targets' },
        permalink: { type: 'string', description: 'Link to the post that went out (required — the only record it did)' },
        publishedAt: { type: 'string', description: 'ISO-8601 instant it went out (optional; defaults to now)' },
      },
      required: ['issueId', 'targetId', 'permalink'],
    },
  },
  {
    name: 'retry_failed_publish_targets',
    description: 'Re-fire only the FAILED targets on a Work Item with a fresh idempotency key; published ones are untouched. Asynchronous — read get_post_status afterwards.',
    inputSchema: {
      type: 'object',
      properties: {
        issueId: { type: 'string', description: 'Work Item ID' },
      },
      required: ['issueId'],
    },
  },
  // --- Posts: the publishing pipeline in one call each ---
  {
    name: 'create_post',
    description: 'Create, schedule and submit a Post in one call: caption, media, destinations, schedule, review. Returns the confirmation table (postId, status, time, destinations) plus blockers, warnings and nextStep. Poll get_post_status after.',
    inputSchema: {
      type: 'object',
      properties: {
        text: { type: 'string', description: 'The caption that goes out' },
        title: { type: 'string', description: 'Post title (optional — defaults to the caption\'s first line). Published as the YouTube title and TikTok headline.' },
        media: {
          type: 'array',
          items: {
            type: 'object',
            properties: {
              path: { type: 'string', description: 'A file on this machine' },
              url: { type: 'string', description: 'A public http(s) URL' },
              label: { type: 'string' },
              width: { type: 'number' },
              height: { type: 'number' },
              durationSeconds: { type: 'number' },
            },
          },
          description: 'Files to attach, in order. Every destination inherits all of them unless it names assetIds.',
        },
        targets: {
          type: 'array',
          items: {
            type: 'object',
            properties: {
              platform: { type: 'string', description: 'facebook, instagram, youtube, tiktok, … (from list_publish_targets)' },
              account: { type: 'string', description: 'The account\'s label or connectionId from list_publish_targets. Omit for the manual destination.' },
              format: { type: 'string', enum: ['feed', 'reel', 'story'], description: 'The surface this destination publishes to (default feed). Must be one of that platform\'s formats in list_publish_targets.' },
              captionOverride: { type: 'string', description: 'Copy for this destination alone (optional). Ignored by a story.' },
              assetIds: { type: 'array', items: { type: ['string', 'number'] }, description: 'Ordered subset of media for this destination: 0-based indexes into `media`, or asset ids. Omit to inherit all; a story keeps only the first.' },
              options: { type: 'object', additionalProperties: true, description: 'Platform options, keyed exactly as list_publish_targets reports them under that platform\'s optionKeys. Never guess a key.' },
            },
            required: ['platform'],
          },
          description: 'Where it goes. At least one.',
        },
        scheduledFor: { type: 'string', description: 'ISO-8601 fire time (optional — defaults to the next five-minute mark the chosen destinations accept; ignored when publishOnApproval is true)' },
        publishOnApproval: { type: 'boolean', description: 'Publish as soon as approved (optional, default false). No fire time is chosen here: entering the scheduled status stamps the earliest time every destination accepts.' },
        timezone: { type: 'string', description: 'IANA zone the schedule is authored in (optional — defaults to this machine\'s)' },
        submit: { type: 'boolean', description: 'Submit for review once ready (default true). false leaves it in Draft.' },
        reviewers: { type: 'array', items: { type: 'string' }, description: 'Reviewers to assign, by name, email or user id (must hold the REVIEWER or ADMIN role)' },
        workflow: { type: 'string', description: 'Workflow slug (optional — required only when more than one Workflow publishes)' },
      },
      required: ['text', 'targets'],
    },
  },
  {
    name: 'get_post_status',
    description: 'Where a Post stands, live from the server: status, schedule, every destination\'s state/permalink/errorMessage, the gate\'s blockers and warnings, review state and nextStep. The read-back after create_post, submit_post, an approval, a scheduled publish or a retry.',
    inputSchema: {
      type: 'object',
      properties: {
        postId: { type: 'string', description: 'The Post\'s Work Item ID' },
      },
      required: ['postId'],
    },
  },
  {
    name: 'submit_post',
    description: 'Hand a Post to review: assigns the named reviewers, then takes the move the gate names (In Review on a reviewed Workflow, Scheduled on one without a review gate) if nothing blocks it. Returns the same confirmation as create_post; blockers mean nothing moved.',
    inputSchema: {
      type: 'object',
      properties: {
        postId: { type: 'string', description: 'The Post\'s Work Item ID' },
        reviewers: { type: 'array', items: { type: 'string' }, description: 'Reviewers to assign, by name, email or user id (optional)' },
      },
      required: ['postId'],
    },
  },
  {
    name: 'list_posts',
    description: 'Posts across every publishing Workflow, newest schedule first, each with its destinations\' state, permalinks and format (shown only when not feed). Filter by status, a scheduledFor window (since/until) or platform.',
    inputSchema: {
      type: 'object',
      properties: {
        status: { type: 'string', description: 'Workflow status, e.g. SCHEDULED, PUBLISHED, FAILED (optional)' },
        since: { type: 'string', description: 'ISO-8601; only Posts scheduled at or after this (optional)' },
        until: { type: 'string', description: 'ISO-8601; only Posts scheduled at or before this (optional)' },
        platform: { type: 'string', description: 'Only Posts with a destination on this platform (optional)' },
        limit: { type: 'number', description: 'Max rows, up to 50 (optional)' },
      },
    },
  },
  {
    name: 'get_post_analytics',
    description: 'What happened to a Post after it went out: views, likes, comments and shares per destination as the platform reports them, with the series of snapshots and the totals. Empty until the connection\'s post_metrics feed has read the counters (every six hours after publishing).',
    inputSchema: {
      type: 'object',
      properties: {
        postId: { type: 'string', description: 'The Post\'s Work Item ID' },
        since: { type: 'string', description: 'ISO-8601; only snapshots observed at or after this (optional)' },
      },
      required: ['postId'],
    },
  },
  {
    name: 'list_top_posts',
    description: 'The project\'s best-performing published destinations ranked by one metric (views by default; likes, comments, shares, saves, reach, impressions), each with its Post, account and permalink.',
    inputSchema: {
      type: 'object',
      properties: {
        metric: { type: 'string', description: 'views (default), likes, comments, shares, saves, reach or impressions' },
        platform: { type: 'string', description: 'Only destinations on this platform (optional)' },
        since: { type: 'string', description: 'ISO-8601; only destinations read at or after this (optional)' },
        limit: { type: 'number', description: 'Rows, up to 100 (default 20)' },
      },
    },
  },
  {
    name: 'get_marketing_insights',
    description: 'What is working across the project\'s published Posts: totals, engagement rate by platform, format and time, best and worst posts, top-performing Creatives, and movers vs the prior window. Read-only. Use get_post_analytics for one Post\'s series and list_top_posts for a full ranking by one metric.',
    inputSchema: {
      type: 'object',
      properties: {
        window: { type: 'string', enum: ['7d', '30d', '90d'], description: 'How far back to look (default 30d)' },
        platform: { type: 'string', description: 'Restrict to one platform, from the project\'s connected platforms (optional)' },
      },
    },
  },
  {
    name: 'submit_review',
    description: 'Record a review verdict on a Post as this API key\'s user, who must be an assigned REVIEWER or ADMIN. Approving on a reviewed Workflow schedules it in the same call. TikTok consent is a human step, done in the Conductor UI, not this tool. Returns the confirmation table.',
    inputSchema: {
      type: 'object',
      properties: {
        postId: { type: 'string', description: 'The Post\'s Work Item ID' },
        verdict: { type: 'string', enum: ['approve', 'request_changes'], description: 'The verdict' },
        summary: { type: 'string', description: 'Review notes (optional)' },
      },
      required: ['postId', 'verdict'],
    },
  },
  // --- Creatives: Brand Kit + Creative library + local rendering ---
  {
    name: 'get_brand_kit',
    description: "Read a workspace Brand Kit: colour tokens, font, logo/wordmark/badge URLs, CTA claim, copy rules, approved lines, enabled placements, and the Knowledge page path holding its prose context. Omit kitId for the project's default kit. Also carries `configured: false` plus a `nextStep` when the kit has never been edited — check this before writing a Creative against it.",
    inputSchema: {
      type: 'object',
      properties: {
        kitId: { type: 'string', description: "Brand Kit id (optional — omit for the project's default kit)" },
      },
    },
  },
  {
    name: 'update_brand_kit',
    description: "Patch a workspace Brand Kit's name, tokens, font, CTA claim, copy rules, approved lines, enabled placements or Knowledge page path. Every field is set-replace and optional — omit to leave it unchanged — except tokens, which merges onto the kit's existing set (only the keys given change). A 422 names the failing field. Call get_brand_kit after to verify.",
    inputSchema: {
      type: 'object',
      properties: {
        kitId: { type: 'string', description: "Brand Kit id (optional — omit for the project's default kit)" },
        name: { type: 'string', description: 'Kit display name (optional)' },
        fontFamily: { type: 'string', description: 'Font family name (optional)' },
        fontUrl: { type: 'string', description: 'URL the renderer loads the font from (optional)' },
        tokens: {
          type: 'object',
          additionalProperties: { type: 'string' },
          description: 'CSS custom properties the renderer maps to (e.g. accent, accent2, darkBg, darkInk, lightBg, lightCard, ink, ink2), each a #RRGGBB hex colour. Only the keys given here change — every other existing token is kept (optional).',
        },
        ctaClaim: { type: 'string', description: 'The call-to-action line rendered on a Creative (optional)' },
        accentPhraseRequired: { type: 'boolean', description: 'Whether a Creative headline must mark exactly one *accent phrase* (optional)' },
        copyRules: {
          type: 'array',
          description: "Replaces the whole list of brand copy rules (optional). Each is enforced against every field it names on every Creative write.",
          items: {
            type: 'object',
            properties: {
              id: { type: 'string' },
              pattern: { type: 'string', description: 'Regex tested against the field text' },
              flags: { type: 'string', description: 'Any of "i" (case-insensitive), "m" (multiline) — optional' },
              message: { type: 'string', description: 'Shown verbatim when the rule fails' },
              fields: { type: 'array', items: { type: 'string', enum: ['headline', 'body', 'caption'] } },
              exceptPattern: { type: 'string', description: 'Matches stripped from the text before pattern is tested (optional)' },
            },
            required: ['id', 'pattern', 'message', 'fields'],
          },
        },
        approvedLines: { type: 'array', items: { type: 'string' }, description: 'Replaces the whole list of pre-cleared verbatim lines (optional). Mutually exclusive with addApprovedLines.' },
        addApprovedLines: { type: 'array', items: { type: 'string' }, description: 'Appends to the existing approved lines instead of replacing them (optional). Mutually exclusive with approvedLines.' },
        enabledPlacements: { type: 'array', items: { type: 'string' }, description: 'Placement keys (from the creative registry) this kit exports by default (optional)' },
        knowledgePagePath: { type: 'string', description: "Wiki page path holding this brand's prose context (optional)" },
      },
    },
  },
  {
    name: 'upload_brand_image',
    description: "Upload a workspace's logo mark, wordmark or badge into a Brand Kit image slot from a local file or a public URL (mint, PUT, confirm in one call). wordmark_light is light-coloured lettering, for a dark frame; wordmark_dark is dark lettering, for a light frame. Call get_brand_kit after to verify.",
    inputSchema: {
      type: 'object',
      properties: {
        kitId: { type: 'string', description: "Brand Kit id (optional — omit for the project's default kit)" },
        slot: {
          type: 'string',
          enum: ['mark', 'wordmark_dark', 'wordmark_light', 'badge'],
          description: 'mark: standalone logomark. wordmark_dark: dark lettering, for light frames. wordmark_light: light lettering, for dark frames. badge: a compact combined lockup.',
        },
        filePath: { type: 'string', description: 'A file on this machine (use this or url)' },
        url: { type: 'string', description: 'A public http(s) URL (use this or filePath)' },
      },
      required: ['slot'],
    },
  },
  {
    name: 'list_creative_media',
    description: "The project's media library (photos, videos, audio) — id, label, kind, dimensions/duration, checked/blocked review state (with reason), provenance and a URL to look at it. Call before upload_creative_media to reuse existing, already-checked media instead of uploading a duplicate.",
    inputSchema: {
      type: 'object',
      properties: {
        kind: { type: 'string', enum: ['IMAGE', 'VIDEO', 'AUDIO'], description: 'Filter to one kind (optional — omit for every kind)' },
        includeBlocked: { type: 'boolean', description: 'Include media a human has marked blocked (optional, default false)' },
      },
    },
  },
  {
    name: 'list_creatives',
    description: "List the project's Creatives (photo + headline + layout), newest display number first, lettered variants grouped together. Filter by state or brandKitId.",
    inputSchema: {
      type: 'object',
      properties: {
        state: { type: 'string', enum: ['DRAFT', 'READY', 'ARCHIVED'], description: 'Filter by state (optional)' },
        brandKitId: { type: 'string', description: 'Filter to Creatives on one Brand Kit (optional)' },
      },
    },
  },
  {
    name: 'get_creative',
    description: "One Creative in full: its fields, its readiness checklist (what still blocks it going to review), the other lettered variants in its family, each variant's attributed performance (posts, views, engagement rate, average view percentage, 72h views), and the family's active or most recent hook experiment (state, and once decided, the winner and its numbers). Call after create_creative/update_creative/create_experiment to verify.",
    inputSchema: {
      type: 'object',
      properties: {
        creativeId: { type: 'string', description: 'Creative id, or its display id such as 12a' },
      },
      required: ['creativeId'],
    },
  },
  {
    name: 'create_creative',
    description: "Create a Creative (photo, headline, layout, theme, body, caption, alt text, optional story/carousel sequence), or, with variantOf, cut a lettered variant of an existing one instead (inherits everything but headline/name — every other field here is ignored). Set kind to CLIP to use a finished video as-is via clipMedia instead — no photo, headline or layout needed. Refused with the failing rule's message on a bad layout/theme/placement, the kit's accent-phrase rule, a copy rule, or (when state is READY) a readiness rule. Call get_creative after to verify.",
    inputSchema: {
      type: 'object',
      properties: {
        variantOf: { type: 'string', description: 'Cut a lettered variant of this existing Creative instead of creating a fresh one (optional)' },
        brandKitId: { type: 'string', description: "Brand Kit this Creative renders with (optional — defaults to the project's default kit)" },
        name: { type: 'string', description: 'Internal name, not shown on the artwork (optional)' },
        state: { type: 'string', enum: ['DRAFT', 'READY', 'ARCHIVED'], description: 'Defaults to DRAFT (optional)' },
        kind: {
          type: 'string',
          enum: ['STILL', 'MOTION', 'CLIP'],
          description: 'Defaults to STILL (a brand-rendered photo/headline). CLIP is a finished video used as-is via clipMedia. MOTION is the same brand layout animated into a short video — set motion (and optionally audio) too.',
        },
        clipMedia: {
          type: 'object',
          additionalProperties: { type: 'string' },
          description: 'CLIP only: media ids per placement from upload_creative_media, e.g. {"default": mediaId, "9x16": mediaId}. "default" covers any placement without its own entry.',
        },
        motion: {
          type: 'object',
          description: 'MOTION only: the animation timeline. preset: fade-up (default) | word-by-word | accent-pop | none. durationSec: 3-60, default 8. background.source: photo (default, animated by background.motion: zoom-in default | zoom-out | pan-left | pan-right | none) or clip (background.clipMediaId, a VIDEO media id, plus background.clipStartSec). endCard: default true — the last 2s hold the finished composition with the CTA.',
          properties: {
            preset: { type: 'string', enum: ['fade-up', 'word-by-word', 'accent-pop', 'none'] },
            durationSec: { type: 'number' },
            background: {
              type: 'object',
              properties: {
                source: { type: 'string', enum: ['photo', 'clip'] },
                motion: { type: 'string', enum: ['zoom-in', 'zoom-out', 'pan-left', 'pan-right', 'none'] },
                clipMediaId: { type: 'string' },
                clipStartSec: { type: 'number' },
              },
            },
            endCard: { type: 'boolean' },
          },
        },
        audio: {
          type: 'object',
          description: 'MOTION only: source: clip (the background clip\'s own sound — default when it has one) | track (a library track) | none. trackId: an AUDIO media id from upload_creative_media (source=track). volume: 0-1, default 0.8 (track only). fadeOutSec: 0-5, default 1 (track only).',
          properties: {
            source: { type: 'string', enum: ['clip', 'track', 'none'] },
            trackId: { type: 'string' },
            volume: { type: 'number' },
            fadeOutSec: { type: 'number' },
          },
        },
        layout: { type: 'string', description: "Layout key from the creative registry, e.g. stacked/bleed/card/split (optional — defaults to the registry's first layout)" },
        theme: { type: 'string', enum: ['dark', 'light'], description: 'Optional — defaults to dark' },
        photoId: { type: 'string', description: 'A photo from upload_creative_photo (optional)' },
        focalOverride: { type: 'object', additionalProperties: { type: 'string' }, description: 'Per-placement focal point override, e.g. {"9x16":"50% 30%"} (optional)' },
        headline: { type: 'string', description: 'Exactly one *accent phrase* marked with asterisks, when the Brand Kit requires one (optional)' },
        body: { type: 'string', description: 'Body copy (optional)' },
        caption: { type: 'string', description: "The post caption this Creative is meant for, carried through but never rendered onto the artwork (optional)" },
        altText: { type: 'string', description: 'Accessibility description of the photo, not the copy (optional)' },
        placements: { type: 'array', items: { type: 'string' }, description: "Extra placement keys this Creative opts into beyond the Brand Kit's enabled set (optional)" },
        sequenceKind: { type: 'string', enum: ['story', 'carousel'], description: 'Set to render a multi-beat sequence instead of one frame (optional)' },
        sequence: {
          type: 'array',
          description: 'Story beats (2-7) or carousel cards (2-10); each inherits the Creative and overrides only what changes (optional)',
          items: {
            type: 'object',
            properties: {
              headline: { type: 'string' },
              body: { type: 'string' },
              photoId: { type: 'string' },
              cta: { type: 'boolean', description: 'Forces the CTA row on/off for this beat (optional — default: only the last beat shows it)' },
            },
          },
        },
        carouselRatio: { type: 'string', description: 'Aspect ratio key for a carousel sequence, e.g. 4x5 or 1x1 (optional)' },
        lockup: { type: 'string', enum: ['plain', 'chip'], description: 'Optional — defaults to plain. "chip" puts the logo lockup on a white pill, for busy photography.' },
        layoutOverrides: {
          type: 'object',
          description: 'Per-placement overrides of layout-derived numbers, in pixels (optional). A placement key not named here keeps the layout\'s own default.',
          properties: {
            band: { type: 'object', additionalProperties: { type: 'integer' }, description: 'Overrides the stacked layout\'s photo band height per placement, e.g. {"9x16": 1200}' },
            padBottom: { type: 'object', additionalProperties: { type: 'integer' }, description: "Overrides the 9x16 panel's bottom safe-zone clearance per placement" },
          },
        },
      },
    },
  },
  {
    name: 'update_creative',
    description: "Patch a Creative's fields. version must equal its current value (from get_creative) or the write is refused with a 409 conflict — reread and reapply on top of the newer version rather than retrying blind. A failing structural or readiness rule is refused with the rule's own message. Call get_creative after to verify.",
    inputSchema: {
      type: 'object',
      properties: {
        creativeId: { type: 'string', description: 'Creative id, or its display id such as 12a' },
        version: { type: 'number', description: "The Creative's current version, from get_creative" },
        brandKitId: { type: 'string' },
        name: { type: 'string' },
        state: { type: 'string', enum: ['DRAFT', 'READY', 'ARCHIVED'] },
        kind: { type: 'string', enum: ['STILL', 'MOTION', 'CLIP'] },
        clipMedia: { type: 'object', additionalProperties: { type: 'string' } },
        motion: {
          type: 'object',
          properties: {
            preset: { type: 'string', enum: ['fade-up', 'word-by-word', 'accent-pop', 'none'] },
            durationSec: { type: 'number' },
            background: {
              type: 'object',
              properties: {
                source: { type: 'string', enum: ['photo', 'clip'] },
                motion: { type: 'string', enum: ['zoom-in', 'zoom-out', 'pan-left', 'pan-right', 'none'] },
                clipMediaId: { type: 'string' },
                clipStartSec: { type: 'number' },
              },
            },
            endCard: { type: 'boolean' },
          },
        },
        audio: {
          type: 'object',
          properties: {
            source: { type: 'string', enum: ['clip', 'track', 'none'] },
            trackId: { type: 'string' },
            volume: { type: 'number' },
            fadeOutSec: { type: 'number' },
          },
        },
        layout: { type: 'string' },
        theme: { type: 'string', enum: ['dark', 'light'] },
        photoId: { type: 'string' },
        focalOverride: { type: 'object', additionalProperties: { type: 'string' } },
        headline: { type: 'string' },
        body: { type: 'string' },
        caption: { type: 'string' },
        altText: { type: 'string' },
        placements: { type: 'array', items: { type: 'string' } },
        sequenceKind: { type: 'string', enum: ['story', 'carousel'] },
        sequence: {
          type: 'array',
          items: {
            type: 'object',
            properties: {
              headline: { type: 'string' },
              body: { type: 'string' },
              photoId: { type: 'string' },
              cta: { type: 'boolean' },
            },
          },
        },
        carouselRatio: { type: 'string' },
        lockup: { type: 'string', enum: ['plain', 'chip'] },
        layoutOverrides: {
          type: 'object',
          properties: {
            band: { type: 'object', additionalProperties: { type: 'integer' } },
            padBottom: { type: 'object', additionalProperties: { type: 'integer' } },
          },
        },
      },
      required: ['creativeId', 'version'],
    },
  },
  {
    name: 'upload_creative_media',
    description: "Upload a photo, video or audio file into the project's media library from a local file or a public URL, recording its provenance (source, licence, aiGenerated) for the readiness checklist. Kind, dimensions, duration and audio presence are detected automatically; a video also gets an extracted poster frame. Use the returned media's id as a Creative's photoId (STILL) or a clipMedia entry (CLIP).",
    inputSchema: {
      type: 'object',
      properties: {
        filePath: { type: 'string', description: 'A file on this machine (use this or url)' },
        url: { type: 'string', description: 'A public http(s) URL (use this or filePath)' },
        label: { type: 'string', description: 'Human label (optional — defaults to the filename, without its extension)' },
        source: { type: 'string', description: 'Provenance note, e.g. a URL or "Generated with <model> on <date>" (optional)' },
        licence: { type: 'string', description: 'e.g. "Own work", "Unsplash Licence", "Generated, house use" (optional)' },
        aiGenerated: { type: 'boolean', description: 'Whether the file is AI-generated (optional, informational only)' },
      },
    },
  },
  {
    name: 'upload_creative_photo',
    description: 'Deprecated alias for upload_creative_media (same behavior, including for video/audio now) — prefer that name.',
    inputSchema: {
      type: 'object',
      properties: {
        filePath: { type: 'string' },
        url: { type: 'string' },
        label: { type: 'string' },
        source: { type: 'string' },
        licence: { type: 'string' },
        aiGenerated: { type: 'boolean' },
      },
    },
  },
  {
    name: 'render_creative',
    description: 'Render a Creative locally (Playwright on this machine) to upload-ready frames — one JPEG per placement for STILL, or one MP4 with a poster per placement for MOTION (ffmpeg-encoded on this machine) — or a small contact sheet with previewOnly. Runs synchronously: a STILL placement takes seconds; a MOTION placement takes roughly 20s per 8s of video, so a multi-placement MOTION render can take a couple of minutes. Returns the render id, state, frame URLs (MOTION frames also carry durationSeconds/hasAudio/posterUrl) and any warnings. Call preview_creative to actually look at the result.',
    inputSchema: {
      type: 'object',
      properties: {
        creativeId: { type: 'string', description: 'Creative id, or its display id such as 12a' },
        previewOnly: { type: 'boolean', description: 'Render one small contact sheet instead of full-size placement frames (optional, default false)' },
        renderer: { type: 'string', description: 'Attribution tag stored on the render (optional, default "mcp")' },
        workflowRunId: { type: 'string', description: 'Attribute this render to a Workflow run (optional)' },
      },
      required: ['creativeId'],
    },
  },
  {
    name: 'preview_creative',
    description: "Look at a Creative: returns its latest contact sheet as an image (rendering a fresh previewOnly one first if none exists yet). For MOTION this is three key moments across the animation, not the finished video, with a text note giving the duration and — once a full render of the current version exists — its rendered MP4 URLs. Use this to judge the actual artwork — does the headline read at a glance, is the copy sitting on the subject's face — not just the data. If the image is too large to inline it returns the URL instead, with a note saying so.",
    inputSchema: {
      type: 'object',
      properties: {
        creativeId: { type: 'string', description: 'Creative id, or its display id such as 12a' },
      },
      required: ['creativeId'],
    },
  },
  {
    name: 'attach_creative_to_post',
    description: "Attach a Creative's rendered frames to a Post as its media — placement to platform (9x16 to TikTok/Instagram Reels, 4x5 to Instagram feed, 1x1 to Facebook, story to story targets; sequence frames in order) — for every destination that has not set its own custom media. Uses the latest SUCCEEDED, non-preview render unless renderId is given. Call get_post_status (postId) to verify.",
    inputSchema: {
      type: 'object',
      properties: {
        creativeId: { type: 'string', description: 'Creative id, or its display id such as 12a' },
        postId: { type: 'string', description: "The Post's Work Item id — same value get_post_status takes as postId" },
        workItemId: { type: 'string', description: 'Deprecated alias for postId' },
        renderId: { type: 'string', description: 'A specific render to attach (optional — defaults to the latest SUCCEEDED render)' },
      },
      required: ['creativeId'],
    },
  },
  {
    name: 'create_experiment',
    description: "Start a hook experiment comparing every lettered variant of a Creative's family (needs at least two; only one RUNNING experiment per family at a time — refused with a conflict otherwise). It settles itself once every variant has published and reported, or via the weekly insights job — nothing to poll. Call get_creative to see its state and, once decided, the winner and numbers.",
    inputSchema: {
      type: 'object',
      properties: {
        creativeId: { type: 'string', description: 'Any Creative in the family — a lettered variant or the root; resolved automatically' },
        metric: { type: 'string', enum: ['views', 'engagement_rate', 'avg_view_pct'], description: 'Optional — defaults to views. The decision prefers avg_view_pct whenever every variant reports it, regardless of this choice.' },
        windowHours: { type: 'number', description: 'Hours after each variant fires before it counts toward the decision (optional, default 72)' },
      },
      required: ['creativeId'],
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

/**
 * An image content block (base64-encoded bytes + MIME type) plus an optional text block of
 * metadata — the shape a client needs to actually render the image rather than just read about it.
 * `preview_creative` is the first tool to need this; every other tool emits text only.
 */
export function imageResponse(image: { data: Buffer; mimeType: string }, meta?: unknown) {
  const content: Array<{ type: 'image'; data: string; mimeType: string } | { type: 'text'; text: string }> = [
    { type: 'image', data: image.data.toString('base64'), mimeType: image.mimeType },
  ]
  if (meta !== undefined) {
    content.push({ type: 'text', text: JSON.stringify(meta) })
  }
  return { content }
}

/**
 * A shorthand string keeps every existing call site working; the richer object form lets the dispatch
 * catch hand back the server's own status/code/title alongside its message, without changing the
 * top-level `{ error: ... }` JSON shape callers and tests already rely on.
 */
function errorResponse(error: string | { error: string; status?: number; code?: string; title?: string }) {
  const payload = typeof error === 'string' ? { error } : error
  return {
    content: [
      {
        type: 'text' as const,
        text: JSON.stringify(payload),
      },
    ],
    isError: true,
  }
}

/** Refreshes an existing Claude plugin install (commands/skills under .claude/) in place when it was
 * written by an older package version — same logic `conductor init` uses, scoped to the plugin's own
 * files. Never touches anything when no install exists. Logs one stderr line on an actual refresh;
 * swallows any error so a broken refresh never blocks the server from starting. */
export function refreshPluginAssetsOnStartup(): void {
  try {
    const result = refreshPluginAssetsIfOutdated(
      getAssetSrcDir(),
      path.join(os.homedir(), '.claude'),
      path.join(process.cwd(), '.claude')
    )
    if (result.refreshed) {
      process.stderr.write(`conductor: refreshed Claude plugin assets (${result.location})\n`)
    }
  } catch (err) {
    process.stderr.write(`conductor: could not refresh Claude plugin assets: ${err instanceof Error ? err.message : String(err)}\n`)
  }
}

export async function runMcpServer(): Promise<void> {
  refreshPluginAssetsOnStartup()

  const server = new Server(
    { name: 'conductor-mcp', version: '0.1.0' },
    { capabilities: { tools: {} } }
  )

  server.setRequestHandler(ListToolsRequestSchema, async () => {
    return { tools: TOOLS }
  })

  server.setRequestHandler(CallToolRequestSchema, async (request, extra) => {
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
      // Creative tools accept a display id ("12a") wherever they take a Creative.
      for (const key of ['creativeId', 'variantOf'] as const) {
        if (typeof params[key] === 'string' && (CREATIVE_TOOLS as readonly string[]).includes(name)) {
          params[key] = await resolveCreativeId(params[key] as string, config)
        }
      }
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
              tags: params['tags'] as string[] | undefined,
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
                state: params['state'] as string | undefined,
                status: params['status'] as string | string[] | undefined,
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
              scheduledFor: params['scheduledFor'] as string | undefined,
              scheduleTimezone: params['scheduleTimezone'] as string | undefined,
              publishOnApproval: params['publishOnApproval'] as boolean | undefined,
              tags: params['tags'] as string[] | undefined,
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
              tag: params['tag'] as string | undefined,
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
              skipped: params['skipped'] as KnowledgeSkippedSource[] | undefined,
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

        // --- Project Docs ---
        case 'list_project_docs': {
          const result = await listProjectDocs(
            {
              folder: params['folder'] as string | undefined,
              query: params['query'] as string | undefined,
            },
            config
          )
          return successResponse(result)
        }
        case 'read_project_doc': {
          const result = await readProjectDoc(
            {
              path: params['path'] as string | undefined,
              docId: params['docId'] as string | undefined,
              versionNumber: params['versionNumber'] as number | undefined,
            },
            config
          )
          return successResponse(result)
        }
        case 'write_project_doc': {
          const result = await writeProjectDoc(
            {
              path: params['path'] as string,
              content: params['content'] as string,
            },
            config
          )
          return successResponse(result)
        }
        case 'move_project_doc': {
          const result = await moveProjectDoc(
            {
              path: params['path'] as string | undefined,
              docId: params['docId'] as string | undefined,
              newPath: params['newPath'] as string,
            },
            config
          )
          return successResponse(result)
        }
        case 'delete_project_doc': {
          const result = await deleteProjectDoc(
            {
              path: params['path'] as string | undefined,
              docId: params['docId'] as string | undefined,
            },
            config
          )
          return successResponse(result)
        }
        case 'set_project_doc_task': {
          const result = await setProjectDocTask(
            {
              path: params['path'] as string | undefined,
              docId: params['docId'] as string | undefined,
              lineNumber: params['lineNumber'] as number,
              checked: params['checked'] as boolean,
            },
            config
          )
          return successResponse(result)
        }
        case 'list_project_doc_comments': {
          const result = await listProjectDocComments(
            {
              path: params['path'] as string | undefined,
              docId: params['docId'] as string | undefined,
              includeResolved: params['includeResolved'] as boolean | undefined,
            },
            config
          )
          return successResponse(result)
        }
        case 'comment_on_project_doc': {
          const result = await commentOnProjectDoc(
            {
              path: params['path'] as string | undefined,
              docId: params['docId'] as string | undefined,
              lineNumber: params['lineNumber'] as number,
              content: params['content'] as string,
              quotedText: params['quotedText'] as string | undefined,
            },
            config
          )
          return successResponse(result)
        }
        case 'respond_to_project_doc_comment': {
          const result = await respondToProjectDocComment(
            {
              path: params['path'] as string | undefined,
              docId: params['docId'] as string | undefined,
              commentId: params['commentId'] as string,
              reply: params['reply'] as string | undefined,
              resolve: params['resolve'] as boolean | undefined,
            },
            config
          )
          return successResponse(result)
        }
        case 'list_project_doc_versions': {
          const result = await listProjectDocVersions(
            {
              path: params['path'] as string | undefined,
              docId: params['docId'] as string | undefined,
            },
            config
          )
          return successResponse(result)
        }
        case 'restore_project_doc_version': {
          const result = await restoreProjectDocVersion(
            {
              path: params['path'] as string | undefined,
              docId: params['docId'] as string | undefined,
              versionNumber: params['versionNumber'] as number,
            },
            config
          )
          return successResponse(result)
        }
        case 'move_project_folder': {
          const result = await moveProjectFolder(
            {
              path: params['path'] as string,
              newPath: params['newPath'] as string,
            },
            config
          )
          return successResponse(result)
        }
        case 'delete_project_folder': {
          const result = await deleteProjectFolder({ path: params['path'] as string }, config)
          return successResponse(result)
        }
        case 'list_publish_targets': {
          return successResponse(
            await listPublishTargets({ issueId: params['issueId'] as string | undefined }, config)
          )
        }
        case 'set_publish_targets': {
          return successResponse(
            await setPublishTargets(
              {
                issueId: params['issueId'] as string,
                targets: params['targets'] as PublishTargetSelection[],
              },
              config
            )
          )
        }
        case 'list_assets': {
          return successResponse(await listAssets({ issueId: params['issueId'] as string }, config))
        }
        case 'create_post': {
          return successResponse(await createPost(params as unknown as CreatePostParams, config))
        }
        case 'get_post_status': {
          return successResponse(await getPostStatus({ postId: params['postId'] as string }, config))
        }
        case 'submit_post': {
          return successResponse(
            await submitPost(
              { postId: params['postId'] as string, reviewers: params['reviewers'] as string[] | undefined },
              config
            )
          )
        }
        case 'list_posts': {
          return successResponse(
            await listPosts(
              {
                status: params['status'] as string | undefined,
                since: params['since'] as string | undefined,
                until: params['until'] as string | undefined,
                platform: params['platform'] as string | undefined,
                limit: params['limit'] as number | undefined,
              },
              config
            )
          )
        }
        case 'get_marketing_insights': {
          return successResponse(
            await getMarketingInsights(config, {
              window: params['window'] as '7d' | '30d' | '90d' | undefined,
              platform: params['platform'] as string | undefined,
            })
          )
        }
        case 'get_post_analytics': {
          return successResponse(
            await getPostAnalytics({ postId: params['postId'] as string, since: params['since'] as string | undefined }, config)
          )
        }
        case 'list_top_posts': {
          return successResponse(
            await listTopPosts(
              {
                metric: params['metric'] as string | undefined,
                platform: params['platform'] as string | undefined,
                since: params['since'] as string | undefined,
                limit: params['limit'] as number | undefined,
              },
              config
            )
          )
        }
        case 'submit_review': {
          return successResponse(
            await submitReview(
              {
                postId: params['postId'] as string,
                verdict: params['verdict'] as string,
                summary: params['summary'] as string | undefined,
              },
              config
            )
          )
        }
        case 'upload_asset': {
          return successResponse(
            await uploadAsset(
              {
                issueId: params['issueId'] as string,
                filePath: params['filePath'] as string | undefined,
                url: params['url'] as string | undefined,
                type: params['type'] as string | undefined,
                label: params['label'] as string | undefined,
                width: params['width'] as number | undefined,
                height: params['height'] as number | undefined,
                durationSeconds: params['durationSeconds'] as number | undefined,
              },
              config
            )
          )
        }
        case 'complete_manual_publish': {
          return successResponse(
            await completeManualPublish(
              {
                issueId: params['issueId'] as string,
                targetId: params['targetId'] as string,
                permalink: params['permalink'] as string,
                publishedAt: params['publishedAt'] as string | undefined,
              },
              config
            )
          )
        }
        case 'retry_failed_publish_targets': {
          return successResponse(
            await retryFailedPublishTargets({ issueId: params['issueId'] as string }, config)
          )
        }
        case 'get_brand_kit': {
          return successResponse(await getBrandKit({ kitId: params['kitId'] as string | undefined }, config))
        }
        case 'update_brand_kit': {
          return successResponse(await updateBrandKit(params as unknown as UpdateBrandKitParams, config))
        }
        case 'upload_brand_image': {
          return successResponse(await uploadBrandImage(params as unknown as UploadBrandImageParams, config))
        }
        case 'list_creative_media': {
          return successResponse(await listCreativeMedia(params as unknown as ListCreativeMediaParams, config))
        }
        case 'list_creatives': {
          return successResponse(
            await listCreatives(
              { state: params['state'] as string | undefined, brandKitId: params['brandKitId'] as string | undefined },
              config
            )
          )
        }
        case 'get_creative': {
          return successResponse(await getCreative({ creativeId: params['creativeId'] as string }, config))
        }
        case 'create_creative': {
          return successResponse(await createCreative(params as unknown as CreateCreativeParams, config))
        }
        case 'update_creative': {
          return successResponse(await updateCreative(params as unknown as UpdateCreativeParams, config))
        }
        case 'upload_creative_media':
        case 'upload_creative_photo': {
          const upload = name === 'upload_creative_media' ? uploadCreativeMedia : uploadCreativePhoto
          return successResponse(
            await upload(
              {
                filePath: params['filePath'] as string | undefined,
                url: params['url'] as string | undefined,
                label: params['label'] as string | undefined,
                source: params['source'] as string | undefined,
                licence: params['licence'] as string | undefined,
                aiGenerated: params['aiGenerated'] as boolean | undefined,
              },
              config
            )
          )
        }
        case 'render_creative': {
          // A MOTION render runs past a minute. When the client asked for progress, each render log line
          // goes out as a progress notification, so clients that time out idle tool calls (60s is common)
          // see a live call instead of giving up on it.
          const progressToken = request.params._meta?.progressToken
          let step = 0
          const onProgress = progressToken === undefined
            ? undefined
            : (message: string) => {
                step += 1
                void extra.sendNotification({
                  method: 'notifications/progress',
                  params: { progressToken, progress: step, message },
                }).catch(() => {})
              }
          return successResponse(
            await renderCreativeTool(
              {
                creativeId: params['creativeId'] as string,
                previewOnly: params['previewOnly'] as boolean | undefined,
                renderer: params['renderer'] as string | undefined,
                workflowRunId: params['workflowRunId'] as string | undefined,
              },
              config,
              onProgress
            )
          )
        }
        case 'preview_creative': {
          const result = await previewCreative({ creativeId: params['creativeId'] as string }, config)
          if (result.image) {
            return imageResponse(result.image, {
              renderId: result.renderId,
              state: result.state,
              url: result.url,
              note: result.note,
            })
          }
          return successResponse(result)
        }
        case 'attach_creative_to_post': {
          return successResponse(
            await attachCreativeToPost(
              {
                creativeId: params['creativeId'] as string,
                postId: params['postId'] as string | undefined,
                workItemId: params['workItemId'] as string | undefined,
                renderId: params['renderId'] as string | undefined,
              },
              config
            )
          )
        }
        case 'create_experiment': {
          return successResponse(
            await createExperiment(
              {
                creativeId: params['creativeId'] as string,
                metric: params['metric'] as string | undefined,
                windowHours: params['windowHours'] as number | undefined,
              },
              config
            )
          )
        }
        case 'upload_project_doc_image': {
          const result = await uploadProjectDocImage(
            {
              path: params['path'] as string | undefined,
              docId: params['docId'] as string | undefined,
              filePath: params['filePath'] as string,
            },
            config
          )
          return successResponse(result)
        }
        default:
          return errorResponse(`Unknown tool: ${name}`)
      }
    } catch (err) {
      if (err instanceof ApiError) {
        return errorResponse({ error: err.message, status: err.status, code: err.code, title: err.title })
      }
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
