const doc = `## What it does

RevenueCat manages in-app subscriptions. This integration reports subscription revenue metrics — active trials, active subscriptions, MRR, and recent revenue and customer trends — on the **Overview** tab.

## How authentication works

Connect with a **V2 secret key** (\`sk_...\`), your RevenueCat **project ID**, and an optional **display currency** (defaults to the project's own currency if left blank). The secret key is stored encrypted and never re-displayed.

## How Conductor uses it

Workflow steps can pull the same metrics with an \`integration\` step:

\`\`\`yaml
- id: revenue
  uses: integration
  with:
    connector: revenuecat
    operation: mrr
\`\`\`

Credentials are resolved at run time from the active connection — they never appear in the workflow YAML. See the **Tools** tab on this page for the full list of callable operations, their parameters, and output shapes; the **Overview** tab shows the same metrics as a live dashboard.
`

export default doc
