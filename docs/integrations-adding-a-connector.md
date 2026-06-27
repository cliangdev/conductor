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

## External API models

### Anti-corruption layer

Each connector is the anti-corruption layer (ACL) between a third-party API and our domain. External API concepts must not leak past the connector boundary — `ConnectorData.data` is always `Map<String, Object>`, and callers (MCP tools, workflow steps) never see third-party types. Keep all external API vocabulary inside the connector package.

### model/ subpackage

Create a `model/` subpackage for the typed request/response shapes your connector uses:

```
connector/<id>/
  <Name>Connector.java
  model/
    package-info.java        # canonical link to the external API's spec/discovery doc
    <Api>SomeResponse.java   # one record per response shape you deserialize
    <Api>SomeRequest.java    # one record per request body you construct
```

**Prefer typed records over `Map.class`.** Pass the record class to `RestTemplate.exchange(...)` — Jackson deserializes into it. Request bodies are records too (`@JsonInclude(NON_NULL)` on optional fields), replacing inline `Map.of(...)` calls. This makes the API surface explicit, catches field issues at compile time, and localizes updates when the external API changes.

**Official SDK?** Use one if it fits the auth model (e.g. `GcpBillingConnector` uses the BigQuery SDK for SQL queries). If the SDK doesn't support per-user OAuth2 access tokens or pulls in conflicting transitive dependencies, use RestTemplate + typed records instead.

**Spec reference.** In `package-info.java`, link to the external API's OpenAPI/discovery document — this is the source of truth for field names, types, and deprecation notices. If the connector grows to many endpoints, the `model/` package can be regenerated directly from that spec using OpenAPI Generator without changing the connector or domain code.

### REST best practices for outbound calls

- **Timeouts**: always build via `ConnectorHttp.restTemplate()` — never hand-roll a `RestTemplate` or omit timeouts.
- **Error handling**: branch by HTTP status code (`404` → setup problem, `403` → auth problem, `429` → rate limit, etc.). Don't catch everything as a generic error — map specific status codes to specific `ConnectorData` states (`setupRequired`, `degraded`).
- **Content-Type**: set `MediaType.APPLICATION_JSON` on POST request headers explicitly; don't rely on defaults.
- **Null fields**: use `@JsonInclude(NON_NULL)` on optional request record fields (or at the class level) so absent values are never serialized as `null` in the outbound body.
- **URL encoding**: when a resource identifier contains special characters (e.g. `sc-domain:example.com`), use `URI.create(...)` with a pre-encoded string rather than a raw `String` URL — Spring's `RestTemplate` double-encodes `%` in String URLs.

## Known SPI gaps / follow-ups

- **No `onDisconnect` hook.** Connectors that cache per-connection tokens (GitHub, Apple Search Ads)
  expose an `evict(connectionId)` method, but there is no generic disconnect lifecycle event to call
  it from. If per-connection cache purging on disconnect becomes important, add an `onDisconnect`
  extension point to the `Connector` SPI so all connectors can hook it symmetrically.
