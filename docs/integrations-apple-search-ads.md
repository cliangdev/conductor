# Apple Search Ads connector — operations

The `apple-search-ads` connector pulls paid-acquisition metrics (new downloads, spend, CPA/CPT,
conversion, top keywords/search terms) from the Apple Ads Campaign Management API v5. Unlike the
other connectors, its auth is a developer-signed credential that **expires and must be rotated**.

## How auth works

1. The connector reads five credentials from the connection config: `clientId`, `teamId`, `keyId`,
   `privateKey` (the `.p8` contents, stored encrypted as the connection secret), and `orgId`.
2. `AppleAdsTokenService` (in `integration/connector/applesearchads/`) builds an **ES256-signed JWT**
   client secret from the `.p8` key, exchanges it at `https://appleid.apple.com/auth/oauth2/token`
   for a 1-hour access token, and caches that token in memory per connection.
3. Reporting calls go to `https://api.searchads.apple.com/api/v5/...` with
   `Authorization: Bearer <token>` and `X-AP-Context: orgId=<orgId>`.

All Apple-specific logic is isolated to the `applesearchads/` package — nothing leaks into the
shared OAuth/credential framework.

## Key rotation (required ≤ every 180 days)

The JWT client secret derived from the `.p8` key has a **maximum 180-day lifetime**. The private key
itself does not auto-expire, but rotate it on the same cadence as a hygiene practice. When the key is
rotated or revoked in the Apple Ads UI, the connector starts returning `DEGRADED` ("Check Apple
Search Ads credentials").

To rotate:

1. In **Apple Ads → Account Settings → API**, generate a new API key for the API user. Download the
   new `.p8` file and note the new `keyId` (the `clientId`, `teamId`, and `orgId` are unchanged).
2. In Conductor, open the project's **Apple Search Ads** integration, disconnect, and reconnect with
   the new `keyId` + new `.p8` contents (or update the connection config in place).
3. Revoke the old key in the Apple Ads UI once the new one is confirmed working.

## Notes / gotchas

- **No sandbox.** Apple provides no test environment — validate against a low-spend live campaign.
  Local development uses the `LocalAppleSearchAdsConnector` stub (active under the `local` profile),
  which returns fake data and makes no Apple calls.
- **Rate limits are unpublished.** Apple returns an `X-Rate-Limit` header but documents no numeric
  limit; the connector caches data for 6h (`getMaxCacheAge`) to stay well clear.
- **`newDownloads` vs `installs`.** The connector reports `newDownloads` (first-time installs) as the
  acquisition metric; `installs` includes redownloads and overstates new-user acquisition.
