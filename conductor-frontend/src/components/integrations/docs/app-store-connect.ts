const doc = `## What it does

App Store Connect reports how an iOS app performs in the App Store: impressions, product page views, first-time downloads and redownloads, split by traffic source (App Store search, browse, app and web referrers, App Clips), plus the app's public rating count and average. The numbers feed the **App Store** card on the Marketing overview and its detail page. Connect it once per app.

## How authentication works

In App Store Connect open **Users and Access → Integrations → App Store Connect API**, note the **Issuer ID**, generate a key, and download its **.p8** file (Apple lets you download it once). Enter the **Issuer ID**, **Key ID** and the app's numeric **Apple ID** here, and paste the whole .p8 file as the private key. An optional **Ratings country** (two letters, default \`us\`) picks the storefront the rating is read from.

Creating the app's analytics report request needs an Admin key; the connection check says plainly when the key's role is too weak, when the key is wrong and when the app id is wrong. The private key is stored encrypted and never shown again.

## Good to know

- **Data lags 1-2 days.** Apple publishes a day late, so the newest days are empty rather than zero. The last few days are re-read every run and shown as provisional because Apple restates them.
- **History starts when the report request was created.** Apple has no data from before that, so a new connection fills in only what exists.
- **Low-volume days can look low.** Apple hides rows below its privacy threshold, so figures can sit under App Store Connect's own App Analytics screens.
- **Conversion rate** is first-time downloads divided by unique impressions, Apple's definition.
`

export default doc
