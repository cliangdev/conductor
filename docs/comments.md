# Comments

One comment model for every commentable document: a document attached to a Work Item (PRDs, specs, HTML pages) and a project doc. People and agents write to it the same way.

## Model

Table `comments` (with `comment_replies`). A comment has exactly one **target**:

| Target | Columns | Notes |
|---|---|---|
| Work Item document | `work_item_id` + `document_id` | `documentHash` is stamped; `outdated` is computed at read |
| Project doc | `project_doc_id` | `outdated` is always false for now; comments carry no `documentHash` until a save re-stamps them |

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

A thread is **open** or **resolved**. Resolving stores `resolved_at` and who resolved it (a user, or `resolved_by_label` for an agent). Anyone with access may resolve or reopen; reopening clears all three. `line_stale` means a line comment's text could not be found again after a save; see [Re-anchoring on save](#re-anchoring-on-save).

### Authorship

A comment or reply is written by a **user** (`author_id`) or an **agent** (`author_label`, e.g. `Agent (run a1b2c3d4)`; `author_agent_id` names the agent when it is known: a comment-agent run, or an in-process tool call; see [Attribution](#attribution-of-agent-writes)). A CHECK guarantees one of the two. Responses carry `authorKind` (`USER` | `AGENT`), `authorName` and, when `author_agent_id` is set, `authorAgent {id, name, avatar}`.

Permissions:

- Create, reply, resolve, reopen: any project member, and any machine credential for the project (project API key, run token).
- Edit text (`content`): the author only. An agent may edit only comments an agent wrote. Sets `editedAt`.
- Delete a comment or reply: its author, or a project editor/admin. A machine actor may delete only machine-written rows.

## Re-anchoring on save

Every save of a Work Item document (`DocumentService.upsertDocumentByFilename`) or a project doc (`ProjectDocService.updateDoc`, which version restore also goes through) re-finds the anchors of that target's **unresolved** comments (`CommentAnchorService.reanchor`). Resolved comments are history and are left alone. The checkbox toggle (`setTaskState`) skips it: no line moves.

| Comment | Found again | Not found |
|---|---|---|
| Quote (`anchor.exact`) | `anchor.prefix`/`suffix` refreshed from the new text, `documentHash` set to the new hash, `anchorStatus = ANCHORED` | `anchorStatus = ORPHANED`; hash and anchor left as written, so `outdated` stays true. Clients show it under "Whole document" with its original quote |
| Line | Same line still has the stored `quotedText` (trimmed): kept. Else the text is on exactly one other line: `lineNumber` moves there. Hash re-stamped, `lineStale` cleared | `lineStale = true` (text gone, edited, or on several lines) |
| Whole document | Hash re-stamped | n/a |

So `outdated` (hash mismatch) is now **false for any comment that carried over**; it stays true only for orphaned quotes and for comments resolved before the save. `anchorStatus` is null until the document is first saved after V206. A quote that returns in a later save is `ANCHORED` again.

The server finds quotes the way the browser does. `TextProjection` flattens the document to its visible text (HTML parsed with jsoup, markdown rendered with commonmark first; whitespace collapsed, one space at block boundaries and `<br>`, script/style/noscript/template/`[hidden]` dropped) and `QuoteMatcher` is a port of `findQuote` in `htmlBridge.ts` (exact match, best prefix/suffix agreement, 32-character context). The two stay in step through `testdata/text-quote-cases.json`, run by `TextQuoteCasesTest` (JUnit) and `src/lib/textQuote.cases.test.ts` (vitest, real bridge and `MarkdownRenderer`). Known gap: the server cannot see CSS, so text hidden by a stylesheet (not the `hidden` attribute) is still projected.

## API

`/api/v2/projects/{projectId}/comments` (spec: `openapi-v2.yaml`, controller: `CommentsController`, logic: `CommentService`).

| Request | Purpose |
|---|---|
| `GET ?workItemId=&documentId=` or `?projectDocId=` `&status=open\|resolved\|all` | Threads with nested replies, oldest first. `workItemId` alone lists every document of the Work Item. Default status `all`. |
| `POST {target, content, anchor?, lineNumber?, documentHash?}` | Create a thread. `target` is `{workItemId, documentId}` or `{projectDocId}`. |
| `PATCH /{commentId} {content?, status?: OPEN\|RESOLVED}` | Edit, resolve or reopen. |
| `DELETE /{commentId}` | Delete the thread. |
| `POST /{commentId}/replies {content}` | Reply. |
| `GET /mentionables?q=` | Members and addressable agents for the `@` picker (see [Mentions](#mentions)). |
| `PATCH /{commentId}/replies/{replyId} {content}` | Edit a reply (author only). |
| `DELETE /{commentId}/replies/{replyId}` | Delete a reply. |

A thread response has `id`, `kind`, `status`, the target ids, `documentName`, author fields, `content`, `mentions[]`, `lineNumber` (LINE only), `quotedText`, `anchor` (QUOTE only), `documentHash`, `lineStale`, `anchorStatus` (`ANCHORED` | `ORPHANED` | null), `outdated`, `resolvedAt`, `resolvedByName`, `editedAt`, `createdAt`, `updatedAt`, `agentRequests[]` and `replies[]` (each reply has `mentions[]` too).

### Older routes (shims)

Still served, with their original response shapes, until the contract step below:

- `/api/v2/projects/{p}/work-items/{wi}/comments` (create, list, reply, resolve, delete). Original anchor rules: HTML needs `anchor`, markdown needs `lineNumber`. Principals are no longer cast to `User`, so run tokens work.
- `/api/v1/projects/{p}/docs/{doc}/comments` (list, create, reply, resolve, delete), via `DocCommentService`, now a facade over `CommentService`.

## Suggested edits

A QUOTE comment can carry replacement text for its quote (`suggested_text`, `V208`). A person accepts or rejects it; accepting edits the document.

| Request | Purpose |
|---|---|
| `POST /comments {…, anchor, suggestion: {text}}` | Create a suggestion (status `PENDING`). Needs `anchor` (400 otherwise); `text` may be empty (a deletion) but must differ from the selection. 422 when the quote is not suggestable. |
| `POST /comments/suggestion-check {target, anchor}` | `{suggestable, reason?}`, so the composer can enable the toggle. Never writes. |
| `POST /comments/{id}/suggestion-decision {decision: ACCEPT\|REJECT}` | Decide. Returns the updated thread. |

Responses carry `suggestion {text, status, decidedByName?, decidedAt?}` (`status` `PENDING` \| `ACCEPTED` \| `REJECTED`), null on an ordinary comment.

**Suggestable** means `SourceQuoteLocator` maps the selection (written against the *rendered* text) to exactly one contiguous span of the *source*. Strict by design:

- Markdown: the quote must be plain source text (whitespace may differ, e.g. a wrapped line). Emphasis, links, code spans or entities inside the quote, or a span across a blank line, are refused. A quote wholly inside one emphasis run is fine.
- HTML: the quote must sit inside one text node (jsoup); a span crossing an element boundary is refused. Entities are matched decoded and kept as written outside the edit; the replacement is HTML-escaped.
- Repeats: the occurrence the reader chose is the one `QuoteMatcher` picks in the rendered text; it maps to the same-numbered source occurrence only when source and rendered text have the same number of occurrences, otherwise it is refused.

All refusals read "This text appears more than once or spans formatting — comment instead".

**Deciding** is for people only (403 for an agent, run token or project key; agents may create suggestions). `ACCEPT` needs the Editor role, re-locates the quote in the current source (409 "The text changed since this was suggested" if it is not found in exactly one place), and saves through `DocumentService.upsertDocumentByFilename` (Work Item document) or `ProjectDocService.updateDoc` (project doc, which adds a version) with the content hash it read as `baseContentHash`, so [re-anchoring](#re-anchoring-on-save) runs for the other comments. Then it sets `ACCEPTED`, `decided_by/at` and resolves the thread. `REJECT` sets `REJECTED` and resolves; the document is untouched. Both are one transaction. The suggestion's own thread is resolved before the save, so it is never marked orphaned. A decided suggestion answers 409; a comment that is not a suggestion answers 400.

## Notifications

| Signal | When | Chat event (`EventType`, channel group Issues) |
|---|---|---|
| `conductor.work_item.comment_added` / `comment_replied` | a comment or reply on a Work Item document | `COMMENT_ADDED` / `COMMENT_REPLY` |
| `conductor.project_doc.comment_added` / `comment_replied` | a comment or reply on a project doc. Payload: `docId, docTitle, commentAuthor, excerpt, link` | `DOC_COMMENT_ADDED` / `DOC_COMMENT_REPLY` |
| `conductor.comment.mentioned` | a comment, reply or edit adds a [mention](#mentions); only the mentions that are new in that write | `COMMENT_MENTIONED` |

A workspace opts into each chat event per channel, as for every event type (new types are off until switched on; the settings page lists them from `ChannelGroup`). Excerpts show mention tokens as plain `@Name`. Agent authors are named by their label, or by the agent's name when it was asked (see [Attribution](#attribution-of-agent-writes)).

`conductor.comment.mentioned` payload: `projectId`, `commentId`, `replyId?`, `workItemId?` + `documentId?` or `projectDocId?`, `documentTitle` (Work Item title / filename, or the doc title), `authorId?` (null for a machine), `authorName`, `authorKind` (`USER` | `AGENT`), `excerpt`, `link` (the deep link below), `mentionedNames` (`@Bob, @Editor Bot`), `userIds[]`, `agentIds[]`. The Discord card reads "<author> mentioned <names> on <document>" with the link; it carries an explicit empty `allowed_mentions`, and nothing in it pings anyone.

**Email.** Each newly mentioned member (never the author, never an agent) gets a short email through Resend (`EmailService.sendMentionEmail`; skipped, with a log line, when `RESEND_API_KEY` is not set), sent after the writing transaction commits, so a comment that rolls back emails nobody and a failed email never reaches the commenter. Text is escaped; the subject is "<author> mentioned you on <document>".

**Deep links** (in the email and the chat card):

- Work Item document: `${FRONTEND_URL}/app/projects/{p}/{area}/{nouns}/{displayId}?doc=<documentId>&comment=<commentId>` (falls back to `/app/projects/{p}/issues/{workItemId}?...` when the Work Item's workflow area cannot be resolved).
- Project doc: `${FRONTEND_URL}/app/projects/{p}/docs/{docId}?comment=<commentId>`.

The frontend must read `?doc=` (select that document's tab) and `?comment=` (focus and scroll to that thread) on both pages.

## Mentions

A mention is a token in the body of a comment or reply (no mentions table):

| Token | Names |
|---|---|
| `@[Display Name](user:<userId>)` | a project member |
| `@[Display Name](agent:<agentId>)` | an `ACTIVE`, [addressable](conversations.md#addressable-agents) agent of the project |

The stable agent identifier is the **agent id** (the UUID in `agents.id`; it survives a rename). An agent's **slug** is also accepted on input and rewritten to the id, so hand-written tokens work.

`MentionParser` re-checks every token on **every** write that carries text (create, reply, edit of a comment or reply, and the v1 project-doc shim) and stores the validated body, so what is stored is what was checked:

- the id is rewritten with the canonical id and **name** (a forged display name never survives);
- a token that does not check out is flattened to plain `@Name`: a user who is not a member of this project (including an id from another project or a made-up one), an agent that is not `ACTIVE`, not addressable, not in this project, or unknown;
- **machine-written text cannot mention agents** (run tokens, project API keys): agent tokens are flattened. An agent may still mention people. This is the first of two loop guards (see below);
- the same mention twice is one mention. Text without `@[` costs no lookups.

Reads need no lookup: responses derive `mentions` from the stored tokens. (Rows written by an older release through the shared database are not validated; they can show a stale or forged name in `mentions` but never notify or dispatch anyone.)

Responses (comment threads and replies) carry:

```json
"mentions": [ { "kind": "USER" | "AGENT", "id": "<userId|agentId>", "name": "Bob" } ]
```

**On edit**, only mentions that were not in the previous body are published; unchanged mentions notify nobody again.

### Picker: `GET /api/v2/projects/{projectId}/comments/mentionables?q=`

Members first, then agents (alphabetical, at most 20), `q` a case-insensitive substring of the name (and an agent's slug); blank returns everyone. Only `ACTIVE`, addressable agents appear. It is the picker's endpoint (no existing members or agents endpoint filters by addressable, or carries both in one shape). Insert the pick as `@[<name>](<kind lower-case>:<id>)`.

```json
[
  { "kind": "USER",  "id": "<userId>",  "name": "Bob", "avatarUrl": "https://…" | null, "avatar": null },
  { "kind": "AGENT", "id": "<agentId>", "name": "Editor Bot", "avatarUrl": null, "avatar": { "emoji": "🤖", "color": "#4f46e5" } }
]
```

## @agent in a thread

A person mentioning an agent asks it to act on the thread: revise the document as the thread asks, answer a question, or ask a clarifying question, and reply in the thread. The agent never resolves the thread.

1. **Publish.** The write publishes `conductor.comment.mentioned` with `agentIds[]`.
2. **Guards.** `CommentAgentDispatchSubscriber` dispatches only when `authorKind` is `USER`: text an agent wrote never starts a run (second loop guard, after the parser stripping agent mentions from machine text). It defers to after the transaction commits (`AfterCommit`), so a rolled-back comment starts nothing and the run can see the committed comment.
3. **Request ledger.** `CommentAgentRequestService.begin` inserts a `comment_agent_requests` row (V207) in its own transaction, under a lock on the comment row. One request per (thread, agent) may be in flight: while an earlier one is undispatched (younger than 10 minutes) or its run is not terminal, a new mention starts nothing. A partial unique index closes the same window in the database (`WHERE workflow_run_id IS NULL AND error IS NULL`); the part an index cannot see (the run's status lives in another table) is the locked check.
4. **Checks, in plain words.** Refusals are stored on the row as `error` and shown under the thread: "<Agent> isn't active, so it can't respond. Activate it under Automation → Agents."; "Set up an AI provider in Settings → AI Providers so <Agent> can run." (no usable runtime: `AgentRuntimeResolver`); a workspace's own workflow already named `conductor-comment-agent`; a trigger failure.
5. **Run.** `CommentAgentWorkflowProvisioner.ensure` seeds (or refreshes) the `conductor-comment-agent` [system workflow](workflows.md#system-managed-workflows) from `resources/comments/comment-agent.yaml`, and `WorkflowTriggerService.fireTrigger` starts it with the thread as its event payload: `commentId`, `requestId`, `agentId`, `requesterName`, the target (`workItemId` + `documentFilename`, or `projectDocId`), `documentTitle`, and `threadText` (the quote and every message, mentions as `@Name`, capped at 20,000 characters). The run id is recorded on the request.
6. **The agent's task** (domain-agnostic: PRDs, briefs, wiki pages): read the thread, read the document with `read_document` / `read_project_doc` and note its `contentHash`, make the change with **one** write passing `baseContentHash` (on a 409: read again, redo, write once more), then `respond_to_comment` starting "Changed: …" (or the answer, or the clarifying question). Never resolve, never mention agents, touch nothing else.
7. **Tools.** The step uses `with.grant_tools` ([workflows.md](workflows.md#agent--run-an-ai-agent)) to give the run the six `comment:` tools. On the `claude-code` runtime these are the Conductor MCP tools of the same name, so the runner image needs a CLI that ships `list_comments`, `respond_to_comment`, `read_document` and the `baseContentHash` writes (CLI 0.60.0 or later); on the `api` runtime `CommentToolProvider` implements them in-process.

### Attribution of agent writes

A comment or reply written with a **run token** whose workflow run has a `comment_agent_requests` row is recorded as that agent: `author_agent_id` = the agent, `author_label` = its name, so responses show `authorKind: AGENT`, `authorName: "Editor Bot"` and `authorAgent {id, name, avatar}`. (`CommentAgentAttribution`, applied by `CommentsController`; `ProjectActor` gained an optional `agentId`.) The agent's first reply also fills the request's `reply_id`. Any other run token or project API key stays the anonymous `Agent (run a1b2c3d4)`. The `api` runtime tools write as the agent directly.

### `agentRequests` on a thread

Each thread response carries the requests on it, oldest first (two queries however many threads):

```json
"agentRequests": [
  {
    "agentId": "<agentId>",
    "agentName": "Editor Bot",
    "avatar": { "emoji": "🤖", "color": "#4f46e5" },
    "status": "PENDING" | "RUNNING" | "SUCCEEDED" | "FAILED",
    "error": "plain words, set when FAILED" | null,
    "createdAt": "2026-11-15T10:00:00Z"
  }
]
```

`status` is derived, never stored: no run yet → `PENDING` (`FAILED` once the row is older than 10 minutes with no run); run `PENDING` / `PENDING_LOCAL_PICKUP` → `PENDING`; `RUNNING` / `CANCELLING` → `RUNNING`; `SUCCESS` → `SUCCEEDED`; `FAILED`, `CANCELLED`, `LOCAL_PICKUP_TIMEOUT` → `FAILED` (with an `error`). A dispatch refusal is `FAILED` immediately. The create/reply response is built before the commit, so it carries no request yet: a client polls the thread list (every 5 s, up to 20 minutes) while any request is `PENDING` or `RUNNING`, and shows "Working on it…" or the `error`.

### Anchor context for quote-only comments

`add_comment` (MCP) sends `anchor: {exact}` only. On `POST /comments` a QUOTE anchor with `exact` but neither `prefix` nor `suffix` gets them from the document: the current text is projected with `TextProjection`, `exact` is found with `QuoteMatcher` (the first occurrence when it appears more than once) and the 32-character `prefix`/`suffix` are filled the way the browser's `buildQuote` does. `sectionId`/`sectionTitle` are only kept if sent; they are not derived. If the text is not in the document the answer is `400 That text isn't in the document`. An anchor that already carries context is stored as sent, and the original Work Item route (`/work-items/{wi}/comments`) does not guess (old clients keep their behaviour).

## Web UI behaviour

- **Mentions:** typing `@` in any composer (rail, sheets, popover, whole-document, replies, edits) opens a listbox fed by `mentionables`. The box shows `@Name`; the stored token is substituted on send, and tokens are turned back into `@Name` when editing. Bodies render mentions as chips. The **Mine** filter keeps threads I wrote or am mentioned in (thread or any reply).
- **`@agent`:** a thread shows "<agent> is working on it…" while a request is `PENDING`/`RUNNING`, or the failure text if `FAILED`. After posting a comment or reply with an agent token the list is refetched once about 1.5s later, then every 5s while anything is in flight (stops after 20 minutes), and on window focus. When a request finishes, the page reloads the document's content and hash.
- **Suggested edits:** a "Suggest edit" toggle appears on new selection comments (not replies, whole-document comments or review drafts); it calls `suggestion-check` and is disabled with the reason when the selection is not suggestable. Cards show the old text struck out and the new text inserted, with Accept/Reject. Accepting reloads the document; 403 shows "Only editors can accept suggestions", 409 shows the server's detail and refetches.
- **Deep links:** `?comment=<id>` on a project doc, and `?comment=<id>[&doc=<documentId>]` on a Work Item (the document tab is picked from `doc`, else from the comment), selects the document, focuses the thread (scrolls to its highlight, or opens the sheet on narrow screens) and then removes the parameters from the address.

## Expand / contract

Preview deploys share the production database, so the move is in steps:

1. **Expand** (`V204`): `comments` / `comment_replies` become nullable where needed and gain the project-doc and agent columns; the old release keeps working (it always fills the columns that became nullable and never sees project-doc rows).
2. **Copy** (`V205`): every `doc_comments` / `doc_comment_replies` row is copied under the same id, and triggers mirror later writes to the legacy tables into the unified ones, one direction only. Code in this release writes `comments` only, so nothing is written twice. Limitation: the previous release does not read what this release writes for project docs.
3. **Contract** (later migration): drop the triggers and the legacy tables `doc_comments` / `doc_comment_replies`, and remove the v1 project-doc comment paths, the old Work Item comment routes, and the `DocComment*` classes.

`doc_comments` is legacy: do not write to it.

## Agent tools (MCP)

`list_comments`, `add_comment` (optional `suggestion` = replacement text for `quote`) and `respond_to_comment` cover both targets (`issueId` + `filename`, or `docId` for a project doc). `read_document` returns a Work Item document's text and `contentHash`; `read_project_doc` returns the same hash for a project doc. Both `write_document` and `write_project_doc` accept `baseContentHash`: when it differs from the current content hash the backend answers 409 ("Document changed since you read it") and writes nothing. For a project doc the hash is the SHA-256 of the stored content (`contentHash` on `ProjectDocResponse`). `/conductor:comments` is the command that loops over open threads using these tools.
