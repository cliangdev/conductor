# Legacy v1 Issue surface — deprecation tracking

Phase 1 renamed the internal domain to **Work Item** (`WorkItem` entity → `work_items` table,
`WorkItemService`, `WorkItemRepository`). Phase 2a stands up the canonical **`/api/v2` `work-items`**
resource that delegates to those services. The old v1 `issues` surface stays served and unchanged
(additive migration); this doc tracks what is now legacy, its v2 replacement, and the removal criteria.

## Legacy surface → v2 replacement

| Legacy v1 surface | Status | v2 / canonical replacement |
| --- | --- | --- |
| `GET  /api/v1/projects/{projectId}/issues` (`listIssues`) | deprecated | `GET  /api/v2/projects/{projectId}/work-items` (`listWorkItems`) |
| `POST /api/v1/projects/{projectId}/issues` (`createIssue`) | deprecated | `POST /api/v2/projects/{projectId}/work-items` (`createWorkItem`) |
| `GET  /api/v1/projects/{projectId}/issues/{issueId}` (`getIssue`) | deprecated | `GET  /api/v2/projects/{projectId}/work-items/{workItemId}` (`getWorkItem`) |
| `PATCH /api/v1/projects/{projectId}/issues/{issueId}` (`patchIssue`) | deprecated | `PATCH /api/v2/projects/{projectId}/work-items/{workItemId}` (`patchWorkItem`) |
| `DELETE /api/v1/projects/{projectId}/issues/{issueId}` (`deleteIssue`) | deprecated | `DELETE /api/v2/projects/{projectId}/work-items/{workItemId}` (`deleteWorkItem`) |
| `GET /api/v1/projects/{projectId}/issues/{issueId}/available-transitions` (`listAvailableTransitions`) | active (sub-resource, see Phase 2b) | `GET /api/v2/.../work-items/{workItemId}/available-transitions` (`getWorkItemAvailableTransitions`) |
| (no v1 equivalent) | new in v2 | `GET /api/v2/projects/{projectId}/work-items/by-display/{displayId}` (`getWorkItemByDisplayId`) |
| MCP `*_issue` tools (`create_issue`, `get_issue`, `update_issue`, `list_issues`, `set_issue_status`) | legacy naming | to migrate to `work_item` tool naming (tracked separately) |
| Old CLI `conductor issue …` commands | legacy | to migrate to `work-item` commands (tracked separately) |
| Local files `~/.conductor/{projectId}/issues/**` | legacy layout | to migrate to a `work-items` layout (tracked separately) |

The v2 `WorkItemResponse` is the v1 `IssueResponse` plus a `workflow` slug field, and adds the
`by-display` lookup. All v2 endpoints delegate to the shared `WorkItemService` /
`WorkItemWorkflowService` — there is no duplicated business logic.

## Removal criteria

The legacy v1 issue core endpoints (and the `*_issue` MCP tools / old CLI / `issues/**` file layout)
are removed only once:

- **Old-CLI usage → 0** — telemetry shows no remaining traffic from the legacy `conductor issue` CLI
  or the `*_issue` MCP tools against the v1 issue core paths.
- All first-party clients (frontend, CLI, MCP) cut over to `/api/v2 work-items`.

## Phase 2b (tracked follow-up)

- Migrate the **sub-resource** endpoints under `/issues/{issueId}/…` (documents, comments, reviewers,
  reviews, assets, step-runs, metrics) to the `work-items` path.
- **Physically extract** the legacy issue paths/schemas out of `openapi.yaml` into a dedicated
  legacy spec, leaving `openapi-v2.yaml` as the canonical external contract.
- Rename the MCP `*_issue` tools and the CLI `issue` commands to their `work-item` equivalents.
