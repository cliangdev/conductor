# conductor-backend

Spring Boot 4 REST API for Conductor. Java 21, Maven, PostgreSQL 15, Flyway migrations, Firebase-based auth.

## Stack

- Spring Boot 4, Spring Security, Spring Data JPA
- PostgreSQL 15 via Flyway migrations
- Firebase Admin SDK for Google OAuth verification
- JJWT for app-issued JWTs
- Resend for transactional email
- Google Cloud Storage for document storage
- Testcontainers + WireMock + H2 for tests

## Prerequisites

- Java 21 (Temurin recommended)
- Maven 3.9+
- A running PostgreSQL 15 instance (the root `docker-compose.yml` provides one)

## Local development

The easiest path is the root `make dev` target, which starts backend, frontend, and Postgres in Docker. If you just want the backend:

```bash
# Start Postgres only
docker compose up -d postgres

# Configure env (see `.env.local.example` at the repo root)
export DATABASE_URL=jdbc:postgresql://localhost:5432/conductor
export DATABASE_USERNAME=conductor
export DATABASE_PASSWORD=conductor
export APP_JWT_SECRET=dev-secret-32-bytes-min-change-me-plz
# Firebase + Resend + GCP vars are optional for most local work
# — see `CLAUDE.md` at the repo root for the full list.

# Run the server
mvn spring-boot:run
```

The API listens on `http://localhost:8080` by default. Swagger UI is available at `http://localhost:8080/swagger-ui.html`.

## Tests

```bash
mvn test
```

Integration tests spin up a real Postgres via Testcontainers and mock external HTTP dependencies with WireMock. No external services are required.

## OpenAPI-first workflow

All REST endpoints are defined in `src/main/resources/openapi.yaml`. When you add or change an endpoint:

1. Edit `openapi.yaml`.
2. Run `mvn generate-sources` — this produces controller interfaces and request/response DTOs under `target/generated-sources/openapi`.
3. Implement the generated interface in a `@RestController` under `src/main/java/com/conductor/controller`.

Do **not** hand-write controller interfaces or DTOs — they're regenerated on every build.

## Project layout

```
src/main/java/com/conductor/
├── config/        Spring Security, GCP storage, RestTemplate beans
├── controller/    REST controllers implementing generated interfaces
├── dto/           Manually-authored DTOs (most are generated)
├── entity/        JPA entities
├── exception/     GlobalExceptionHandler, typed exceptions (RFC 7807)
├── repository/    Spring Data JPA repositories
├── security/      JWT filter, API key filter, Firebase verification
└── service/       Business logic

src/main/resources/
├── openapi.yaml                 Source of truth for all API endpoints
├── application.yml              Default config
└── db/migration/V*.sql          Flyway migrations (immutable once merged)
```

## Deployment

Production deploys to Google Cloud Run via `.github/workflows/backend-cd.yml`. First-time GCP setup (service account, Workload Identity Federation, Secret Manager) is documented in [DEPLOY.md](DEPLOY.md).

## Further reading

- [Root README](../README.md) — architecture overview and monorepo setup
- [CLAUDE.md](../CLAUDE.md) — repo-wide conventions
- [CONTRIBUTING.md](../CONTRIBUTING.md) — how to propose changes
