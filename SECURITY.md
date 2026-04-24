# Security Policy

## Reporting a vulnerability

**Please do not open a public issue for security vulnerabilities.**

Instead, report them privately by opening a [GitHub Security Advisory](https://github.com/cliangdev/conductor/security/advisories/new). This keeps the report private between you and the maintainer until a fix is ready.

Include in your report:

- A description of the issue and its impact
- Steps to reproduce (or a proof-of-concept, if safe to share)
- Affected component (backend, frontend, cli, mcp, worker) and version/commit
- Any suggested remediation

## Response expectations

- **Acknowledgement:** within 72 hours of the report.
- **Initial assessment:** within 7 days — we'll confirm whether the report is in-scope and a rough severity.
- **Fix timeline:** depends on severity. Critical issues are prioritized; lower-severity issues are batched with regular releases.
- **Credit:** with your permission, we'll credit you in the advisory and any release notes. Conductor does not currently offer monetary bounties.

## Scope

**In scope:**

- The Conductor backend, frontend, CLI, MCP server, and worker code in this repository
- Docker images and deployment configurations shipped from this repository
- Dependencies we vendor or configure (but third-party CVEs should usually be reported upstream)

**Out of scope:**

- Issues requiring physical access to a user's machine
- Denial-of-service via resource exhaustion that requires privileged network access
- Issues in forks that have diverged from this repository
- Social engineering attacks

## Supported versions

Only the `main` branch and the most recent released version of `@cliangdev/conductor` on npm are actively supported. Older versions will not receive security patches — please upgrade.

## Disclosure

Once a fix is merged and released, we'll publish the security advisory (and typically a CVE, for higher-severity issues) crediting the reporter unless they request otherwise.
