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
6. Optional: set the **vendor number** (Payments and Financial Reports, Sales and Trends) to read Sales Reports
   downloads of record, and the **provider token** (the number after `pt=` in a link from App Analytics →
   Campaigns → Generate a Link) so Conductor can make App Store campaign links for Posts, bios, creators and email.

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
- Metrics: `impressions`, `impressions_unique`, `page_views`, `page_views_unique`, `downloads`
  (first-time), `redownloads`, `total_downloads` (first-time plus redownloads, written only where both exist;
  the numerator of Apple's per-source conversion rate), and the snapshots `rating_count`, `rating_avg`
  (today's value only, accumulating day by day from the first pull).
- **Zero days.** Apple's files leave out zero rows. On a day a report covers, a metric with no row is stored
  as 0: for the total, and for each of `APP_STORE_SEARCH`, `APP_STORE_BROWSE`, `APP_REFERRER` and
  `WEB_REFERRER`, the sources the overview and the report read one by one (per-source conversion, the
  organic search split). Other sources keep only the rows Apple sent; a day no report covers stays empty.
  A report covers the day before each instance's processing date, and every day an instance carries rows for:
  the history Apple ships in a new request's first instances has no instance per day. Contract version 4 added
  the per-source zeros and version 5 the carried days, each making the feed re-walk its history once.
- A day's rows are the sum of all segments of the report instance. When several instances carry the
  same report date, the one with the latest processing date wins.
- **Campaign tokens.** It also reads **App Downloads Detailed** from the same request and stores first-time
  downloads per App Store campaign token as `campaign_downloads` with dimension `ct:{token}` (the `ct` of an
  `apps.apple.com/...?pt=…&ct=…` link). Apple leaves out rows under its privacy threshold, so a token with no
  row has no value, not zero. A missing or unreadable Detailed report never affects the Standard metrics.
- **Downloads of record.** With the optional `vendorNumber` (Sales and Trends vendor number) set, it reads the
  daily Sales Reports summary and stores `downloads_of_record` (first-time download units of this app, product
  types 1, 1F and 1T) and `sales_report_days` (1 per day read, for coverage). Apple answers 404 both before it
  publishes a day and for a day with no sales, so a 404 on a day at least three days old (Pacific, Apple's report
  zone) counts as a covered day with zero downloads, and a recent 404 writes nothing. A Sales failure never fails
  the Analytics part of a pull.
- Reports contract version 5; a version change makes the feed re-read its 90-day history once.

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

## Agent tool: `get_app_store_listing`

A read-only operation agents get through the connector tool bridge (`app-store-connect_get_app_store_listing`).
It returns the app's listing so an agent can reason about App Store optimisation:

- the **live** version and the newest **draft** (editable) version, each with per-locale `name`,
  `subtitle`, `keywords` (comma string), `promotionalText` and `descriptionLength` (the long description
  itself is not returned);
- **Custom Product Pages** (`id`, `name`, `visible`);
- **Product Page Optimization tests** (`name`, `state`). When the account cannot read them (403/404) the
  rest still returns and `experimentsAvailable` is `false`.

Calls (all `https://api.appstoreconnect.apple.com`): `/v1/apps/{id}/appStoreVersions?filter[platform]=IOS&limit=200`
(never with `sort`, Apple answers 400; the live and draft versions are picked client-side by parsed version
number), `/v1/appStoreVersions/{id}/appStoreVersionLocalizations`, `/v1/apps/{id}/appInfos` then
`/v1/appInfos/{id}/appInfoLocalizations`, `/v1/apps/{id}/appCustomProductPages`,
`/v1/apps/{id}/appStoreVersionExperimentsV2`. `links.next` is followed.

Things to know:

- **No arguments.** Connector tools ignore the model's arguments and read from the connection's first active
  connection, so the tool takes none and always returns every locale that fits.
- **Size.** The agent tool cuts results at 8,000 bytes. The result is built to stay under ~7 KB: locales are
  dropped from the end (`en-US` first, then other `en-*`, then the rest alphabetically) and
  `truncated: true` is set with `localesIncluded` / `localesTotal`. More than 25 Custom Product Pages or tests are
  capped with `customProductPagesTotal` / `experimentsTotal`.
- **Errors.** If the versions call fails the output carries `listingError` (plain message) instead of `listing`.
- **Cost.** The listing is read inside the connector's `fetchData`, so it is fetched with each status refresh
  too (cached 6 hours); agent calls force a fresh read.
- The `local` profile returns a generic sample listing.
