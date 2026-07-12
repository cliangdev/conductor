const doc = `## What it does

PostHog is a product analytics platform. This integration pulls web analytics — visitors, pageviews, sessions, bounce rate, and average session duration — into Conductor so you can see product usage trends without leaving your workspace. The **Overview** tab renders a pageview trend chart plus top pages and top traffic sources.

## How authentication works

Connect with a **personal API key** (\`phx_...\`) and your PostHog **project ID** (a numeric identifier). Both are entered once when you connect; the API key is stored encrypted and never re-displayed.

## How Conductor uses it

Workflow steps can pull the same data on a schedule with an \`integration\` step:

\`\`\`yaml
- id: pageviews
  uses: integration
  with:
    connector: posthog
    operation: pageview_trend
\`\`\`

Credentials are resolved at run time from the active connection — they never appear in the workflow YAML. See the **Tools** tab on this page for the full list of callable operations, their parameters, and output shapes; the **Overview** tab shows the same metrics as a live dashboard.
`

export default doc
