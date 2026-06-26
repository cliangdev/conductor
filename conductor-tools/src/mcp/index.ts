#!/usr/bin/env node
import { Server } from '@modelcontextprotocol/sdk/server/index.js'
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js'
import { ListToolsRequestSchema, CallToolRequestSchema } from '@modelcontextprotocol/sdk/types.js'
import { getConfig, resolveProject } from './config.js'
import { createIssue, updateIssue, setIssueStatus, listIssues, getIssue } from './tools/issues.js'
import { deleteDocument, scaffoldDocument } from './tools/documents.js'
import { listIssueComments } from './tools/comments.js'
import {
  listWorkflows,
  getAvailableTransitions,
  transitionWorkItem,
  recordAsset,
  reportStepRun,
  createWorkflow,
  getWorkflow,
  updateWorkflow,
  publishWorkflow,
  dispatchWorkflow,
  getWorkflowRun,
} from './tools/workflows.js'
import { listIntegrationTools } from './tools/integrations.js'

const TOOLS = [
  {
    name: 'create_issue',
    description: 'Create a new issue (Work Item) in the project',
    inputSchema: {
      type: 'object',
      properties: {
        type: { type: 'string', description: 'Work Item type, validated against the Workflow (e.g. PRD, FEATURE_REQUEST, BUG_REPORT)' },
        title: { type: 'string', description: 'Issue title' },
        description: { type: 'string', description: 'Issue description (optional)' },
        workflow: { type: 'string', description: 'Workflow slug to run on (optional; defaults to ENGINEERING). Use list_workflows to discover.' },
      },
      required: ['type', 'title'],
    },
  },
  {
    name: 'update_issue',
    description: 'Update an existing issue',
    inputSchema: {
      type: 'object',
      properties: {
        issueId: { type: 'string', description: 'Issue ID' },
        title: { type: 'string', description: 'New title (optional)' },
        description: { type: 'string', description: 'New description (optional)' },
      },
      required: ['issueId'],
    },
  },
  {
    name: 'set_issue_status',
    description: 'Update the status of an issue',
    inputSchema: {
      type: 'object',
      properties: {
        issueId: { type: 'string', description: 'Issue ID' },
        status: { type: 'string', description: 'New status' },
      },
      required: ['issueId', 'status'],
    },
  },
  {
    name: 'list_issues',
    description: 'List issues in the project',
    inputSchema: {
      type: 'object',
      properties: {
        type: { type: 'string', description: 'Filter by type (optional)' },
        status: { type: 'string', description: 'Filter by status (optional)' },
      },
    },
  },
  {
    name: 'get_issue',
    description: 'Get a single issue by ID',
    inputSchema: {
      type: 'object',
      properties: {
        issueId: { type: 'string', description: 'Issue ID' },
      },
      required: ['issueId'],
    },
  },
  {
    name: 'scaffold_document',
    description: 'Create an empty document file locally and register it with the backend. Returns absolutePath (use this with the Write tool — Write requires absolute paths) and localPath (relative, for display).',
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
    name: 'list_issue_comments',
    description: 'List comments on an issue, optionally filtered by resolved status',
    inputSchema: {
      type: 'object',
      properties: {
        issueId: { type: 'string', description: 'Issue ID' },
        resolved: {
          type: 'boolean',
          description: 'Filter by resolved status. true = resolved only, false = unresolved only, omit = all comments',
        },
      },
      required: ['issueId'],
    },
  },
  {
    name: 'list_workflows',
    description: 'List the project\'s Workflows (slug, name, area, display noun, allowed types). Discovery entry point: resolve a natural-language workflow name to its slug before creating a Work Item.',
    inputSchema: { type: 'object', properties: {} },
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
    name: 'dispatch_workflow',
    description: 'Manually trigger a workflow run for testing. Returns runId. Only works on PUBLISHED YAML automation workflows (not statechart lifecycle workflows).',
    inputSchema: {
      type: 'object',
      properties: {
        workflowId: { type: 'string', description: 'Workflow definition ID' },
        inputs: { type: 'object', description: 'Optional input values passed to the workflow run' },
      },
      required: ['workflowId'],
    },
  },
  {
    name: 'get_workflow_run',
    description: 'Get status and step details for a workflow run. Returns status (PENDING/RUNNING/SUCCESS/FAILED), per-job and per-step breakdown, and step logs. Call once after dispatch_workflow to verify the test run started or succeeded before reporting to the user. workflowId and runId come from the dispatch_workflow response.',
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
        case 'create_issue':
        case 'create_work_item': {
          const result = await createIssue(
            {
              type: params['type'] as string,
              title: params['title'] as string,
              description: params['description'] as string | undefined,
              workflow: params['workflow'] as string | undefined,
            },
            config
          )
          return successResponse(result)
        }
        case 'list_integration_tools': {
          return successResponse(await listIntegrationTools({}, config))
        }
        case 'list_workflows': {
          return successResponse(await listWorkflows({}, config))
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
        case 'update_issue': {
          const result = await updateIssue(
            {
              issueId: params['issueId'] as string,
              title: params['title'] as string | undefined,
              description: params['description'] as string | undefined,
            },
            config
          )
          return successResponse(result)
        }
        case 'set_issue_status': {
          const result = await setIssueStatus(
            {
              issueId: params['issueId'] as string,
              status: params['status'] as string,
            },
            config
          )
          return successResponse(result)
        }
        case 'list_issues': {
          const result = await listIssues(
            {
              type: params['type'] as string | undefined,
              status: params['status'] as string | undefined,
            },
            config
          )
          return successResponse(result)
        }
        case 'get_issue': {
          const result = await getIssue(
            { issueId: params['issueId'] as string },
            config
          )
          return successResponse(result)
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
        case 'list_issue_comments': {
          const result = await listIssueComments(
            {
              issueId: params['issueId'] as string,
              resolved: params['resolved'] as boolean | undefined,
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
