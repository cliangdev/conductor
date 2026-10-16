# App Store Connect connector — operations

The `app-store-connect` connector reads the daily App Store numbers of one iOS app from Apple's
**Analytics Reports API** and the app's public rating from the iTunes lookup. It feeds the `STORE`
channel of the Marketing overview through the `channel_metrics` feed (see
[`integrations-adding-a-connector.md`](integrations-adding-a-connector.md#channel-metrics-feeds-the-channel_metrics-sink)).
It is multi-instance: connect it once per app.

## Creating the API key

1. In App Store Connect open **Users and Access → Integrations → App Store Connect API** (Team Keys).
2. Note the **Issuer ID** shown at the top of the page.
3. Generate a key. Note its **Key ID** and download the `.p8` file (Apple lets you download it once).
4. Find the app's numeric **Apple ID** (App Store Connect → the app → App Information → General).
5. In Conductor open **Integrations → App Store Connect**, enter the Issuer ID, Key ID and Apple ID,
   paste the whole `.p8` file as the private key, and optionally set the ratings country (two letters,
   default `us`).

**Role.** Creating the app's report request needs an **Admin** key. Reading reports once the request
exists is allowed to roles that can read Analytics Reports. We could not confirm the exact minimum
role against Apple's documentation, so use an Admin key, or a role that can read Analytics Reports
and let an Admin key create the request first. The connection's health check says plainly when the
key's role is too weak (403), when the key is wrong (401) and when the app id is wrong (404).

## What it does

- Finds the app's `ONGOING` analytics report request, or creates one. A request that already exists
  (for example one another tool created) is reused, never duplicated.
- Reads the reports **App Store Discovery and Engagement Standard** (impressions, page views) and
  **App Downloads Standard** (first-time downloads, redownloads). The report names are exact: without
  "Standard" Apple returns an empty list and no error.
- Stores each metric as a total and per source type (`source:APP_STORE_SEARCH`, `APP_STORE_BROWSE`,
  `APP_REFERRER`, `WEB_REFERRER`, `APP_CLIP`, `INSTITUTIONAL_PURCHASE`, `UNAVAILABLE`, `OTHER`).
- Metrics: `impressions`, `impressions_unique`, `page_views`, `page_views_unique`, `downloads`,
  `redownloads`, and the snapshots `rating_count`, `rating_avg` (today's value only, accumulating day
  by day from the first pull).
- A day's rows are the sum of all segments of the report instance. When several instances carry the
  same report date, the one with the latest processing date wins.

## Gotchas

- **Data lags 1-2 days.** Apple publishes a day late; the newest days return no rows and nothing is
  stored for them (never a zero). The last 3 days are re-pulled every run and shown as provisional
  because Apple restates them.
- **Data starts when the request was created.** Apple only produces reports from the moment the
  `ONGOING` request exists. The 90-day backfill fills what Apple has, not more.
- **Privacy thresholds.** Apple hides rows below its reporting threshold, so low-volume days can be
  missing or lower than App Store Connect's own App Analytics screens.
- **Segments are pre-signed downloads** (gzip or zip). They are fetched without an `Authorization`
  header and unpacked by sniffing the first bytes.
- **Columns are read by header name.** If Apple removes a column the pull fails with a message naming
  it rather than storing misaligned numbers.
- **Ratings** come from `https://itunes.apple.com/lookup` for the chosen country only; a failed lookup
  is logged and skipped and never fails the store pull.
- **Auth** is an ES256 JWT (`kid` = Key ID, `iss` = Issuer ID, `aud` = `appstoreconnect-v1`, 15 minute
  expiry) used directly as the bearer; it is cached for 10 minutes per connection.
- **Local development** uses `LocalAppStoreConnectConnector` (the `local` profile), which returns a
  deterministic fake series and makes no Apple calls.
