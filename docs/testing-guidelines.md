# Testing Guidelines (conductor-backend)

Conventions for fast, reliable backend tests. The overriding goal: keep the suite
fast by minimizing how many Spring contexts and Postgres containers it builds,
without sacrificing isolation.

## Pick the lightest context

Use the narrowest test that proves the behavior:

- **Pure unit test** (no Spring) — a plain JUnit test that news up the class with
  mocked collaborators. Default for service/logic tests.
- **`@WebMvcTest(Controller.class)`** — controller + serialization + security
  slice, collaborators mocked with `@MockitoBean`. For request/response contracts.
- **`@DataJpaTest`** — repository slice against the DB. For query/mapping tests.
- **Full `@SpringBootTest`** — only for true end-to-end flows that exercise the
  wired application over HTTP. These are the most expensive; add them sparingly.

## Share one Postgres container

Tests that need a real Postgres extend the shared base in
`src/test/java/com/conductor/support/`:

- `AbstractE2ETest` — full app on a random port with a `TestRestTemplate`.
- `AbstractNoneWebIntegrationTest` — full context, no web server (repository /
  service integration tests).

Both inherit one **singleton** `PostgreSQLContainer` (started once per JVM) from
`AbstractPostgresIntegrationTest`. **Never declare a per-class `@Container`** — a
private container spawns a new database *and* a new Spring context, which is the
single biggest cause of slow suites.

**Isolation contract:** subclasses share one database. Isolate by unique
identifiers (UUIDs); never assert on globally-scoped counts (assert within a
project/issue/run you created in the test). The one exception: tests that
**enqueue workflow jobs** must keep their own private `@Container` — the workflow
job-queue scheduler claims *any* ready job, so a shared queue lets one context's
scheduler execute another test's job (see the workflow E2E tests).

`ConnectorFeedScheduler` is the same kind of exception, handled differently: it claims
globally-scoped `connector_feed` rows the same way the job-queue scheduler claims jobs,
*and* a claimed pull can enqueue a whole workflow run's worth of jobs on top. Rather than
give every context this scheduler's blast radius, it's **off by default in the test
profile** via `conductor.connector-feed.enabled=false` in
`src/test/resources/application.properties` — a shared constant, not a per-class
`@DynamicPropertySource` override, so it doesn't fragment the context cache (see
"Protect the context cache" below). `ConnectorFeedRepositoryIT` (testing
`ConnectorFeedRepository#claimDue` directly, without the scheduler bean) still needs its
own private `@Container` because that query claims globally-scoped rows regardless of
which bean issues it. `ConnectorFeedSchedulerIntegrationTest`, which does exercise the
scheduler, additionally carries a class-level
`@TestPropertySource(properties = "conductor.connector-feed.enabled=true")` to flip the
flag back on for just that context — both tests already pay for a fresh context via their
own `@Container`, so the property override doesn't cost anything extra.

## Protect the context cache

Spring caches and reuses a context only when its configuration is identical. Each
of these forces a *new* context (and a new Hikari pool) — keep them out of the
common path:

- A distinct `@MockitoBean`/`@MockBean` set.
- A `@DynamicPropertySource` value that differs per class (e.g. a per-class temp
  dir or port). Prefer a constant in `src/test/resources/application.properties`
  so the config stays identical across classes.
- A different `@ActiveProfiles` or `webEnvironment`.
- `@DirtiesContext` — avoid unless genuinely required; it evicts the context.

## Scheduling and async in tests

Schedulers (`@EnableScheduling`) run in every full-context test; some E2E tests
depend on them (e.g. the workflow queue poller), so **don't blanket-disable
scheduling**. Instead the test profile keeps `spring.datasource.hikari.connection-timeout`
short so a scheduled task that fires during context teardown fails fast instead of
blocking for the 30s default.

For async assertions use **Awaitility**, not `Thread.sleep`, and await an
observable state change.

## General hygiene

- One behavior per test; arrange–act–assert.
- No shared mutable state between tests; set up what you need in `@BeforeEach`.
- Name tests for the behavior they verify, not the method they call.
