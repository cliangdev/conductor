---
name: conductor:prd
description: Create PRDs for Conductor projects through discovery, research, and guided writing. Writes an HTML PRD by default (markdown on request), or imports an existing HTML page / claude.ai artifact as a PRD.
argument-hint: "[import <claude.ai artifact URL or .html path>]"
allowed-tools: mcp__conductor__*, AskUserQuestion, Task, Read, Write, Glob, Grep, WebSearch, Bash, Artifact
---

# /conductor:prd

You are a PRD creation assistant for the Conductor workflow.

## Trigger
This skill runs when the user invokes `/conductor:prd`.

If the argument starts with `import` (`/conductor:prd import <claude.ai artifact URL or .html path>`), skip Phases 1-3 and follow **Import Route** at the end of this file. Otherwise continue with the phases below.

## Prerequisites Check
Read `~/.conductor/config.json`. If missing or lacking `localPath`, `projectId`, or `apiKey`, respond:
> Run `conductor login` and `conductor init` first to set up Conductor in this project.

## Phase 1 — Discovery

Use **AskUserQuestion** throughout this phase to keep interactions structured and choices explicit.

Start with a single open-ended question:
> "What are you trying to build? Tell me about the problem you're solving."

Ask adaptive follow-up questions (one at a time via AskUserQuestion) until you have clarity on all four areas:
- **Problem**: What pain point does this solve? Who feels it?
- **Users**: Who are the primary users and what do they need?
- **Solution**: What is the core approach or mechanism?
- **Scope**: Is this one feature/change, or a larger initiative?

Do NOT proceed to research until the problem, users, and core solution are clear.

If the scope seems large (>3 distinct epics), use AskUserQuestion to ask:
> "This sounds like a large initiative. Would you like to create one overarching PRD with an epic breakdown, or start with the most important piece first?"

With options:
- "One overarching PRD with epic breakdown" (Recommended for large initiatives)
- "Start with the most important piece first"

## Phase 2 — Research

### 2a. Codebase Research (always)

Before drafting, explore the codebase:
1. Read `CLAUDE.md` for conventions and architecture
2. Search for relevant existing patterns using Grep/Glob
3. Identify tech stack, data models, and constraints relevant to the feature
4. Note any files that will need to change
5. **If the feature involves UI**: scan existing frontend pages and components to catalogue the design language in use — layout structure, spacing conventions, color/typography primitives, component library (e.g. shadcn/ui, MUI), interaction patterns, and navigation model. Record these as the baseline that new UI must match.

Use the codebase context to make the PRD technically accurate and grounded.

### 2b. Technology Research (when confidence < 70%)

During discovery, note any technologies, libraries, or frameworks mentioned by the user. After completing codebase research, evaluate your confidence in each:

**Spawn the `conductor-researcher` agent** when ANY of these conditions apply:
- User mentions a library, framework, or API you're less than 70% confident about
- User explicitly asks "what is X?" or "should we use X vs Y?"
- The proposed technical approach involves unfamiliar integrations or third-party services

**Skip research** for technologies well-established in this stack: Next.js 14, Spring Boot 3.3, PostgreSQL, shadcn/ui, Tailwind CSS, Firebase Auth, GCP Cloud Storage.

Spawn pattern:
```
Task({
  subagent_type: "conductor-researcher",
  prompt: `Research the following for PRD context:

Technologies: {list of technologies to research}
User is building: {brief description of what they're building}

Questions to answer:
1. {specific question 1}
2. {specific question 2}

Return findings in the standard research output format with sources.`,
  description: "Research: {technologies}"
})
```

## Phase 3 — Generate

### 3a. Draft Outline

Present a brief outline and confirm intent before writing the full PRD:
- Title
- Problem (1 sentence)
- Goals (2-3 bullet points)
- P0/P1 feature list (names + 1 sentence each)
- Non-Goals (explicit exclusions)

Use AskUserQuestion:
- "Looks good, write the full PRD" (Recommended)
- "Adjust the scope"
- "Add/remove features"
- "Start over"

### 3b. Write Full PRD

**Format.** Write the PRD as **HTML** (`prd.html`) unless the user asked for markdown, in which case use the markdown fallback (`prd.md`). Both formats carry the same schema-v2 content: seven required sections in a fixed order, stable feature and acceptance-criterion IDs, and a contract block on every P0 feature. Target 1-2 pages — concise enough to review in under 5 minutes.

**Content guidelines (both formats):**
- The seven required sections appear in this exact order: Problem, Glossary, Goals, Non-Goals, Users, Reference Patterns, Features. Optional sections (Out of Scope, Open Questions, Supporting Documents) come after.
- If Glossary or Reference Patterns truly has nothing to add, say so explicitly ("None") rather than omitting the section or leaving it empty
- Features get stable IDs that restart within each priority: `P0-1`, `P0-2`, ... and `P1-1`, `P1-2`, ...
- Every P0 feature carries What, Not, Inputs, Outputs, Invariants and Acceptance Criteria. For a P0 feature with no exclusions, write "None" under Not. P1 features may omit Not, Inputs, Outputs and Invariants
- Every acceptance criterion gets a stable ID matching `^AC-P[01]-\d+\.\d+$` (e.g. `AC-P0-1.1`), unique within the PRD, with the feature's ID inside it (`AC-P0-3.2` belongs to `P0-3`)
- Acceptance criteria are verifiable: Given/When/Then or measurable thresholds, not "works correctly"
- Keep technical implementation details out of the PRD (they go in `architecture.md`) and edge cases out (they go in implementation specs)
- Leave Supporting Documents empty until documents exist

**HTML contract (`prd.html`):**
- `<head>` has `<title>` and three metas: `conductor:doc-type` = `prd`, `conductor:schema-version` = `2`, `conductor:issue-id` = the Work Item ID
- Each section is `<section id="{name}" data-prd-section="{name}">` with an `<h2>`; names, in order: `problem`, `glossary`, `goals`, `non-goals`, `users`, `reference-patterns`, `features`, then optional `out-of-scope`, `open-questions`, `supporting-documents`
- An empty Glossary or Reference Patterns contains exactly `<p><em>None.</em></p>`
- Each feature is `<article id="P0-1" data-feature="P0-1">` with an `<h3>`, then a `<dl>` whose `<dt>`s are What, Not, Inputs, Outputs, Invariants, then `<ul data-acs>` of `<li data-ac="AC-P0-1.1">…</li>`
- Self-contained: inline `<style>` and `<script>`, Google Fonts, and CDN scripts only from cdnjs.cloudflare.com, cdn.jsdelivr.net or unpkg.com with a pinned version. No `fetch`/XHR and no `window.claude` calls — Conductor renders the page in a sandbox with no network access. The page needs no script; add one only when it earns its place
- Escape `<`, `>` and `&` in text. Keep ids stable: reviewers' comments reference the nearest section id

### HTML PRD Template

Keep this structure and token block; adapt the visual style to the product's design language from Phase 2a when the PRD is UI-heavy.

```html
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="conductor:doc-type" content="prd">
<meta name="conductor:schema-version" content="2">
<meta name="conductor:issue-id" content="{issueId}">
<title>{title}</title>
<style>
:root{--bg:#fff;--fg:#1a1d21;--muted:#5b6470;--line:#e3e6ea;--card:#f6f7f9;--accent:#2f5bea}
@media (prefers-color-scheme:dark){:root{--bg:#14161a;--fg:#e8eaed;--muted:#9aa3ae;--line:#2a2e35;--card:#1c1f25;--accent:#7b9bff}}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--fg);font:16px/1.6 system-ui,-apple-system,"Segoe UI",sans-serif}
nav{position:sticky;top:0;z-index:1;display:flex;gap:16px;overflow-x:auto;white-space:nowrap;padding:10px 16px;background:var(--bg);border-bottom:1px solid var(--line);font-size:14px}
nav a{color:var(--muted);text-decoration:none}nav a:hover{color:var(--accent)}
main{max-width:760px;margin:0 auto;padding:0 16px 64px}
h1{font-size:2rem;line-height:1.2;margin:32px 0 4px}
h2{font-size:1.25rem;margin:0 0 12px}
h3{font-size:1.05rem;margin:0 0 8px}
.sub{color:var(--muted);margin:0 0 8px}
section{padding:24px 0;border-top:1px solid var(--line);scroll-margin-top:56px}
article{background:var(--card);border:1px solid var(--line);border-radius:10px;padding:16px;margin:12px 0}
dl{display:grid;grid-template-columns:max-content 1fr;gap:6px 16px;margin:0 0 12px}
dt{color:var(--muted);font-weight:600}dd{margin:0}
ul[data-acs]{margin:0;padding-left:20px}
@media (max-width:560px){dl{grid-template-columns:1fr}dd{margin-bottom:6px}}
</style>
</head>
<body>
<nav>
<a href="#problem">Problem</a><a href="#glossary">Glossary</a><a href="#goals">Goals</a><a href="#non-goals">Non-Goals</a><a href="#users">Users</a><a href="#reference-patterns">Reference Patterns</a><a href="#features">Features</a>
</nav>
<main>
<h1>{title}</h1>
<p class="sub">PRD · {issueId}</p>

<section id="problem" data-prd-section="problem"><h2>Problem</h2>
<p>{2-3 sentences: what hurts, for whom, and how we know}</p></section>

<section id="glossary" data-prd-section="glossary"><h2>Glossary</h2>
<dl><dt>{Term}</dt><dd>{project-specific definition}</dd></dl>
<!-- nothing to define: replace the <dl> with <p><em>None.</em></p> --></section>

<section id="goals" data-prd-section="goals"><h2>Goals</h2>
<ul><li>{Measurable outcome}</li></ul></section>

<section id="non-goals" data-prd-section="non-goals"><h2>Non-Goals</h2>
<ul><li>{Explicit exclusion}</li></ul></section>

<section id="users" data-prd-section="users"><h2>Users</h2>
<p>{Target users and their primary needs, 2-4 sentences}</p></section>

<section id="reference-patterns" data-prd-section="reference-patterns"><h2>Reference Patterns</h2>
<ul><li><strong>{Aspect}</strong>: {existing file or convention to mirror}</li></ul>
<!-- none apply: replace the <ul> with <p><em>None.</em></p> --></section>

<section id="features" data-prd-section="features"><h2>Features</h2>
<h3 class="sub">P0: Must Have</h3>
<article id="P0-1" data-feature="P0-1"><h3>P0-1: {Feature Name}</h3>
<dl>
<dt>What</dt><dd>{precise scope in 1 sentence}</dd>
<dt>Not</dt><dd>{explicit exclusions, or <em>None.</em>}</dd>
<dt>Inputs</dt><dd>{what triggers this / what data flows in}</dd>
<dt>Outputs</dt><dd>{artifact, response or state change}</dd>
<dt>Invariants</dt><dd>{properties that must always hold}</dd>
</dl>
<ul data-acs>
<li data-ac="AC-P0-1.1">{Given/When/Then or measurable threshold}</li>
<li data-ac="AC-P0-1.2">{testable criterion}</li>
</ul></article>

<h3 class="sub">P1: Should Have</h3>
<article id="P1-1" data-feature="P1-1"><h3>P1-1: {Feature Name}</h3>
<dl><dt>What</dt><dd>{precise scope in 1 sentence}</dd></dl>
<ul data-acs><li data-ac="AC-P1-1.1">{testable criterion}</li></ul></article>
</section>

<section id="out-of-scope" data-prd-section="out-of-scope"><h2>Out of Scope</h2>
<ul><li>{Item explicitly deferred}</li></ul></section>

<section id="open-questions" data-prd-section="open-questions"><h2>Open Questions</h2>
<ul><li>{unresolved item}</li></ul></section>

<section id="supporting-documents" data-prd-section="supporting-documents"><h2>Supporting Documents</h2>
<p>Links are added here as supporting documents are created.</p></section>
</main>
</body>
</html>
```

### Markdown fallback (`prd.md`, only when the user asks for markdown)

Same content rules. Markdown specifics:
- Frontmatter MUST include `schemaVersion: 2`
- If `Glossary` or `Reference Patterns` has nothing to add, write `_None._`
- Every P0 feature includes all six bold-prefix lines: `**What**`, `**Not**`, `**Inputs**`, `**Outputs**`, `**Invariants**`, `**Acceptance Criteria**` (`**Not**: _None._` when nothing is excluded)
- Each AC line is `- [ ] \`AC-P0-1.1\` …`

```markdown
---
issueId: {issueId}
title: {title}
createdAt: {ISO timestamp}
schemaVersion: 2
---

# {title}

## Problem
{2-3 sentences: what hurts, for whom, and how we know}

## Glossary
- **{Term}**: {project-specific definition, distinguishing it from generic usage}

(If truly nothing to define, write `_None._`)

## Goals
- {Measurable outcome 1}
- {Measurable outcome 2}

## Non-Goals
- {Explicit exclusion 1}

## Users
{Target users and their primary needs — 2-4 sentences}

## Reference Patterns
- **{Aspect}**: {existing file or convention to mirror, e.g. "follow `src/commands/doctor.ts` — same Commander registration, `--json` output mode, exit codes"}

(If truly no reference patterns apply, write `_None._`)

## Features

### P0: Must Have

#### P0-1: {Feature Name}
- **What**: {precise scope in 1 sentence}
- **Not**: {explicit exclusions, or `_None._` if nothing is excluded}
- **Inputs**: {what triggers this feature / what data flows in}
- **Outputs**: {what artifact, response, or state change results}
- **Invariants**: {properties that must always hold — uniqueness, ordering, idempotency, etc.}
- **Acceptance Criteria**:
  - [ ] `AC-P0-1.1` {Given/When/Then or measurable threshold}
  - [ ] `AC-P0-1.2` {testable criterion}

### P1: Should Have

#### P1-1: {Feature Name}
- **What**: {precise scope in 1 sentence}
- **Acceptance Criteria**:
  - [ ] `AC-P1-1.1` {testable criterion}

### Out of Scope
- {Item explicitly deferred}

## Open Questions
- [ ] {unresolved item}

## Supporting Documents
{Links will be added here as supporting documents are created}
```

### 3c. Self-Check

Before showing the PRD to the user in Step 3d, run through the checklist for the format you wrote and fix any item that fails. Do not skip — this is the gate that keeps schema v2 PRDs deterministic for downstream agents. `conductor lint {issueId}` runs the same checks after saving.

**If HTML (`prd.html`):**
- [ ] `<head>` has `<title>` and the metas `conductor:doc-type` = `prd`, `conductor:schema-version` = `2`, `conductor:issue-id`
- [ ] Seven `<section id data-prd-section>` elements in order: `problem`, `glossary`, `goals`, `non-goals`, `users`, `reference-patterns`, `features` — each with an `<h2>`
- [ ] `glossary` and `reference-patterns` hold real entries or exactly `<p><em>None.</em></p>`
- [ ] Every feature is `<article id="P0-n" data-feature="P0-n">` (numbering restarts per priority) with an `<h3>`
- [ ] Every P0 feature's `<dl>` has `<dt>`s What, Not, Inputs, Outputs, Invariants, and a `<ul data-acs>` follows (P1 features may omit all but What)
- [ ] Every criterion is `<li data-ac="AC-P0-1.1">` with an id matching `^AC-P[01]-\d+\.\d+$`
- [ ] Every AC id is unique, and its feature part matches the enclosing article (`AC-P0-3.2` only inside `P0-3`)
- [ ] No `fetch`/XHR/`window.claude`; scripts, if any, are inline or pinned from cdnjs.cloudflare.com, cdn.jsdelivr.net or unpkg.com

**If markdown (`prd.md`):**
- [ ] Frontmatter contains `schemaVersion: 2`
- [ ] All seven H2 sections are present in this exact order: `Problem`, `Glossary`, `Goals`, `Non-Goals`, `Users`, `Reference Patterns`, `Features`
- [ ] `Glossary` and `Reference Patterns` are non-empty (either real entries, or the literal text `_None._`)
- [ ] Every P0 feature has all six bold-prefix lines: `**What**`, `**Not**`, `**Inputs**`, `**Outputs**`, `**Invariants**`, `**Acceptance Criteria**` (P0 features with no exclusions write `**Not**: _None._`)
- [ ] Every feature heading uses a stable ID — `P0-1`, `P0-2`, ... within P0; `P1-1`, `P1-2`, ... within P1 (numbering restarts per priority)
- [ ] Every acceptance criterion line matches the regex `^- \[[ x]\] \`AC-P[01]-\d+\.\d+\`` (the AC ID is wrapped in backticks immediately after the checkbox)
- [ ] Every AC ID is unique within the PRD (no two ACs share the same `AC-P{0|1}-{n}.{m}` token)
- [ ] AC IDs match their feature: a feature `P0-3` only has ACs of the form `AC-P0-3.{m}`

If any check fails, edit the draft until all pass, then proceed to 3d.

### 3d. Review

Present the full PRD and ask via AskUserQuestion:
- "Save as-is" (Recommended)
- "Make changes"
- "Discard and start over"

## Phase 4 — Save

Call MCP tools in this exact sequence:

1. **Resolve the lifecycle Workflow** (discover-then-create — never assume ENGINEERING):
   `list_workflows({kind: "LIFECYCLE"})`, then pick the Workflow whose allowed `types` include `PRD` (the
   engineering lifecycle in a default project). Capture its `slug` as `workflow`. If more than one lifecycle
   Workflow allows `PRD`, use AskUserQuestion to let the user choose which one this PRD belongs to.

2. **Create the Work Item**: `create_work_item({workflow, type: "PRD", title, description})`
   - Receive: `{issueId, displayId, localPath}`

3. **Scaffold document**: `scaffold_document({issueId, filename: "prd.html"})` (or `"prd.md"` when writing markdown). The filename extension sets the content type.
   - Receive: `{localPath: ".conductor/issues/{issueId}/prd.html", absolutePath: "/abs/path/to/.conductor/issues/{issueId}/prd.html"}`

4. **Write content**: Use the Write tool with the `absolutePath` from step 3 (Write requires absolute paths) to write the full PRD, with the `issueId` from step 2 in the `conductor:issue-id` meta tag (HTML) or the YAML frontmatter (markdown).

5. **Move to IN_REVIEW** (definition-driven): call `get_available_transitions({issueId})` to confirm `IN_REVIEW` is an available next status from the Workflow's initial status, then `transition_work_item({issueId, toStatus: "IN_REVIEW"})`, signalling the PRD is ready for team review.

6. **Confirm**: "PRD saved — **{displayId}** is now IN_REVIEW."

Then offer supporting documents via AskUserQuestion:
> "Would you like to add any supporting documents?"

Options:
- **Architecture diagram** (`architecture.md`) — Mermaid system diagram + component table
- **Wireframes** (`wireframes.md`) — ASCII layouts for desktop and mobile
- **HTML mock** (`mockup.html`) — standalone HTML prototype
- None — finish

For each accepted supporting document:
1. `scaffold_document({issueId, filename: "{doc-filename}"})`
2. Write the template content (see templates below) using the Write tool with the returned `absolutePath`
3. **Update the PRD's Supporting Documents section**: Read the saved PRD file (use the `absolutePath` returned from step 3 of the main save sequence), replace the placeholder line with a relative link entry, and write it back.
   - HTML PRD: add `<li><a href="./architecture.md">Architecture</a> (architecture.md)</li>` inside a `<ul>` in the `supporting-documents` section. Conductor's HTML viewer does not yet turn `./` links into document tabs, so keep the filename visible as text next to the link.
   - Markdown PRD: `- [Architecture](./architecture.md)` (the markdown viewer opens it as a tab).

**UI/UX quality bar for wireframes and HTML mocks:**
Before writing any UI artifact, recall the design language catalogued in Phase 2a. All wireframes and mocks must:
- **Be user-first**: present the user's primary task prominently; avoid burying key actions in menus or below the fold
- **Be consistent with the existing product**: reuse the same layout skeleton, navigation position, spacing scale, component vocabulary, and color/type tokens found in codebase research — do not invent new patterns when existing ones apply
- **Show real interaction states**: include empty state, loading, error, and success variants — not just the happy path
- **Follow progressive disclosure**: surface only what users need at each step; defer advanced options to secondary panels or modals
- **Apply accessibility basics**: adequate contrast, meaningful labels on controls, keyboard-navigable flows
- **Annotate decisions**: for non-obvious layout choices, add a brief rationale comment so reviewers understand intent

## Supporting Document Templates

### Mermaid authoring rules (ASCII-safe — follow these to avoid render failures)

Conductor renders Mermaid in the web app, and its parser rejects several characters that are easy to write by reflex. Keep every ```mermaid block ASCII-safe:

- **No `;` inside `sequenceDiagram` message labels** — `;` is a statement separator and splits the line. Use a comma: `A->>B: do x, then y` (not `do x; then y`).
- **No `#` in labels** — Mermaid treats `#` as an HTML-entity escape. Write `Gate 1`, not `Gate #1`.
- **No `(`, `)`, or `:` in participant aliases** — `participant Web as Founder` is fine; `participant Web as Web app (Founder)` and `participant S as conductor:video-batch` break the parser. Drop the parens/colon.
- **No unicode arrows or dashes in labels** — use `->`/`to` and `-`, not `→`, `–`, `—`.
- **Line breaks in node labels use `<br/>`, never `\n`** — `\n` renders literally. `A["Service<br/>status"]`, not `A["Service\nstatus"]`.
- **Give subgraphs the `id ["Title"]` form** when you reference them in edges: `subgraph API ["Spring Boot API"]`.

`conductor lint` flags these (errors for `;` / alias chars, warnings for `#` / literal `\n`) — run it after writing supporting docs.

### Architecture Diagram (architecture.md)
```markdown
---
issueId: {issueId}
type: architecture
title: {title} — Architecture
---

# {title} — Architecture

## System Overview

\`\`\`mermaid
flowchart TD
    User([User]) --> Frontend[Next.js Frontend]
    Frontend --> API[Spring Boot API]
    API --> DB[(PostgreSQL)]
    API --> Storage[GCP Cloud Storage]
\`\`\`

## Component Responsibilities

| Component | Technology | Responsibility |
|-----------|-----------|----------------|
| Frontend  | Next.js 14 | UI, auth, API calls |
| API       | Spring Boot 3.3 | Business logic, data |
| Database  | PostgreSQL 15 | Persistent storage |
| Storage   | GCP Cloud Storage | File storage |

## Key Sequence

\`\`\`mermaid
sequenceDiagram
    participant U as User
    participant F as Frontend
    participant A as API
    participant D as DB
    U->>F: User action
    F->>A: API request
    A->>D: Query
    D-->>A: Result
    A-->>F: Response
    F-->>U: Updated UI
\`\`\`
```

### Wireframes (wireframes.md)
```markdown
---
issueId: {issueId}
type: wireframes
title: {title} — Wireframes
---

# {title} — Wireframes

> Style notes: mirrors existing product design language — [note the component library, layout skeleton, and navigation model observed in Phase 2a, e.g. "left sidebar nav, shadcn/ui Card + Button primitives, 4px spacing scale"]

## User Flow

\`\`\`
[Entry point] → [Primary action] → [Confirmation / Result]
                      ↓ (error path)
               [Error state] → [Recovery action]
\`\`\`

## Desktop Layout — {Primary Screen Name}

\`\`\`
+--------------------+------------------------------------------+
|  {Nav / Sidebar}   |  {Page Title}          [{Action Button}] |
|                    +------------------------------------------+
|  {Nav Item 1}  ◀  |  {Section heading}                       |
|  {Nav Item 2}      |  +--------------------------------------+ |
|  {Nav Item 3}      |  |  {Primary Content}                   | |
|                    |  |                                      | |
|                    |  |  {Field / Element 1}  [  Action  ]   | |
|                    |  |  {Field / Element 2}                 | |
|                    |  +--------------------------------------+ |
+--------------------+------------------------------------------+
\`\`\`

## Mobile Layout — {Primary Screen Name}

\`\`\`
+-----------------------------+
|  ☰  {App Name}             |
+-----------------------------+
|  {Page Title}               |
|  ─────────────────────────  |
|  {Primary Content}          |
|                             |
|  {Field / Element 1}        |
|  [      Primary Action    ] |
+-----------------------------+
\`\`\`

## State Variants

### Empty State
\`\`\`
+------------------------------------------+
|  {Illustration or icon placeholder}      |
|  {Heading: "No {items} yet"}             |
|  {Sub-copy: what user should do next}    |
|  [  {Primary CTA}  ]                     |
+------------------------------------------+
\`\`\`

### Loading State
\`\`\`
+------------------------------------------+
|  ▓▓▓▓▓▓▓▓▓▓▓▓  (skeleton line)          |
|  ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓  (skeleton line)    |
+------------------------------------------+
\`\`\`

### Error State
\`\`\`
+------------------------------------------+
|  ⚠  {Error message}                      |
|  {Recovery instruction}    [Retry]        |
+------------------------------------------+
\`\`\`

## Element Annotations

| Element | Component | Interaction | Notes |
|---------|-----------|-------------|-------|
| {Element 1} | {e.g. Button / Card / Input} | {click / hover / focus behavior} | {any non-obvious rationale} |
| {Element 2} | ... | ... | ... |

## Accessibility Notes
- {e.g. "Primary action button must have visible focus ring and aria-label"}
- {e.g. "Error messages linked to input via aria-describedby"}
```

### HTML Mock (mockup.html)
Write a valid standalone HTML file with inline CSS — no external dependencies.

**Style consistency rules for HTML mocks:**
- Match the color palette, border-radius, and font observed in the existing product (reference the Phase 2a findings; if unknown, use a clean neutral palette — white/gray-50 backgrounds, gray-900 text, a single accent color)
- Replicate the existing layout skeleton (sidebar, top nav, or other chrome) so the new screen looks like it belongs in the product
- Use the same component vocabulary: if the product uses card containers with subtle shadows, use that here; if it uses bordered panels, do that instead
- Show at least two states: the default/happy-path view AND one of empty/loading/error
- Add a small `<!-- note: ... -->` comment for any placeholder or approximation that differs from the real product

## Import Route

`/conductor:prd import <claude.ai artifact URL or .html path>` brings an existing HTML page into Conductor as a PRD. It uses your Claude Code access to the artifact, because Conductor's backend cannot fetch private claude.ai pages.

1. **Read the page.**
   - A `.html` / `.htm` path: Read the file.
   - A claude.ai artifact URL (`claude.ai/artifact/{id}` or `claude.ai/code/artifact/{id}`): call the Artifact tool with `action: "read"` and the URL.
     - If the result is only a summary because the artifact belongs to someone else, **stop**. Say the full page is only readable for artifacts the user owns, and ask them to download the `.html` file and re-run `/conductor:prd import <path>`.
     - If the result lists declared runtime capabilities (live data, shared state, Claude calls), warn the user: Conductor renders pages in a sandbox with no network and no `window.claude`, so those parts will not work there.
     - For the user's own artifact the result names a saved file that holds the full page: Read that file.
2. **Detect whether it already meets the HTML PRD contract**: the `conductor:doc-type` = `prd` meta tag plus `data-prd-section` and `data-ac` markers (Grep the content).
   - **Meets it**: keep the page verbatim. Skip to step 3 and only set `conductor:issue-id` (adding the meta tag if absent) once the Work Item exists. Run the HTML self-check from 3c and report any failures to the user rather than rewriting their page.
   - **Does not meet it**: use AskUserQuestion with these options:
     - "Convert to Conductor PRD (keeps content and styling, adds the structure)" (Recommended) — restructure the page into the seven sections, feature articles and `data-ac` criteria; keep the author's wording, CSS and layout. Do not invent content: put real source material in each section, write "None" where the page has nothing, and draft any missing P0 fields (Not, Inputs, Outputs, Invariants, acceptance criteria) from the page's own content, flagging them for review. Run the 3c self-check, then 3d.
     - "Attach as-is as a supporting document"
     - "Cancel" — stop.
   - **Attach as-is**: ask (AskUserQuestion) which Work Item to attach to — an existing one (`list_work_items`) or a new one (resolve the Workflow and create it as in Phase 4 steps 1-2). Then `scaffold_document({issueId, filename})` with a descriptive `.html` filename that is not `prd.html`, Write the page verbatim to the returned `absolutePath`, and confirm. No status transition.
3. **Save a PRD** (meets the contract, or converted) with the Phase 4 sequence: resolve the Workflow, `create_work_item` (title from `<title>` or the `<h1>`, description from the Problem section), `scaffold_document({issueId, filename: "prd.html"})`, Write, then the IN_REVIEW transition and confirmation. Offer supporting documents as in Phase 4.
