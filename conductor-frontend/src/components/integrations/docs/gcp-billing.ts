const doc = `## What it does

Reports Google Cloud spend by service, sourced from your project's **BigQuery billing export**. The **Overview** tab shows total cost for the current month, month-over-month change, and a per-service cost breakdown.

## How authentication works

Setup is two steps:

1. **Connect with Google** — an OAuth flow authorizes Conductor to query BigQuery on your behalf.
2. **Pick a GCP project and BigQuery dataset** — once connected, Conductor lists your GCP projects and, for the one you choose, its BigQuery datasets. Select the dataset that holds your billing export.

The billing export must already be enabled in that GCP project (**GCP Console → Billing → Billing export → BigQuery export**) before a dataset will contain any rows — newly enabled exports can take 24–48 hours to populate.

## How Conductor uses it

Workflow steps can pull the same cost data with an \`integration\` step:

\`\`\`yaml
- id: cost
  uses: integration
  with:
    connector: gcp-billing
    operation: cost_by_service
\`\`\`

Credentials are resolved at run time from the active connection — they never appear in the workflow YAML. See the **Tools** tab on this page for the full list of callable operations, their parameters, and output shapes; the **Overview** tab shows the same cost breakdown as a live dashboard.
`

export default doc
