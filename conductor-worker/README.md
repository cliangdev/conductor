# conductor-worker

Job execution worker for Conductor. Runs user-defined jobs inside Docker containers and streams logs back to the backend.

The backend hands jobs to a worker via HTTP; the worker pulls the requested image, starts a container with a scoped ephemeral token in the environment, streams logs to a callback URL, and reports success/failure + outputs back when the container exits.

## Stack

- Node.js 20+, TypeScript
- Express for the HTTP server
- Docker (via the local Docker socket) for job execution
- Jest + Supertest for tests

## Prerequisites

- Node.js 20+
- Docker daemon reachable (the worker talks to the Docker socket)
- A shared secret the backend will present as `Bearer <token>` when calling the worker

## Environment

| Variable | Default | Description |
|----------|---------|-------------|
| `PORT` | `3001` | HTTP port |
| `WORKER_AUTH_TOKEN` | _(required)_ | Bearer token the backend must present |
| `CONDUCTOR_MAX_CONCURRENT_JOBS` | `5` | Soft cap on concurrent jobs; additional requests return `503` |

See [`src/auth.ts`](src/auth.ts) for the exact auth logic and [`src/docker.ts`](src/docker.ts) for the container launch flow.

## Run locally

```bash
npm install
export WORKER_AUTH_TOKEN=dev-worker-token
npm run dev
```

The worker listens on `http://localhost:3001` and exposes:

- `POST /run-job` — launch a job (backend-only; requires `Authorization: Bearer ...`)

## Tests

```bash
npm test
```

## Build and run compiled output

```bash
npm run build   # emits dist/
npm start       # runs dist/index.js
```

## Deployment

The worker is packaged as a container image and typically runs alongside a Docker daemon (for example, as a VM or dedicated host in the `runner-image/` deployment). See [`runner-image/`](../runner-image) at the repo root for the runner host image.

## Further reading

- [Root README](../README.md) — architecture overview
- [docs/workflows.md](../docs/workflows.md) — how jobs, steps, and runs are modeled end-to-end
