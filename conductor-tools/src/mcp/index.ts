#!/usr/bin/env node
import { Server } from '@modelcontextprotocol/sdk/server/index.js'
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js'
import { ListToolsRequestSchema, CallToolRequestSchema } from '@modelcontextprotocol/sdk/types.js'
import { getConfig } from './config.js'
import { createIssue, updateIssue, setIssueStatus, listIssues, getIssue } from './tools/issues.js'
import { deleteDocument, scaffoldDocument } from './tools/documents.js'
import { listIssueComments } from './tools/comments.js'

const TOOLS = [
  {
    name: 'create_issue',
    description: 'Create a new issue in the project',
    inputSchema: {
      type: 'object',
      properties: {
        type: { type: 'string', description: 'Issue type (e.g. task, epic, story)' },
        title: { type: 'string', description: 'Issue title' },
        description: { type: 'string', description: 'Issue description (optional)' },
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
    description: 'Create an empty document file locally and register it with the backend. Use the returned localPath to write content with the Write tool.',
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

    const { name, arguments: args } = request.params
    const params = (args ?? {}) as Record<string, unknown>

    try {
      switch (name) {
        case 'create_issue': {
          const result = await createIssue(
            {
              type: params['type'] as string,
              title: params['title'] as string,
              description: params['description'] as string | undefined,
            },
            config
          )
          return successResponse(result)
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
