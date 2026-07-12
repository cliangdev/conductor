const doc = `## What it does

Apple Search Ads runs paid App Store search campaigns. This integration reports campaign spend, downloads, and cost-per-acquisition (CPA), plus top keywords and search terms when a campaign is configured, on the **Overview** tab.

## How authentication works

Create an API key in **Apple Ads → Account Settings → API**, then enter its identifiers here: **Client ID**, **Team ID**, **Key ID**, the **.p8 private key**, and your **Org ID**. An optional **Campaign ID** enables keyword- and search-term-level reporting. The private key is stored encrypted and never re-displayed.

## How Conductor uses it

Workflow steps can pull the same data with an \`integration\` step:

\`\`\`yaml
- id: ad_spend
  uses: integration
  with:
    connector: apple-search-ads
    operation: campaign_report
\`\`\`

Credentials are resolved at run time from the active connection — they never appear in the workflow YAML. See the **Tools** tab on this page for the full list of callable operations, their parameters, and output shapes; the **Overview** tab shows the same metrics as a live dashboard.
`

export default doc
