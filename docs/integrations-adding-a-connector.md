# Adding an integration connector

The connector SPI lives in `conductor-backend/src/main/java/com/conductor/integration/`. A connector
is auto-discovered as a Spring bean (no central registration list) and declares what it can do by the
capability interfaces it implements: `FetchConnector` (pull), `WebhookConnector` (push),
`ActionConnector` (outbound). Most data connectors are `FetchConnector`s.

## Checklist

1. **Backend connector** — `integration/connector/<id>/<Name>Connector.java`, annotated
   `@Component @Profile("!local")`, implementing `FetchConnector`. Give it a stable `getId()` (the
   `connectorId` used everywhere downstream), `getMetadata()` (`ConnectorCategory`, `iconLabel`),
   `getSpec()` (config fields), `fetchData()`, `checkHealth()`, and `getMaxCacheAge()`. Build the
   `RestTemplate` via `ConnectorHttp.restTemplate()` (don't hand-roll timeouts). Return data with
   `ConnectorData.healthy/degraded/setupRequired`; series points use the key **`value`** (the
   frontend reads `value`).
2. **Local stub** — `integration/connector/local/Local<Name>Connector.java`, `@Profile("local")`,
   returning realistic fake data so the page renders end-to-end without real credentials.
3. **OAuth (only if applicable)** — implement `OAuth2Connector` and return your Google scopes from
   `oauthScopes()`. The shared `OAuthFlowService` reads them — you do not touch that service. (Non-
   Google OAuth, or custom auth like Apple's signed-JWT exchange, stays fully inside your connector
   package; see `integration-apple-search-ads.md`.)
4. **Frontend page** — `conductor-frontend/src/components/integrations/<Name>ConnectorPage.tsx`
   (default export, `{ projectId }` prop). Reuse the shared `ConnectorHeader`, `StatCard`, and
   `lib/format` helpers rather than re-declaring them.
5. **Router switch** — add a `case '<id>':` to the switch in
   `app/projects/[projectId]/integrations/[connectorId]/page.tsx` rendering your page.
6. **Logo** — drop `conductor-frontend/public/integrations/<id>.svg` (filename **must equal**
   `getId()`). `ConnectorIcon` picks it up automatically and falls back to the `iconLabel` text badge
   if it's missing — no code change needed.
7. **Tests** — a connector unit test (mock the `RestTemplate`) asserting payload mapping and the
   `setupRequired`/`degraded` paths, matching the existing connector tests.

No database migration is needed — the generic `connection` / `connection_data_cache` tables already
serve every connector. Only add to `openapi.yaml` if your connector needs bespoke endpoints (e.g. a
post-OAuth config picker).

## Known SPI gaps / follow-ups

- **No `onDisconnect` hook.** Connectors that cache per-connection tokens (GitHub, Apple Search Ads)
  expose an `evict(connectionId)` method, but there is no generic disconnect lifecycle event to call
  it from. If per-connection cache purging on disconnect becomes important, add an `onDisconnect`
  extension point to the `Connector` SPI so all connectors can hook it symmetrically.
