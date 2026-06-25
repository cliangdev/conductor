---
name: conductor:prd
description: Create PRDs for Conductor projects through discovery, research, and guided writing
allowed-tools: mcp__conductor__*, AskUserQuestion, Task, Read, Write, Glob, Grep, WebSearch, Bash
---

# /conductor:prd

You are a PRD creation assistant for the Conductor workflow.

## Trigger
This skill runs when the user invokes `/conductor:prd`.

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

Write the PRD using the format below. Target 1-2 pages — keep it concise enough to review in under 5 minutes.

**Guidelines:**
- Frontmatter MUST include `schemaVersion: 2`
- The seven required H2 sections must appear in this exact order: `Problem`, `Glossary`, `Goals`, `Non-Goals`, `Users`, `Reference Patterns`, `Features`
- If `Glossary` or `Reference Patterns` truly has nothing to add, write `_None._` rather than omitting the section
- Features get stable IDs that restart within each priority: `P0-1`, `P0-2`, ... and `P1-1`, `P1-2`, ...
- Every P0 feature must include all six bold-prefix lines: `**What**`, `**Not**`, `**Inputs**`, `**Outputs**`, `**Invariants**`, `**Acceptance Criteria**`
- For P0 features with no exclusions, write `**Not**: _None._` rather than omitting the line; P1 features may omit `Not`
- Every acceptance criterion line gets a stable ID matching the regex `^AC-P[01]-\d+\.\d+$` (e.g. `AC-P0-1.1`, `AC-P1-2.3`); AC IDs must be unique within the PRD
- Acceptance criteria should be verifiable: use "Given/When/Then" or measurable thresholds — not vague descriptions like "works correctly"
- Keep technical implementation details out of the main PRD (they go in `architecture.md`)
- Keep edge cases out of the main PRD (they go in implementation specs)
- Leave the Supporting Documents section empty — links will be added when docs are created

### PRD Format

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
- **{Term}**: {definition}

(If truly nothing to define, write `_None._`)

## Goals
- {Measurable outcome 1}
- {Measurable outcome 2}

## Non-Goals
- {Explicit exclusion 1}
- {Explicit exclusion 2}

## Users
{Target users and their primary needs — 2-4 sentences}

## Reference Patterns
- **{Aspect}**: {existing file or convention to mirror, e.g. "follow `src/commands/doctor.ts` — same Commander registration, `--json` output mode, exit codes"}
- **{Aspect}**: {pattern}

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

#### P0-2: {Feature Name}
- **What**: {precise scope in 1 sentence}
- **Not**: _None._
- **Inputs**: {inputs}
- **Outputs**: {outputs}
- **Invariants**: {invariants}
- **Acceptance Criteria**:
  - [ ] `AC-P0-2.1` {testable criterion}

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

Before showing the PRD to the user in Step 3d, run through this checklist mentally and fix any items that fail. Do not skip — this is the gate that keeps schema v2 PRDs deterministic for downstream agents.

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

1. **Create issue**: `create_issue({type: "PRD", title, description})`
   - Receive: `{issueId, displayId, localPath}`

2. **Scaffold document**: `scaffold_document({issueId, filename: "prd.md"})`
   - Receive: `{localPath: ".conductor/issues/{issueId}/prd.md", absolutePath: "/abs/path/to/.conductor/issues/{issueId}/prd.md"}`

3. **Write content**: Use the Write tool with the `absolutePath` from step 2 (Write requires absolute paths) to write the full PRD with YAML frontmatter including the `issueId` from step 1.

4. **Move to IN_REVIEW** (definition-driven): call `get_available_transitions({issueId})` to confirm `IN_REVIEW` is an available next status from `DRAFT`, then `transition_work_item({issueId, toStatus: "IN_REVIEW"})`, signalling the PRD is ready for team review.

5. **Confirm**: "PRD saved — **{displayId}** is now IN_REVIEW."

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
3. **Update the PRD's Supporting Documents section**: Read the saved `prd.md` (use the `absolutePath` returned from step 2 of the main save sequence), replace the placeholder line with a relative link entry (e.g. `- [Architecture](./architecture.md)`), and write it back.

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
