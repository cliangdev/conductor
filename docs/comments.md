# Comments

One comment model for every commentable document: a document attached to a Work Item (PRDs, specs, HTML pages) and a project doc. People and agents write to it the same way.

## Model

Table `comments` (with `comment_replies`). A comment has exactly one **target**:

| Target | Columns | Notes |
|---|---|---|
| Work Item document | `work_item_id` + `document_id` | `documentHash` is stamped; `outdated` is computed at read |
| Project doc | `project_doc_id` | `outdated` is always false for now |

A database CHECK enforces "exactly one target". Deleting the document or project doc deletes its comments.

### Kinds

`kind` is derived from the anchor columns, never stored:

| Kind | Anchor | When |
|---|---|---|
| `QUOTE` | `anchor` (W3C text-quote: `exact`, `prefix`, `suffix`, `sectionId`, `sectionTitle`), `quotedText` = the selection | any document |
| `LINE` | `lineNumber`, `quotedText` = that source line | markdown and project docs, never HTML |
| `DOCUMENT` | none | the whole document |

Sending both `anchor` and `lineNumber` is a 400. An HTML document rejects `lineNumber`.

### State

A thread is **open** or **resolved**. Resolving stores `resolved_at` and who resolved it (a user, or `resolved_by_label` for an agent). Anyone with access may resolve or reopen; reopening clears all three. `line_stale` is the old "line may have moved" flag: saving a project doc still marks every unresolved comment stale.

### Authorship

A comment or reply is written by a **user** (`author_id`) or an **agent** (`author_label`, e.g. `Agent (run a1b2c3d4)`; `author_agent_id` is reserved for attributing a named agent). A CHECK guarantees one of the two. Responses carry `authorKind` (`USER` | `AGENT`), `authorName` and, when `author_agent_id` is set, `authorAgent {id, name, avatar}`.

Permissions:

- Create, reply, resolve, reopen: any project member, and any machine credential for the project (project API key, run token).
- Edit text (`content`): the author only. An agent may edit only comments an agent wrote. Sets `editedAt`.
- Delete a comment or reply: its author, or a project editor/admin. A machine actor may delete only machine-written rows.

## API

`/api/v2/projects/{projectId}/comments` (spec: `openapi-v2.yaml`, controller: `CommentsController`, logic: `CommentService`).

| Request | Purpose |
|---|---|
| `GET ?workItemId=&documentId=` or `?projectDocId=` `&status=open\|resolved\|all` | Threads with nested replies, oldest first. `workItemId` alone lists every document of the Work Item. Default status `all`. |
| `POST {target, content, anchor?, lineNumber?, documentHash?}` | Create a thread. `target` is `{workItemId, documentId}` or `{projectDocId}`. |
| `PATCH /{commentId} {content?, status?: OPEN\|RESOLVED}` | Edit, resolve or reopen. |
| `DELETE /{commentId}` | Delete the thread. |
| `POST /{commentId}/replies {content}` | Reply. |
| `PATCH /{commentId}/replies/{replyId} {content}` | Edit a reply (author only). |
| `DELETE /{commentId}/replies/{replyId}` | Delete a reply. |

A thread response has `id`, `kind`, `status`, the target ids, `documentName`, author fields, `content`, `lineNumber` (LINE only), `quotedText`, `anchor` (QUOTE only), `documentHash`, `lineStale`, `outdated`, `resolvedAt`, `resolvedByName`, `editedAt`, `createdAt`, `updatedAt` and `replies[]`.

### Older routes (shims)

Still served, with their original response shapes, until the contract step below:

- `/api/v2/projects/{p}/work-items/{wi}/comments` (create, list, reply, resolve, delete). Original anchor rules: HTML needs `anchor`, markdown needs `lineNumber`. Principals are no longer cast to `User`, so run tokens work.
- `/api/v1/projects/{p}/docs/{doc}/comments` (list, create, reply, resolve, delete), via `DocCommentService`, now a facade over `CommentService`.

## Notifications

Work Item comments publish `conductor.work_item.comment_added` / `comment_replied` as before, naming an agent author by its label. Project-doc comments publish nothing yet; mentions and their notifications come with the @mention work.

## Expand / contract

Preview deploys share the production database, so the move is in steps:

1. **Expand** (`V204`): `comments` / `comment_replies` become nullable where needed and gain the project-doc and agent columns; the old release keeps working (it always fills the columns that became nullable and never sees project-doc rows).
2. **Copy** (`V205`): every `doc_comments` / `doc_comment_replies` row is copied under the same id, and triggers mirror later writes to the legacy tables into the unified ones, one direction only. Code in this release writes `comments` only, so nothing is written twice. Limitation: the previous release does not read what this release writes for project docs.
3. **Contract** (later migration): drop the triggers and the legacy tables `doc_comments` / `doc_comment_replies`, and remove the v1 project-doc comment paths, the old Work Item comment routes, and the `DocComment*` classes.

`doc_comments` is legacy: do not write to it.

## Agent tools (MCP)

`list_comments`, `add_comment` and `respond_to_comment` cover both targets (`issueId` + `filename`, or `docId` for a project doc). `read_document` returns a Work Item document's text and `contentHash`; `read_project_doc` returns the same hash for a project doc. Both `write_document` and `write_project_doc` accept `baseContentHash`: when it differs from the current content hash the backend answers 409 ("Document changed since you read it") and writes nothing. For a project doc the hash is the SHA-256 of the stored content (`contentHash` on `ProjectDocResponse`). `/conductor:comments` is the command that loops over open threads using these tools.
