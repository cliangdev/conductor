const doc = `## What it does

Google Search Console reports organic search performance. This integration surfaces clicks, impressions, click-through rate, and average position for a verified property, along with top queries, top pages, and a branded-vs-non-branded click split, on the **Overview** tab.

## How authentication works

Setup is two steps:

1. **Connect with Google** — an OAuth flow authorizes Conductor to read Search Console data on your behalf.
2. **Pick a verified property** — choose from the list of properties your Google account has verified access to (either a domain property, \`sc-domain:example.com\`, or a URL-prefix property, \`https://example.com/\`), and optionally set a **brand term** used to split branded vs. non-branded clicks.

## How Conductor uses it

Workflow steps can pull the same data with an \`integration\` step:

\`\`\`yaml
- id: seo_data
  uses: integration
  with:
    connector: gsc
    operation: search_analytics
\`\`\`

Credentials are resolved at run time from the active connection — they never appear in the workflow YAML. See the **Tools** tab on this page for the full list of callable operations, their parameters, and output shapes; the **Overview** tab shows the same metrics as a live dashboard.
`

export default doc
