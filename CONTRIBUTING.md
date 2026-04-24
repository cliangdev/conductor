# Contributing to Conductor

Thanks for your interest in contributing! Conductor is a source-available project under the [PolyForm Noncommercial License 1.0.0](LICENSE) — contributions of all sizes (bug reports, docs, code) are welcome.

By participating, you agree to uphold our [Code of Conduct](CODE_OF_CONDUCT.md).

## Reporting bugs

Open a [bug report issue](https://github.com/cliangdev/conductor/issues/new?template=bug_report.md). Please include reproduction steps, environment details (OS, Node/Java versions, component), and redacted logs or screenshots.

**Security vulnerabilities:** do not file a public issue. Use [GitHub Security Advisories](https://github.com/cliangdev/conductor/security/advisories/new) — see [SECURITY.md](SECURITY.md) for full details.

## Suggesting features

Open a [feature request issue](https://github.com/cliangdev/conductor/issues/new?template=feature_request.md) describing the problem you're trying to solve, your proposed solution, and any alternatives considered. For open-ended discussion, use [Discussions](https://github.com/cliangdev/conductor/discussions).

## Proposing code changes

1. **Fork** the repository and create a branch off `main`.
2. **Make your change.** Keep PRs focused — one logical change per PR. Update tests and docs alongside the code.
3. **Run tests locally** before pushing:
   - Backend: `cd conductor-backend && mvn test`
   - Frontend: `cd conductor-frontend && npm run lint && npx vitest run`
   - Tools: `cd conductor-tools && npm run build && npx vitest run`
   - Full stack: `make dev` then `make e2e`
4. **Open a pull request** against `main`. Fill out the PR template. Link the issue it addresses.
5. **Sign the CLA.** First-time contributors will be prompted by a bot on their first PR (see below).
6. **Respond to review feedback.** CI must be green before merge.

Local development setup is documented in the [root README](README.md#local-development) and the subproject READMEs.

## Contributor License Agreement (CLA)

Before your first contribution can be merged, you'll need to sign the project's [CLA](.github/CLA.md). A bot will comment on your PR with instructions — you sign once by posting a short comment, and the signature covers all future contributions.

The CLA grants the project maintainer the rights needed to distribute your contribution under the project's license and, if ever needed, under alternative licensing terms. Your own copyright is preserved.

## Coding conventions

Broad conventions are documented in [CLAUDE.md](CLAUDE.md) (originally written for AI coding agents but equally useful for humans). Subproject-specific notes live in each subproject's README. In short:

- **Backend:** OpenAPI-first — edit `openapi.yaml`, run `mvn generate-sources`, implement the generated interface.
- **Frontend:** TypeScript + React, shadcn/ui, Tailwind. Keep components small and colocated with their feature.
- **Migrations:** Flyway `V<n>__description.sql` in `conductor-backend/src/main/resources/db/migration`.
- **Tests:** Add a test with every behavior change. Integration tests for backend touch a real Postgres (via Testcontainers).

## Commit messages

Use a short, imperative subject line (under ~72 chars). A brief body is welcome for non-trivial changes. No strict convention (Conventional Commits, etc.) is required, but keep the intent clear.

## Commercial use

This project is **free for personal and non-commercial use**. Commercial use requires a separate license — see [LICENSE](LICENSE) for the full terms and open a GitHub Discussion if you'd like to explore commercial licensing.
