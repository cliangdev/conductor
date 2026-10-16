const doc = `## What it does

Product metrics reads your product's own numbers (first opens, onboarding, trials, paid, cost per trial) from a URL your backend serves, so the Marketing overview can show the in-app half of the funnel after App Store downloads. You can connect several backends, one per connection. It feeds the **In-app** card, its detail page and the in-app stages of the funnel.

## How authentication works

Enter the **Metrics URL** (https only) and an **API key**, sent as a bearer token. Use a read-only key. The connection check makes a one-day request and says plainly when the URL is wrong, the key is rejected or the response breaks the contract. The key is stored encrypted and never shown again.

## Good to know

- **Conductor only reads.** Your backend owns the contract (version 1) and every number, including targets. A target set by your backend shows as "Set in ..." and cannot be edited in Conductor.
- **Days after "data complete through" are provisional.** They are drawn dashed, left out of totals and re-read on the next sync.
- **Small samples are flagged.** A window resting on fewer than 20 users or events is marked small sample and never read as a real change.
- **Cross-checks.** If your backend sends reference store downloads or ad spend and they differ from the App Store or Ads connector by more than 1%, that channel shows a mismatch with both figures.
- **Aggregates only.** Never send user ids, install ids or other per-person identifiers.
`

export default doc
