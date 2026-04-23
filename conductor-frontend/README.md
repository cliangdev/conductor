# conductor-frontend

Next.js 15 web app for Conductor. App Router, TypeScript, Tailwind CSS, shadcn/ui primitives. Authenticates via Firebase Google OAuth and a backend-issued JWT stored in an HTTP-only cookie.

## Stack

- Next.js 15 (App Router), React 19, TypeScript
- Tailwind CSS + shadcn/ui + `@radix-ui` primitives
- Firebase JS SDK for Google OAuth
- `react-markdown` + `remark-gfm` + `rehype-highlight` + `mermaid` for PRD rendering
- Monaco editor for code blocks
- `@xyflow/react` + `dagre` for workflow DAG visualization
- Vitest (unit) + Playwright (E2E)

## Prerequisites

- Node.js 20+
- The Conductor backend running locally (typically via `make dev` at the repo root)

## Environment

Copy `.env.local.example` to `.env.local` and fill in:

| Variable | Description |
|----------|-------------|
| `NEXT_PUBLIC_API_URL` | Backend base URL (e.g. `http://localhost:8080`) |
| `NEXT_PUBLIC_FIREBASE_API_KEY` | Firebase Web API key |
| `NEXT_PUBLIC_FIREBASE_AUTH_DOMAIN` | Firebase auth domain |
| `NEXT_PUBLIC_FIREBASE_PROJECT_ID` | Firebase project ID |

## Run locally

```bash
npm install
npm run dev
```

Open <http://localhost:3000>.

## Tests

```bash
# Unit + component tests (Vitest + Testing Library + jsdom)
npm test

# E2E tests (Playwright) — run from the repo root, requires the full stack via `make dev`
cd .. && make e2e
```

## Lint

```bash
npm run lint
```

## Project layout

```
src/
├── app/
│   ├── app/projects/[projectId]/
│   │   ├── issues/              Issue list
│   │   │   └── [issueId]/       Issue detail: PRD viewer + comments + reviews
│   │   └── members/             Member management
│   ├── invites/[token]/accept/  Invite acceptance flow
│   └── login/                   Firebase OAuth login
├── components/
│   ├── comments/                CommentableDocument, CommentThread, NewCommentForm
│   ├── issues/                  StatusDropdown and issue-list widgets
│   ├── markdown/                MarkdownRenderer
│   ├── members/                 MemberRow
│   ├── reviews/                 ReviewSubmissionForm, ReviewersSummaryPanel
│   └── ui/                      shadcn/ui primitives
├── contexts/                    AuthContext (Firebase + JWT), ProjectContext
└── lib/api.ts                   apiGet / apiPost / apiPatch / apiDelete helpers
```

## Deployment

Deploys to Google Cloud Run via `.github/workflows/frontend-cd.yml`. Firebase public config values are injected at container build time as Docker build args; runtime config is minimal.

## Further reading

- [Root README](../README.md) — architecture overview
- [CLAUDE.md](../CLAUDE.md) — repo-wide conventions
- [CONTRIBUTING.md](../CONTRIBUTING.md) — how to propose changes
